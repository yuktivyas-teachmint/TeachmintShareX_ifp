package com.teachmint.sharex.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Hardware-accelerated H.264 decoder for Android using MediaCodec.
 *
 * Decodes H.264 NAL units and renders directly to a [Surface] (TextureView
 * or SurfaceView). This bypasses CPU-side YUV→RGB conversion entirely —
 * the GPU handles it as part of Surface compositing.
 *
 * Usage:
 * ```
 * // In Compose: provide a Surface from TextureView
 * decoder.setSurface(Surface(surfaceTexture))
 *
 * // From AirPlayReceiver when session is ready:
 * decoder.start(rtpReceiver.nalUnits, session.videoSdp)
 * ```
 *
 * The decoder handles the race between Surface availability and stream start:
 * whichever arrives second triggers actual codec initialisation.
 */
class H264StreamDecoder {

    private var codec: MediaCodec? = null
    @Volatile private var outputSurface: Surface? = null
    @Volatile private var pendingNalFlow: SharedFlow<ByteArray>? = null
    private var pendingSdp: AirPlaySdp? = null
    @Volatile private var isStarted = false

    /** Video aspect ratio from decoded output format, observed by the UI for letterboxing. */
    private val _videoAspectRatio = MutableStateFlow<Float?>(null)
    val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio

    /** Cached SPS/PPS from the stream, used to re-initialize codec after Surface recreation. */
    @Volatile private var cachedSps: ByteArray? = null
    @Volatile private var cachedPps: ByteArray? = null

    /** Cached IDR keyframe — replayed on codec restart so the decoder has a reference frame
     *  even when the AirPlay source doesn't send a fresh IDR (common with static screens). */
    @Volatile private var cachedIdr: ByteArray? = null

    /** Running counters for queued/dequeued video frames to detect decoder stalls. */
    @Volatile private var queuedFrameCount = 0L
    @Volatile private var renderedFrameCount = 0L

    /** Stall fallback state: try HW first, then SW decoder once if no output appears. */
    @Volatile private var forceSoftwareDecoder = false
    @Volatile private var softwareFallbackAttempted = false

    /** Job that pre-collects from the NAL flow to buffer initial NALs before the surface is ready. */
    private var spsCacheJob: Job? = null

    /** Buffered NALs collected before the surface was ready, replayed into the codec on init. */
    private val pendingNalBuffer = mutableListOf<ByteArray>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Surface lifecycle ─────────────────────────────────────────────────────

    /**
     * Monotonic counter incremented each time a non-null Surface is set.
     * Used to detect stale setSurface(null) calls from old TextureViews that
     * arrive after a new Surface was already attached during grid layout changes.
     */
    private var surfaceGeneration = 0L

    /**
     * Called by the UI layer when a [Surface] (from TextureView) becomes available.
     * If [start] was already called, codec initialisation begins immediately.
     * If the codec is already running on a different surface, it is restarted
     * so that output is redirected to the new surface.
     *
     * Pass null when the Surface is destroyed. Use the overload [clearSurface]
     * with generation to guard against stale clears from old TextureViews.
     */
    @Synchronized
    fun setSurface(surface: Surface?): Long {
        Log.d(TAG, "setSurface(${if (surface != null) "valid" else "null"}) pendingFlow=${pendingNalFlow != null} isStarted=$isStarted cachedSps=${cachedSps != null} gen=$surfaceGeneration")
        val previousSurface = outputSurface
        if (surface == null) {
            outputSurface = null
            releaseSurface(previousSurface)
            Log.d(TAG, "Surface force-cleared (gen=$surfaceGeneration)")
            return surfaceGeneration
        }
        surfaceGeneration++
        val gen = surfaceGeneration
        outputSurface = surface
        var swappedOutputSurfaceInPlace = false
        if (pendingNalFlow != null) {
            if (isStarted && codec != null) {
                // Some vendor decoders freeze on setOutputSurface() during rapid
                // TextureView moves (grid pin/unpin transitions). When we already
                // have an IDR cached, prefer a fast decoder restart for reliability.
                if (cachedIdr != null) {
                    Log.d(
                        TAG,
                        "Surface changed gen=$gen — restarting decoder with cached IDR " +
                            "to avoid setOutputSurface freeze",
                    )
                    releaseCodecAndCoroutines()
                } else {
                    // Early stream phase: keep setOutputSurface fallback because we
                    // may not have a keyframe yet for safe decoder bootstrap.
                    try {
                        codec!!.setOutputSurface(surface)
                        Log.d(TAG, "Surface swapped via setOutputSurface() gen=$gen — codec continues")
                        swappedOutputSurfaceInPlace = true
                    } catch (e: Exception) {
                        Log.w(TAG, "setOutputSurface() failed, falling back to restart: ${e.message}")
                        releaseCodecAndCoroutines()
                    }
                }
            }
            if (!swappedOutputSurfaceInPlace) {
                initAndStart()
            }
        }
        if (previousSurface != null && previousSurface !== outputSurface) {
            releaseSurface(previousSurface)
        }
        return gen
    }

