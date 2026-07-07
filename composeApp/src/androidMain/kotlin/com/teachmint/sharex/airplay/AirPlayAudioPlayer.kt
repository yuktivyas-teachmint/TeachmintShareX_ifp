package com.teachmint.sharex.airplay

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow

/**
 * Plays decoded PCM audio from [AacDecoder] using Android [AudioTrack].
 *
 * Operates in streaming mode with a ~80ms write-ahead buffer to absorb
 * jitter from the RTP → AAC decode pipeline without gaps.
 */
class AirPlayAudioPlayer {

    private var track: AudioTrack? = null
    private var currentSampleRate = 0
    private var currentChannels   = 0
    @Volatile
    private var muted: Boolean = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(pcmFrames: SharedFlow<AacDecoder.PcmFrame>) {
        scope.launch {
            pcmFrames.collect { frame ->
                runCatching { play(frame) }
                    .onFailure { Log.e("AirPlay", "Audio: playback error: ${it.message}") }
            }
        }
    }

    fun stop() {
        scope.cancel()
        drainAndRelease()
    }

    private var playedFrameCount = 0L
    private var mutedFrameCount = 0L

    fun setMuted(value: Boolean) {
        Log.d("AirPlay", "Audio: setMuted=$value (was=$muted)")
        muted = value
        if (value) {
            // Release the track when muted to avoid underruns
            drainAndRelease()
        }
    }

    // ── playback ──────────────────────────────────────────────────────────────

    private fun play(frame: AacDecoder.PcmFrame) {
        if (muted) {
            mutedFrameCount++
            if (mutedFrameCount == 1L || mutedFrameCount % 500L == 0L) {
                Log.d("AirPlay", "Audio: muted, skipping frame count=$mutedFrameCount")
            }
            return
        }
        ensureTrack(frame.sampleRate, frame.channels)
        val t = track ?: return
        var offset = 0
        while (offset < frame.data.size) {
            val wrote = t.write(
                frame.data,
                offset,
                frame.data.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (wrote <= 0) {
                Log.w("AirPlay", "Audio: write returned $wrote at offset=$offset size=${frame.data.size}")
                break
            }
            offset += wrote
        }
        playedFrameCount++
        if (playedFrameCount == 1L || playedFrameCount % 500L == 0L) {
            Log.d("AirPlay", "Audio: played frame=$playedFrameCount size=${frame.data.size}")
        }
    }

    /**
     * Opens (or reopens) an [AudioTrack] if the audio format changed.
     * S16 LE PCM — matches what [AacDecoder] outputs via MediaCodec.
     */
    private fun ensureTrack(sampleRate: Int, channels: Int) {
        if (track != null && currentSampleRate == sampleRate && currentChannels == channels) return

        drainAndRelease()

        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
                            else               AudioFormat.CHANNEL_OUT_STEREO

        val minBuf  = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        // Keep a larger jitter buffer for AirPlay mirror audio to avoid underruns
        // on bursty RTP arrival patterns.
        val bufSize = maxOf(minBuf * 4, (sampleRate * 0.25 * channels * 2).toInt())

        track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
            AudioTrack.MODE_STREAM,
        ).also { it.play() }

        currentSampleRate = sampleRate
        currentChannels   = channels
        Log.d("AirPlay", "Audio: track opened ${sampleRate}Hz/${channels}ch buf=${bufSize}B")
    }

    private fun drainAndRelease() {
        track?.runCatching {
            stop()
            release()
        }
        track             = null
        currentSampleRate = 0
        currentChannels   = 0
    }
}
