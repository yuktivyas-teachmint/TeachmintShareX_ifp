package com.teachmint.sharex.airplay

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Parses AirPlay 2 mirror TCP stream (type 110) into H.264 NAL units.
 *
 * The AirPlay mirror TCP protocol uses a simple framing format:
 *
 *   ┌──────────────────────────────────┐
 *   │  128-byte frame header           │
 *   │    [0-3]  payloadSize  (LE u32)  │
 *   │    [4]    payloadType  (u8)      │
 *   │    [8-15] NTP timestamp          │
 *   │    [16+]  display info / padding │
 *   ├──────────────────────────────────┤
 *   │  payload (payloadSize bytes)     │
 *   └──────────────────────────────────┘
 *     ... repeats ...
 *
 * Frame types:
 *   - Type 1 (config): Payload = AVCC DecoderConfigurationRecord (SPS/PPS)
 *   - Type 0 (video):  Payload = AES-128-CTR encrypted H.264 in AVCC format
 *
 * FairPlay stream key derivation (per mirror_buffer.c from RPiPlay/UxPlay):
 *   1. eaeskey = SHA-512(fairplayAesKey + ecdhSecret)[0..15]
 *   2. streamKey = SHA-512("AirPlayStreamKey{connID}" + eaeskey)[0..15]
 *   3. streamIV  = SHA-512("AirPlayStreamIV{connID}"  + eaeskey)[0..15]
 *   4. AES-128-CTR decrypt each video payload (continuous cipher stream)
 *
 * Emitted NAL units are raw bytes (no start codes, no length prefixes)
 * — ready for [H264StreamDecoder] which prepends Annex-B start codes.
 */