    /**
     * Clears the surface only if the given generation matches the current one.
     * This prevents a stale onDispose/onSurfaceTextureDestroyed from an old
     * TextureView from clearing a newer, valid Surface.
     */
    @Synchronized
    fun clearSurface(generation: Long) {
        if (generation != surfaceGeneration) {
            Log.d(TAG, "clearSurface: stale gen=$generation (current=$surfaceGeneration), ignoring")
            return
        }
        val previousSurface = outputSurface
        outputSurface = null
        releaseSurface(previousSurface)
        Log.d(TAG, "clearSurface: gen=$generation cleared — codec stays alive, frames discarded")
    }

    @Synchronized
    fun isSurfaceAttached(): Boolean = outputSurface != null

    @Synchronized
    fun isActiveSurfaceGeneration(generation: Long): Boolean {
        return generation != 0L && generation == surfaceGeneration && outputSurface != null
    }

    // ── Stream lifecycle ──────────────────────────────────────────────────────

    /**
     * Called by [AirPlayReceiver] when an RTSP session is ready.
     * If the Surface is already attached, codec initialisation begins immediately.
     * Handles reconnection by stopping previous codec if already started.
     */
    @Synchronized
    fun start(nalUnits: SharedFlow<ByteArray>, sdp: AirPlaySdp?) {
        Log.d(TAG, "start() called — surface=${outputSurface != null} isStarted=$isStarted sdp=${sdp != null}")
        if (isStarted) {
            Log.d(TAG, "Resetting decoder for new stream")
            releaseCodecAndCoroutines()
        }
        pendingNalFlow = nalUnits
        pendingSdp     = sdp
        // Reset cached SPS/PPS/IDR for a fresh stream — they'll be extracted from incoming NALs
        cachedSps = null
        cachedPps = null
        cachedIdr = null
        queuedFrameCount = 0L
        renderedFrameCount = 0L
        forceSoftwareDecoder = false
        softwareFallbackAttempted = false
        // Always pre-buffer to capture the stream's SPS/PPS/IDR before codec init.
        // If the surface is already attached (reconnect after YouTube teardown), auto-trigger
        // initAndStart() on first IDR so the codec gets valid out-of-band CSD.
        // Without this, the HW decoder starts cold (no CSD) and stalls on the first keyframe.
        startPreBufferCollection(nalUnits) {
            if (outputSurface != null) {
                Log.d(TAG, "pre-buffer: first IDR with surface ready — triggering initAndStart()")
                scope.launch {
                    synchronized(this@H264StreamDecoder) {
                        if (codec == null && outputSurface != null) initAndStart()
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        val previousSurface = outputSurface
        outputSurface = null
        releaseCodecAndCoroutines()
        pendingNalFlow = null
        pendingSdp     = null
        cachedSps      = null
        cachedPps      = null
        cachedIdr      = null
        queuedFrameCount = 0L
        renderedFrameCount = 0L
        forceSoftwareDecoder = false
        softwareFallbackAttempted = false
        releaseSurface(previousSurface)
    }

    // ── NAL pre-buffering ─────────────────────────────────────────────────────

    /**
     * Starts a collector that buffers ALL incoming NALs (SPS, PPS, IDR, P-frames)
     * while the Surface is not yet available. When [initAndStart] runs, it flushes
     * this buffer into the codec so the initial IDR keyframe is not lost.
     * Caps at [MAX_PRE_BUFFER] to avoid unbounded memory growth.
     */
    private fun startPreBufferCollection(nalUnits: SharedFlow<ByteArray>, onFirstIdr: (() -> Unit)? = null) {
        spsCacheJob?.cancel()
        pendingNalBuffer.clear()
        var idrCallbackFired = false
        // UNDISPATCHED: the collector registers as a SharedFlow subscriber immediately
        // in the caller's context, before control returns. This ensures SPS/PPS emitted
        // by the mirror parser right after this call are captured in the pre-buffer.
        spsCacheJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            Log.d(TAG, "pre-buffer: started collecting (hasIdrCallback=${onFirstIdr != null})")
            nalUnits.collect { nal ->
                val nalType = if (nal.isNotEmpty()) nal[0].toInt() and 0x1F else -1
                if (nalType == 7 && nal.size <= MAX_CSD_SIZE) cachedSps = nal.copyOf()
                if (nalType == 8 && nal.size <= MAX_CSD_SIZE) cachedPps = nal.copyOf()
                if (nalType == 5) cachedIdr = nal.copyOf()
                synchronized(pendingNalBuffer) {
                    if (pendingNalBuffer.size < MAX_PRE_BUFFER) {
                        pendingNalBuffer.add(nal.copyOf())
                    }
                }
                if (nalType in intArrayOf(5, 7, 8)) {
                    Log.d(TAG, "pre-buffer: NAL type=$nalType size=${nal.size} buffered=${pendingNalBuffer.size}")
                }
                // Fire once when the first IDR arrives — allows the caller to trigger
                // codec init when the surface is already attached (reconnect scenario).
                if (nalType == 5 && !idrCallbackFired) {
                    idrCallbackFired = true
                    onFirstIdr?.invoke()
                }
            }
        }
    }

    // ── Codec init ────────────────────────────────────────────────────────────

    private fun initAndStart() {
        val surface = outputSurface ?: return
        val flow    = pendingNalFlow ?: return

        // Snapshot and clear the pre-buffer while synchronized
        var bufferedNals: List<ByteArray>
        synchronized(pendingNalBuffer) {
            bufferedNals = pendingNalBuffer.toList()
            pendingNalBuffer.clear()
        }

        // If the buffer doesn't contain an IDR keyframe, inject the cached one.
        // This is critical for codec restart (e.g. after setOutputSurface failure):
        // without an IDR reference frame the codec can't decode P-frames, and the
        // AirPlay source won't send a fresh IDR until the screen content changes.
        val idr = cachedIdr
        if (idr != null) {
            val hasIdr = bufferedNals.any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 5 }
            if (!hasIdr) {
                bufferedNals = bufferedNals + idr
                Log.d(TAG, "Injected cached IDR (${idr.size}B) into replay buffer for codec bootstrap")
            }
        }

        try {
            queuedFrameCount = 0L
            renderedFrameCount = 0L
            // Configure MediaFormat — width/height are overridden by in-band SPS.
            // Using 1920×1080 as an initial hint; MediaCodec reads real dims from SPS/PPS CSD.
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)

            // Use SPS/PPS from SDP first, then from cache (persisted across Surface recreation)
            val sps = pendingSdp?.sps ?: cachedSps
            val pps = pendingSdp?.pps ?: cachedPps
            if (sps != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(ANNEX_B_START + sps))
                Log.d(TAG, "CSD-0 (SPS): ${sps.size}B source=${if (pendingSdp?.sps != null) "sdp" else "cache"}")
            }
            if (pps != null) {
                format.setByteBuffer("csd-1", ByteBuffer.wrap(ANNEX_B_START + pps))
                Log.d(TAG, "CSD-1 (PPS): ${pps.size}B source=${if (pendingSdp?.pps != null) "sdp" else "cache"}")
            }

            // Low-latency mode for real-time display (API 30+ hint, ignored on older devices)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            codec = createDecoder().apply {
                configure(format, surface, null, 0)
                start()
            }
            isStarted = true
            Log.d(
                TAG,
                "MediaCodec decoder started [${codec?.name}] → Surface " +
                    "(hasSps=${sps != null} hasPps=${pps != null}) buffered=${bufferedNals.size} " +
                    "softwareFallback=$forceSoftwareDecoder",
            )

            // UNDISPATCHED: the feedLoop registers as a SharedFlow subscriber on the
            // caller's thread before this launch returns.  This eliminates the window
            // between pre-buffer cancellation and feedLoop subscription that caused
            // the decoder to miss NAL units (including IDR keyframes), resulting in a
            // permanently blank surface.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                replayBufferThenFeed(bufferedNals, flow)
            }
            // feedLoop is now a registered subscriber of the SharedFlow
            // (UNDISPATCHED ran until flow.collect suspended).  Safe to stop the
            // pre-buffer collector without any emission gap.
            spsCacheJob?.cancel()
            spsCacheJob = null

