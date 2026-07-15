package com.teachmint.sharex.share.host

import com.teachmint.sharex.share.shared.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Service for hosts to connect to the remote signaling server.
 * This enables web clients to discover and connect to the host over the internet.
 */
class RemoteSignalingService(
    private val scope: CoroutineScope,
    private val remoteServerUrl: String,
    private val hostId: String,
    private var hostName: String,
    private val onClientConnected: (ClientInfo) -> Unit,
    private val onClientDisconnected: (String) -> Unit,
    private val onMessage: (String, SignalingMessage) -> Unit,
) {
    private val httpClient: HttpClient = createHttpClient()
    private val mutex = Mutex()
    private var session: DefaultClientWebSocketSession? = null
    private var receiverJob: Job? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val connectedClients: StateFlow<List<ClientInfo>> = _connectedClients.asStateFlow()

    private val connectedClientsMap = mutableMapOf<String, ClientInfo>()
    private var currentPin: String? = null
    private var currentPinExpiresAtEpochMs: Long? = null

    suspend fun connect(pin: String? = null, pinExpiresAtEpochMs: Long? = null) {
        mutex.withLock {
            when (_state.value) {
                is ConnectionState.Connected, is ConnectionState.Connecting -> return
                else -> Unit
            }
            _state.value = ConnectionState.Connecting

            // Cancel any lingering receiver job from a previous connection so it
            // cannot clobber the new session's state or set session to null.
            receiverJob?.cancel()
            receiverJob = null
            session?.let { old ->
                runCatching { old.close() }
                session = null
            }

            try {
                println("REMOTE_SIGNALING: Connecting to $remoteServerUrl")
                // V-007: Send auth token via header instead of URL query parameter
                val wsSession = httpClient.webSocketSession(urlString = remoteServerUrl) {
                    RemoteServerConfig.SIGNALING_AUTH_TOKEN?.let { token ->
                        headers.append("Authorization", "Bearer $token")
                    }
                }
                session = wsSession
                _state.value = ConnectionState.Connected
                currentPin = pin
                currentPinExpiresAtEpochMs = pinExpiresAtEpochMs

                // Register as host
                val platform = getPlatformName()
                send(SignalingMessage.RegisterHost(
                    hostId = hostId,
                    hostName = hostName,
                    platform = platform,
                    pin = currentPin,
                    pinExpiresAtEpochMs = currentPinExpiresAtEpochMs,
                ))

                println("REMOTE_SIGNALING: Registered as host: $hostId ($hostName) on $platform")

                receiverJob = scope.launch {
                    try {
                        for (frame in wsSession.incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                val message = runCatching {
                                    decodeSignalingMessage(text)
                                }.getOrElse { parseError ->
                                    val nonSignalingType = extractJsonType(text)
                                    if (nonSignalingType == "hot") {
                                        val descriptiveError =
                                            "Connected to a non-signaling websocket endpoint (received 'hot'). " +
                                                "Please configure the signaling server URL. Current URL: $remoteServerUrl"
                                        println("REMOTE_SIGNALING: $descriptiveError")
                                        _state.value = ConnectionState.Error(descriptiveError)
                                        wsSession.close()
                                        return@launch
                                    }
                                    println(
                                        "REMOTE_SIGNALING: Ignoring non-signaling frame. " +
                                            "Error=${parseError.message}, payload=$text"
                                    )
                                    null
                                }
                                if (message != null) {
                                    handleMessage(message)
                                }
                            }
                        }
                        println("REMOTE_SIGNALING: WebSocket closed normally")
                        _state.value = ConnectionState.Idle
                    } catch (e: Exception) {
                        val normalized = normalizeWebSocketError(e)
                        println("REMOTE_SIGNALING: WebSocket error: $normalized")
                        e.printStackTrace()
                        _state.value = ConnectionState.Error(normalized)
                    } finally {
                        session = null
                    }
                }
            } catch (e: Exception) {
                val normalized = normalizeWebSocketError(e)
                println("REMOTE_SIGNALING: Failed to connect: $normalized")
                _state.value = ConnectionState.Error(normalized)
                session = null
            }
        }
    }

    private fun extractJsonType(payload: String): String? {
        val match = typeRegex.find(payload) ?: return null
        return match.groupValues.getOrNull(1)
    }

    companion object {
        private val typeRegex = Regex("""\"type\"\s*:\s*\"([^\"]+)\"""")
    }

    suspend fun disconnect() {
        mutex.withLock {
            // Unregister from server
            try {
                send(SignalingMessage.UnregisterHost(hostId))
            } catch (e: Exception) {
                println("REMOTE_SIGNALING: Failed to unregister: ${e.message}")
            }

            receiverJob?.cancel()
            receiverJob = null
            session?.close()
            session = null
            _state.value = ConnectionState.Idle
            connectedClientsMap.clear()
            _connectedClients.value = emptyList()
        }
    }

    suspend fun updatePin(pin: String, pinExpiresAtEpochMs: Long) {
        mutex.withLock {
            currentPin = pin
            currentPinExpiresAtEpochMs = pinExpiresAtEpochMs

            val currentSession = session
            if (currentSession == null) {
                println("REMOTE_SIGNALING: Cannot update pin - session is null")
                return
            }

            try {
                registerHost(currentSession)
                println("REMOTE_SIGNALING: Updated host pin")
                _state.value = ConnectionState.Connected
            } catch (e: Exception) {
                val normalized = normalizeWebSocketError(e)
                println("REMOTE_SIGNALING: Failed to update host pin: $normalized")
                _state.value = ConnectionState.Error(normalized)
                session = null
            }
        }
    }

    suspend fun updateHostName(name: String) {
        mutex.withLock {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) return
            hostName = normalizedName

            val currentSession = session
            if (currentSession == null) {
                println("REMOTE_SIGNALING: Stored host name update for next reconnect")
                return
            }

            try {
                registerHost(currentSession)
                println("REMOTE_SIGNALING: Updated host name to '$hostName'")
                _state.value = ConnectionState.Connected
            } catch (e: Exception) {
                val normalized = normalizeWebSocketError(e)
                println("REMOTE_SIGNALING: Failed to update host name: $normalized")
                _state.value = ConnectionState.Error(normalized)
                session = null
            }
        }
    }

    private suspend fun registerHost(currentSession: DefaultClientWebSocketSession) {
        val platform = getPlatformName()
        val payload = encodeSignalingMessage(
            SignalingMessage.RegisterHost(
                hostId = hostId,
                hostName = hostName,
                platform = platform,
                pin = currentPin,
                pinExpiresAtEpochMs = currentPinExpiresAtEpochMs,
            )
        )
        currentSession.outgoing.send(Frame.Text(payload))
    }

    suspend fun send(clientId: String, message: SignalingMessage) {
        val wsSession = session
        if (wsSession == null) {
            println("REMOTE_SIGNALING: Cannot send message - session is null")
            return
        }

        try {
            val relay = SignalingMessage.Relay(
                from = hostId,
                to = clientId,
                payload = message
            )
            val payload = encodeSignalingMessage(relay)
            wsSession.outgoing.send(Frame.Text(payload))
            println("REMOTE_SIGNALING: Sent message to client $clientId")
        } catch (e: Exception) {
            val normalized = normalizeWebSocketError(e)
            println("REMOTE_SIGNALING: Error sending message: $normalized")
            _state.value = ConnectionState.Error(normalized)
            session = null
        }
    }

    private suspend fun send(message: SignalingMessage) {
        val wsSession = session
        if (wsSession == null) {
            println("REMOTE_SIGNALING: Cannot send message - session is null")
            return
        }

        try {
            val payload = encodeSignalingMessage(message)
            wsSession.outgoing.send(Frame.Text(payload))
        } catch (e: Exception) {
            val normalized = normalizeWebSocketError(e)
            println("REMOTE_SIGNALING: Error sending message: $normalized")
            _state.value = ConnectionState.Error(normalized)
            session = null
        }
    }

    private fun normalizeWebSocketError(error: Throwable): String {
        val raw = error.message?.trim().orEmpty()
        val localhostHint = if (
            remoteServerUrl.contains("localhost", ignoreCase = true) ||
            remoteServerUrl.contains("127.0.0.1")
        ) {
            " On mobile devices, 'localhost' points to that device itself. Use ws://<signaling-server-lan-ip>:8090/ws or a public wss URL."
        } else {
            ""
        }

        if (raw.contains("localhost/127.0.0.1")) {
            return "Cannot reach signaling server at $remoteServerUrl.$localhostHint"
        }

        val looksLikeBrowserEvent = raw.contains("\"isTrusted\":true") ||
            raw.contains("\"type\":\"error\"") ||
            raw.contains("\"type\": \"error\"") ||
            raw.contains("Event", ignoreCase = true) ||
            raw.isBlank()
        if (!looksLikeBrowserEvent) {
            return "Connection lost: $raw"
        }

        return "Cannot reach signaling server at $remoteServerUrl.$localhostHint " +
            "Ensure signaling server is running and the URL is correct."
    }

    private fun handleMessage(message: SignalingMessage) {
        when (message) {
            is SignalingMessage.Hello -> {
                println("REMOTE_SIGNALING: Received hello from server: ${message.clientName}")
            }

            is SignalingMessage.Relay -> {
                // Message from a client
                handleRelayedMessage(message)
            }

            is SignalingMessage.Error -> {
                println("REMOTE_SIGNALING: Server error: ${message.message}")
                _state.value = ConnectionState.Error(message.message)
            }

            else -> {
                println("REMOTE_SIGNALING: Unhandled message: ${message::class.simpleName}")
            }
        }
    }

    private fun handleRelayedMessage(relay: SignalingMessage.Relay) {
        val clientId = relay.from
        val payload = relay.payload

        when (payload) {
            is SignalingMessage.Hello -> {
                val alreadyConnected = connectedClientsMap.containsKey(clientId)
                val clientInfo = ClientInfo(
                    clientId = clientId,
                    name = payload.clientName,
                    platform = payload.platform,
                )
                connectedClientsMap[clientId] = clientInfo
                _connectedClients.value = connectedClientsMap.values.toList()
                if (alreadyConnected) {
                    println("REMOTE_SIGNALING: Ignoring duplicate Hello from already-connected client: $clientId")
                } else {
                    onClientConnected(clientInfo)
                    println("REMOTE_SIGNALING: Client connected: $clientId (${payload.clientName})")
                }
            }

            is SignalingMessage.ClientDisconnected -> {
                connectedClientsMap.remove(clientId)
                _connectedClients.value = connectedClientsMap.values.toList()
                onClientDisconnected(clientId)
                println("REMOTE_SIGNALING: Client disconnected: $clientId")
            }

            else -> {
                // Relay other signaling messages to the application
                onMessage(clientId, payload)
            }
        }
    }

    sealed class ConnectionState {
        data object Idle : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
}
