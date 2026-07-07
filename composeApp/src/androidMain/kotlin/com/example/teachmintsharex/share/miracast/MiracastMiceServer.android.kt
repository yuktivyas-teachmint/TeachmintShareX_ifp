package com.example.teachmintsharex.share.miracast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * MS-MICE control server for Miracast over Infrastructure.
 *
 * Windows source connects to this TCP port and sends:
 * - SOURCE_READY (includes RTSP source port)
 * - STOP_PROJECTION
 */
class MiracastMiceServer(
    private val onSourceReady: (MiceSourceReadyMessage) -> Unit,
    private val onStopProjection: (String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var isRunning = false

    suspend fun start(port: Int = MiracastPorts.MICE_CONTROL_PORT, bindAddress: String = "0.0.0.0") {
        if (isRunning) {
            println("MIRACAST_MICE: ⚠️ Server already running")
            return
        }

        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port))
        }
        isRunning = true
        println("MIRACAST_MICE: ✅ MS-MICE server started on port $port")

        acceptJob = scope.launch {
            while (coroutineContext.isActive && isRunning) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                } catch (e: Exception) {
                    if (coroutineContext.isActive && isRunning) {
                        println("MIRACAST_MICE: ⚠️ Accept error: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null

        runCatching { acceptJob?.cancelAndJoin() }
        acceptJob = null
        println("MIRACAST_MICE: 🔴 MS-MICE server stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        val sourceAddress = socket.inetAddress?.hostAddress.orEmpty()
        println("MIRACAST_MICE: 📱 Control channel connected from $sourceAddress")

        try {
            socket.soTimeout = 15_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            while (coroutineContext.isActive && isRunning && !socket.isClosed) {
                val size = readUint16(input) ?: break
                if (size < 4) {
                    println("MIRACAST_MICE: ⚠️ Ignoring short message size=$size")
                    continue
                }

                // MS-MICE size includes these 2 size bytes; payload is the remaining bytes.
                val payload = ByteArray(size - 2)
                input.readFully(payload)
                parseMessage(sourceAddress, payload)
            }
        } catch (_: java.io.EOFException) {
            // Remote closed control channel.
        } catch (e: Exception) {
            if (isRunning) {
                println("MIRACAST_MICE: ⚠️ Client error from $sourceAddress: ${e.message}")
            }
        } finally {
            runCatching { socket.close() }
            println("MIRACAST_MICE: 🔌 Control channel disconnected: $sourceAddress")
        }
    }

    private fun parseMessage(sourceAddress: String, payload: ByteArray) {
        if (payload.size < 2) {
            println("MIRACAST_MICE: ⚠️ Ignoring malformed payload (len=${payload.size})")
            return
        }

        val version = payload[0].toInt() and 0xFF
        val command = payload[1].toInt() and 0xFF
        val tlvs = parseTlvs(payload, startOffset = 2)

        when (command) {
            COMMAND_SOURCE_READY -> {
                val sourceId = tlvs[TLV_SOURCE_ID]?.let(::decodeSourceId)
                val friendlyName = tlvs[TLV_FRIENDLY_NAME]?.let(::decodeFriendlyName)
                val rtspPort = tlvs[TLV_RTSP_PORT]
                    ?.takeIf { it.size >= 2 }
                    ?.let(::decodeRtspPort)
                    ?: MiracastPorts.WFD_RTSP_PORT

                println(
                    "MIRACAST_MICE: ✅ SOURCE_READY v=$version " +
                        "source=$sourceAddress rtspPort=$rtspPort sourceId=${sourceId ?: "unknown"} " +
                        "name=${friendlyName ?: "unknown"}",
                )
                onSourceReady(
                    MiceSourceReadyMessage(
                        sourceAddress = sourceAddress,
                        rtspPort = rtspPort,
                        sourceId = sourceId,
                        friendlyName = friendlyName,
                    ),
                )
            }

            COMMAND_STOP_PROJECTION -> {
                val sourceId = tlvs[TLV_SOURCE_ID]?.let(::decodeSourceId)
                println(
                    "MIRACAST_MICE: 🛑 STOP_PROJECTION " +
                        "from $sourceAddress sourceId=${sourceId ?: "unknown"}",
                )
                onStopProjection(sourceId)
            }

            else -> {
                println(
                    "MIRACAST_MICE: ℹ️ Ignoring unsupported command=0x${command.toString(16)} " +
                        "version=$version",
                )
            }
        }
    }

    private fun parseTlvs(payload: ByteArray, startOffset: Int): Map<Int, ByteArray> {
        val output = linkedMapOf<Int, ByteArray>()
        var offset = startOffset

        // MS-MICE TLV layout:
        // type: 1 byte, length: 2 bytes (network order), value: <length> bytes.
        while (offset + 3 <= payload.size) {
            val type = payload[offset].toInt() and 0xFF
            val length = ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                (payload[offset + 2].toInt() and 0xFF)
            offset += 3

            if (length < 0 || offset + length > payload.size) {
                println("MIRACAST_MICE: ⚠️ Invalid TLV type=$type length=$length")
                break
            }

            output[type] = payload.copyOfRange(offset, offset + length)
            offset += length
        }

        return output
    }

    private fun decodeSourceId(bytes: ByteArray): String {
        return if (bytes.size == 16) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val most = buffer.long
            val least = buffer.long
            UUID(most, least).toString()
        } else {
            bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
        }
    }

    private fun decodeFriendlyName(bytes: ByteArray): String {
        return runCatching {
            bytes.toString(Charsets.UTF_16LE).trim('\u0000', ' ')
        }.getOrElse {
            bytes.toString(Charsets.UTF_8).trim('\u0000', ' ')
        }
    }

    private fun decodeRtspPort(bytes: ByteArray): Int {
        if (bytes.size < 2) return MiracastPorts.WFD_RTSP_PORT
        return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    }

    private fun readUint16(input: DataInputStream): Int? {
        val msb = input.read()
        if (msb == -1) return null

        val lsb = input.read()
        if (lsb == -1) return null

        return ((msb and 0xFF) shl 8) or (lsb and 0xFF)
    }

    private companion object {
        const val COMMAND_SOURCE_READY = 0x01
        const val COMMAND_STOP_PROJECTION = 0x02

        // TLV type identifiers per MS-MICE.
        const val TLV_FRIENDLY_NAME = 0x00
        const val TLV_RTSP_PORT = 0x02
        const val TLV_SOURCE_ID = 0x03
    }
}

data class MiceSourceReadyMessage(
    val sourceAddress: String,
    val rtspPort: Int,
    val sourceId: String?,
    val friendlyName: String?,
)
