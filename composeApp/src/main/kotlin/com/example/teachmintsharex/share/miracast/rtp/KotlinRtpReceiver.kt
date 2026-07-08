package com.example.teachmintsharex.share.miracast.rtp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Pure Kotlin RTP receiver implementation
 * Fallback when native implementation is not available
 */
class KotlinRtpReceiver(
    private val port: Int,
    private val onH264Data: (data: ByteArray, ptsUs: Long, size: Int, isKeyFrame: Boolean) -> Unit,
    private val onAudioData: ((data: ByteArray, ptsUs: Long) -> Unit)? = null
) : RtpReceiver {

    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var receiveThread: Thread? = null
    private val tsdemuxer = SimpleTSDemuxer(onH264Data, onAudioData)

    companion object {
        private const val TAG = "KotlinRtpReceiver"
        private const val RECV_BUFFER_SIZE = 4 * 1024 * 1024 // 4MB
        private const val MAX_PACKET_SIZE = 65536
        private const val SOCKET_TIMEOUT_MS = 1000
    }

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        if (running) {
            return@withContext Result.success(Unit)
        }

        try {
            socket = DatagramSocket(port).apply {
                soTimeout = SOCKET_TIMEOUT_MS
                receiveBufferSize = RECV_BUFFER_SIZE
            }

            running = true
            receiveThread = thread(start = true, name = "RTP-Kotlin-$port") {
                receiveLoop()
            }

            Log.d(TAG, "📡 Kotlin RTP receiver started on port $port")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Kotlin RTP receiver", e)
            Result.failure(e)
        }
    }

    override fun stop() {
        if (!running) return

        Log.d(TAG, "🛑 Stopping Kotlin RTP receiver")
        running = false

        receiveThread?.interrupt()
        receiveThread?.join(2000)
        receiveThread = null

        socket?.close()
        socket = null

        Log.d(TAG, "Kotlin RTP receiver stopped")
    }

    override fun isRunning(): Boolean = running

    private fun receiveLoop() {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        Log.d(TAG, "Receive loop started")

        while (running) {
            try {
                socket?.receive(packet) ?: break

                if (packet.length > 0) {
                    parseRTPPacket(buffer, packet.length)
                }
            } catch (e: SocketTimeoutException) {
                // Timeout - check running flag and continue
                continue
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Error in receive loop", e)
                }
                break
            }
        }

        Log.d(TAG, "Receive loop ended")
    }

    private fun parseRTPPacket(data: ByteArray, size: Int) {
        if (size < 12) return

        // Parse RTP header
        val version = (data[0].toInt() shr 6) and 0x03
        if (version != 2) return

        val padding = (data[0].toInt() shr 5) and 0x01 != 0
        val extension = (data[0].toInt() shr 4) and 0x01 != 0
        val csrcCount = data[0].toInt() and 0x0F

        // Calculate header size
        var offset = 12 + (csrcCount * 4)
        if (size < offset) return

        // Extract timestamp
        val timestamp = (data[4].toInt() and 0xFF shl 24) or
                       (data[5].toInt() and 0xFF shl 16) or
                       (data[6].toInt() and 0xFF shl 8) or
                       (data[7].toInt() and 0xFF)

        // Handle extension
        if (extension) {
            if (size < offset + 4) return
            val extLength = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                           (data[offset + 3].toInt() and 0xFF)
            offset += 4 + (extLength * 4)
            if (size < offset) return
        }

        // Handle padding
        var payloadSize = size - offset
        if (padding && size > offset) {
            val padLength = data[size - 1].toInt() and 0xFF
            if (padLength <= payloadSize) {
                payloadSize -= padLength
            }
        }

        // Extract TS payload and process
        if (payloadSize > 0) {
            val timestampUs = (timestamp.toLong() * 1_000_000L) / 90_000L
            processTSPayload(data, offset, payloadSize, timestampUs)
        }
    }

    private fun processTSPayload(data: ByteArray, offset: Int, size: Int, timestampUs: Long) {
        // Process TS packets (188 bytes each)
        val TS_PACKET_SIZE = 188
        var currentOffset = offset

        while (currentOffset + TS_PACKET_SIZE <= offset + size) {
            if (data[currentOffset] == 0x47.toByte()) { // TS sync byte
                tsdemuxer.processTSPacket(data, currentOffset, TS_PACKET_SIZE)
            }
            currentOffset += TS_PACKET_SIZE
        }
    }

    /**
     * Simplified TS demuxer for Kotlin implementation
     * Extracts H.264 from TS packets
     */
    private class SimpleTSDemuxer(
        private val onH264Data: (data: ByteArray, ptsUs: Long, size: Int, isKeyFrame: Boolean) -> Unit,
        private val onAudioData: ((data: ByteArray, ptsUs: Long) -> Unit)? = null
    ) {
        private var videoPid: Int = 0
        private var audioPid: Int = 0
        private var pmtPid: Int = 0
        private val pesBuffers = mutableMapOf<Int, PESBuffer>()

        private data class PESBuffer(
            val data: ByteArrayOutputStream = ByteArrayOutputStream(),
            var pts: Long = 0,
            var hasPts: Boolean = false
        )

        fun processTSPacket(data: ByteArray, offset: Int, size: Int) {
            if (size != 188 || data[offset] != 0x47.toByte()) return

            val payloadStart = (data[offset + 1].toInt() and 0x40) != 0
            val pid = ((data[offset + 1].toInt() and 0x1F) shl 8) or (data[offset + 2].toInt() and 0xFF)
            val hasPayload = (data[offset + 3].toInt() and 0x10) != 0

            if (!hasPayload) return

            var payloadOffset = offset + 4

            // Skip adaptation field if present
            if ((data[offset + 3].toInt() and 0x20) != 0) {
                val adaptationLength = data[payloadOffset].toInt() and 0xFF
                payloadOffset += 1 + adaptationLength
                if (payloadOffset >= offset + size) return
            }

            val payloadSize = offset + size - payloadOffset

            when {
                pid == 0 -> processPAT(data, payloadOffset, payloadSize)
                pid == pmtPid && pmtPid != 0 -> processPMT(data, payloadOffset, payloadSize)
                pid == videoPid && videoPid != 0 -> processPES(data, payloadOffset, payloadSize, pid, payloadStart)
                pid == audioPid && audioPid != 0 -> processPES(data, payloadOffset, payloadSize, pid, payloadStart)
            }
        }

        private fun processPAT(data: ByteArray, offset: Int, size: Int) {
            var idx = offset
            if (size > 0 && data[idx] != 0.toByte()) {
                idx += 1 + (data[idx].toInt() and 0xFF)
            } else if (size > 0) {
                idx += 1
            }

            if (idx + 8 > offset + size) return

            val sectionLength = ((data[idx + 1].toInt() and 0x0F) shl 8) or (data[idx + 2].toInt() and 0xFF)
            idx += 8

            val programsEnd = idx + sectionLength - 9
            while (idx + 4 <= programsEnd) {
                val programNumber = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx + 1].toInt() and 0xFF)
                val programPid = ((data[idx + 2].toInt() and 0x1F) shl 8) or (data[idx + 3].toInt() and 0xFF)

                if (programNumber != 0) {
                    pmtPid = programPid
                    Log.d(TAG, "Found PMT PID: 0x${pmtPid.toString(16)}")
                    break
                }
                idx += 4
            }
        }

        private fun processPMT(data: ByteArray, offset: Int, size: Int) {
            var idx = offset
            if (size > 0 && data[idx] != 0.toByte()) {
                idx += 1 + (data[idx].toInt() and 0xFF)
            } else if (size > 0) {
                idx += 1
            }

            if (idx + 12 > offset + size) return

            val sectionLength = ((data[idx + 1].toInt() and 0x0F) shl 8) or (data[idx + 2].toInt() and 0xFF)
            val programInfoLength = ((data[idx + 10].toInt() and 0x0F) shl 8) or (data[idx + 11].toInt() and 0xFF)

            idx += 12 + programInfoLength

            val streamsEnd = idx + sectionLength - 13 - programInfoLength
            while (idx + 5 <= streamsEnd) {
                val streamType = data[idx].toInt() and 0xFF
                val elementaryPid = ((data[idx + 1].toInt() and 0x1F) shl 8) or (data[idx + 2].toInt() and 0xFF)
                val esInfoLength = ((data[idx + 3].toInt() and 0x0F) shl 8) or (data[idx + 4].toInt() and 0xFF)

                if ((streamType == 0x1B || streamType == 0x24) && videoPid == 0) {
                    videoPid = elementaryPid
                    Log.d(TAG, "Found H.264 video PID: 0x${videoPid.toString(16)}")
                }

                // AAC ADTS (0x0F), AAC LATM (0x11), LPCM (0x83)
                if ((streamType == 0x0F || streamType == 0x11 || streamType == 0x83) && audioPid == 0) {
                    audioPid = elementaryPid
                    Log.d(TAG, "Found audio PID: 0x${audioPid.toString(16)} (type: 0x${streamType.toString(16)})")
                }

                idx += 5 + esInfoLength
            }
        }

        private fun processPES(data: ByteArray, offset: Int, size: Int, pid: Int, payloadStart: Boolean) {
            val buffer = pesBuffers.getOrPut(pid) { PESBuffer() }

            if (payloadStart && size >= 6 &&
                data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() && data[offset + 2] == 1.toByte()) {

                // Flush previous PES
                if (buffer.data.size() > 0) {
                    if (pid == audioPid) {
                        flushAudioPES(buffer)
                    } else {
                        flushPES(buffer)
                    }
                }

                buffer.data.reset()
                buffer.hasPts = false

                val streamId = data[offset + 3].toInt() and 0xFF
                var idx = offset + 6

                // Parse PTS if present — for both video (0xE0) and audio (0xC0) stream IDs
                if (idx + 3 <= offset + size &&
                    ((streamId and 0xE0) == 0xE0 || (streamId and 0xE0) == 0xC0)) {
                    val ptsFlags = (data[idx + 1].toInt() shr 6) and 0x03
                    val pesHeaderLength = data[idx + 2].toInt() and 0xFF
                    idx += 3

                    if ((ptsFlags and 0x02) != 0 && idx + 5 <= offset + size) {
                        buffer.pts = parsePTS(data, idx)
                        buffer.hasPts = true
                    }

                    idx += pesHeaderLength
                }

                if (idx < offset + size) {
                    buffer.data.write(data, idx, offset + size - idx)
                }
            } else {
                buffer.data.write(data, offset, size)
            }
        }

        private fun parsePTS(data: ByteArray, offset: Int): Long {
            var pts = 0L
            pts = pts or ((data[offset].toLong() and 0x0E) shl 29)
            pts = pts or ((data[offset + 1].toLong() and 0xFF) shl 22)
            pts = pts or ((data[offset + 2].toLong() and 0xFE) shl 14)
            pts = pts or ((data[offset + 3].toLong() and 0xFF) shl 7)
            pts = pts or ((data[offset + 4].toLong() and 0xFF) shr 1)

            return (pts * 1_000_000L) / 90_000L
        }

        private fun flushPES(buffer: PESBuffer) {
            val pesData = buffer.data.toByteArray()
            if (pesData.isEmpty()) return

            // Find NAL start code
            var startOffset = -1
            for (i in 0 until pesData.size - 3) {
                if (pesData[i] == 0.toByte() && pesData[i + 1] == 0.toByte() &&
                    (pesData[i + 2] == 1.toByte() ||
                     (pesData[i + 2] == 0.toByte() && i + 3 < pesData.size && pesData[i + 3] == 1.toByte()))) {
                    startOffset = if (pesData[i + 2] == 1.toByte()) i + 3 else i + 4
                    break
                }
            }

            if (startOffset >= 0 && startOffset < pesData.size) {
                val nalType = pesData[startOffset].toInt() and 0x1F
                val isKeyFrame = nalType == 5

                onH264Data(pesData, buffer.pts, pesData.size, isKeyFrame)
            }
        }

        private fun flushAudioPES(buffer: PESBuffer) {
            val pesData = buffer.data.toByteArray()
            if (pesData.isEmpty()) return

            onAudioData?.invoke(pesData, buffer.pts)
        }
    }
}