class AirPlayMirrorStreamParser(
    private val input: InputStream,
    private val ecdhSecret: ByteArray? = null,
    private val streamConnectionID: Long = 0L,
    private val fairplayAesKey: ByteArray? = null,
    private val fallbackFairplayAesKeys: List<ByteArray> = emptyList(),
    private val fairplayStreamIv: ByteArray? = null,
    private val encryptionType: Int = 1,
) {
    val nalUnits = MutableSharedFlow<ByteArray>(replay = 4, extraBufferCapacity = 64)

    /** AES-128-CTR cipher — continuous stream cipher, always fed block-aligned data. */
    private var aesCipher: Cipher? = null

    /** Cross-frame partial block state (matches RPiPlay's mirror_buffer). */
    private var nextDecryptCount = 0
    private val ogBuf = ByteArray(16)
    private var activeFairplayAesKey: ByteArray? = null
    private var activeLegacyConnIdDerivation: Boolean = false

    suspend fun parse() {
        initDecryption()
        var frameCount = 0
        var configReceived = false
        try {
            while (currentCoroutineContext().isActive) {
                val header = readExact(HEADER_SIZE) ?: break

                val payloadSize = header.readInt32LE(0)
                val payloadType = header[4].toInt() and 0xFF

                if (payloadSize < 0 || payloadSize > MAX_PAYLOAD) {
                    Log.w(TAG, "Invalid payload size $payloadSize (type=$payloadType) — skipping")
                    continue
                }

                if (payloadSize == 0) continue

                val payload = readExact(payloadSize) ?: break

                when (payloadType) {
                    TYPE_CONFIG -> {
                        Log.d(TAG, "Config frame: ${payloadSize}B, first32=${payload.take(32).hex()}")
                        val avcc = parseAvccRecord(payload, 0)
                        if (avcc != null) {
                            val (sps, pps, _) = avcc
                            Log.d(TAG, "SPS (${sps.size}B) NAL type=0x${"%02x".format(sps[0])}")
                            Log.d(TAG, "PPS (${pps.size}B) NAL type=0x${"%02x".format(pps[0])}")
                            nalUnits.emit(sps)
                            nalUnits.emit(pps)
                            configReceived = true
                        } else {
                            Log.w(TAG, "Failed to parse AVCC from config payload")
                        }
                    }

                    TYPE_VIDEO -> {
                        frameCount++

                        if (!configReceived) {
                            if (frameCount <= 3) Log.w(TAG, "Skipping video frame — no config yet")
                            continue
                        }

                        // Decrypt the video payload (CTR mode = stream cipher, continuous state)
                        if (frameCount <= 5) {
                            Log.d(TAG, "Video frame #$frameCount RAW: ${payloadSize}B first16=${payload.take(16).hex()}")
                        }
                        var decrypted = decryptPayload(payload)
                        if (frameCount == 1 && aesCipher != null) {
                            decrypted = maybeSwitchKeyForFirstFrame(payload, decrypted)
                        }

                        if (frameCount <= 5 || frameCount % 300 == 0) {
                            val isEncrypted = aesCipher != null
                            Log.d(TAG, "Video frame #$frameCount: ${payloadSize}B" +
                                    " first16=${decrypted.take(16).hex()}" +
                                    if (isEncrypted) " (decrypted)" else " (raw)")
                        }

                        val emitted = emitVideoNalsAdaptive(
                            data = decrypted,
                            frameNum = frameCount,
                            sourceLabel = "decrypted",
                            // When encrypted, random decrypted bytes can accidentally look
                            // like a single NAL and poison decoder state.
                            allowSingleRawFallback = aesCipher == null,
                        )

                        // Some macOS streams can arrive as already-usable payloads even when
                        // an encryption context is negotiated. If decrypted bytes are not
                        // parseable but raw payload is parseable in structured formats (AVCC /
                        // Annex-B), use the raw output for this frame only.
                        //
                        // IMPORTANT:
                        // Do NOT disable AES decryption based on single-raw-NAL heuristics.
                        // Random encrypted bytes can look like a plausible raw NAL by chance
                        // and permanently switching to raw pass-through causes persistent
                        // frozen/blank AirPlay tiles.
                        if (emitted == 0 && aesCipher != null) {
                            val rawEmitted = emitVideoNalsAdaptive(
                                data = payload,
                                frameNum = frameCount,
                                sourceLabel = "raw-fallback",
                                allowSingleRawFallback = false,
                            )
                            if (rawEmitted > 0) {
                                Log.d(
                                    TAG,
                                    "Frame #$frameCount: decrypted parse failed but raw payload parsed " +
                                        "($rawEmitted NALs). Keeping AES decryption enabled.",
                                )
                            }
                        }
                    }

                    else -> {
                        if (frameCount <= 5) {
                            Log.d(TAG, "Unknown frame type=$payloadType size=$payloadSize")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Stream ended: ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.d(TAG, "Parser finished — $frameCount video frames processed")
    }

    // ── FairPlay AES-128-CTR decryption ─────────────────────────────────────────

    private fun initDecryption(
        keyOverride: ByteArray? = null,
        attemptLabel: String = "primary",
        forceLegacyConnIdDerivation: Boolean = false,
        forceEcdhMix: Boolean = false,
    ): Boolean {
        Log.d(TAG, "initDecryption[$attemptLabel]: et=$encryptionType")

        if (encryptionType == 0) {
            Log.d(TAG, "Encryption type et=0 — stream is unencrypted, skipping decryption init")
            aesCipher = null
            activeFairplayAesKey = null
            return true
        }

        val aesKey = keyOverride ?: fairplayAesKey
        if (aesKey == null) {
            Log.w(TAG, "Decryption not available — no fairplayAesKey")
            return false
        }
        activeFairplayAesKey = aesKey.copyOf()
        activeLegacyConnIdDerivation = forceLegacyConnIdDerivation

        val sha512 = MessageDigest.getInstance("SHA-512")
        val connIdStr = streamConnectionID.toULong().toString()
        val ecdh = ecdhSecret

        val directIv = fairplayStreamIv?.takeIf { it.size >= 16 }?.copyOf(16)
        val useDirectKeyIv = !forceLegacyConnIdDerivation &&
            encryptionType >= 32 &&
            directIv != null &&
            aesKey.size >= 16

        val streamKey: ByteArray
        val streamIV: ByteArray
        if (useDirectKeyIv) {
            // Modern AirPlay (et>=32) sends explicit eiv; deriving IV from connID can
            // produce unreadable payloads on macOS mirror sessions.
            streamKey = aesKey.copyOf(16)
            streamIV = directIv
            Log.d(
                TAG,
                "Using direct stream key+iv for et=$encryptionType [$attemptLabel] " +
                    "key=${streamKey.hex()} iv=${streamIV.hex()}",
            )
        } else {
            if (forceLegacyConnIdDerivation && encryptionType >= 32) {
                Log.d(TAG, "Using legacy connID key/iv derivation fallback for et=$encryptionType [$attemptLabel]")
            }
            if (forceEcdhMix && ecdh != null) {
                Log.d(TAG, "Using forced ECDH mix fallback for et=$encryptionType [$attemptLabel]")
            }
            if (encryptionType >= 32 && directIv == null) {
                Log.w(TAG, "et=$encryptionType without eiv — falling back to connID-derived IV")
            }

            // Derive the effective key used for stream key/IV derivation.
            // Legacy et=1/2 paths use the ECDH mix-in.
            // Modern AirPlay sessions (for example et=32 on macOS) already provide the
            // post-FairPlay key material; mixing ECDH again breaks video decryption.
            val effectiveKey: ByteArray
            val useEcdhMix = ecdh != null && (
                encryptionType == 1 ||
                    encryptionType == 2 ||
                    forceEcdhMix
                )
            if (useEcdhMix) {
                Log.d(TAG, "Deriving eaeskey: et=$encryptionType aesKey(${aesKey.size}B)=${aesKey.hex()} ecdh(${ecdh.size}B)=${ecdh.hex()} connID=$connIdStr (raw=$streamConnectionID)")
                sha512.update(aesKey, 0, aesKey.size.coerceAtMost(16))
                sha512.update(ecdh, 0, ecdh.size.coerceAtMost(32))
                effectiveKey = sha512.digest().copyOf(16)
                Log.d(TAG, "eaeskey: ${effectiveKey.hex()}")
            } else {
                Log.d(
                    TAG,
                    "Using aesKey directly (et=$encryptionType, ecdhAvail=${ecdh != null}, " +
                        "useEcdhMix=$useEcdhMix): aesKey(${aesKey.size}B)=${aesKey.hex()} connID=$connIdStr",
                )
                effectiveKey = aesKey.copyOf(aesKey.size.coerceAtMost(16))
            }

            // Derive streamKey = SHA-512("AirPlayStreamKey{connID}" + effectiveKey)[0..15]
            val keyLabel = "AirPlayStreamKey$connIdStr"
            sha512.reset()
            sha512.update(keyLabel.toByteArray(Charsets.UTF_8))
            sha512.update(effectiveKey, 0, 16)
            streamKey = sha512.digest().copyOf(16)

            // Derive streamIV = SHA-512("AirPlayStreamIV{connID}" + effectiveKey)[0..15]
            val ivLabel = "AirPlayStreamIV$connIdStr"
            sha512.reset()
            sha512.update(ivLabel.toByteArray(Charsets.UTF_8))
            sha512.update(effectiveKey, 0, 16)
            streamIV = sha512.digest().copyOf(16)

            Log.d(TAG, "Stream key=${streamKey.hex()}, IV=${streamIV.hex()}")
            Log.d(TAG, "Key label='$keyLabel', IV label='$ivLabel'")
        }

        aesCipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(streamKey, "AES"), IvParameterSpec(streamIV))
        }
        nextDecryptCount = 0
        ogBuf.fill(0)

        Log.d(TAG, "AES-128-CTR decryption initialized (et=$encryptionType, attempt=$attemptLabel)")
        return true
    }

    private fun maybeSwitchKeyForFirstFrame(payload: ByteArray, decrypted: ByteArray): ByteArray {
        val nalLen = if (decrypted.size >= 4) decrypted.readInt32BE(0) else -1
        val primaryProbe = findBestAvccProbe(decrypted, maxProbeOffset = minOf(4096, decrypted.size - 8))
        val primaryValidStrict = looksLikeVideoPayload(data = decrypted, requireStrongProbe = true)
        val primaryValidRelaxed = looksLikeVideoPayload(data = decrypted, requireStrongProbe = false)
        Log.d(
            TAG,
            "Frame #1 NAL length check: nalLen=$nalLen payloadSize=${payload.size} " +
                "strict=$primaryValidStrict relaxed=$primaryValidRelaxed " +
                "probe=${primaryProbe?.toDebugString() ?: "none"}",
        )
        if (primaryValidStrict) return decrypted

        Log.w(TAG, "⚠ First frame NAL length is invalid — decryption key may be wrong!")
        Log.w(TAG, "  first32=${decrypted.take(32).hex()}")

        if (fallbackFairplayAesKeys.isEmpty()) return decrypted

        val primaryKey = activeFairplayAesKey
        val shouldTryLegacyForEt32 = encryptionType >= 32 && fairplayStreamIv?.size ?: 0 >= 16

        data class Probe(
            val key: ByteArray,
            val label: String,
            val forceLegacy: Boolean,
            val forceEcdhMix: Boolean,
        )
        val probes = mutableListOf<Probe>()

        if (shouldTryLegacyForEt32 && primaryKey != null) {
            probes += Probe(primaryKey, "primary-legacy", true, false)
            probes += Probe(primaryKey, "primary-legacy-ecdh", true, true)
        }

        fallbackFairplayAesKeys.forEachIndexed { index, candidateRaw ->
            if (candidateRaw.isEmpty()) return@forEachIndexed
            if (primaryKey != null && candidateRaw.contentEquals(primaryKey) && !shouldTryLegacyForEt32) return@forEachIndexed
            probes += Probe(candidateRaw, "fallback#$index", false, false)
            if (shouldTryLegacyForEt32) {
                probes += Probe(candidateRaw, "fallback#$index-legacy", true, false)
                probes += Probe(candidateRaw, "fallback#$index-legacy-ecdh", true, true)
            }
        }

        var relaxedFallback: Probe? = null
        for (probe in probes) {
            if (!initDecryption(
                    keyOverride = probe.key,
                    attemptLabel = probe.label,
                    forceLegacyConnIdDerivation = probe.forceLegacy,
                    forceEcdhMix = probe.forceEcdhMix,
                )
            ) {
                continue
            }
            val trial = decryptPayload(payload)
            val trialNalLen = if (trial.size >= 4) trial.readInt32BE(0) else -1
            val trialProbe = findBestAvccProbe(trial, maxProbeOffset = trial.size - 8)
            val trialValidStrict = looksLikeVideoPayload(data = trial, requireStrongProbe = true)
            val trialValidRelaxed = looksLikeVideoPayload(data = trial, requireStrongProbe = false)
            Log.d(
                TAG,
                "Frame #1 ${probe.label} probe: nalLen=$trialNalLen " +
                    "strict=$trialValidStrict relaxed=$trialValidRelaxed " +
                    "probe=${trialProbe?.toDebugString() ?: "none"} " +
                    "first16=${trial.take(16).hex()}",
            )
            if (trialValidStrict) {
                Log.w(TAG, "Frame #1: switched to ${probe.label} decryption mode")
                return trial
            }
            if (trialValidRelaxed && relaxedFallback == null) {
                relaxedFallback = probe
            }
        }

        relaxedFallback?.let { probe ->
            if (initDecryption(
                    keyOverride = probe.key,
                    attemptLabel = "${probe.label}-relaxed",
                    forceLegacyConnIdDerivation = probe.forceLegacy,
                    forceEcdhMix = probe.forceEcdhMix,
                )
            ) {
                val trial = decryptPayload(payload)
                Log.w(TAG, "Frame #1: switched to ${probe.label} using relaxed validation")
                return trial
            }
        }

        // Restore primary decrypt state so subsequent frames stay aligned.
        if (primaryKey != null && initDecryption(
                keyOverride = primaryKey,
                attemptLabel = "restore-primary",
                forceLegacyConnIdDerivation = false,
                forceEcdhMix = false,
            )
        ) {
            return decryptPayload(payload)
        }
        return decrypted
    }

    private fun looksLikeVideoPayload(
        data: ByteArray,
        requireStrongProbe: Boolean,
    ): Boolean {
        val probe = findBestAvccProbe(data, maxProbeOffset = 4096)
        if (probe != null) {
            if (!requireStrongProbe || probe.nalCount >= 2) {
                return true
            }
        }
        return hasLikelyAnnexB(data)
    }

    private data class AvccProbe(
        val offset: Int,
        val littleEndianLength: Boolean,
        val nalCount: Int,
    ) {
        fun toDebugString(): String =
            "offset=$offset endian=${if (littleEndianLength) "LE" else "BE"} nals=$nalCount"
    }

    private fun findBestAvccProbe(data: ByteArray, maxProbeOffset: Int): AvccProbe? {
        if (data.size < 8) return null
        val maxOffset = minOf(maxProbeOffset, data.size - 8)
        var best: AvccProbe? = null

        fun consider(offset: Int, littleEndian: Boolean) {
            val count = countLikelyAvccNals(data = data, startOffset = offset, littleEndianLength = littleEndian)
            if (count <= 0) return
            val candidate = AvccProbe(offset = offset, littleEndianLength = littleEndian, nalCount = count)
            if (best == null ||
                candidate.nalCount > best!!.nalCount ||
                (candidate.nalCount == best!!.nalCount && candidate.offset < best!!.offset)
            ) {
                best = candidate
            }
        }

        for (offset in 0..maxOffset) {
            consider(offset, littleEndian = false)
            consider(offset, littleEndian = true)
        }
        return best
    }

    private fun countLikelyAvccNals(
        data: ByteArray,
        startOffset: Int,
        littleEndianLength: Boolean,
    ): Int {
        var offset = startOffset
        var count = 0
        while (offset + 4 <= data.size && count < 10) {
            val nalLen = if (littleEndianLength) data.readInt32LE(offset) else data.readInt32BE(offset)
            offset += 4
            // Use Long arithmetic to prevent integer overflow when nalLen is very large
            if (nalLen <= 0 || offset.toLong() + nalLen.toLong() > data.size) break
            val nalType = data[offset].toInt() and 0x1F
            if (nalType !in 1..23) return 0
            count++
            offset += nalLen
        }
        return count
    }

    private fun hasLikelyAnnexB(data: ByteArray): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            val isStartCode4 =
                data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() &&
                    data[i + 3] == 1.toByte()
            val isStartCode3 =
                data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte()
            if (isStartCode4 || isStartCode3) {
                val startCodeLen = if (isStartCode4) 4 else 3
                val nalIndex = i + startCodeLen
                if (nalIndex < data.size) {
                    val nalType = data[nalIndex].toInt() and 0x1F
                    if (nalType in 1..23) return true
                }
                i += startCodeLen
                continue
            }
            i++
        }
        return false
    }

    /**
     * Decrypts a video payload using AES-128-CTR, matching RPiPlay's mirror_buffer_decrypt().
     *
     * The algorithm handles cross-frame partial AES blocks:
     * 1. XOR leading bytes with saved keystream from previous frame's last partial block
     * 2. Decrypt aligned 16-byte blocks via the cipher
     * 3. For trailing partial block: pad to 16 bytes, decrypt full block, save unused keystream
     *
     * IMPORTANT: We always feed block-aligned data to cipher.update() so the Java cipher
     * doesn't buffer anything internally. This ensures the CTR counter advances identically
     * to RPiPlay's implementation.
     */
    private fun decryptPayload(input: ByteArray): ByteArray {
        val cipher = aesCipher ?: return input
        val output = ByteArray(input.size)
        var inputOffset = 0

        // Step 1: XOR prefix with saved keystream bytes from previous frame
        if (nextDecryptCount > 0) {
            val n = nextDecryptCount.coerceAtMost(input.size)
            val startIdx = 16 - nextDecryptCount
            for (i in 0 until n) {
                output[i] = (input[i].toInt() xor ogBuf[startIdx + i].toInt()).toByte()
            }
            inputOffset = n
        }

        // Step 2: Decrypt aligned blocks (always block-aligned for cipher.update)
        val remaining = input.size - inputOffset
        val encryptLen = (remaining / 16) * 16
        if (encryptLen > 0) {
            val decrypted = cipher.update(input, inputOffset, encryptLen)
            if (decrypted != null) {
                System.arraycopy(decrypted, 0, output, inputOffset, decrypted.size)
            }
        }

        // Step 3: Handle trailing partial block — pad to 16 bytes and decrypt full block
        val restLen = remaining % 16
        nextDecryptCount = 0
        if (restLen > 0) {
            val restStart = input.size - restLen
            ogBuf.fill(0)
            System.arraycopy(input, restStart, ogBuf, 0, restLen)
            // Decrypt a full 16-byte block (advances CTR counter by 1, matching RPiPlay)
            val decryptedOg = cipher.update(ogBuf)
            if (decryptedOg != null) {
                System.arraycopy(decryptedOg, 0, ogBuf, 0, 16)
            }
            // Copy only the actual data bytes to output
            for (j in 0 until restLen) {
                output[restStart + j] = ogBuf[j]
            }
            // Save remaining keystream bytes for next frame's prefix
            nextDecryptCount = 16 - restLen
        }

        return output
    }

    // ── AVCC record parsing ────────────────────────────────────────────────────

    private fun parseAvccRecord(data: ByteArray, offset: Int): Triple<ByteArray, ByteArray, Int>? {
        var avccStart = -1
        for (i in offset until data.size - 8) {
            if (data[i] == 0x01.toByte() &&
                data[i + 4] == 0xFF.toByte() &&
                (data[i + 5].toInt() and 0xE0) == 0xE0
            ) {
                val profile = data[i + 1].toInt() and 0xFF
                if (profile in VALID_PROFILES) {
                    avccStart = i
                    break
                }
            }
        }

        if (avccStart < 0) return null

        var pos = avccStart + 5
        val numSps = data[pos].toInt() and 0x1F
        pos++

        var sps: ByteArray? = null
        for (i in 0 until numSps) {
            if (pos + 2 > data.size) return null
            val spsLen = data.readInt16BE(pos)
            pos += 2
            if (spsLen <= 0 || pos + spsLen > data.size) return null
            sps = data.copyOfRange(pos, pos + spsLen)
            pos += spsLen
        }

        if (pos >= data.size) return null
        val numPps = data[pos].toInt() and 0xFF
        pos++

        var pps: ByteArray? = null
        for (i in 0 until numPps) {
            if (pos + 2 > data.size) return null
            val ppsLen = data.readInt16BE(pos)
            pos += 2
            if (ppsLen <= 0 || pos + ppsLen > data.size) return null
            pps = data.copyOfRange(pos, pos + ppsLen)
            pos += ppsLen
        }

        if (sps == null || pps == null) return null
        return Triple(sps, pps, pos)
    }

    // ── NAL unit emission (AVCC format) ────────────────────────────────────────

    private suspend fun emitVideoNalsAdaptive(
        data: ByteArray,
        frameNum: Int,
        sourceLabel: String,
        allowSingleRawFallback: Boolean,
    ): Int {
        // 1) AVCC (length-prefixed) — expected on most AirPlay mirror streams.
        val avccCount = emitAvccNals(data, frameNum, sourceLabel)
        if (avccCount > 0) return avccCount

        // 2) Some senders prepend per-frame metadata before AVCC payload.
        // Probe for an embedded AVCC start offset (BE/LE length prefixes).
        val probe = findBestAvccProbe(data, maxProbeOffset = 4096)
        if (probe != null && (probe.offset > 0 || probe.littleEndianLength)) {
            val embeddedAvccCount = emitAvccNals(
                data = data,
                frameNum = frameNum,
                sourceLabel = "$sourceLabel+probe",
                startOffset = probe.offset,
                littleEndianLength = probe.littleEndianLength,
                logInvalidFirstNal = false,
            )
            if (embeddedAvccCount > 0) {
                Log.d(TAG, "Frame #$frameNum [$sourceLabel]: AVCC probe hit ${probe.toDebugString()}")
                return embeddedAvccCount
            }
        }
        // Some senders carry a large encrypted/metadata prefix before AVCC payload.
        // For early frames, scan the full payload once before giving up.
        if (frameNum <= 10 && data.size > 4096) {
            val deepProbe = findBestAvccProbe(data, maxProbeOffset = data.size - 8)
            if (deepProbe != null && (deepProbe.offset > 0 || deepProbe.littleEndianLength)) {
                val deepCount = emitAvccNals(
                    data = data,
                    frameNum = frameNum,
                    sourceLabel = "$sourceLabel+deep-probe",
                    startOffset = deepProbe.offset,
                    littleEndianLength = deepProbe.littleEndianLength,
                    logInvalidFirstNal = false,
                )
                if (deepCount > 0) {
                    Log.d(TAG, "Frame #$frameNum [$sourceLabel]: deep AVCC probe hit ${deepProbe.toDebugString()}")
                    return deepCount
                }
            }
        }

        // 3) Annex-B (start-code delimited).
        val annexBCount = emitAnnexBNals(data, frameNum, sourceLabel)
        if (annexBCount > 0) return annexBCount

        // 4) Single raw NAL fallback.
        val nalType = data.firstOrNull()?.toInt()?.and(0x1F) ?: -1
        val isLikelySingleNal = allowSingleRawFallback && nalType in 1..12
        if (isLikelySingleNal) {
            nalUnits.emit(data)
            if (frameNum <= 10 || frameNum % 300 == 0) {
                Log.d(
                    TAG,
                    "Frame #$frameNum [$sourceLabel]: emitted single raw NAL " +
                        "(type=$nalType size=${data.size})",
                )
            }
            return 1
        }

        if (frameNum <= 10 || frameNum % 300 == 0) {
            Log.d(
                TAG,
                "Frame #$frameNum [$sourceLabel]: no parseable NAL units " +
                    "(size=${data.size}, first16=${data.take(16).hex()})",
            )
        }
        return 0
    }

    private suspend fun emitAvccNals(
        data: ByteArray,
        frameNum: Int,
        sourceLabel: String,
    ): Int = emitAvccNals(
        data = data,
        frameNum = frameNum,
        sourceLabel = sourceLabel,
        startOffset = 0,
        littleEndianLength = false,
        logInvalidFirstNal = true,
    )

    private suspend fun emitAvccNals(
        data: ByteArray,
        frameNum: Int,
        sourceLabel: String,
        startOffset: Int,
        littleEndianLength: Boolean,
        logInvalidFirstNal: Boolean,
    ): Int {
        if (startOffset !in 0 until data.size) return 0
        var offset = startOffset
        var nalCount = 0

        while (offset + 4 <= data.size) {
            val nalLen = if (littleEndianLength) data.readInt32LE(offset) else data.readInt32BE(offset)
            offset += 4

            // Use Long arithmetic to prevent integer overflow when nalLen is very large
            if (nalLen <= 0 || offset.toLong() + nalLen.toLong() > data.size) {
                if (logInvalidFirstNal && nalCount == 0 && frameNum <= 10) {
                    Log.w(
                        TAG,
                        "Frame #$frameNum [$sourceLabel]: invalid AVCC NAL len=$nalLen " +
                            "at offset ${offset - 4} startOffset=$startOffset " +
                            "endian=${if (littleEndianLength) "LE" else "BE"} " +
                            "dataSize=${data.size} first16=${data.take(16).hex()}",
                    )
                }
                break
            }

            val nal = data.copyOfRange(offset, offset + nalLen)
            if (frameNum <= 5) {
                val nalType = if (nal.isNotEmpty()) nal[0].toInt() and 0x1F else -1
                Log.d(TAG, "Frame #$frameNum NAL: type=$nalType len=$nalLen")
            }
            nalUnits.emit(nal)
            offset += nalLen
            nalCount++
        }

        if (frameNum <= 10 || frameNum % 300 == 0) {
            Log.d(
                TAG,
                "Frame #$frameNum [$sourceLabel]: emitted $nalCount AVCC NAL units " +
                    "(dataSize=${data.size}, startOffset=$startOffset, endian=${if (littleEndianLength) "LE" else "BE"})",
            )
        }
        return nalCount
    }

    private suspend fun emitAnnexBNals(
        data: ByteArray,
        frameNum: Int,
        sourceLabel: String,
    ): Int {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        var currentStart = -1

        while (i + 3 < data.size) {
            val isStartCode4 =
                data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() &&
                    data[i + 3] == 1.toByte()
            val isStartCode3 =
                data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte()

            if (isStartCode4 || isStartCode3) {
                val startCodeLen = if (isStartCode4) 4 else 3
                if (currentStart >= 0 && i > currentStart) {
                    ranges.add(currentStart until i)
                }
                currentStart = i + startCodeLen
                i += startCodeLen
                continue
            }
            i++
        }

        if (currentStart >= 0 && currentStart < data.size) {
            ranges.add(currentStart until data.size)
        }

        var emitted = 0
        for (range in ranges) {
            if (range.first >= range.last) continue
            val nal = data.copyOfRange(range.first, range.last)
            if (nal.isEmpty()) continue
            val nalType = nal[0].toInt() and 0x1F
            if (nalType !in 1..23) continue
            nalUnits.emit(nal)
            emitted++
        }

        if ((frameNum <= 10 || frameNum % 300 == 0) && emitted > 0) {
            Log.d(
                TAG,
                "Frame #$frameNum [$sourceLabel]: emitted $emitted Annex-B NAL units " +
                    "(dataSize=${data.size})",
            )
        }
        return emitted
    }

    // ── I/O ────────────────────────────────────────────────────────────────────

    private fun readExact(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read < 0) return if (offset > 0) buf.copyOf(offset) else null
            offset += read
        }
        return buf
    }

    companion object {
        private const val TAG = "AirPlay/Mirror"
        private const val HEADER_SIZE = 128
        private const val MAX_PAYLOAD = 10_000_000
        private const val TYPE_CONFIG = 1
        private const val TYPE_VIDEO = 0
        private val VALID_PROFILES = listOf(66, 77, 88, 100, 110, 122, 244)
    }
}

// ── ByteArray helpers (file-private) ─────────────────────────────────────────

private fun ByteArray.readInt32BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readInt32LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.readInt16BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or
            (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.hex(): String =
    joinToString("") { "%02x".format(it) }

private fun List<Byte>.hex(): String =
    joinToString("") { "%02x".format(it) }
