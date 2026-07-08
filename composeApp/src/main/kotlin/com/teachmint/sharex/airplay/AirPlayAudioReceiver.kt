package com.teachmint.sharex.airplay

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Receives AAC audio over RTP/UDP and emits raw AAC access units.
 *
 * Parses MPEG4-GENERIC RTP payload (AU-headers + AU data).
 */
class AirPlayAudioReceiver(
    private val rtpPort: Int,
    private val rtcpPort: Int = 0,
    private val sdp: AirPlayAudioSdp?,
) {
    private enum class DecryptMode { AUTO, RAW, CTR, CBC }
    private data class DecryptCandidate(val mode: DecryptMode, val keyIndex: Int = -1, val ivIndex: Int = -1)
    private data class AccessUnitSlice(val start: Int, val end: Int, val isMarker: Boolean)
    private data class ParseResult(val slices: List<AccessUnitSlice>, val source: String)

    private companion object {
        private const val MAX_AU_SIZE = 4096
        private const val MAX_AU_COUNT = 8
        private val VALID_AAC_ELD_FIRST_BYTES = setOf(0x8c, 0x8d, 0x8e, 0x80, 0x81, 0x82, 0x20)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rtpSocket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null
    private val isAppleLossless: Boolean =
        sdp?.codec?.contains("AppleLossless", ignoreCase = true) == true ||
            sdp?.codec?.contains("ALAC", ignoreCase = true) == true
    private val isAacEld: Boolean =
        sdp?.codec?.contains("ELD", ignoreCase = true) == true

    private val _aacFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 200)
    val aacFrames: SharedFlow<ByteArray> = _aacFrames
    private var packetCount = 0L
    private var emittedFrameCount = 0L
    private var rtcpPacketCount = 0L
    private var auParseFallbackCount = 0L
    private var auLittleEndianParseCount = 0L
    private var nonAudioPayloadTypeDropCount = 0L
    private var duplicateTimestampCoalesceCount = 0L
    private var markerFrameDropCount = 0L
    private var decryptModeSwitchCount = 0L
    private var decryptKeySwitchCount = 0L
    private var decryptIvSwitchCount = 0L
    private var decryptFailureCount = 0L
    private var redundantDropCount = 0L
    private var invalidEldHeaderCount = 0L
    private var eldRecoverySwitchCount = 0L
    private var pendingTimestamp: Long = Long.MIN_VALUE
    private var pendingPayload: ByteArray? = null
    // Ring buffer of recent payload hashes for dedup (redundantAudio interleaves A,B,A,B)
    private val recentPayloadHashes = LongArray(4)
    private var recentHashIndex = 0
    private val expectedPayloadType: Int = sdp?.payloadType ?: 96
    private val audioEncryptionType: Int = sdp?.encryptionType ?: 0
    private val audioAesKey: ByteArray? = sdp?.aesKey?.takeIf { it.size >= 16 }?.copyOf(16)
    private val audioAesKeys: List<ByteArray> = buildList {
        sdp?.aesKeyCandidates?.forEach { key ->
            if (key.size >= 16) add(key.copyOf(16))
        }
        audioAesKey?.let { primary ->
            if (none { it.contentEquals(primary) }) add(primary)
        }
    }
    private val audioAesIv: ByteArray? = sdp?.aesIv?.takeIf { it.size >= 16 }?.copyOf(16)
    private val audioAesIvs: List<ByteArray> = buildList {
        sdp?.aesIvCandidates?.forEach { iv ->
            if (iv.size >= 16) add(iv.copyOf(16))
        }
        audioAesIv?.let { primary ->
            if (none { it.contentEquals(primary) }) add(primary)
        }
    }
    private val canAttemptDecrypt: Boolean = audioEncryptionType != 0 && audioAesKeys.isNotEmpty() && audioAesIvs.isNotEmpty()
    private var preferredDecryptMode: DecryptMode = if (canAttemptDecrypt) DecryptMode.AUTO else DecryptMode.RAW
    private var preferredDecryptKeyIndex: Int = 0
    private var preferredIvIndex: Int = 0

    fun start() {
        scope.launch { receiveLoop() }
        if (rtcpPort > 0) {
            scope.launch { receiveControlLoop() }
        }
    }

    fun stop() {
        scope.cancel()
        rtpSocket?.close()
        rtpSocket = null
        rtcpSocket?.close()
        rtcpSocket = null
    }

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val sock = DatagramSocket(rtpPort).also { rtpSocket = it }
        sock.soTimeout = 200
        Log.d(
            "AirPlay",
            "AudioRTP: listening on UDP port $rtpPort codec=${sdp?.codec ?: "unknown"} " +
                "encrypted=$canAttemptDecrypt et=$audioEncryptionType " +
                "keys=${audioAesKeys.size} ivs=${audioAesIvs.size}",
        )

        val buf    = ByteArray(65535)
        val packet = DatagramPacket(buf, buf.size)

        try {
            while (isActive && !sock.isClosed) {
                try {
                    sock.receive(packet)
                    packetCount++
                    processPacket(buf, packet.length)
                    if (packetCount == 1L || packetCount % 200L == 0L) {
                        Log.d(
                            "AirPlay",
                            "AudioRTP: packets=$packetCount emitted=$emittedFrameCount codec=${sdp?.codec ?: "unknown"}",
                        )
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: SocketException) {
                    if (isActive) Log.e("AirPlay", "AudioRTP: socket error: ${e.message}")
                    break
                }
            }
        } finally {
            flushPendingTimestampCandidate()
        }
    }

    private suspend fun receiveControlLoop() = withContext(Dispatchers.IO) {
        val sock = DatagramSocket(rtcpPort).also { rtcpSocket = it }
        sock.soTimeout = 200
        Log.d("AirPlay", "AudioRTCP: listening on UDP port $rtcpPort")

        val buf = ByteArray(1500)
        val packet = DatagramPacket(buf, buf.size)

        while (isActive && !sock.isClosed) {
            try {
                sock.receive(packet)
                rtcpPacketCount++
                if (rtcpPacketCount == 1L || rtcpPacketCount % 100L == 0L) {
                    Log.d("AirPlay", "AudioRTCP: packets=$rtcpPacketCount")
                }
            } catch (_: SocketTimeoutException) {
            } catch (e: SocketException) {
                if (isActive) Log.e("AirPlay", "AudioRTCP: socket error: ${e.message}")
                break
            }
        }
    }

    private suspend fun processPacket(data: ByteArray, length: Int) {
        if (length < 12) return

        val version = (data[0].toInt() ushr 6) and 0x3
        if (version != 2) return
        val payloadType = data[1].toInt() and 0x7F
        val rtpTimestamp = readUInt32(data, 4)
        if (expectedPayloadType in 0..127 && payloadType != expectedPayloadType) {
            nonAudioPayloadTypeDropCount++
            if (nonAudioPayloadTypeDropCount == 1L || nonAudioPayloadTypeDropCount % 200L == 0L) {
                Log.d(
                    "AirPlay",
                    "AudioRTP: dropping payloadType=$payloadType expected=$expectedPayloadType " +
                        "drops=$nonAudioPayloadTypeDropCount",
                )
            }
            return
        }
        val hasExt  = (data[0].toInt() and 0x10) != 0
        val csrcCnt = data[0].toInt() and 0x0F

        var payloadOffset = 12 + csrcCnt * 4
        if (hasExt && payloadOffset + 4 <= length) {
            val extLen = readUShort(data, payloadOffset + 2)
            payloadOffset += 4 + extLen * 4
        }
        if (payloadOffset >= length) return
        bufferByTimestampAndMaybeEmit(rtpTimestamp, data, payloadOffset, length)
    }

    private suspend fun bufferByTimestampAndMaybeEmit(
        rtpTimestamp: Long,
        data: ByteArray,
        payloadOffset: Int,
        length: Int,
    ) {
        val payloadLen = length - payloadOffset
        if (payloadLen <= 0) return
        val candidate = data.copyOfRange(payloadOffset, length)

        if (pendingTimestamp == Long.MIN_VALUE) {
            pendingTimestamp = rtpTimestamp
            pendingPayload = candidate
            return
        }

        if (rtpTimestamp == pendingTimestamp) {
            duplicateTimestampCoalesceCount++
            val current = pendingPayload
            if (current == null || candidate.size > current.size) {
                pendingPayload = candidate
            }
            if (duplicateTimestampCoalesceCount == 1L || duplicateTimestampCoalesceCount % 200L == 0L) {
                Log.d(
                    "AirPlay",
                    "AudioRTP: coalescing duplicate timestamp=$rtpTimestamp " +
                        "count=$duplicateTimestampCoalesceCount",
                )
            }
            return
        }

        flushPendingTimestampCandidate()
        pendingTimestamp = rtpTimestamp
        pendingPayload = candidate
    }

    private suspend fun flushPendingTimestampCandidate() {
        val payload = pendingPayload ?: return
        emitPayloadAsAccessUnits(payload, 0, payload.size)
        pendingPayload = null
        pendingTimestamp = Long.MIN_VALUE
    }

    private suspend fun emitPayloadAsAccessUnits(
        data: ByteArray,
        payloadOffset: Int,
        length: Int,
    ) {
        val remaining = length - payloadOffset
        if (remaining <= 0) return
        val rawPayload =
            if (payloadOffset == 0 && length == data.size) data else data.copyOfRange(payloadOffset, length)

        // Diagnostic: on early packets, dump decrypt results for every key+iv+mode
        // combination so we can identify the correct decryption.
        if (emittedFrameCount == 0L && packetCount <= 5 && canAttemptDecrypt && audioAesIvs.isNotEmpty()) {
            Log.d("AirPlay", "AudioRTP: DIAG pkt#$packetCount raw first8=${rawPayload.toHex(8)} size=${rawPayload.size} numIVs=${audioAesIvs.size} numKeys=${audioAesKeys.size}")
            for ((ivi, iv) in audioAesIvs.withIndex()) {
                Log.d("AirPlay", "AudioRTP: DIAG iv[$ivi]=${iv.toHex(16)}")
                for ((ki, key) in audioAesKeys.withIndex()) {
                    for (mode in listOf("CBC", "CTR")) {
                        try {
                            val result = if (mode == "CBC") {
                                val encLen = (rawPayload.size / 16) * 16
                                val out = rawPayload.copyOf()
                                if (encLen > 0) {
                                    Cipher.getInstance("AES/CBC/NoPadding").run {
                                        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                        doFinal(rawPayload, 0, encLen, out, 0)
                                    }
                                }
                                out
                            } else {
                                Cipher.getInstance("AES/CTR/NoPadding").run {
                                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                    doFinal(rawPayload)
                                }
                            }
                            val first8 = result.toHex(8)
                            val len16 = ((result[0].toInt() and 0xFF) shl 8) or (result[1].toInt() and 0xFF)
                            val lenOk = len16 in 1..result.size
                            val auParsed = parseStructuredAccessUnits(result) != null
                            Log.d("AirPlay", "AudioRTP: DIAG key[$ki] iv[$ivi] $mode first8=$first8 len16=$len16 lenOk=$lenOk auParsed=$auParsed")
                        } catch (e: Exception) {
                            Log.d("AirPlay", "AudioRTP: DIAG key[$ki] iv[$ivi] $mode err=${e.message}")
                        }
                    }
                }
            }
        }

        // Apple Lossless streams carry one ALAC access unit per RTP packet
        // (no MPEG4-GENERIC AU headers).
        if (isAppleLossless) {
            emitAudioFrame(rawPayload, 0, rawPayload.size)
            return
        }

        // AAC-ELD mirror audio: each RTP payload is a single raw AAC-ELD access unit.
        // No AU-header section, no length prefix — feed directly to the decoder.
        // Drop redundant duplicates (AirPlay sends each frame twice when redundantAudio=true).
        if (isAacEld) {
            // Marker frames (00 68 34 00) are 4-byte raw silence/sync packets that AirPlay sends
            // before real audio begins. They must be dropped BEFORE the key probe because
            // AES-CBC of a 4-byte payload is identity (no full 16-byte block), so the probe
            // would see garbage scores and select the wrong key, corrupting all subsequent frames.
            if (isAirPlayAacMarkerFrame(rawPayload, 0, rawPayload.size)) {
                recordMarkerDrop()
                return
            }
            val hash = rawPayload.contentHashCode().toLong() xor (rawPayload.size.toLong() shl 32)
            if (recentPayloadHashes.contains(hash)) {
                redundantDropCount++
                if (redundantDropCount == 1L || redundantDropCount % 500L == 0L) {
                    Log.d("AirPlay", "AudioRTP: dropped redundant ELD frame count=$redundantDropCount")
                }
                return
            }
            recentPayloadHashes[recentHashIndex % recentPayloadHashes.size] = hash
            recentHashIndex++
            if (canAttemptDecrypt && emittedFrameCount == 0L) {
                probeEldDecryptionKeyAndMode(rawPayload)
            }
            var frameCandidate = currentPreferredCandidate()
            var frame = payloadForCandidate(rawPayload, frameCandidate)
            var firstByte = if (frame.isNotEmpty()) frame[0].toInt() and 0xFF else -1
            var validEld = isLikelyAacEldFirstByte(firstByte)

            if (canAttemptDecrypt && !validEld) {
                invalidEldHeaderCount++
                val recovery = selectBestEldCandidate(rawPayload)
                if (recovery != null && recovery.first != frameCandidate) {
                    eldRecoverySwitchCount++
                    frameCandidate = recovery.first
                    frame = recovery.second
                    maybeSwitchDecryptSelection(frameCandidate)
                    firstByte = if (frame.isNotEmpty()) frame[0].toInt() and 0xFF else -1
                    validEld = isLikelyAacEldFirstByte(firstByte)
                    Log.w(
                        "AirPlay",
                        "AudioRTP: ELD recovered candidate=${frameCandidate.mode}/key=${frameCandidate.keyIndex}/iv=${frameCandidate.ivIndex} " +
                            "switchCount=$eldRecoverySwitchCount firstByte=0x${"%02x".format(firstByte)} validEld=$validEld",
                    )
                } else if (invalidEldHeaderCount == 1L || invalidEldHeaderCount % 200L == 0L) {
                    Log.w(
                        "AirPlay",
                        "AudioRTP: ELD invalid header with candidate=${frameCandidate.mode}/key=${frameCandidate.keyIndex}/iv=${frameCandidate.ivIndex} " +
                            "count=$invalidEldHeaderCount firstByte=0x${"%02x".format(firstByte)}",
                    )
                }
            } else if (validEld && invalidEldHeaderCount > 0L) {
                invalidEldHeaderCount = 0L
            }

            if (emittedFrameCount < 5 || (!validEld && invalidEldHeaderCount % 200L == 1L)) {
                Log.d(
                    "AirPlay",
                    "AudioRTP: ELD frame#$emittedFrameCount size=${frame.size} " +
                        "encrypted=$canAttemptDecrypt mode=${frameCandidate.mode} " +
                        "keyIdx=${frameCandidate.keyIndex} ivIdx=${frameCandidate.ivIndex} " +
                        "raw=${rawPayload.toHex(8)} dec=${frame.toHex(8)} " +
                        "firstByte=0x${"%02x".format(firstByte)} validEld=$validEld",
                )
            }
            emitAudioFrame(frame, 0, frame.size)
            return
        }

        val emittedBefore = emittedFrameCount
        val selectedCandidates = pickCandidateSpecs()
        for (candidate in selectedCandidates) {
            val payload = payloadForCandidate(rawPayload, candidate)
            val parsed = parseStructuredAccessUnits(payload)
            if (parsed != null) {
                var emitted = 0
                for (slice in parsed.slices) {
                    if (slice.isMarker) {
                        recordMarkerDrop()
                        continue
                    }
                    emitAudioFrame(payload, slice.start, slice.end)
                    emitted++
                }
                if (parsed.source == "mpeg4-le") {
                    auLittleEndianParseCount++
                    if (auLittleEndianParseCount == 1L || auLittleEndianParseCount % 200L == 0L) {
                        Log.d(
                            "AirPlay",
                            "AudioRTP: parsed MPEG4-GENERIC AU headers in little-endian " +
                                "count=$auLittleEndianParseCount",
                        )
                    }
                }
                maybeSwitchDecryptSelection(candidate)
                if (emitted == 0) {
                    // Marker-only packets are valid and should not fall back to raw.
                    return
                }
                return
            }
        }

        val fallbackCandidates = if (canAttemptDecrypt) {
            pickCandidateSpecs()
        } else {
            listOf(DecryptCandidate(DecryptMode.RAW, -1))
        }
        var chosenFallbackCandidate: DecryptCandidate? = null
        var fallback: ByteArray? = null
        for (candidate in fallbackCandidates) {
            val payload = payloadForCandidate(rawPayload, candidate)
            val candidateFrame = extractRawAacFallbackFrame(payload, 0, payload.size) ?: continue
            chosenFallbackCandidate = candidate
            fallback = candidateFrame
            break
        }
        val chosenCandidate = chosenFallbackCandidate ?: return
        val chosenFallback = fallback ?: return
        emitAudioFrame(chosenFallback, 0, chosenFallback.size)
        maybeSwitchDecryptSelection(chosenCandidate)

        auParseFallbackCount++
        if (auParseFallbackCount == 1L || auParseFallbackCount % 200L == 0L) {
            Log.w(
                "AirPlay",
                "AudioRTP: AU parse fallback engaged count=$auParseFallbackCount " +
                    "payload=${remaining}B emittedDelta=${emittedFrameCount - emittedBefore} " +
                    "mode=${chosenCandidate.mode} keyIndex=${chosenCandidate.keyIndex} ivIndex=${chosenCandidate.ivIndex} " +
                    "first16=${chosenFallback.toHex(16)}",
            )
        }
    }

    private fun pickCandidateSpecs(): List<DecryptCandidate> {
        // AirPlay mirror audio uses AES-CBC. Try CBC first in AUTO mode.
        val modeOrder = if (canAttemptDecrypt) {
            when (preferredDecryptMode) {
                DecryptMode.AUTO -> listOf(DecryptMode.CBC, DecryptMode.CTR, DecryptMode.RAW)
                DecryptMode.RAW -> listOf(DecryptMode.RAW, DecryptMode.CBC, DecryptMode.CTR)
                DecryptMode.CTR -> listOf(DecryptMode.CTR, DecryptMode.RAW, DecryptMode.CBC)
                DecryptMode.CBC -> listOf(DecryptMode.CBC, DecryptMode.RAW, DecryptMode.CTR)
            }
        } else {
            listOf(DecryptMode.RAW)
        }

        val keyOrder = if (audioAesKeys.isNotEmpty()) {
            buildList {
                val safePreferred = preferredDecryptKeyIndex.coerceIn(0, audioAesKeys.lastIndex)
                add(safePreferred)
                for (idx in audioAesKeys.indices) {
                    if (idx != safePreferred) add(idx)
                }
            }
        } else {
            emptyList()
        }

        val ivOrder = if (audioAesIvs.isNotEmpty()) {
            buildList {
                val safePreferred = preferredIvIndex.coerceIn(0, audioAesIvs.lastIndex)
                add(safePreferred)
                for (idx in audioAesIvs.indices) {
                    if (idx != safePreferred) add(idx)
                }
            }
        } else {
            emptyList()
        }

        val candidates = mutableListOf<DecryptCandidate>()
        for (mode in modeOrder) {
            when (mode) {
                DecryptMode.RAW -> candidates += DecryptCandidate(DecryptMode.RAW, -1, -1)
                DecryptMode.CTR,
                DecryptMode.CBC,
                -> keyOrder.forEach { keyIdx ->
                    ivOrder.forEach { ivIdx ->
                        candidates += DecryptCandidate(mode, keyIdx, ivIdx)
                    }
                }
                DecryptMode.AUTO -> {}
            }
        }
        return candidates.distinctBy { Triple(it.mode, it.keyIndex, it.ivIndex) }
    }

    private fun payloadForCandidate(rawPayload: ByteArray, candidate: DecryptCandidate): ByteArray {
        if (!canAttemptDecrypt) return rawPayload
        return when (candidate.mode) {
            DecryptMode.RAW,
            DecryptMode.AUTO,
            -> rawPayload
            DecryptMode.CTR -> decryptCtr(rawPayload, candidate.keyIndex, candidate.ivIndex)
            DecryptMode.CBC -> decryptCbc(rawPayload, candidate.keyIndex, candidate.ivIndex)
        }
    }

    private fun currentPreferredCandidate(): DecryptCandidate {
        val candidates = pickCandidateSpecs()
        return candidates.firstOrNull() ?: DecryptCandidate(DecryptMode.RAW, -1, -1)
    }

    private fun selectBestEldCandidate(rawPayload: ByteArray): Pair<DecryptCandidate, ByteArray>? {
        var bestCandidate: DecryptCandidate? = null
        var bestPayload: ByteArray? = null
        var bestScore = Int.MIN_VALUE
        for (candidate in pickCandidateSpecs()) {
            val payload = payloadForCandidate(rawPayload, candidate)
            val score = scoreEldCandidate(payload, candidate)
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
                bestPayload = payload
            }
        }
        val chosenCandidate = bestCandidate ?: return null
        val chosenPayload = bestPayload ?: return null
        return chosenCandidate to chosenPayload
    }

    private fun scoreEldCandidate(payload: ByteArray, candidate: DecryptCandidate): Int {
        if (payload.isEmpty()) return Int.MIN_VALUE
        val firstByte = payload[0].toInt() and 0xFF
        val validHeader = isLikelyAacEldFirstByte(firstByte)
        val elemId = firstByte ushr 5
        val sampleSize = payload.size.coerceAtMost(64)
        val distinct = payload.take(sampleSize).toSet().size
        val structureScore = sampleSize - distinct
        val elemScore = when (elemId) {
            1 -> 15
            0 -> 10
            in 2..6 -> 3
            else -> 0
        }
        val modeScore = when (candidate.mode) {
            DecryptMode.CBC -> 6
            DecryptMode.CTR -> 3
            else -> 0
        }
        val rawPenalty = if (canAttemptDecrypt && candidate.mode == DecryptMode.RAW) -8 else 0
        val headerScore = if (validHeader) 120 else 0
        return headerScore + elemScore + structureScore + modeScore + rawPenalty
    }

    private fun isLikelyAacEldFirstByte(firstByte: Int): Boolean {
        return firstByte in VALID_AAC_ELD_FIRST_BYTES
    }

    private fun decryptCtr(rawPayload: ByteArray, keyIndex: Int, ivIndex: Int = preferredIvIndex): ByteArray {
        val key = audioAesKeys.getOrNull(keyIndex) ?: return rawPayload
        val iv = audioAesIvs.getOrNull(ivIndex) ?: return rawPayload
        return runCatching {
            Cipher.getInstance("AES/CTR/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                doFinal(rawPayload)
            }
        }.getOrElse {
            decryptFailureCount++
            if (decryptFailureCount == 1L || decryptFailureCount % 200L == 0L) {
                Log.w("AirPlay", "AudioRTP: CTR decrypt failed count=$decryptFailureCount keyIndex=$keyIndex ivIndex=$ivIndex err=${it.message}")
            }
            rawPayload
        }
    }

    private fun decryptCbc(rawPayload: ByteArray, keyIndex: Int, ivIndex: Int = preferredIvIndex): ByteArray {
        val key = audioAesKeys.getOrNull(keyIndex) ?: return rawPayload
        val iv = audioAesIvs.getOrNull(ivIndex) ?: return rawPayload
        return runCatching {
            val encryptedLen = (rawPayload.size / 16) * 16
            val output = rawPayload.copyOf()
            if (encryptedLen > 0) {
                Cipher.getInstance("AES/CBC/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                    doFinal(rawPayload, 0, encryptedLen, output, 0)
                }
            }
            output
        }.getOrElse {
            decryptFailureCount++
            if (decryptFailureCount == 1L || decryptFailureCount % 200L == 0L) {
                Log.w("AirPlay", "AudioRTP: CBC decrypt failed count=$decryptFailureCount keyIndex=$keyIndex ivIndex=$ivIndex err=${it.message}")
            }
            rawPayload
        }
    }

    /**
     * Exhaustive probe: try every key × IV × {CBC, CTR} on the first AAC-ELD frame.
     * Scores each combination by byte-level entropy (lower = more structured = likely correct)
     * and valid AAC element header detection.
     *
     * Encrypted (random) data has ~256 distinct byte values in 256+ byte payloads.
     * Correctly decrypted AAC-ELD has structured patterns with fewer distinct values.
     */
    private fun probeEldDecryptionKeyAndMode(rawPayload: ByteArray) {
        if (!canAttemptDecrypt || audioAesKeys.isEmpty() || audioAesIvs.isEmpty() || rawPayload.isEmpty()) return

        data class ProbeResult(val keyIdx: Int, val ivIdx: Int, val mode: DecryptMode, val score: Int, val label: String)
        val results = mutableListOf<ProbeResult>()

        Log.d("AirPlay", "AudioRTP: ELD probe starting: ${audioAesKeys.size} keys × ${audioAesIvs.size} IVs × 2 modes = ${audioAesKeys.size * audioAesIvs.size * 2} combos, payload=${rawPayload.size}B")

        for (ivi in audioAesIvs.indices) {
            for (ki in audioAesKeys.indices) {
                for (mode in listOf(DecryptMode.CBC, DecryptMode.CTR)) {
                    val trial = try {
                        if (mode == DecryptMode.CBC) decryptCbc(rawPayload, ki, ivi) else decryptCtr(rawPayload, ki, ivi)
                    } catch (_: Exception) { continue }
                    if (trial.isEmpty()) continue

                    // Byte entropy: count distinct byte values in first 64 bytes.
                    // Random data → ~64 distinct values. Structured AAC → fewer.
                    val sampleSize = trial.size.coerceAtMost(64)
                    val distinctBytes = trial.take(sampleSize).toSet().size

                    // AAC element ID check
                    val elemId = (trial[0].toInt() and 0xFF) ushr 5
                    val elemBonus = when (elemId) {
                        1 -> 15   // CPE — expected for stereo
                        0 -> 10   // SCE — possible
                        in 2..6 -> 3
                        else -> 0
                    }
                    val firstByte = trial[0].toInt() and 0xFF
                    val validHeaderBonus = if (isLikelyAacEldFirstByte(firstByte)) 120 else 0

                    // Score: lower entropy is better (invert to make higher = better).
                    // Max distinct in 64 bytes is 64. Score = (64 - distinct) + elemBonus.
                    val entropyScore = (sampleSize - distinctBytes) + elemBonus + validHeaderBonus

                    results += ProbeResult(ki, ivi, mode, entropyScore, "key[$ki]/iv[$ivi]/$mode")
                    Log.d(
                        "AirPlay",
                        "AudioRTP: ELD probe $mode key[$ki] iv[$ivi] distinct=$distinctBytes/$sampleSize " +
                            "elemId=$elemId firstByte=0x${"%02x".format(firstByte)} " +
                            "validEld=${isLikelyAacEldFirstByte(firstByte)} score=$entropyScore first8=${trial.toHex(8)}",
                    )
                }
            }
        }

        val best = results.maxByOrNull { it.score }
        if (best != null) {
            preferredDecryptKeyIndex = best.keyIdx
            preferredIvIndex = best.ivIdx
            preferredDecryptMode = best.mode
            Log.d(
                "AirPlay",
                "AudioRTP: ELD probe → selected ${best.label} (score=${best.score})",
            )
        }
    }

    private fun maybeSwitchDecryptSelection(candidate: DecryptCandidate) {
        if (candidate.mode != preferredDecryptMode && candidate.mode != DecryptMode.AUTO) {
            preferredDecryptMode = candidate.mode
            decryptModeSwitchCount++
            Log.d("AirPlay", "AudioRTP: switched decrypt mode to ${candidate.mode} switchCount=$decryptModeSwitchCount")
        }
        if (candidate.keyIndex >= 0 && candidate.keyIndex != preferredDecryptKeyIndex) {
            preferredDecryptKeyIndex = candidate.keyIndex
            decryptKeySwitchCount++
            Log.d("AirPlay", "AudioRTP: switched decrypt key index to ${candidate.keyIndex} switchCount=$decryptKeySwitchCount")
        }
        if (candidate.ivIndex >= 0 && candidate.ivIndex != preferredIvIndex) {
            preferredIvIndex = candidate.ivIndex
            decryptIvSwitchCount++
            Log.d("AirPlay", "AudioRTP: switched decrypt iv index to ${candidate.ivIndex} switchCount=$decryptIvSwitchCount")
        }
    }

    private fun parseStructuredAccessUnits(payload: ByteArray): ParseResult? {
        parseMpeg4GenericAccessUnits(payload, littleEndianAuHeaders = false)?.let { return it }
        parseMpeg4GenericAccessUnits(payload, littleEndianAuHeaders = true)?.let { return it }
        parseLengthPrefixedAccessUnits(payload, littleEndianLength = false)?.let { return it }
        parseLengthPrefixedAccessUnits(payload, littleEndianLength = true)?.let { return it }
        return null
    }

    private fun parseLengthPrefixedAccessUnits(
        data: ByteArray,
        littleEndianLength: Boolean,
    ): ParseResult? {
        if (data.size < 2) return null
        var cursor = 0
        val slices = mutableListOf<AccessUnitSlice>()
        var count = 0
        while (cursor + 2 <= data.size) {
            val auSize = if (littleEndianLength) readUShortLE(data, cursor) else readUShort(data, cursor)
            if (auSize <= 0 || auSize > MAX_AU_SIZE) return null
            val start = cursor + 2
            val end = start + auSize
            if (end > data.size) return null
            val isMarker = isAirPlayAacMarkerFrame(data, start, auSize)
            slices += AccessUnitSlice(start, end, isMarker)
            count++
            if (count > MAX_AU_COUNT) return null
            cursor = end
        }
        if (cursor != data.size || slices.isEmpty()) return null
        return ParseResult(
            slices = slices,
            source = if (littleEndianLength) "len-prefix-le" else "len-prefix-be",
        )
    }

    private fun parseMpeg4GenericAccessUnits(
        data: ByteArray,
        littleEndianAuHeaders: Boolean,
    ): ParseResult? {
        if (data.size < 4) return null
        val auHeadersBits = if (littleEndianAuHeaders) readUShortLE(data, 0) else readUShort(data, 0)
        if (auHeadersBits <= 0) return null

        val bitsAvailable = (data.size - 2) * 8
        if (auHeadersBits > bitsAvailable) return null

        val auHeaderBytes = (auHeadersBits + 7) / 8
        val headersStart = 2
        val headersEnd = headersStart + auHeaderBytes
        if (headersEnd > data.size) return null

        // AirPlay AAC RTP commonly uses 16-bit AU headers (13-bit size + 3-bit index).
        if (auHeaderBytes < 2 || auHeaderBytes % 2 != 0) return null

        val numAUs = auHeaderBytes / 2
        if (numAUs !in 1..MAX_AU_COUNT) return null

        val slices = mutableListOf<AccessUnitSlice>()
        var payloadCursor = headersEnd
        for (i in 0 until numAUs) {
            val headerOffset = headersStart + i * 2
            val word = if (littleEndianAuHeaders) readUShortLE(data, headerOffset) else readUShort(data, headerOffset)
            val auSize = (word ushr 3) and 0x1FFF
            if (auSize <= 0 || auSize > MAX_AU_SIZE || payloadCursor + auSize > data.size) {
                return null
            }
            val start = payloadCursor
            val end = payloadCursor + auSize
            slices += AccessUnitSlice(start, end, isAirPlayAacMarkerFrame(data, start, auSize))
            payloadCursor = end
        }
        if (payloadCursor != data.size) return null
        return ParseResult(
            slices = slices,
            source = if (littleEndianAuHeaders) "mpeg4-le" else "mpeg4-be",
        )
    }

    private fun extractRawAacFallbackFrame(
        data: ByteArray,
        payloadOffset: Int,
        length: Int,
    ): ByteArray? {
        val remaining = length - payloadOffset
        if (remaining <= 0) return null
        if (isAirPlayAacMarkerFrame(data, payloadOffset, remaining)) {
            recordMarkerDrop()
            return null
        }

        // Common raw-frame variant: 16-bit length prefix followed by one AAC unit.
        if (remaining > 2) {
            val declaredSize = readUShort(data, payloadOffset)
            if (declaredSize > 0 && declaredSize <= remaining - 2) {
                val start = payloadOffset + 2
                if (isAirPlayAacMarkerFrame(data, start, declaredSize)) {
                    recordMarkerDrop()
                    return null
                }
                return data.copyOfRange(start, start + declaredSize)
            }
        }

        // Generic fallback: whole RTP payload is one AAC unit.
        return data.copyOfRange(payloadOffset, length)
    }

    private suspend fun emitAudioFrame(data: ByteArray, start: Int, end: Int) {
        if (end <= start) return
        emittedFrameCount++
        _aacFrames.emit(data.copyOfRange(start, end))
    }

    private fun readUShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readUShortLE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
    }

    private fun recordMarkerDrop() {
        markerFrameDropCount++
        if (markerFrameDropCount == 1L || markerFrameDropCount % 200L == 0L) {
            Log.d("AirPlay", "AudioRTP: dropped marker frames=$markerFrameDropCount")
        }
    }

    private fun isAirPlayAacMarkerFrame(data: ByteArray, start: Int, size: Int): Boolean {
        if (size != 4) return false
        if (start + 4 > data.size) return false
        return data[start] == 0x00.toByte() &&
            data[start + 1] == 0x68.toByte() &&
            data[start + 2] == 0x34.toByte() &&
            data[start + 3] == 0x00.toByte()
    }

    private fun ByteArray.toHex(maxBytes: Int): String {
        val takeBytes = minOf(size, maxBytes)
        return this.take(takeBytes).joinToString("") { "%02x".format(it) } +
            if (size > takeBytes) "…(${size}B)" else ""
    }
}