            scope.launch { drainLoop() }
        } catch (e: Exception) {
            Log.e(TAG, "initAndStart failed: ${e.message}", e)
        }
    }

    /**
     * Feeds the pre-buffered NALs into the codec first, then continues with the
     * live [SharedFlow]. This ensures the IDR keyframe captured before the surface
     * was ready is delivered to the decoder.
     */
    private suspend fun replayBufferThenFeed(buffered: List<ByteArray>, liveFlow: SharedFlow<ByteArray>) {
        Log.d(TAG, "Replaying ${buffered.size} buffered NALs into codec")
        for (nal in buffered) {
            feedNal(nal) ?: return  // codec gone — abort
        }

        // Drain NALs captured by the pre-buffer collector after init snapshot.
        // This closes the race where frame-1 IDR arrives while codec is bootstrapping
        // and would otherwise be dropped before live collection starts.
        val lateBuffered = synchronized(pendingNalBuffer) {
            val copy = pendingNalBuffer.toList()
            pendingNalBuffer.clear()
            copy
        }
        if (lateBuffered.isNotEmpty()) {
            Log.d(TAG, "Replaying ${lateBuffered.size} late-buffered NALs before live feed")
            for (nal in lateBuffered) {
                feedNal(nal) ?: return
            }
        }

        Log.d(TAG, "Buffer replay done, switching to live feed")
        feedLoop(liveFlow)
    }

    /** Feeds a single NAL to the codec. Returns false if codec is gone. */
    private fun feedNal(nal: ByteArray): Unit? {
        val c = codec ?: return null
        val annexB = ANNEX_B_START + nal
        val nalType = if (nal.isNotEmpty()) nal[0].toInt() and 0x1F else -1
        // Only treat small NALs as codec config — real SPS/PPS are typically < 256 bytes.
        // Large NALs with a coincidental type 7/8 first byte are garbled video frames.
        val isCsd = (nalType == 7 || nalType == 8) && nal.size <= MAX_CSD_SIZE
        val flags = when {
            isCsd -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            nalType == 5 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }

        if (isCsd && nalType == 7) cachedSps = nal.copyOf()
        if (isCsd && nalType == 8) cachedPps = nal.copyOf()
        if (nalType == 5) cachedIdr = nal.copyOf()

        try {
            var inputIdx = -1
            var retries = 0
            while (inputIdx < 0 && retries < 5) {
                inputIdx = c.dequeueInputBuffer(10_000)
                retries++
            }
            if (inputIdx < 0) return Unit

            val buf = c.getInputBuffer(inputIdx) ?: return Unit
            buf.clear()
            if (annexB.size <= buf.remaining()) {
                buf.put(annexB)
                c.queueInputBuffer(inputIdx, 0, annexB.size, System.nanoTime() / 1000L, flags)
                if (!isCsd) {
                    queuedFrameCount++
                }
            } else {
                c.queueInputBuffer(inputIdx, 0, 0, 0, 0)
            }
        } catch (e: IllegalStateException) {
            Log.d(TAG, "feedNal: codec released")
            return null
        }
        return Unit
    }

    // ── Input: NAL → codec ────────────────────────────────────────────────────

    private suspend fun feedLoop(nalUnits: SharedFlow<ByteArray>) {
        var frameCount = 0
        try {
            nalUnits.collect { nal ->
                val c = codec ?: throw CancellationException("codec cleared")
            val annexB = ANNEX_B_START + nal

            // Detect NAL type for codec-config flagging (SPS=7, PPS=8)
            val nalType = if (nal.isNotEmpty()) nal[0].toInt() and 0x1F else -1
            // Only treat small NALs as codec config — real SPS/PPS are typically < 256 bytes.
            val isCsd = (nalType == 7 || nalType == 8) && nal.size <= MAX_CSD_SIZE
            val flags = when {
                isCsd -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                nalType == 5 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                else -> 0
            }

            // Cache SPS/PPS/IDR for codec restart after Surface recreation
            if (isCsd && nalType == 7) cachedSps = nal.copyOf()
            if (isCsd && nalType == 8) cachedPps = nal.copyOf()
            if (nalType == 5) cachedIdr = nal.copyOf()

            if (frameCount < 5 || frameCount % 1000 == 0) {
                Log.d(TAG, "feedLoop: NAL #$frameCount type=$nalType size=${nal.size} flags=$flags")
            }
            frameCount++

            try {
                // Retry briefly if no input buffer is available yet
                var inputIdx = -1
                var retries  = 0
                while (inputIdx < 0 && retries < 5 && currentCoroutineContext().isActive) {
                    inputIdx = c.dequeueInputBuffer(10_000)
                    retries++
                }
                if (inputIdx < 0) return@collect

                val buf = c.getInputBuffer(inputIdx) ?: return@collect
                buf.clear()
                if (annexB.size <= buf.remaining()) {
                    buf.put(annexB)
                    c.queueInputBuffer(inputIdx, 0, annexB.size, System.nanoTime() / 1000L, flags)
                    if (!isCsd) {
                        queuedFrameCount++
                    }
                } else {
                    Log.w(TAG, "NAL too large (${annexB.size}B) — skipping")
                    c.queueInputBuffer(inputIdx, 0, 0, 0, 0)
                }
            } catch (e: IllegalStateException) {
                // Codec was released concurrently (surface change / stop) — exit gracefully
                Log.d(TAG, "feedLoop: codec released, exiting")
                throw CancellationException("codec released")
            }
            }
        } catch (_: CancellationException) {
            // Expected during stop/restart when codec or scope is cancelled.
            }
    }

    // ── Output: release buffer → rendered to Surface ──────────────────────────

    private suspend fun drainLoop() = withContext(Dispatchers.IO) {
        val info = MediaCodec.BufferInfo()
        var renderedFramesLocal = 0L
        var discardedFrames = 0L
        val loopStartMs = System.currentTimeMillis()
        var lastStallLogMs = 0L
        var lastRenderProgressMs = loopStartMs
        while (isActive) {
            val c = codec ?: break
            try {
                when (val outputIdx = c.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // If we keep queuing frames but never dequeue output, this decoder
                        // instance is likely stalled. Restart once, then force SW decoder once.
                        val now = System.currentTimeMillis()
                        val queued = queuedFrameCount
                        val rendered = renderedFrameCount
                        val elapsedMs = now - loopStartMs
                        if (queued >= STALL_MIN_QUEUED_FRAMES &&
                            rendered == 0L &&
                            elapsedMs >= STALL_TIMEOUT_MS
                        ) {
                            val canLog = now - lastStallLogMs >= 1_000
                            if (canLog) {
                                Log.w(
                                    TAG,
                                    "drainLoop: decoder output stalled (queued=$queued rendered=$rendered " +
                                        "elapsedMs=$elapsedMs codec=${c.name})",
                                )
                                lastStallLogMs = now
                            }
                            val shouldForceSoftware = !softwareFallbackAttempted
                            restartDecoderAfterStall(forceSoftware = shouldForceSoftware)
                            break
                        }

                        // Mid-stream recovery: if we already rendered before, but queued
                        // frames keep increasing while rendered frame count is flat for too
                        // long, decoder is likely stuck on a single frame.
                        val noRenderForMs = now - lastRenderProgressMs
                        val queuedNotRendered = queued - rendered
                        if (
                            outputSurface != null &&
                            rendered > 0L &&
                            queuedNotRendered >= MID_STREAM_STALL_BACKLOG_FRAMES &&
                            noRenderForMs >= MID_STREAM_STALL_TIMEOUT_MS
                        ) {
                            val canLog = now - lastStallLogMs >= 1_000
                            if (canLog) {
                                Log.w(
                                    TAG,
                                    "drainLoop: mid-stream stall detected " +
                                        "(queued=$queued rendered=$rendered backlog=$queuedNotRendered " +
                                        "noRenderForMs=$noRenderForMs codec=${c.name})",
                                )
                                lastStallLogMs = now
                            }
                            val shouldForceSoftware = !softwareFallbackAttempted
                            restartDecoderAfterStall(forceSoftware = shouldForceSoftware)
                            break
                        }
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = c.outputFormat
                        val w = runCatching { fmt.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                        val h = runCatching { fmt.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                        Log.d(TAG, "output format changed ${w}×$h")
                        if (w > 0 && h > 0) {
                            _videoAspectRatio.value = w.toFloat() / h.toFloat()
                        }
                    }
                    else -> {
                        if (outputIdx >= 0) {
                            // Some codecs don't send INFO_OUTPUT_FORMAT_CHANGED reliably on
                            // surface swaps. Infer aspect ratio from outputFormat if needed.
                            runCatching {
                                val fmt = c.outputFormat
                                val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                                val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                                if (w > 0 && h > 0) {
                                    val ratio = w.toFloat() / h.toFloat()
                                    val current = _videoAspectRatio.value
                                    if (current == null || abs(current - ratio) > 0.01f) {
                                        _videoAspectRatio.value = ratio
                                    }
                                }
                            }
                            // render = true only when a valid Surface is attached.
                            // When the Surface is temporarily null (grid layout change),
                            // discard frames (render=false) to keep the codec pipeline flowing.
                            val render = outputSurface != null
                            c.releaseOutputBuffer(outputIdx, render)
                            if (render) {
                                renderedFramesLocal++
                                renderedFrameCount++
                                lastRenderProgressMs = System.currentTimeMillis()
                                if (renderedFramesLocal == 1L) {
                                    Log.d(TAG, "drainLoop: first frame rendered to surface")
                                }
                            } else {
                                discardedFrames++
                                if (discardedFrames <= 3 || discardedFrames % 100 == 0L) {
                                    Log.d(
                                        TAG,
                                        "drainLoop: frame discarded (no surface) " +
                                            "total=$discardedFrames rendered=$renderedFramesLocal",
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                // Codec was released concurrently (surface change / stop) — exit gracefully
                Log.d(
                    TAG,
                    "drainLoop: codec released, exiting " +
                        "(rendered=$renderedFramesLocal discarded=$discardedFrames)",
                )
                break
            } catch (e: Exception) {
                Log.e(TAG, "drainLoop: unexpected error (${e.javaClass.simpleName}): ${e.message}", e)
                break
            }
        }
        Log.d(TAG, "drainLoop: exiting — rendered=$renderedFramesLocal discarded=$discardedFrames")
    }

    @Synchronized
    private fun restartDecoderAfterStall(forceSoftware: Boolean) {
        if (forceSoftware) {
            softwareFallbackAttempted = true
            forceSoftwareDecoder = true
            Log.w(TAG, "Restarting decoder after stall with software fallback")
        } else {
            Log.w(TAG, "Restarting decoder after stall with hardware decoder")
        }
        releaseCodecAndCoroutines()
        if (pendingNalFlow != null && outputSurface != null) {
            initAndStart()
        }
    }

    private fun createDecoder(): MediaCodec {
        if (!forceSoftwareDecoder) {
            return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        SOFTWARE_CODEC_CANDIDATES.forEach { codecName ->
            runCatching {
                MediaCodec.createByCodecName(codecName).also {
                    Log.d(TAG, "Using software decoder candidate: $codecName")
                }
            }.onSuccess { return it }
        }
        Log.w(TAG, "No software decoder candidate available, falling back to type lookup")
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    private fun releaseCodecAndCoroutines() {
        // Nullify codec first so feedLoop/drainLoop exit on their next iteration,
        // then cancel coroutines and release the saved codec reference.
        val codecToRelease = codec
        codec     = null
        isStarted = false
        spsCacheJob?.cancel()
        spsCacheJob = null
        synchronized(pendingNalBuffer) { pendingNalBuffer.clear() }
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runCatching { codecToRelease?.stop() }
        runCatching { codecToRelease?.release() }
    }

    private fun releaseSurface(surface: Surface?) {
        runCatching { surface?.release() }
    }

    companion object {
        private const val TAG = "AirPlay/H264"
        private val ANNEX_B_START = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        /** Max NALs to buffer before the surface is ready. ~2-3 seconds of stream. */
        private const val MAX_PRE_BUFFER = 300
        /** Max size for a NAL to be treated as SPS/PPS codec config data (real SPS/PPS are < 256B). */
        private const val MAX_CSD_SIZE = 256
        /** Trigger decoder stall recovery after this many queued frames with no output. */
        private const val STALL_MIN_QUEUED_FRAMES = 120L
        /** Wait this long for first output before attempting stall recovery. */
        private const val STALL_TIMEOUT_MS = 3_000L
        /** Mid-stream stall: queued-rendered backlog threshold before recovery. */
        private const val MID_STREAM_STALL_BACKLOG_FRAMES = 120L
        /** Mid-stream stall: no rendered progress window before recovery. */
        private const val MID_STREAM_STALL_TIMEOUT_MS = 2_000L
        private val SOFTWARE_CODEC_CANDIDATES = listOf(
            "c2.android.avc.decoder",
            "OMX.google.h264.decoder",
        )
    }
}
