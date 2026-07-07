package com.teachmint.sharex.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.nio.ByteBuffer

/**
 * AAC/ALAC decoder for Android using MediaCodec.
 *
 * Decodes raw AAC access units from [AirPlayAudioReceiver] into signed 16-bit PCM
 * for [AirPlayAudioPlayer]. Handles both AAC-ELD (AirPlay screen mirroring default)
 * and AAC-LC via the codec profile in the SDP.
 */
class AacDecoder(private val sdp: AirPlayAudioSdp?) {

    data class PcmFrame(
        val data: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    )

    private var codec: MediaCodec? = null

    private val _pcmFrames = MutableSharedFlow<PcmFrame>(extraBufferCapacity = 200)
    val pcmFrames: SharedFlow<PcmFrame> = _pcmFrames
    private var inputFrameCount = 0L
    private var outputFrameCount = 0L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start(aacFrames: SharedFlow<ByteArray>) {
        initCodec()
        scope.launch {
            aacFrames.collect { frame -> runCatching { decodeFrame(frame) } }
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    // ── Codec init ────────────────────────────────────────────────────────────

    private fun initCodec() {
        val sampleRate = sdp?.sampleRate ?: 44100
        val channels   = sdp?.channels   ?: 2
        val isAlac     = sdp?.codec?.contains("AppleLossless", ignoreCase = true) == true
        val isEld      = sdp?.codec?.contains("ELD", ignoreCase = true) == true ||
            sdp?.codec?.contains("aac-eld", ignoreCase = true) == true
        val mimeType   = if (isAlac) "audio/alac" else MediaFormat.MIMETYPE_AUDIO_AAC

        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channels)

        if (!isAlac && !isEld) {
            // For AAC-LC, set the profile explicitly.
            // For AAC-ELD, let the csd-0 AudioSpecificConfig define the profile —
            // setting KEY_AAC_PROFILE can conflict with csd-0 on some Android decoders.
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
        }

        // Supply AudioSpecificConfig (or ALACSpecificBox). In mirror mode we often
        // don't get codec config in SDP, so derive sane defaults from codec metadata.
        val codecConfig = sdp?.codecConfig ?: deriveDefaultCodecConfig(
            sampleRate = sampleRate,
            channels = channels,
            isAlac = isAlac,
            isEld = isEld,
            samplesPerFrame = sdp?.samplesPerFrame ?: 480,
        )
        codecConfig?.let { cfg ->
            format.setByteBuffer("csd-0", ByteBuffer.wrap(cfg))
            Log.d("AirPlay", "AAC: using csd-0=${cfg.joinToString("") { "%02x".format(it) }}")
        }

        try {
            codec = MediaCodec.createDecoderByType(mimeType).apply {
                configure(format, null, null, 0)
                start()
            }
            Log.d("AirPlay", "AAC: started codec=${sdp?.codec ?: "AAC"} ${sampleRate}Hz/${channels}ch")
        } catch (e: Exception) {
            Log.e("AirPlay", "AAC: failed to init codec: ${e.message}")
        }
    }

    // ── Per-frame decode ──────────────────────────────────────────────────────

    private suspend fun decodeFrame(aacBytes: ByteArray) {
        val c = codec ?: return
        val sampleRate = sdp?.sampleRate ?: 44100
        val channels   = sdp?.channels   ?: 2
        inputFrameCount++

        // Log first 10 input frames for debugging
        if (inputFrameCount <= 10) {
            val hex = aacBytes.take(32).joinToString("") { "%02x".format(it) }
            Log.d("AirPlay", "AAC: input frame#$inputFrameCount size=${aacBytes.size} first32=$hex")
        }

        // Feed AAC frame to codec input
        val inputIdx = withContext(Dispatchers.IO) { c.dequeueInputBuffer(10_000) }
        if (inputIdx >= 0) {
            val buf = c.getInputBuffer(inputIdx) ?: return
            buf.clear()
            buf.put(aacBytes)
            c.queueInputBuffer(inputIdx, 0, aacBytes.size, System.nanoTime() / 1000L, 0)
        } else if (inputFrameCount <= 5) {
            Log.w("AirPlay", "AAC: input buffer unavailable for frame#$inputFrameCount")
        }

        // Drain all available PCM output
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIdx = withContext(Dispatchers.IO) { c.dequeueOutputBuffer(info, 0) }
            if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val fmt = c.outputFormat
                Log.d("AirPlay", "AAC: output format changed: sr=${fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)} " +
                    "ch=${fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} " +
                    "enc=${fmt.getInteger(MediaFormat.KEY_PCM_ENCODING, 2)}")
                continue
            }
            if (outputIdx < 0) break

            val outBuf = c.getOutputBuffer(outputIdx) ?: run {
                c.releaseOutputBuffer(outputIdx, false)
                continue
            }

            val pcm = ByteArray(info.size)
            outBuf.position(info.offset)
            outBuf.get(pcm, 0, info.size)
            c.releaseOutputBuffer(outputIdx, false)

            if (pcm.isNotEmpty()) {
                outputFrameCount++
                _pcmFrames.emit(PcmFrame(pcm, sampleRate, channels))

                // Diagnostic: analyze PCM content for first few frames
                if (outputFrameCount <= 5 || outputFrameCount % 200L == 0L) {
                    // Compute min/max/rms of 16-bit LE samples
                    var minSample = Short.MAX_VALUE.toInt()
                    var maxSample = Short.MIN_VALUE.toInt()
                    var sumSq = 0.0
                    val numSamples = pcm.size / 2
                    for (i in 0 until numSamples) {
                        val sample = (pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)
                        val s = sample.toShort().toInt()
                        if (s < minSample) minSample = s
                        if (s > maxSample) maxSample = s
                        sumSq += s.toDouble() * s.toDouble()
                    }
                    val rms = if (numSamples > 0) Math.sqrt(sumSq / numSamples).toInt() else 0
                    val allZero = minSample == 0 && maxSample == 0
                    Log.d(
                        "AirPlay",
                        "AAC: pcm#$outputFrameCount size=${pcm.size} samples=$numSamples " +
                            "min=$minSample max=$maxSample rms=$rms allZero=$allZero " +
                            "from input=$inputFrameCount",
                    )
                }
            }
        }
    }

    private fun deriveDefaultCodecConfig(
        sampleRate: Int,
        channels: Int,
        isAlac: Boolean,
        isEld: Boolean,
        samplesPerFrame: Int = 480,
    ): ByteArray? {
        if (isAlac) return null

        if (isEld) {
            // Build AAC-ELD AudioSpecificConfig dynamically.
            // Layout (MSB-first): escape(5) extType(6) freqIdx(4) chanCfg(4) flf(1) zeros(12)
            val freqIndex = aacSampleRateIndex(sampleRate) ?: 4 // default 44100
            val chanConfig = channels.coerceIn(1, 7)
            // Per ISO 14496-3 for ER_AAC_ELD:
            //   frameLengthFlag=0 → 512 samples/frame
            //   frameLengthFlag=1 → 480 samples/frame
            // AirPlay spf=480, so use frameLengthFlag=1. UxPlay also uses flf=1 (f8e85000).
            val frameLengthFlag = 1
            val bits = (0b11111 shl 27) or   // escape (audioObjectType > 31)
                (0b000111 shl 21) or          // ext type 7 → ER_AAC_ELD (39)
                (freqIndex shl 17) or
                (chanConfig shl 13) or
                (frameLengthFlag shl 12)
            Log.d(
                "AirPlay",
                "AAC: ELD csd-0 computed: ${sampleRate}Hz/${channels}ch spf=$samplesPerFrame " +
                    "flf=$frameLengthFlag → ${"%08x".format(bits)}",
            )
            return byteArrayOf(
                ((bits ushr 24) and 0xFF).toByte(),
                ((bits ushr 16) and 0xFF).toByte(),
                ((bits ushr 8) and 0xFF).toByte(),
                (bits and 0xFF).toByte(),
            )
        }

        val freqIndex = aacSampleRateIndex(sampleRate) ?: return null
        val safeChannels = channels.coerceIn(1, 7)
        val asc = ((2 and 0x1F) shl 11) or ((freqIndex and 0x0F) shl 7) or ((safeChannels and 0x0F) shl 3)
        return byteArrayOf(
            ((asc ushr 8) and 0xFF).toByte(),
            (asc and 0xFF).toByte(),
        )
    }

    private fun aacSampleRateIndex(sampleRate: Int): Int? =
        when (sampleRate) {
            96_000 -> 0
            88_200 -> 1
            64_000 -> 2
            48_000 -> 3
            44_100 -> 4
            32_000 -> 5
            24_000 -> 6
            22_050 -> 7
            16_000 -> 8
            12_000 -> 9
            11_025 -> 10
            8_000 -> 11
            7_350 -> 12
            else -> null
        }
}
