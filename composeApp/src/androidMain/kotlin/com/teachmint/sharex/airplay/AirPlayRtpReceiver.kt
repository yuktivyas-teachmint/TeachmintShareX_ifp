package com.teachmint.sharex.airplay

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*

/**
 * Receives H.264 video over RTP/UDP and reassembles complete NAL units.
 *
 * Handles RFC 6184 payload formats:
 *  • Single NAL unit (type 1-23)
 *  • STAP-A (type 24) — multiple small NALs in one packet
 *  • FU-A (type 28)   — single large NAL fragmented across packets
 */
class AirPlayRtpReceiver(
    private val rtpPort: Int,
    private val sdp: AirPlaySdp?,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null

    private val _nalUnits = MutableSharedFlow<ByteArray>(extraBufferCapacity = 120)
    val nalUnits: SharedFlow<ByteArray> = _nalUnits

    private val fuaBuffer  = mutableMapOf<Int, ByteArray>()
    private val fuaNalType = mutableMapOf<Int, Int>()

    fun start() {
        scope.launch {
            sdp?.sps?.let { _nalUnits.emit(it) }
            sdp?.pps?.let { _nalUnits.emit(it) }
            receiveLoop()
        }
    }

    fun stop() {
        scope.cancel()
        socket?.close()
        socket = null
    }

    private suspend fun receiveLoop() = withContext(Dispatchers.IO) {
        val sock = DatagramSocket(rtpPort).also { socket = it }
        sock.soTimeout = 200
        Log.d("AirPlay", "RTP: listening on UDP port $rtpPort")

        val buf    = ByteArray(65535)
        val packet = DatagramPacket(buf, buf.size)

        while (isActive && !sock.isClosed) {
            try {
                sock.receive(packet)
                processPacket(buf, packet.length)
            } catch (_: SocketTimeoutException) {
            } catch (e: SocketException) {
                if (isActive) Log.e("AirPlay", "RTP: socket error: ${e.message}")
                break
            }
        }
    }

    private suspend fun processPacket(data: ByteArray, length: Int) {
        if (length < 12) return

        val version = (data[0].toInt() ushr 6) and 0x3
        if (version != 2) return

        val hasPadding   = (data[0].toInt() and 0x20) != 0
        val hasExtension = (data[0].toInt() and 0x10) != 0
        val csrcCount    = data[0].toInt() and 0x0F
        val ssrc         = readInt(data, 8)

        var offset = 12 + csrcCount * 4
        if (hasExtension && offset + 4 <= length) {
            val extWordLen = readUShort(data, offset + 2)
            offset += 4 + extWordLen * 4
        }
        if (hasPadding && length > 0) {
            val padBytes = data[length - 1].toInt() and 0xFF
            if (padBytes >= length - offset) return
        }
        if (offset >= length) return

        val nalHeader = data[offset].toInt() and 0xFF
        val nalType   = nalHeader and 0x1F

        when {
            nalType in 1..23 -> emitNal(data.copyOfRange(offset, length))
            nalType == 24    -> handleStapA(data, offset, length)
            nalType == 28    -> handleFuA(data, offset, length, ssrc, nalHeader)
        }
    }

    private suspend fun handleStapA(data: ByteArray, start: Int, length: Int) {
        var offset = start + 1
        while (offset + 2 <= length) {
            val nalSize = readUShort(data, offset)
            offset += 2
            if (offset + nalSize > length) break
            emitNal(data.copyOfRange(offset, offset + nalSize))
            offset += nalSize
        }
    }

    private suspend fun handleFuA(data: ByteArray, offset: Int, length: Int, ssrc: Int, nalHeader: Int) {
        if (offset + 2 > length) return
        val fuHeader    = data[offset + 1].toInt() and 0xFF
        val isStart     = (fuHeader and 0x80) != 0
        val isEnd       = (fuHeader and 0x40) != 0
        val fragNalType = fuHeader and 0x1F
        val nri                 = (nalHeader and 0x60) shr 5
        val reconstructedHeader = ((nri shl 5) or fragNalType).toByte()
        val fragment = data.copyOfRange(offset + 2, length)

        if (isStart) {
            fuaBuffer[ssrc]  = byteArrayOf(reconstructedHeader) + fragment
            fuaNalType[ssrc] = fragNalType
        } else {
            val existing = fuaBuffer[ssrc] ?: return
            fuaBuffer[ssrc] = existing + fragment
        }

        if (isEnd) {
            val fullNal = fuaBuffer.remove(ssrc) ?: return
            fuaNalType.remove(ssrc)
            emitNal(fullNal)
        }
    }

    private suspend fun emitNal(nal: ByteArray) { _nalUnits.emit(nal) }

    private fun readUShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset    ].toInt() and 0xFF) shl 24) or
        ((data[offset + 1].toInt() and 0xFF) shl 16) or
        ((data[offset + 2].toInt() and 0xFF) shl 8)  or
         (data[offset + 3].toInt() and 0xFF)
}
