package com.example.teachmintsharex.share.miracast

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import com.example.teachmintsharex.share.miracast.rtp.RtpReceiver
import com.example.teachmintsharex.share.miracast.rtp.RtpReceiverFactory
import com.example.teachmintsharex.share.miracast.rtp.NativeRtpReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Coordinates the Miracast media pipeline:
 * RTP (UDP) -> MPEG-TS demux -> H264 decode -> Surface.
 */
object MiracastPlaybackManager {
    private const val HOST_TASK_PENDING_INTENT_REQUEST_CODE = 7300
    private const val STREAM_START_TIMEOUT_MS = 10_000L
    private const val STREAM_STALL_TIMEOUT_MS = 8_000L
    private val lock = Any()

    private var pendingSession: MiracastStreamInfo? = null
    private var activeSession: MiracastStreamInfo? = null
    private var receiver: RtpReceiver? = null
    private var statsJob: Job? = null
    private var decoder: MiracastH264Decoder? = null
    private var audioDecoder: MiracastAudioDecoder? = null
    private var attachedSurface: Surface? = null
    private var viewerRef: WeakReference<Activity>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(MiracastPlaybackState())
    val state: StateFlow<MiracastPlaybackState> = _state.asStateFlow()

    /** Audio starts muted; host user must explicitly enable speaker. */
    private val _isAudioMuted = AtomicBoolean(true)
    val isAudioMuted: Boolean get() = _isAudioMuted.get()

    fun setAudioMuted(muted: Boolean) {
        _isAudioMuted.set(muted)
        synchronized(lock) {
            audioDecoder?.isMuted = muted
        }
    }

    fun registerViewer(activity: Activity) {
        synchronized(lock) {
            viewerRef = WeakReference(activity)
            if (attachedSurface != null && activeSession == null && pendingSession != null) {
                startReceiverIfReadyLocked()
            }
        }
    }

    fun unregisterViewer(activity: Activity) {
        synchronized(lock) {
            if (viewerRef?.get() === activity) {
                viewerRef = null
            }
        }
    }

    fun startSession(context: Context, streamInfo: MiracastStreamInfo) {
        synchronized(lock) {
            stopReceiverLocked()
            clearSurfaceLocked()
            pendingSession = streamInfo
            activeSession = null
            _state.value = _state.value.copy(
                session = streamInfo,
                isSessionActive = false,
                waitingForSurface = attachedSurface == null,
                receivedRtpPackets = 0,
                extractedVideoSamples = 0,
                decodedFrames = 0,
                videoWidth = null,
                videoHeight = null,
                videoAspectRatio = null,
                lastFrameAtElapsedMs = null,
                sessionStartedAtElapsedMs = null,
                lastError = null,
            )
        }

        bringHostTaskToFront(context)

        synchronized(lock) {
            startReceiverIfReadyLocked()
        }
    }

    fun stopSession(closeViewer: Boolean = true) {
        val viewer: Activity?
        synchronized(lock) {
            pendingSession = null
            activeSession = null
            stopReceiverLocked()
            clearSurfaceLocked()
            _state.value = _state.value.copy(
                session = null,
                isSessionActive = false,
                waitingForSurface = false,
                receivedRtpPackets = 0,
                extractedVideoSamples = 0,
                decodedFrames = 0,
                videoWidth = null,
                videoHeight = null,
                videoAspectRatio = null,
                lastFrameAtElapsedMs = null,
                sessionStartedAtElapsedMs = null,
                lastError = null,
            )
            viewer = viewerRef?.get()
        }

        if (closeViewer) {
            viewer?.runOnUiThread {
                if (!viewer.isFinishing) {
                    viewer.finish()
                }
            }
        }
    }

    fun attachSurface(surface: Surface) {
        synchronized(lock) {
            attachedSurface = surface
            decoder?.release()
            decoder = MiracastH264Decoder(surface) { outputWidth, outputHeight, outputAspect ->
                synchronized(lock) {
                    _state.value = _state.value.copy(
                        videoWidth = outputWidth,
                        videoHeight = outputHeight,
                        videoAspectRatio = outputAspect,
                    )
                }
            }
            _state.value = _state.value.copy(waitingForSurface = false)
            startReceiverIfReadyLocked()
        }
    }

    fun detachSurface(surface: Surface?) {
        synchronized(lock) {
            if (surface != null && attachedSurface != null && attachedSurface !== surface) {
                return
            }
            attachedSurface = null
            decoder?.release()
            decoder = null
            _state.value = _state.value.copy(waitingForSurface = pendingSession != null)
        }
    }

    fun ensureViewerVisibleFromHost(activity: Activity) {
        // Miracast now renders in the host grid cards instead of a dedicated receiver activity.
        // Keep this API as a no-op for compatibility with existing call sites.
        activity.hashCode()
    }

