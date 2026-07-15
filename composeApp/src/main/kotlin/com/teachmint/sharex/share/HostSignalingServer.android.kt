package com.teachmint.sharex.share.host

import com.teachmint.sharex.share.shared.*

import com.teachmint.sharex.filetransfer.getShareXDirectoryPath
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.readByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.server.plugins.origin
import io.ktor.websocket.CloseReason
import kotlin.time.Duration.Companion.seconds

class HostSignalingServer constructor(
    private val onClientConnected: (ClientInfo) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onMessage: (String, SignalingMessage) -> Unit,
    private val hostNameProvider: () -> String,
) {
    private val sessions = ConcurrentHashMap<String, io.ktor.websocket.WebSocketSession>()
    private val clients = ConcurrentHashMap<String, ClientInfo>()
    private val _connectedClients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val connectedClients: SharedFlow<List<ClientInfo>> = _connectedClients.asStateFlow()

    private var engine: EmbeddedServer<*, *>? = null
    private var activePort: Int? = null

    suspend fun start(port: Int): Int {
        activePort?.let { currentPort ->
            if (engine != null) {
                return currentPort
            }
        }
        // CIO can surface bind failures on a background worker; preflight the port first.
        val startPort = findAvailablePort(port)
        if (startPort != port) {
            println(
                "HOST_SIGNALING: ⚠️ Port $port is busy; using fallback port $startPort",
            )
        }
        val server = embeddedServer(CIO, port = startPort) {
            configureWebSockets()
        }
        engine = server
        try {
            server.start(wait = false)
            activePort = startPort
        } catch (error: Throwable) {
            engine = null
            activePort = null
            throw error
        }
        return startPort
    }

    suspend fun stop() {
        engine?.stop(500, 2000)
        engine = null
        activePort = null
        sessions.clear()
        clients.clear()
        _connectedClients.value = emptyList()
    }

    suspend fun send(clientId: String, message: SignalingMessage) {
        val session = sessions[clientId] ?: return
        val payload = ShareXJson.encodeToString(message)
        session.send(Frame.Text(payload))
    }

    private fun Application.configureWebSockets() {
        install(WebSockets) {
            // ReplayKit transitions on iOS can temporarily delay websocket callbacks.
            // Use more tolerant keep-alive windows so host does not drop the client mid-flow.
            pingPeriod = 30.seconds
            timeout = 90.seconds
            maxFrameSize = 1024 * 1024 // 1 MB
        }
        routing {
            get(HOST_NAME_ENDPOINT_PATH) {
                val resolvedHostName = hostNameProvider().trim().ifBlank { "ShareX Host" }
                call.respondText(resolvedHostName)
            }
            post(FILE_UPLOAD_ENDPOINT_PATH) {
                try {
                    val rawFileName = call.request.header("X-File-Name")
                    if (rawFileName.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing X-File-Name header")
                        return@post
                    }
                    val fileName = URLDecoder.decode(rawFileName, "UTF-8")
                        .replace(Regex("[/\\\\]"), "_") // sanitize path separators
                    val dirPath = getShareXDirectoryPath()
                    val targetFile = File(dirPath, fileName)
                    val channel = call.receiveChannel()
                    val bytes = channel.readRemaining().readByteArray()
                    targetFile.writeBytes(bytes)
                    println("FILE_UPLOAD: Saved ${bytes.size} bytes as ${targetFile.absolutePath}")
                    call.respondText("OK")
                } catch (e: Exception) {
                    println("FILE_UPLOAD: Error: ${e.message}")
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, "Upload failed: ${e.message}")
                }
            }
            webSocket("/ws") {
                // F-004: Enforce connection limits
                val clientIp = call.request.origin.remoteHost
                val totalConnections = sessions.size
                if (totalConnections >= MAX_CONCURRENT_CONNECTIONS) {
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Server at capacity"))
                    return@webSocket
                }
                val ipCount = connectionsPerIp.computeIfAbsent(clientIp) { AtomicInteger(0) }
                if (ipCount.incrementAndGet() > MAX_CONNECTIONS_PER_IP) {
                    ipCount.decrementAndGet()
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections from this IP"))
                    return@webSocket
                }

                var clientId: String? = null
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val message = ShareXJson.decodeFromString<SignalingMessage>(frame.readText())
                            when (message) {
                                is SignalingMessage.Hello -> {
                                    clientId = message.clientId
                                    val previousSession = sessions.put(message.clientId, this)
                                    if (previousSession != null && previousSession !== this) {
                                        runCatching {
                                            previousSession.close()
                                        }
                                    }
                                    val info = ClientInfo(
                                        clientId = message.clientId,
                                        name = message.clientName,
                                        platform = message.platform,
                                    )
                                    val isNewClient = clients.put(message.clientId, info) == null
                                    _connectedClients.value = clients.values.toList()
                                    if (isNewClient) {
                                        onClientConnected(info)
                                    }
                                }
                                else -> {
                                    val id = clientId
                                    if (id != null) onMessage(id, message)
                                }
                            }
                        }
                    }
                } finally {
                    ipCount.decrementAndGet()
                    val id = clientId
                    if (id != null) {
                        val removedCurrentSession = sessions.remove(id, this)
                        if (removedCurrentSession) {
                            clients.remove(id)
                            _connectedClients.value = clients.values.toList()
                            onClientDisconnected(id)
                        }
                    }
                }
            }
        }
    }

    private fun findAvailablePort(preferredPort: Int): Int {
        val start = preferredPort.coerceIn(1, MAX_PORT)
        val end = (start + PORT_FALLBACK_WINDOW).coerceAtMost(MAX_PORT)
        for (candidate in start..end) {
            if (isPortAvailable(candidate)) {
                return candidate
            }
        }
        throw BindException("Ports $start-$end are already in use")
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
            }
            true
        } catch (_: BindException) {
            false
        }
    }

    // F-004: Per-IP connection tracking for rate limiting
    private val connectionsPerIp = ConcurrentHashMap<String, AtomicInteger>()

    private companion object {
        const val PORT_FALLBACK_WINDOW = 15
        const val MAX_PORT = 65_535
        // F-004: Connection limits to prevent DoS on resource-constrained IFP devices
        const val MAX_CONCURRENT_CONNECTIONS = 10
        const val MAX_CONNECTIONS_PER_IP = 3
    }
}
