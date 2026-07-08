package com.example.teachmintsharex.share.miracast

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

private const val DEFAULT_WFD_STREAM_URI = "rtsp://x.x.x.x:x/wfd1.0/streamid=0"

/**
 * RTSP server for Miracast over infrastructure (Windows Win+K / Win+P).
 */
class MiracastRtspServer(
    private val onStreamReady: (MiracastStreamInfo) -> Unit,
    private val onStreamStopped: (MiracastStreamInfo?) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var listenPort: Int = DEFAULT_RTSP_PORT
    private var outboundSocket: Socket? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var lastRtspActivityAtMs: Long = 0L

    private val nextPortCandidate = AtomicInteger(19000)
    private val activeControlConnections = AtomicInteger(0)

    suspend fun start(port: Int = DEFAULT_RTSP_PORT, bindAddress: String = "0.0.0.0") {
        if (isRunning) {
            println("MIRACAST_RTSP: ⚠️ Server already running on port $listenPort")
            return
        }

        listenPort = port
        selectorManager = SelectorManager(Dispatchers.IO)
        serverSocket = aSocket(selectorManager!!).tcp().bind(bindAddress, port)
        isRunning = true

        println("MIRACAST_RTSP: ✅ RTSP server started on port $port")

        acceptJob = scope.launch {
            while (coroutineContext.isActive && isRunning) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                } catch (e: Exception) {
                    if (coroutineContext.isActive && isRunning) {
                        println("MIRACAST_RTSP: ⚠️ Accept error: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * For MS-MICE infrastructure mode: sink initiates TCP to source RTSP endpoint.
     * The source still sends WFD RTSP requests over this connected channel.
     */
    suspend fun connectToSource(sourceAddress: String, sourcePort: Int): Result<Unit> {
        if (!isRunning) {
            return Result.failure(IllegalStateException("RTSP server is not running"))
        }

        return runCatching {
            val selector = selectorManager ?: SelectorManager(Dispatchers.IO).also {
                selectorManager = it
            }

            outboundSocket?.let { existing ->
                runCatching { existing.close() }
                outboundSocket = null
            }

            lastRtspActivityAtMs = 0L
            val socket = aSocket(selector).tcp().connect(sourceAddress, sourcePort)
            outboundSocket = socket

            println("MIRACAST_RTSP: 🔄 Connected to source RTSP endpoint $sourceAddress:$sourcePort")
            scope.launch { handleClient(socket) }
        }
    }

    fun stop() {
        isRunning = false
        scope.launch {
            runCatching { acceptJob?.cancelAndJoin() }
            acceptJob = null

            runCatching { outboundSocket?.close() }
            outboundSocket = null

            runCatching { serverSocket?.close() }
            serverSocket = null

            runCatching { selectorManager?.close() }
            selectorManager = null
            lastRtspActivityAtMs = 0L

            println("MIRACAST_RTSP: 🔴 RTSP server stopped")
        }
    }

    fun hasActiveControlConnection(): Boolean = activeControlConnections.get() > 0

    fun hasRecentRtspActivity(withinMs: Long = 5_000): Boolean {
        val lastActivityAt = lastRtspActivityAtMs
        return lastActivityAt > 0 && (System.currentTimeMillis() - lastActivityAt) <= withinMs
    }

    private suspend fun handleClient(socket: Socket) {
        val clientAddress = extractHost(socket.remoteAddress.toString())
        val localAddress = extractHost(socket.localAddress.toString())
        val session = RtspSession(
            sessionId = generateSessionId(),
            socket = socket,
            clientAddress = clientAddress,
            localAddress = localAddress,
        )

        activeControlConnections.incrementAndGet()
        println("MIRACAST_RTSP: 📱 Client connected from $clientAddress")

        try {
            val input = socket.openReadChannel()
            val output = socket.openWriteChannel(autoFlush = true)

            while (coroutineContext.isActive && isRunning) {
                val message = parseRtspMessage(input) ?: break
                lastRtspActivityAtMs = System.currentTimeMillis()
                when (message) {
                    is RtspRequest -> {
                        println("MIRACAST_RTSP: 📩 ${message.method} ${message.uri} (CSeq=${message.cseq})")
                        when (message.method.uppercase(Locale.US)) {
                            "OPTIONS" -> handleOptions(output, message, session)
                            "DESCRIBE" -> handleDescribe(output, message, session)
                            "SETUP" -> handleSetup(output, message, session)
                            "PLAY" -> handlePlay(output, message, session)
                            "TEARDOWN" -> {
                                handleTeardown(output, message, session)
                                break
                            }
                            "GET_PARAMETER" -> handleGetParameter(output, message, session)
                            "SET_PARAMETER" -> handleSetParameter(output, message, session)
                            else -> sendError(output, message.cseq, 501, "Not Implemented")
                        }
                    }
                    is RtspResponse -> {
                        println(
                            "MIRACAST_RTSP: 📥 RTSP/1.0 ${message.statusCode} " +
                                "${message.statusMessage} (CSeq=${message.cseq})",
                        )
                        handleResponse(output, message, session)
                    }
                }
            }
        } catch (e: Exception) {
            println("MIRACAST_RTSP: ⚠️ Client error: ${e.message}")
        } finally {
            if (session.isPlaying) {
                onStreamStopped(session.toStreamInfoOrNull())
            }
            if (outboundSocket === socket) {
                outboundSocket = null
            }
            activeControlConnections.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            runCatching { socket.close() }
            println("MIRACAST_RTSP: 🔌 Client disconnected: $clientAddress")
        }
    }

    private suspend fun handleResponse(
        output: ByteWriteChannel,
        response: RtspResponse,
        session: RtspSession,
    ) {
        val pending = session.pendingRequests.remove(response.cseq)
        if (pending == null) {
            println(
                "MIRACAST_RTSP: ℹ️ Ignoring unsolicited response " +
                    "${response.statusCode} (CSeq=${response.cseq})",
            )
            return
        }

        when (pending) {
            PendingRtspRequestType.OPTIONS_PROBE -> {
                if (response.statusCode == 200) {
                    println("MIRACAST_RTSP: ✅ Sink OPTIONS probe accepted by source")
                } else {
                    println(
                        "MIRACAST_RTSP: ⚠️ Sink OPTIONS probe failed: " +
                            "${response.statusCode} ${response.statusMessage}",
                    )
                }
            }
            PendingRtspRequestType.SETUP -> handleSetupResponse(output, response, session)
            PendingRtspRequestType.PLAY -> handlePlayResponse(response, session)
        }
    }

    private suspend fun handleOptions(
        output: ByteWriteChannel,
        request: RtspRequest,
        session: RtspSession,
    ) {
        val response = buildRtspResponse(
            code = 200,
            message = "OK",
            cseq = request.cseq,
            headers = mapOf(
                "Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER",
            ),
        )
        output.writeStringUtf8(response)

        if (!session.optionsProbeSent) {
            session.optionsProbeSent = true
            sendRequest(
                output = output,
                session = session,
                method = "OPTIONS",
                uri = "*",
                headers = mapOf("Require" to WFD_REQUIRE_TOKEN),
                pendingType = PendingRtspRequestType.OPTIONS_PROBE,
            )
        }
    }

    private suspend fun handleDescribe(
        output: ByteWriteChannel,
        request: RtspRequest,
        session: RtspSession,
    ) {
        val sdp =
            "v=0\r\n" +
                "o=- 0 0 IN IP4 ${session.localAddress}\r\n" +
                "s=TeachmintShareX Miracast Session\r\n" +
                "t=0 0\r\n" +
                "m=video 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=control:streamid=0\r\n"

        val response = buildRtspResponse(
            code = 200,
            message = "OK",
            cseq = request.cseq,
            headers = mapOf("Content-Type" to "application/sdp"),
            body = sdp,
        )
        output.writeStringUtf8(response)
    }

    private suspend fun handleSetup(
        output: ByteWriteChannel,
        request: RtspRequest,
        session: RtspSession,
    ) {
        val transport = request.header("transport") ?: "RTP/AVP/UDP;unicast"
        val clientPorts = extractClientPorts(transport)
        val interleaved = extractInterleavedChannels(transport)

        ensureLocalTransportPorts(session)

        session.clientRtpPort = clientPorts?.first
        session.clientRtcpPort = clientPorts?.second
        session.streamUri = request.uri
        session.isSessionEstablished = true
        session.playbackSessionId = session.playbackSessionId ?: session.sessionId

        val profile = transport.substringBefore(';').ifBlank { "RTP/AVP/UDP" }
        val responseTransport = buildString {
            append(profile)
            append(";unicast")
            if (interleaved != null) {
                append(";interleaved=${interleaved.first}-${interleaved.second}")
            } else {
                if (clientPorts != null) {
                    append(";client_port=${clientPorts.first}-${clientPorts.second}")
                }
                append(";server_port=${session.rtpPort}-${session.rtcpPort}")
            }
            append(";ssrc=${session.ssrcHex}")
            append(";mode=play")
        }

        session.wfdParameters["wfd_client_rtp_ports"] =
            "RTP/AVP/UDP;unicast ${session.rtpPort} 0 mode=play"
        session.wfdParameters["wfd_presentation_URL"] =
            "rtsp://${session.localAddress}/wfd1.0/streamid=0 none"

        val response = buildRtspResponse(
            code = 200,
            message = "OK",
            cseq = request.cseq,
            headers = mapOf(
                "Session" to (session.playbackSessionId ?: session.sessionId),
                "Transport" to responseTransport,
            ),
        )
        output.writeStringUtf8(response)

        println(
            "MIRACAST_RTSP: ✅ SETUP negotiated " +
                "server_port=${session.rtpPort}-${session.rtcpPort} " +
                "client_port=${session.clientRtpPort}-${session.clientRtcpPort}",
        )
    }

    private suspend fun handlePlay(
        output: ByteWriteChannel,
        request: RtspRequest,
        session: RtspSession,
    ) {
        session.isPlaying = true

        val response = buildRtspResponse(
            code = 200,
            message = "OK",
            cseq = request.cseq,
            headers = sessionHeaders(
                session,
                "RTP-Info" to "url=${session.streamUri};seq=0;rtptime=0",
            ),
        )
        output.writeStringUtf8(response)

        val streamInfo = session.toStreamInfoOrNull()
        if (streamInfo != null) {
            println(
                "MIRACAST_RTSP: ▶️ PLAY accepted for ${streamInfo.clientAddress}, " +
                    "RTP port=${streamInfo.rtpPort}",
            )
            onStreamReady(streamInfo)
        } else {
            println("MIRACAST_RTSP: ⚠️ PLAY received but RTP ports are not ready")
        }
    }

    private suspend fun handleTeardown(
        output: ByteWriteChannel,
        request: RtspRequest,
        session: RtspSession,
    ) {
        val response = buildRtspResponse(
            code = 200,
            message = "OK",
            cseq = request.cseq,
            headers = sessionHeaders(session),
        )
        output.writeStringUtf8(response)

        val wasPlaying = session.isPlaying
        session.isPlaying = false
        if (wasPlaying) {
            onStreamStopped(session.toStreamInfoOrNull())
        }
    }

    private suspend fun handleGetParameter(
        output: ByteWriteChannel,
        request: RtspRequest,
        session: RtspSession,
    ) {
        if (!session.isSessionEstablished) {
            ensureLocalTransportPorts(session)
            val requested = parseWfdParameterQueries(request.body)

            val response = buildRtspResponse(
                code = 200,
                message = "OK",
                cseq = request.cseq,
                headers = mapOf("Content-Type" to "text/parameters"),
                body = buildSinkCapabilityBody(session, requested),
            )
            output.writeStringUtf8(response)
            return
        }

        val requested = parseWfdParameterQueries(request.body)
        if (requested.isEmpty()) {
            val response = buildRtspResponse(
                code = 200,
                message = "OK",
                cseq = request.cseq,
                headers = sessionHeaders(session),
            )
            output.writeStringUtf8(response)
            return
        }

        val body = requested.joinToString(separator = "\r\n", postfix = "\r\n") { key ->
            val value = session.wfdParameters[key] ?: defaultWfdValue(key, session)
            "$key: $value"
        }

        val response = buildRtspResponse(
            code = 200,
            message = "OK",
            cseq = request.cseq,
            headers = sessionHeaders(
                session,
                "Content-Type" to "text/parameters",
            ),
            body = body,
        )
        output.writeStringUtf8(response)
    }

    private suspend fun handleSetParameter(
        output: ByteWriteChannel,
        request: RtspRequest,
        session: RtspSession,
    ) {
        val updates = parseWfdParameterAssignments(request.body)
        var shouldSendSetup = false
        if (updates.isNotEmpty()) {
            session.wfdParameters.putAll(updates)
            updates["wfd_presentation_url"]?.let { value ->
                val presentationUri = extractPresentationUri(value)
                if (presentationUri.isNotBlank()) {
                    session.streamUri = presentationUri
                    println("MIRACAST_RTSP: 🎯 Source presentation URL: $presentationUri")
                }
            }
            updates["wfd_trigger_method"]?.let { trigger ->
                println("MIRACAST_RTSP: 🎛️ WFD trigger received: $trigger")
                if (trigger.equals("SETUP", ignoreCase = true) && !session.setupRequestSent) {
                    shouldSendSetup = true
                }
            }
            updates["wfd_video_formats"]?.let { value ->
                println("MIRACAST_RTSP: 🎞️ Source video formats: $value")
            }
        }

        if (shouldSendSetup) {
            sendSetupRequest(output, session)
        }

        val response = buildRtspResponse(
            code = 200,
            message = "OK",
            cseq = request.cseq,
            headers = sessionHeaders(session),
        )
        output.writeStringUtf8(response)
    }

    private suspend fun handleSetupResponse(
        output: ByteWriteChannel,
        response: RtspResponse,
        session: RtspSession,
    ) {
        if (response.statusCode != 200) {
            println(
                "MIRACAST_RTSP: ⚠️ Source rejected SETUP: " +
                    "${response.statusCode} ${response.statusMessage}",
            )
            session.setupRequestSent = false
            return
        }

        val sessionHeader = response.header("session")
        if (sessionHeader.isNullOrBlank()) {
            println("MIRACAST_RTSP: ⚠️ SETUP response missing Session header")
            session.setupRequestSent = false
            return
        }

        session.playbackSessionId = extractSessionId(sessionHeader)
        session.isSessionEstablished = true

        response.header("transport")?.let { transport ->
            extractServerPorts(transport)?.let { (rtpPort, rtcpPort) ->
                session.remoteRtpPort = rtpPort
                session.remoteRtcpPort = rtcpPort
            }
        }

        println(
            "MIRACAST_RTSP: ✅ Source accepted SETUP " +
                "session=${session.playbackSessionId} " +
                "server_port=${session.remoteRtpPort ?: "unknown"}-" +
                "${session.remoteRtcpPort ?: "unknown"} " +
                "client_port=${session.rtpPort}-${session.rtcpPort}",
        )

        sendPlayRequest(output, session)
    }

    private suspend fun handlePlayResponse(
        response: RtspResponse,
        session: RtspSession,
    ) {
        if (response.statusCode != 200) {
            println(
                "MIRACAST_RTSP: ⚠️ Source rejected PLAY: " +
                    "${response.statusCode} ${response.statusMessage}",
            )
            return
        }

        session.isPlaying = true
        val streamInfo = session.toStreamInfoOrNull()
        if (streamInfo != null) {
            println(
                "MIRACAST_RTSP: ▶️ PLAY accepted for ${streamInfo.clientAddress}, " +
                    "local RTP port=${streamInfo.rtpPort}",
            )
            onStreamReady(streamInfo)
        } else {
            println("MIRACAST_RTSP: ⚠️ PLAY response received but local RTP ports are not ready")
        }
    }

    private suspend fun sendError(output: ByteWriteChannel, cseq: Int, code: Int, message: String) {
        val response = buildRtspResponse(code = code, message = message, cseq = cseq)
        output.writeStringUtf8(response)
    }

    private suspend fun sendSetupRequest(output: ByteWriteChannel, session: RtspSession) {
        ensureLocalTransportPorts(session)
        val uri = normalizeRtspUri(session.streamUri, session)
        session.setupRequestSent = true
        sendRequest(
            output = output,
            session = session,
            method = "SETUP",
            uri = uri,
            headers = mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;client_port=${session.rtpPort}-${session.rtcpPort}",
            ),
            pendingType = PendingRtspRequestType.SETUP,
        )
    }

    private suspend fun sendPlayRequest(output: ByteWriteChannel, session: RtspSession) {
        val playbackSessionId = session.playbackSessionId
        if (playbackSessionId.isNullOrBlank()) {
            println("MIRACAST_RTSP: ⚠️ Cannot send PLAY before Session is established")
            return
        }

        sendRequest(
            output = output,
            session = session,
            method = "PLAY",
            uri = normalizeRtspUri(session.streamUri, session),
            headers = mapOf("Session" to playbackSessionId),
            pendingType = PendingRtspRequestType.PLAY,
        )
    }

    private suspend fun sendRequest(
        output: ByteWriteChannel,
        session: RtspSession,
        method: String,
        uri: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        pendingType: PendingRtspRequestType? = null,
    ) {
        val cseq = session.nextCSeq++
        pendingType?.let { session.pendingRequests[cseq] = it }
        val request = buildRtspRequest(
            method = method,
            uri = uri,
            cseq = cseq,
            headers = headers,
            body = body,
        )
        output.writeStringUtf8(request)
        println("MIRACAST_RTSP: 📤 $method $uri (CSeq=$cseq)")
    }

    private fun buildRtspResponse(
        code: Int,
        message: String,
        cseq: Int,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): String {
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val responseHeaders = linkedMapOf<String, String>()
        headers.forEach { (key, value) ->
            responseHeaders[key] = value
        }
        if (bodyBytes != null && responseHeaders.keys.none { it.equals("Content-Length", ignoreCase = true) }) {
            responseHeaders["Content-Length"] = bodyBytes.size.toString()
        }

        val response = StringBuilder()
        response.append("RTSP/1.0 $code $message\r\n")
        appendCommonHeaders(response, cseq)

        responseHeaders.forEach { (key, value) ->
            response.append("$key: $value\r\n")
        }

        response.append("\r\n")
        if (bodyBytes != null) {
            response.append(body)
        }

        return response.toString()
    }

    private fun buildRtspRequest(
        method: String,
        uri: String,
        cseq: Int,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): String {
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val requestHeaders = linkedMapOf<String, String>()
        headers.forEach { (key, value) ->
            requestHeaders[key] = value
        }
        if (bodyBytes != null && requestHeaders.keys.none { it.equals("Content-Length", ignoreCase = true) }) {
            requestHeaders["Content-Length"] = bodyBytes.size.toString()
        }

        val request = StringBuilder()
        request.append("$method $uri RTSP/1.0\r\n")
        appendCommonHeaders(request, cseq)
        requestHeaders.forEach { (key, value) ->
            request.append("$key: $value\r\n")
        }
        request.append("\r\n")
        if (bodyBytes != null) {
            request.append(body)
        }
        return request.toString()
    }

    private fun appendCommonHeaders(builder: StringBuilder, cseq: Int) {
        builder.append(
            "Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))}\r\n",
        )
        builder.append("User-Agent: $USER_AGENT\r\n")
        builder.append("CSeq: $cseq\r\n")
    }

    private fun sessionHeaders(
        session: RtspSession,
        vararg extraHeaders: Pair<String, String>,
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        session.activeSessionIdOrNull()?.let { sessionId ->
            headers["Session"] = sessionId
        }
        extraHeaders.forEach { (key, value) ->
            headers[key] = value
        }
        return headers
    }

    private fun ensureLocalTransportPorts(session: RtspSession) {
        if (session.rtpPort > 0 && session.rtcpPort > 0) return
        val (rtpPort, rtcpPort) = allocatePortPair()
        session.rtpPort = rtpPort
        session.rtcpPort = rtcpPort
    }

    private fun buildSinkCapabilityBody(
        session: RtspSession,
        requestedKeys: List<String>,
    ): String {
        val keys = if (requestedKeys.isNotEmpty()) {
            requestedKeys
        } else {
            listOf(
                "wfd_content_protection",
                "wfd_video_formats",
                "wfd_audio_codecs",
                "wfd_client_rtp_ports",
            )
        }

        return keys.joinToString(separator = "\r\n", postfix = "\r\n") { key ->
            val value = session.wfdParameters[key] ?: defaultWfdValue(key, session)
            "$key: $value"
        }
    }

    private fun allocatePortPair(): Pair<Int, Int> {
        repeat(200) {
            val start = nextPortCandidate.getAndAdd(2).let { if (it % 2 == 0) it else it + 1 }
            val rtp = start
            val rtcp = start + 1
            if (isUdpPortAvailable(rtp) && isUdpPortAvailable(rtcp)) {
                return rtp to rtcp
            }
        }

        val fallback = 5000 + Random.nextInt(1, 1000) * 2
        return fallback to fallback + 1
    }

    private fun isUdpPortAvailable(port: Int): Boolean {
        return runCatching {
            DatagramSocket(port).use {}
            true
        }.getOrDefault(false)
    }

    private fun defaultWfdValue(key: String, session: RtspSession): String {
        return when (key) {
            "wfd_audio_codecs" -> "AAC 0000000F 00"
            "wfd_video_formats" -> "28 00 01 01 ffffffff ffffffff ffffffff 00 0000 0000 00 none none"
            "wfd_3d_video_formats" -> "none"
            "wfd_content_protection" -> "none"
            "wfd_display_edid" -> "none"
            "wfd_coupled_sink" -> "none"
            "wfd_client_rtp_ports" -> "RTP/AVP/UDP;unicast ${session.rtpPort} 0 mode=play"
            "wfd_uibc_capability" -> "none"
            "wfd_connector_type" -> "5"
            "wfd_standby_resume_capability" -> "none"
            "wfd_presentation_URL" -> "rtsp://${session.localAddress}/wfd1.0/streamid=0 none"
            else -> "none"
        }
    }

    private fun extractClientPorts(transport: String): Pair<Int, Int>? {
        val regex = """client_port=(\d+)-(\d+)""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(transport) ?: return null
        val (rtpPort, rtcpPort) = match.destructured
        return rtpPort.toInt() to rtcpPort.toInt()
    }

    private fun extractInterleavedChannels(transport: String): Pair<Int, Int>? {
        val regex = """interleaved=(\d+)-(\d+)""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(transport) ?: return null
        val (first, second) = match.destructured
        return first.toInt() to second.toInt()
    }

    private fun extractServerPorts(transport: String): Pair<Int, Int>? {
        val regex = """server_port=(\d+)(?:-(\d+))?""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(transport) ?: return null
        val rtpPort = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val rtcpPort = match.groupValues.getOrNull(2)?.toIntOrNull() ?: (rtpPort + 1)
        return rtpPort to rtcpPort
    }

    private fun extractHost(socketAddress: String): String {
        val trimmed = socketAddress.trim()
        val withoutLeadingSlash = trimmed.removePrefix("/")
        val withoutPort = withoutLeadingSlash.substringBefore(":")
        return withoutPort.ifBlank { socketAddress }
    }

    private fun extractPresentationUri(value: String): String {
        return value.substringBefore(' ').trim()
    }

    private fun normalizeRtspUri(uri: String, session: RtspSession): String {
        val normalized = uri.trim()
        if (!normalized.startsWith("rtsp://", ignoreCase = true)) {
            return "rtsp://${session.clientAddress}/wfd1.0/streamid=0"
        }

        val parsed = runCatching { URI(normalized) }.getOrNull()
            ?: return "rtsp://${session.clientAddress}/wfd1.0/streamid=0"

        val host = parsed.host?.trim().orEmpty()
        val shouldRewriteHost = host.isBlank() ||
            host == "0.0.0.0" ||
            host == "255.255.255.255" ||
            host == "::" ||
            host == "::1" ||
            host.equals("localhost", ignoreCase = true)

        if (!shouldRewriteHost) {
            return normalized
        }

        if (host.isNotBlank()) {
            println(
                "MIRACAST_RTSP: ℹ️ Rewriting presentation URI host '$host' " +
                    "to source '${session.clientAddress}'",
            )
        }

        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        val path = parsed.rawPath?.takeIf { it.isNotBlank() } ?: "/wfd1.0/streamid=0"
        val query = parsed.rawQuery?.let { "?$it" } ?: ""
        return "rtsp://${session.clientAddress}$port$path$query"
    }

    private fun extractSessionId(sessionHeader: String): String {
        return sessionHeader.substringBefore(';').trim()
    }

    private companion object {
        const val DEFAULT_RTSP_PORT = MiracastPorts.WFD_RTSP_PORT
        const val USER_AGENT = "TeachmintShareX/1.0"
        const val WFD_REQUIRE_TOKEN = "org.wfa.wfd1.0"
    }
}

private suspend fun parseRtspMessage(input: ByteReadChannel): RtspMessage? {
    return try {
        var startLine: String
        while (true) {
            startLine = input.readUTF8Line() ?: return null
            if (startLine.isNotBlank()) {
                break
            }
        }

        val startParts = startLine.split(' ', limit = 3)
        if (startParts.size < 2) return null

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readUTF8Line() ?: break
            if (line.isBlank()) break

            val separator = line.indexOf(':')
            if (separator <= 0) continue

            val key = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            headers[key] = value
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            input.readFully(bodyBytes, 0, contentLength)
            bodyBytes.decodeToString()
        } else {
            ""
        }

        val cseq = headers["cseq"]?.toIntOrNull() ?: 0
        if (startParts[0].startsWith("RTSP/", ignoreCase = true)) {
            val statusCode = startParts.getOrNull(1)?.toIntOrNull() ?: return null
            val statusMessage = startParts.getOrElse(2) { "" }.trim()
            RtspResponse(
                statusCode = statusCode,
                statusMessage = statusMessage,
                cseq = cseq,
                headers = headers,
                body = body,
            )
        } else {
            val method = startParts[0].trim()
            val uri = startParts[1].trim()
            RtspRequest(
                method = method,
                uri = uri,
                cseq = cseq,
                headers = headers,
                body = body,
            )
        }
    } catch (e: Exception) {
        println("MIRACAST_RTSP: ⚠️ RTSP parse error: ${e.message}")
        null
    }
}

private fun parseWfdParameterQueries(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    return body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            if (':' in line) line.substringBefore(':').trim().lowercase(Locale.US)
            else line.lowercase(Locale.US)
        }
        .toList()
}

private fun parseWfdParameterAssignments(body: String): Map<String, String> {
    if (body.isBlank()) return emptyMap()

    val updates = linkedMapOf<String, String>()
    for (line in body.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue

        val separator = trimmed.indexOf(':')
        if (separator <= 0) continue

        val key = trimmed.substring(0, separator).trim().lowercase(Locale.US)
        val value = trimmed.substring(separator + 1).trim()
        updates[key] = value
    }

    return updates
}

data class MiracastStreamInfo(
    val sessionId: String,
    val clientAddress: String,
    val rtpPort: Int,
    val rtcpPort: Int,
)

data class RtspRequest(
    val method: String,
    val uri: String,
    val cseq: Int,
    override val headers: Map<String, String> = emptyMap(),
    override val body: String = "",
) : RtspMessage {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
}

data class RtspResponse(
    val statusCode: Int,
    val statusMessage: String,
    val cseq: Int,
    override val headers: Map<String, String> = emptyMap(),
    override val body: String = "",
) : RtspMessage {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
}

sealed interface RtspMessage {
    val headers: Map<String, String>
    val body: String
}

data class RtspSession(
    val sessionId: String,
    val socket: Socket,
    val clientAddress: String,
    val localAddress: String,
    var rtpPort: Int = 0,
    var rtcpPort: Int = 0,
    var clientRtpPort: Int? = null,
    var clientRtcpPort: Int? = null,
    var isPlaying: Boolean = false,
    var isSessionEstablished: Boolean = false,
    var playbackSessionId: String? = null,
    var nextCSeq: Int = 1,
    var optionsProbeSent: Boolean = false,
    var setupRequestSent: Boolean = false,
    var remoteRtpPort: Int? = null,
    var remoteRtcpPort: Int? = null,
    var streamUri: String = DEFAULT_WFD_STREAM_URI,
    val ssrcHex: String = Random.nextInt().toUInt().toString(16).padStart(8, '0'),
    val wfdParameters: MutableMap<String, String> = mutableMapOf(),
    val pendingRequests: MutableMap<Int, PendingRtspRequestType> = mutableMapOf(),
) {
    fun toStreamInfoOrNull(): MiracastStreamInfo? {
        if (rtpPort <= 0 || rtcpPort <= 0) return null
        return MiracastStreamInfo(
            sessionId = activeSessionIdOrNull() ?: sessionId,
            clientAddress = clientAddress,
            rtpPort = rtpPort,
            rtcpPort = rtcpPort,
        )
    }

    fun activeSessionIdOrNull(): String? {
        return playbackSessionId ?: sessionId.takeIf { isSessionEstablished }
    }
}

enum class PendingRtspRequestType {
    OPTIONS_PROBE,
    SETUP,
    PLAY,
}

private fun generateSessionId(): String {
    val secureRandom = java.security.SecureRandom()
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