    fun hasRecentPlaybackActivity(withinMs: Long = STREAM_STALL_TIMEOUT_MS): Boolean {
        synchronized(lock) {
            val snapshot = _state.value
            if (!snapshot.isSessionActive) return false

            val now = SystemClock.elapsedRealtime()
            val lastFrameAt = snapshot.lastFrameAtElapsedMs
            if (lastFrameAt != null) {
                return (now - lastFrameAt) <= withinMs
            }

            val startedAt = snapshot.sessionStartedAtElapsedMs ?: return false
            return (now - startedAt) <= STREAM_START_TIMEOUT_MS
        }
    }

    private fun bringHostTaskToFront(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val balOptions = ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    HOST_TASK_PENDING_INTENT_REQUEST_CODE,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    balOptions.toBundle(),
                )
                println("MIRACAST_PIPELINE: ℹ️ Bringing ShareX task to foreground for incoming Miracast")
                pendingIntent.send(
                    context,
                    0,
                    null,
                    null,
                    null,
                    null,
                    balOptions.toBundle(),
                )
            } else {
                context.startActivity(launchIntent)
            }
        }
    }

    private fun startReceiverIfReadyLocked() {
        if (receiver != null) return

        val session = pendingSession ?: return
        if (attachedSurface == null || decoder == null) {
            _state.value = _state.value.copy(waitingForSurface = true)
            return
        }

        if (decoder == null) {
            _state.value = _state.value.copy(waitingForSurface = true)
            return
        }

        // Create audio decoder for AAC playback
        audioDecoder?.release()
        val newAudioDecoder = MiracastAudioDecoder()
        newAudioDecoder.isMuted = _isAudioMuted.get()
        audioDecoder = newAudioDecoder

        // Create RTP receiver with factory (native or Kotlin fallback)
        val newReceiver = RtpReceiverFactory.create(
            port = session.rtpPort,
            onH264Data = { data, ptsUs, size, isKeyFrame ->
                val decoderSnapshot = synchronized(lock) { decoder }
                val decoded = decoderSnapshot?.queueSample(data, ptsUs) == true
                synchronized(lock) {
                    val now = SystemClock.elapsedRealtime()
                    _state.value = _state.value.copy(
                        extractedVideoSamples = _state.value.extractedVideoSamples + 1,
                        decodedFrames = decoderSnapshot?.decodedFrames ?: _state.value.decodedFrames,
                        lastFrameAtElapsedMs = if (decoded) now else _state.value.lastFrameAtElapsedMs,
                    )
                }
            },
            onAudioData = { data, ptsUs ->
                newAudioDecoder.queueSample(data, ptsUs)
            }
        )

        receiver = newReceiver
        activeSession = session
        pendingSession = null
        val sessionStartedAt = SystemClock.elapsedRealtime()

        // Start receiver asynchronously and handle errors
        scope.launch {
            val result = newReceiver.start()
            result.onFailure { error ->
                synchronized(lock) {
                    _state.value = _state.value.copy(
                        lastError = "RTP receiver failed: ${error.message}"
                    )
                }
            }
        }

        // Start statistics polling
        statsJob = scope.launch {
            while (isActive) {
                delay(500) // Update stats every 500ms
                var shouldStopStalledSession = false
                var stallSummary = ""
                synchronized(lock) {
                    val currentReceiver = receiver
                    val decoderSnapshot = decoder

                    // Get packet count from native receiver if available
                    val packetCount = (currentReceiver as? NativeRtpReceiver)?.getPacketCount()
                        ?: _state.value.receivedRtpPackets // Fallback to existing count

                    _state.value = _state.value.copy(
                        receivedRtpPackets = packetCount,
                        decodedFrames = decoderSnapshot?.decodedFrames ?: _state.value.decodedFrames,
                    )

                    val snapshot = _state.value
                    val now = SystemClock.elapsedRealtime()
                    if (snapshot.isSessionActive) {
                        val lastFrameAt = snapshot.lastFrameAtElapsedMs
                        val startedAt = snapshot.sessionStartedAtElapsedMs
                        when {
                            lastFrameAt != null && now - lastFrameAt > STREAM_STALL_TIMEOUT_MS -> {
                                shouldStopStalledSession = true
                                stallSummary =
                                    "No decoded video frame for ${now - lastFrameAt}ms " +
                                        "(>${STREAM_STALL_TIMEOUT_MS}ms)"
                            }

                            lastFrameAt == null &&
                                startedAt != null &&
                                now - startedAt > STREAM_START_TIMEOUT_MS -> {
                                    shouldStopStalledSession = true
                                    stallSummary =
                                        "Stream did not produce the first frame for " +
                                            "${now - startedAt}ms (>${STREAM_START_TIMEOUT_MS}ms)"
                                }
                        }
                    }
                }

                if (shouldStopStalledSession) {
                    println("MIRACAST_PIPELINE: ⚠️ $stallSummary. Stopping stale session.")
                    stopSession(closeViewer = true)
                    break
                }
            }
        }

        _state.value = _state.value.copy(
            session = session,
            isSessionActive = true,
            waitingForSurface = false,
            sessionStartedAtElapsedMs = sessionStartedAt,
            lastFrameAtElapsedMs = null,
            lastError = null,
        )

        println(
            "MIRACAST_PIPELINE: ✅ Started RTP receiver on ${session.rtpPort} " +
                "for source ${session.clientAddress}",
        )
    }

    private fun stopReceiverLocked() {
        statsJob?.cancel()
        statsJob = null
        receiver?.stop()
        receiver = null
        audioDecoder?.release()
        audioDecoder = null
        println("MIRACAST_PIPELINE: 🔴 RTP receiver stopped")
    }

    private fun clearSurfaceLocked() {
        val surface = attachedSurface ?: return
        runCatching {
            if (surface.isValid) {
                val canvas = surface.lockHardwareCanvas()
                canvas.drawColor(Color.BLACK)
                surface.unlockCanvasAndPost(canvas)
            }
        }.recoverCatching {
            if (surface.isValid) {
                val canvas = surface.lockCanvas(null)
                canvas.drawColor(Color.BLACK)
                surface.unlockCanvasAndPost(canvas)
            }
        }.onFailure {
            println("MIRACAST_PIPELINE: ⚠️ Failed to clear surface: ${it.message}")
        }
    }
}

