package com.teachmint.sharex.airplay

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * RTSP/1.0 server for AirPlay session management (port 49153).
 *
 * Handles OPTIONS, ANNOUNCE (SDP), SETUP (RTP port alloc), RECORD, TEARDOWN.
 */
class AirPlayRtspServer(
    private val port: Int = AirPlayProtocol.RTSP_PORT,
    private val onSessionReady: (RtspSession) -> Unit,
    private val onSessionEnded: (String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val nextRtpPort = AtomicInteger(AirPlayProtocol.RTP_PORT_RANGE_START)

    private data class SessionBuilder(
        val sessionId: String,
        val clientAddress: String,
        var videoSdp: AirPlaySdp? = null,
        var audioSdp: AirPlayAudioSdp? = null,
        var videoRtpPort: Int = 0,
        var videoRtcpPort: Int = 0,
        var audioRtpPort: Int = 0,
        var audioRtcpPort: Int = 0,
        var videoSetup: Boolean = false,
        var audioSetup: Boolean = false,
    ) {
        fun toSession() = RtspSession(
            sessionId     = sessionId,
            videoRtpPort  = videoRtpPort,
            videoRtcpPort = videoRtcpPort,
            audioRtpPort  = audioRtpPort,
            audioRtcpPort = audioRtcpPort,
            clientAddress = clientAddress,
            videoSdp      = videoSdp,
            audioSdp      = audioSdp,
        )
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        Log.d("AirPlay", "RTSP: start() entered, binding port $port")
        try {
            serverSocket = ServerSocket(port)
        } catch (e: Exception) {
            Log.e("AirPlay", "RTSP: failed to bind port $port: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext
        }
        Log.d("AirPlay", "RTSP: listening on port $port")
        scope.launch {
            while (isActive) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (e: SocketException) {
                    if (isActive) Log.e("AirPlay", "RTSP: Accept error: ${e.message}")
                    break
                }
                launch { handleClient(client) }
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        scope.cancel()
        serverSocket?.close()
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        val clientAddr = socket.inetAddress.hostAddress ?: "unknown"
        Log.d("AirPlay", "RTSP: client connected $clientAddr")
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        var builder: SessionBuilder? = null

        try {
            while (socket.isConnected && !socket.isClosed) {
                val requestLine = reader.readLine()?.trim() ?: break
                if (requestLine.isEmpty()) continue

                val parts = requestLine.split(" ")
                if (parts.size < 2) continue
                val method = parts[0]

                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (line != null && line.isNotBlank()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                    line = reader.readLine()
                }

                val cseq          = headers["cseq"] ?: "0"
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val n = reader.read(buf, totalRead, contentLength - totalRead)
                        if (n < 0) break
                        totalRead += n
                    }
                    String(buf, 0, totalRead)
                } else ""

                Log.d("AirPlay", "RTSP: $method (CSeq=$cseq)")

                when (method) {
                    AirPlayProtocol.RTSP_OPTIONS -> {
                        sendResponse(writer, 200, "OK", cseq, mapOf(
                            "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
                        ))
                    }

                    AirPlayProtocol.RTSP_ANNOUNCE -> {
                        val (vSdp, aSdp) = parseSdpBoth(body)
                        val sid = generateSessionId()
                        builder = SessionBuilder(
                            sessionId     = sid,
                            clientAddress = clientAddr,
                            videoSdp      = vSdp,
                            audioSdp      = aSdp,
                        )
                        Log.d("AirPlay", "RTSP: ANNOUNCE session=${sid} videoSPS=${vSdp?.sps?.size}B")
                        sendResponse(writer, 200, "OK", cseq)
                    }

                    AirPlayProtocol.RTSP_SETUP -> {
                        val b         = builder ?: SessionBuilder(generateSessionId(), clientAddr).also { builder = it }
                        val transport = headers["transport"] ?: ""
                        val clientPorts = parseClientPorts(transport)
                        val uri       = if (parts.size > 1) parts[1] else ""
                        val isAudio   = uri.contains("audio", ignoreCase = true) ||
                                        uri.contains("stream=1", ignoreCase = true)
                        val serverRtp  = allocateRtpPort()
                        val serverRtcp = serverRtp + 1

                        if (isAudio && !b.audioSetup) {
                            b.audioRtpPort  = serverRtp
                            b.audioRtcpPort = serverRtcp
                            b.audioSetup    = true
                            Log.d("AirPlay", "RTSP: SETUP audio → serverRTP=$serverRtp")
                        } else if (!b.videoSetup) {
                            b.videoRtpPort  = serverRtp
                            b.videoRtcpPort = serverRtcp
                            b.videoSetup    = true
                            Log.d("AirPlay", "RTSP: SETUP video → serverRTP=$serverRtp session=${b.sessionId}")
                            onSessionReady(b.toSession())
                        }

                        sendResponse(writer, 200, "OK", cseq, mapOf(
                            "Session"   to b.sessionId,
                            "Transport" to "RTP/AVP/UDP;unicast;" +
                                          "client_port=$clientPorts;" +
                                          "server_port=$serverRtp-$serverRtcp",
                        ))
                    }

                    AirPlayProtocol.RTSP_RECORD -> {
                        Log.d("AirPlay", "RTSP: RECORD → streaming started session=${builder?.sessionId}")
                        sendResponse(writer, 200, "OK", cseq, mapOf(
                            "Session" to (builder?.sessionId ?: ""),
                        ))
                    }

                    AirPlayProtocol.RTSP_SET_PARAMETER,
                    AirPlayProtocol.RTSP_GET_PARAMETER -> sendResponse(writer, 200, "OK", cseq)

                    AirPlayProtocol.RTSP_FLUSH -> {
                        sendResponse(writer, 200, "OK", cseq, mapOf(
                            "Session" to (builder?.sessionId ?: ""),
                        ))
                    }

                    AirPlayProtocol.RTSP_TEARDOWN -> {
                        Log.d("AirPlay", "RTSP: TEARDOWN session=${builder?.sessionId}")
                        sendResponse(writer, 200, "OK", cseq)
                        builder?.sessionId?.let { onSessionEnded(it) }
                        break
                    }

                    else -> sendResponse(writer, 501, "Not Implemented", cseq)
                }
            }
        } catch (_: EOFException) {
        } catch (e: SocketException) {
            if (e.message?.contains("closed") == false) Log.e("AirPlay", "RTSP: Socket error: ${e.message}")
        } catch (e: Exception) {
            Log.e("AirPlay", "RTSP: Error: ${e.message}")
        } finally {
            socket.runCatching { close() }
            builder?.sessionId?.let { onSessionEnded(it) }
            Log.d("AirPlay", "RTSP: client disconnected $clientAddr")
        }
    }

    private fun sendResponse(
        writer: PrintWriter,
        code: Int,
        reason: String,
        cseq: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        writer.print("RTSP/1.0 $code $reason\r\n")
        writer.print("CSeq: $cseq\r\n")
        writer.print("Server: AirTunes/${AirPlayProtocol.SRC_VERS}\r\n")
        for ((k, v) in headers) writer.print("$k: $v\r\n")
        writer.print("\r\n")
        writer.flush()
    }

    private fun parseSdpBoth(sdpBody: String): Pair<AirPlaySdp?, AirPlayAudioSdp?> {
        var inVideo = false; var inAudio = false
        var vPT = 96; var vClock = 90000
        var sps: ByteArray? = null; var pps: ByteArray? = null
        var aPT = 96; var aSampleRate = 44100; var aChannels = 2
        var aCodec = "MPEG4-GENERIC"; var aConfig: ByteArray? = null

        for (raw in sdpBody.lines()) {
            val line = raw.trim()
            if (line.startsWith("m=video")) { inVideo = true; inAudio = false; continue }
            if (line.startsWith("m=audio")) { inAudio = true; inVideo = false; continue }

            when {
                inVideo && line.startsWith("a=rtpmap:") && line.contains("H264", ignoreCase = true) -> {
                    val m = Regex("""a=rtpmap:(\d+)\s+H264/(\d+)""", RegexOption.IGNORE_CASE).find(line)
                    if (m != null) { vPT = m.groupValues[1].toInt(); vClock = m.groupValues[2].toInt() }
                }
                inVideo && line.startsWith("a=fmtp:") && line.contains("sprop-parameter-sets", ignoreCase = true) -> {
                    val sets = line.substringAfter("sprop-parameter-sets=", "")
                                   .substringBefore(";").trim().split(",")
                    // Use android.util.Base64 (API 24+) instead of java.util.Base64 (API 26+)
                    sps = sets.getOrNull(0)?.let { runCatching { Base64.decode(it.trim(), Base64.DEFAULT) }.getOrNull() }
                    pps = sets.getOrNull(1)?.let { runCatching { Base64.decode(it.trim(), Base64.DEFAULT) }.getOrNull() }
                }
                inAudio && line.startsWith("a=rtpmap:") -> {
                    val m = Regex("""a=rtpmap:(\d+)\s+(\S+)/(\d+)(?:/(\d+))?""").find(line)
                    if (m != null) {
                        aPT         = m.groupValues[1].toInt()
                        aCodec      = m.groupValues[2]
                        aSampleRate = m.groupValues[3].toIntOrNull() ?: 44100
                        aChannels   = m.groupValues[4].toIntOrNull() ?: 2
                    }
                }
                inAudio && line.startsWith("a=fmtp:") && line.contains("config=", ignoreCase = true) -> {
                    val hex = line.substringAfter("config=", "").substringBefore(";").trim()
                    if (hex.isNotEmpty()) {
                        aConfig = runCatching {
                            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        }.getOrNull()
                    }
                }
            }
        }

        val videoSdp = AirPlaySdp(vPT, vClock, sps, pps)
        val audioSdp = if (inAudio) AirPlayAudioSdp(aPT, aSampleRate, aChannels, aCodec, aConfig) else null
        return videoSdp to audioSdp
    }

    private fun parseClientPorts(transport: String): String =
        Regex("""client_port=(\d+-\d+)""").find(transport)?.groupValues?.get(1) ?: "0-1"

    private fun allocateRtpPort(): Int {
        val p = nextRtpPort.getAndAdd(2)
        if (p > AirPlayProtocol.RTP_PORT_RANGE_END) nextRtpPort.set(AirPlayProtocol.RTP_PORT_RANGE_START)
        return p
    }

    private fun generateSessionId(): String =
        (System.currentTimeMillis() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}
