package com.teachmint.sharex.share.shared

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Captures system playback audio (the audio of whatever is playing on the
 * device, e.g. a YouTube/video player) via [AudioPlaybackCaptureConfiguration]
 * and makes it available to WebRTC through a shared [SystemAudioBufferProvider].
 *
 * Requirements:
 *  * Android 10 (API 29) or higher.
 *  * `RECORD_AUDIO` runtime permission granted (even for playback capture).
 *  * A live [MediaProjection] (obtained from the user's screen-share consent).
 *  * The audio-producing app must allow capture (`allowAudioPlaybackCapture`
 *    in its manifest, which defaults to true for targetSdk 29+).
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class SystemAudioCapturer(
    private val mediaProjection: MediaProjection,
) {
    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null
    private val keepReading = AtomicBoolean(false)

    @Volatile private var configuredSampleRate: Int = DEFAULT_SAMPLE_RATE
    @Volatile private var configuredChannelCount: Int = DEFAULT_CHANNEL_COUNT
    private val pendingChunks = ArrayDeque<ByteArray>()
    private val lock = Object()
    // Cap at ~200ms of audio to keep latency low. Stale audio beyond this
    // is discarded so the receiver stays close to live.
    private val maxQueuedBytes: Int get() = configuredSampleRate * configuredChannelCount * 2 / 5

    @SuppressLint("MissingPermission")
    fun start() {
        if (audioRecord != null) return

        val sampleRate = DEFAULT_SAMPLE_RATE
        val channelCount = DEFAULT_CHANNEL_COUNT
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, audioFormat)
        if (minBuf <= 0) {
            println("SYSTEM_AUDIO_CAPTURER: ❌ AudioRecord.getMinBufferSize failed ($minBuf); aborting")
            return
        }
        val bufferSize = minBuf * 2

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            println(
                "SYSTEM_AUDIO_CAPTURER: ❌ AudioRecord not initialized (state=${record.state}); " +
                    "aborting playback capture"
            )
            runCatching { record.release() }
            return
        }

        configuredSampleRate = sampleRate
        configuredChannelCount = channelCount
        audioRecord = record

        runCatching { record.startRecording() }.onFailure {
            println("SYSTEM_AUDIO_CAPTURER: ❌ startRecording failed: ${it.message}")
            runCatching { record.release() }
            audioRecord = null
            return
        }

        SystemAudioBufferProvider.register(this)

        keepReading.set(true)
        val t = Thread({ readerLoop(record, bufferSize) }, "SystemAudioCapturer")
        t.priority = Thread.MAX_PRIORITY - 1
        t.isDaemon = true
        readerThread = t
        t.start()
        println(
            "SYSTEM_AUDIO_CAPTURER: ✅ Started playback capture " +
                "${sampleRate}Hz, channels=$channelCount, buf=${bufferSize}B"
        )
    }

    private fun readerLoop(record: AudioRecord, bufferSize: Int) {
        val chunkSize = min(bufferSize, configuredSampleRate * configuredChannelCount * 2 / 10) // ~100ms chunk cap
        val tmp = ByteArray(chunkSize)
        var totalBytesRead = 0L
        var readCount = 0L
        var lastLogTimeMs = System.currentTimeMillis()
        while (keepReading.get()) {
            val n = try {
                record.read(tmp, 0, tmp.size)
            } catch (t: Throwable) {
                println("SYSTEM_AUDIO_CAPTURER: read() threw ${t.message}; stopping loop")
                break
            }
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) {
                    println("SYSTEM_AUDIO_CAPTURER: AudioRecord.read() returned $n; stopping loop")
                    break
                }
                // Silence/transient; keep polling.
                continue
            }
            totalBytesRead += n
            readCount++
            val copy = tmp.copyOf(n)
            synchronized(lock) {
                pendingChunks.addLast(copy)
                var queued = pendingChunks.sumOf { it.size }
                val cap = maxQueuedBytes
                while (queued > cap && pendingChunks.isNotEmpty()) {
                    queued -= pendingChunks.removeFirst().size
                }
            }

            // Log every ~4 seconds to match the video stats interval
            val now = System.currentTimeMillis()
            if (now - lastLogTimeMs >= 4_000L) {
                val elapsedMs = (now - lastLogTimeMs).coerceAtLeast(1L)
                val kBPerSec = totalBytesRead * 1000.0 / elapsedMs / 1000.0
                val queuedBytes = synchronized(lock) { pendingChunks.sumOf { it.size } }
                println(
                    "SYSTEM_AUDIO_CAPTURER: reads=$readCount " +
                        "totalBytes=$totalBytesRead " +
                        "rate=${"%.1f".format(kBPerSec)}kB/s " +
                        "queued=${queuedBytes}B"
                )
                lastLogTimeMs = now
                totalBytesRead = 0L
                readCount = 0L
            }
        }
    }

    /**
     * Drain up to [dst].remaining() bytes of captured PCM into [dst]. Any shortfall
     * is filled with silence. Returns true if any real audio was written.
     */
    fun drainInto(dst: ByteBuffer): Boolean {
        var wroteAnyRealAudio = false
        val need = dst.remaining()
        var remaining = need
        synchronized(lock) {
            while (remaining > 0 && pendingChunks.isNotEmpty()) {
                val head = pendingChunks.peekFirst()
                val take = min(head.size, remaining)
                dst.put(head, 0, take)
                remaining -= take
                wroteAnyRealAudio = true
                if (take == head.size) {
                    pendingChunks.removeFirst()
                } else {
                    val leftover = ByteArray(head.size - take)
                    System.arraycopy(head, take, leftover, 0, leftover.size)
                    pendingChunks.removeFirst()
                    pendingChunks.addFirst(leftover)
                    break
                }
            }
        }
        if (remaining > 0) {
            // Pad with silence so WebRTC always gets a full frame.
            val zero = ByteArray(remaining)
            dst.put(zero)
        }
        return wroteAnyRealAudio
    }

    fun stop() {
        SystemAudioBufferProvider.unregister(this)
        keepReading.set(false)
        readerThread?.let {
            runCatching { it.join(200L) }
        }
        readerThread = null
        audioRecord?.let { record ->
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        audioRecord = null
        synchronized(lock) { pendingChunks.clear() }
        println("SYSTEM_AUDIO_CAPTURER: ⏹️ Stopped playback capture")
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val DEFAULT_CHANNEL_COUNT = 1
    }
}

/**
 * Shared access point between the WebRTC AudioDeviceModule and whichever
 * [SystemAudioCapturer] is currently active for the ongoing share session.
 *
 * The ADM's [org.webrtc.audio.JavaAudioDeviceModule.AudioBufferCallback]
 * always runs (even if no screen share is happening), so this provider has
 * to safely no-op when no capturer is registered — in that case it writes
 * silence and WebRTC transmits a silent audio track.
 */
internal object SystemAudioBufferProvider {
    @Volatile private var current: SystemAudioCapturer? = null

    fun register(capturer: SystemAudioCapturer) {
        current = capturer
    }

    fun unregister(capturer: SystemAudioCapturer) {
        if (current === capturer) current = null
    }

    /**
     * Overwrite [buffer] with system-audio PCM. When no capturer is active,
     * fill with silence. Called from WebRTC's AudioRecordThread at ~100 Hz
     * (every ~10ms) so this must be non-blocking.
     */
    fun fillBuffer(buffer: java.nio.ByteBuffer) {
        buffer.clear()
        val capturer = current
        if (capturer == null) {
            val zero = ByteArray(buffer.remaining())
            buffer.put(zero)
        } else {
            capturer.drainInto(buffer)
        }
        buffer.flip()
    }
}