data class MiracastPlaybackState(
    val session: MiracastStreamInfo? = null,
    val isSessionActive: Boolean = false,
    val waitingForSurface: Boolean = false,
    val receivedRtpPackets: Long = 0,
    val extractedVideoSamples: Long = 0,
    val decodedFrames: Long = 0,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoAspectRatio: Float? = null,
    val lastFrameAtElapsedMs: Long? = null,
    val sessionStartedAtElapsedMs: Long? = null,
    val lastError: String? = null,
)

private class MiracastH264Decoder(
    surface: Surface,
    private val onOutputFormatChanged: (width: Int, height: Int, aspectRatio: Float) -> Unit,
) {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var monotonicPtsUs = 0L

    var decodedFrames: Long = 0
        private set

    init {
        initialize(surface)
    }

    fun queueSample(sample: ByteArray, ptsUs: Long?): Boolean {
        if (sample.isEmpty()) return false

        synchronized(lock) {
            val codec = codec ?: return false

            return runCatching {
                val inputIndex = codec.dequeueInputBuffer(5_000)
                if (inputIndex < 0) {
                    return false
                }

                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return false
                if (sample.size > inputBuffer.capacity()) {
                    // If a giant PES slips in, skip it rather than poisoning decoder state.
                    return false
                }

                inputBuffer.clear()
                inputBuffer.put(sample)

                val presentationTimeUs = ptsUs ?: nextMonotonicPtsUs(sample.size)
                codec.queueInputBuffer(inputIndex, 0, sample.size, presentationTimeUs, 0)

                drainOutput(codec)
                true
            }.getOrElse {
                false
            }
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            codec = null
        }
    }

    private fun initialize(surface: Surface) {
        synchronized(lock) {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            this.codec = codec
        }
    }

    private fun nextMonotonicPtsUs(sampleSize: Int): Long {
        // 30fps fallback pacing if stream PTS is missing.
        monotonicPtsUs += max(33_333L, sampleSize / 10L)
        return monotonicPtsUs
    }

    private fun drainOutput(codec: MediaCodec) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex >= 0 -> {
                    codec.releaseOutputBuffer(outputIndex, true)
                    decodedFrames += 1
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val widthFromFormat = format.safeGetInteger(MediaFormat.KEY_WIDTH) ?: 0
                    val heightFromFormat = format.safeGetInteger(MediaFormat.KEY_HEIGHT) ?: 0
                    val cropLeft = format.safeGetInteger("crop-left")
                    val cropRight = format.safeGetInteger("crop-right")
                    val cropTop = format.safeGetInteger("crop-top")
                    val cropBottom = format.safeGetInteger("crop-bottom")
                    val outputWidth = if (
                        cropLeft != null &&
                        cropRight != null &&
                        cropRight >= cropLeft
                    ) {
                        cropRight - cropLeft + 1
                    } else {
                        widthFromFormat
                    }
                    val outputHeight = if (
                        cropTop != null &&
                        cropBottom != null &&
                        cropBottom >= cropTop
                    ) {
                        cropBottom - cropTop + 1
                    } else {
                        heightFromFormat
                    }
                    val safeHeight = outputHeight.coerceAtLeast(1)
                    val safeWidth = outputWidth.coerceAtLeast(1)
                    val aspectRatio = safeWidth.toFloat() / safeHeight.toFloat()
                    onOutputFormatChanged(safeWidth, safeHeight, aspectRatio)
                    println(
                        "MIRACAST_CODEC: Output format changed to $format " +
                            "(resolved=${safeWidth}x${safeHeight}, aspect=$aspectRatio)",
                    )
                }
                else -> return
            }
        }
    }
}

private fun MediaFormat.safeGetInteger(key: String): Int? {
    return runCatching { getInteger(key) }.getOrNull()
}

/**
 * AAC audio decoder + AudioTrack playback for Miracast audio.
 *
 * Receives raw AAC frames (ADTS or raw) from the TS demuxer, decodes them
 * via MediaCodec, and writes the resulting PCM to an AudioTrack.
 */
private class MiracastAudioDecoder {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var started = false

    @Volatile
    var isMuted: Boolean = true

    init {
        initialize()
    }

    fun queueSample(sample: ByteArray, ptsUs: Long) {
        if (sample.isEmpty()) return

        synchronized(lock) {
            val codec = codec ?: return

            runCatching {
                val inputIndex = codec.dequeueInputBuffer(5_000)
                if (inputIndex < 0) return

                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
                if (sample.size > inputBuffer.capacity()) return

                inputBuffer.clear()

                // If the data starts with an ADTS header (0xFFF), strip it so we
                // feed raw AAC to the decoder (the CSD-0 we configured already
                // describes the stream).  If there is no ADTS header, feed as-is.
                val adtsHeaderSize = detectAdtsHeaderSize(sample)
                if (adtsHeaderSize > 0 && adtsHeaderSize < sample.size) {
                    inputBuffer.put(sample, adtsHeaderSize, sample.size - adtsHeaderSize)
                    codec.queueInputBuffer(
                        inputIndex, 0, sample.size - adtsHeaderSize, ptsUs, 0
                    )
                } else {
                    inputBuffer.put(sample)
                    codec.queueInputBuffer(inputIndex, 0, sample.size, ptsUs, 0)
                }

                drainOutput(codec)
            }
        }
    }

    fun release() {
        synchronized(lock) {
            started = false
            runCatching {
                codec?.stop()
                codec?.release()
            }
            codec = null
            runCatching {
                audioTrack?.stop()
                audioTrack?.release()
            }
            audioTrack = null
        }
    }

    private fun initialize() {
        synchronized(lock) {
            runCatching {
                // WFD typically uses AAC-LC, 48 kHz, stereo
                val sampleRate = 48000
                val channelCount = 2

                // --- AudioTrack ---
                val channelMask = AudioFormat.CHANNEL_OUT_STEREO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
                val trackBufSize = maxOf(minBuf, sampleRate * channelCount * 2 / 5) // ~200ms

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(encoding)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack!!.play()

                // --- MediaCodec AAC decoder ---
                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount
                )
                // AAC-LC profile, CSD-0 = AudioSpecificConfig
                // 0x11 0x90 = AAC-LC, 48kHz, stereo
                format.setByteBuffer(
                    "csd-0",
                    ByteBuffer.wrap(byteArrayOf(0x11.toByte(), 0x90.toByte()))
                )
                format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
                format.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    8 * 1024
                )

                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec!!.configure(format, null, null, 0)
                codec!!.start()
                started = true

                println("MIRACAST_AUDIO: Audio decoder initialized (AAC-LC $sampleRate Hz, stereo)")
            }.onFailure {
                println("MIRACAST_AUDIO: Failed to initialize audio decoder: ${it.message}")
                release()
            }
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val track = audioTrack ?: return
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outputIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outputIndex)
                    if (!isMuted && outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val pcm = ByteArray(bufferInfo.size)
                        outBuf.get(pcm)
                        track.write(pcm, 0, pcm.size)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    println("MIRACAST_AUDIO: Output format changed: $newFormat")
                }
                else -> return
            }
        }
    }

    /**
     * Detect ADTS header and return its size (7 or 9 bytes), or 0 if not ADTS.
     */
    private fun detectAdtsHeaderSize(data: ByteArray): Int {
        if (data.size < 7) return 0
        // ADTS sync word: 12 bits = 0xFFF
        if ((data[0].toInt() and 0xFF) != 0xFF) return 0
        if ((data[1].toInt() and 0xF0) != 0xF0) return 0
        val protectionAbsent = (data[1].toInt() and 0x01) == 1
        return if (protectionAbsent) 7 else 9
    }
}
