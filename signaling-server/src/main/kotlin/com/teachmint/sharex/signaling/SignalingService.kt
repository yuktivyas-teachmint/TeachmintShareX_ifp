package com.teachmint.sharex.signaling

import io.ktor.websocket.*
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SignalingService {
    private companion object {
        const val PIN_COLLISION_SIGNAL_PREFIX = "__sharex_pin_collision__"
        const val MAX_ID_LENGTH = 128
        const val MAX_NAME_LENGTH = 64
        const val MAX_PIN_FAILURES_PER_SESSION = 10
        const val PIN_FAILURE_LOCKOUT_MS = 60_000L
        // F-001: Capacity limits to prevent unbounded state growth
        const val MAX_TOTAL_HOSTS = 10_000
        const val MAX_TOTAL_CLIENTS = 50_000
        const val MAX_CONNECTIONS_PER_IP = 20
        // F-005: Message rate limiting (token bucket)
        const val MAX_MESSAGES_PER_SECOND = 30
        const val RATE_LIMIT_BURST = 60
        // V-010: Idle session timeout (10 minutes)
        const val SESSION_IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        const val SESSION_MAX_DURATION_MS = 24 * 60 * 60 * 1000L
        // V-005: Global PIN attempt limit per target host
        const val MAX_PIN_ATTEMPTS_PER_HOST = 30
        const val PIN_HOST_LOCKOUT_MS = 5 * 60 * 1000L
        private val secureRandom = SecureRandom()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    // Map of hostId -> HostConnection
    private val hosts = ConcurrentHashMap<String, HostConnection>()

    // Map of clientId -> ClientConnection
    private val clients = ConcurrentHashMap<String, ClientConnection>()

    // Map of sessionId (connection ID) -> ConnectionInfo
    private val sessions = ConcurrentHashMap<String, ConnectionInfo>()

    // Map of PIN -> hostId
    private val pinToHostId = ConcurrentHashMap<String, String>()
    private val pinRegex = Regex("^\\d{6}$")

    // Rate limiting: clientIp -> PinAttemptTracker (keyed by IP to prevent reconnection bypass)
    private val pinAttemptTrackers = ConcurrentHashMap<String, PinAttemptTracker>()

    // F-001: Per-IP connection tracking
    private val connectionsPerIp = ConcurrentHashMap<String, AtomicInteger>()

    // F-005: Per-session message rate limiters
    private val sessionRateLimiters = ConcurrentHashMap<String, TokenBucketRateLimiter>()

    // V-005: Per-host global PIN attempt tracking
    private val pinAttemptsPerHost = ConcurrentHashMap<String, PinAttemptTracker>()

    // V-010: Session timestamps for idle/max-duration timeout
    private val sessionStartTimes = ConcurrentHashMap<String, Long>()
    private val sessionLastActivity = ConcurrentHashMap<String, Long>()

    suspend fun handleConnection(session: DefaultWebSocketSession, clientIp: String) {
        // F-001: Enforce per-IP connection limit
        val ipCount = connectionsPerIp.computeIfAbsent(clientIp) { AtomicInteger(0) }
        if (ipCount.incrementAndGet() > MAX_CONNECTIONS_PER_IP) {
            ipCount.decrementAndGet()
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections from this IP"))
            return
        }

        val sessionId = generateSessionId()
        val now = System.currentTimeMillis()
        sessionStartTimes[sessionId] = now
        sessionLastActivity[sessionId] = now
        sessionRateLimiters[sessionId] = TokenBucketRateLimiter(MAX_MESSAGES_PER_SECOND, RATE_LIMIT_BURST)
        println("New WebSocket connection: $sessionId from $clientIp")

        try {
            while (true) {
                // V-010: Enforce idle timeout even for silent clients by using withTimeoutOrNull
                val frame = withTimeoutOrNull(SESSION_IDLE_TIMEOUT_MS) {
                    session.incoming.receiveCatching().getOrNull()
                }

                if (frame == null) {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "Session idle timeout"))
                    break
                }

                // V-010: Check max-duration timeout
                val currentTime = System.currentTimeMillis()
                val startTime = sessionStartTimes[sessionId] ?: currentTime
                if (currentTime - startTime > SESSION_MAX_DURATION_MS) {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "Session max duration exceeded"))
                    break
                }
                sessionLastActivity[sessionId] = currentTime

                if (frame is Frame.Text) {
                    // F-005: Per-session message rate limiting
                    val rateLimiter = sessionRateLimiters[sessionId]
                    if (rateLimiter != null && !rateLimiter.tryConsume()) {
                        // Drop the frame without sending a response to prevent amplification
                        continue
                    }

                    val text = frame.readText()
                    try {
                        val message = decodeSignaling(text)
                        handleMessage(sessionId, session, message, clientIp)
                    } catch (e: Exception) {
                        println("Error parsing message from $sessionId")
                        session.send(
                            Frame.Text(
                                encodeSignaling(
                                    SignalingMessage.Error("Invalid message format")
                                )
                            )
                        )
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            println("WebSocket connection closed: $sessionId")
        } catch (e: Exception) {
            println("WebSocket error: ${e.message}")
            e.printStackTrace()
        } finally {
            ipCount.decrementAndGet()
            sessionRateLimiters.remove(sessionId)
            sessionStartTimes.remove(sessionId)
            sessionLastActivity.remove(sessionId)
            handleDisconnection(sessionId)
        }
    }

    private suspend fun handleMessage(
        sessionId: String,
        session: DefaultWebSocketSession,
        message: SignalingMessage,
        clientIp: String,
    ) {
        cleanupExpiredPins()
        println("Received message: ${message::class.simpleName} from session: $sessionId")

        when (message) {
            is SignalingMessage.RegisterHost -> {
                registerHost(sessionId, session, message)
            }

            is SignalingMessage.UnregisterHost -> {
                // M-6: Verify sender is the actual host before allowing unregister
                val senderInfo = sessions[sessionId]
                if (senderInfo is ConnectionInfo.Host && senderInfo.hostId == message.hostId) {
                    unregisterHost(message.hostId)
                } else {
                    session.send(
                        Frame.Text(encodeSignaling(SignalingMessage.Error("Unauthorized")))
                    )
                }
            }

            is SignalingMessage.ListHosts -> {
                sendHostsList(session)
            }

            is SignalingMessage.JoinHost -> {
                if (!validateId(message.hostId) || !validateId(message.clientId) || !validateName(message.clientName)) {
                    session.send(Frame.Text(encodeSignaling(SignalingMessage.Error("Invalid join data"))))
                } else {
                    registerClient(
                        sessionId = sessionId,
                        session = session,
                        hostId = message.hostId,
                        clientId = message.clientId,
                        clientName = sanitizeName(message.clientName),
                    )
                }
            }

            is SignalingMessage.JoinHostByPin -> {
                registerClientByPin(sessionId, session, message, clientIp)
            }

            is SignalingMessage.Relay -> {
                relayMessage(sessionId, message)
            }

            // WebRTC signaling messages - relay to appropriate peer
            is SignalingMessage.Offer,
            is SignalingMessage.Answer,
            is SignalingMessage.Ice,
            is SignalingMessage.StartShare,
            is SignalingMessage.StopShare,
            is SignalingMessage.ConnectionRejected,
            is SignalingMessage.DisplayRotationChanged,
            is SignalingMessage.RequestReverseShare,
            is SignalingMessage.ReverseShareApproved,
            is SignalingMessage.CancelReverseShare,
            is SignalingMessage.RequestRemoteControl,
            is SignalingMessage.RemoteControlApproved,
            is SignalingMessage.RemoteControlDenied,
            is SignalingMessage.StopRemoteControl,
            is SignalingMessage.DiagnosticLog -> {
                relaySignalingMessage(sessionId, message)
            }

            else -> {
                println("Unhandled message type: ${message::class.simpleName}")
            }
        }
    }

    private fun validateId(id: String?): Boolean =
        !id.isNullOrBlank() && id.length <= MAX_ID_LENGTH

    private fun validateName(name: String?): Boolean =
        !name.isNullOrBlank() && name.length <= MAX_NAME_LENGTH

    private fun sanitizeName(name: String): String =
        name.take(MAX_NAME_LENGTH).trim()

    private suspend fun registerHost(
        sessionId: String,
        session: DefaultWebSocketSession,
        message: SignalingMessage.RegisterHost
    ) {
        // H-7: Validate input fields
        if (!validateId(message.hostId) || !validateName(message.hostName) || !validateName(message.platform)) {
            session.send(Frame.Text(encodeSignaling(SignalingMessage.Error("Invalid registration data"))))
            return
        }

        // F-001: Enforce capacity limit
        val existingHost = hosts[message.hostId]
        if (existingHost == null && hosts.size >= MAX_TOTAL_HOSTS) {
            session.send(Frame.Text(encodeSignaling(SignalingMessage.Error("Server at capacity"))))
            return
        }

        // M-5: If hostId is owned by a stale session, replace it instead of rejecting.
        // This handles the common case where the host app reconnects after a network
        // interruption but the old WebSocket hasn't timed out on the server yet.
        if (existingHost != null && existingHost.sessionId != sessionId) {
            val staleSessionId = existingHost.sessionId
            // Remove stale session entry first so its handleDisconnection won't
            // clean up the host entry we're about to create.
            sessions.remove(staleSessionId)
            runCatching {
                existingHost.session.close(
                    CloseReason(CloseReason.Codes.GOING_AWAY, "Replaced by new connection")
                )
            }
            println("Host reconnected: replacing stale session for ${message.hostId}")
        }

        // Clean up orphan: if this session previously registered a different hostId, remove the old one
        val previousInfo = sessions[sessionId]
        if (previousInfo is ConnectionInfo.Host && previousInfo.hostId != message.hostId) {
            clearHostPin(previousInfo.hostId)
            hosts.remove(previousInfo.hostId)
        }

        val hostConnection = HostConnection(
            hostId = message.hostId,
            sessionId = sessionId,
            hostName = sanitizeName(message.hostName),
            platform = sanitizeName(message.platform),
            session = session,
            connectedClients = existingHost?.connectedClients ?: ConcurrentHashMap.newKeySet(),
        )
        hostConnection.hostName = sanitizeName(message.hostName)
        hostConnection.platform = sanitizeName(message.platform)
        hostConnection.session = session
        hosts[message.hostId] = hostConnection
        sessions[sessionId] = ConnectionInfo.Host(message.hostId)

        updateHostPin(
            hostConnection = hostConnection,
            pin = message.pin,
            pinExpiresAtEpochMs = message.pinExpiresAtEpochMs,
        )

        println("Host registered: ${message.hostId} (${message.hostName}) - platform: ${message.platform}")

        // Send confirmation
        session.send(
            Frame.Text(
                encodeSignaling(
                    SignalingMessage.Hello(
                        clientId = message.hostId,
                        clientName = "Remote Server"
                    )
                )
            )
        )

        // Broadcast updated hosts list to all clients
        broadcastHostsList()
    }

    private suspend fun unregisterHost(hostId: String) {
        val host = hosts[hostId]
        clearHostPin(hostId)
        hosts.remove(hostId)
        println("Host unregistered: $hostId")

        notifyClientsHostGone(host)
        broadcastHostsList()
    }

    private suspend fun registerClientByPin(
        sessionId: String,
        session: DefaultWebSocketSession,
        message: SignalingMessage.JoinHostByPin,
        clientIp: String,
    ) {
        // H-7: Validate input fields
        if (!validateId(message.clientId) || !validateName(message.clientName)) {
            session.send(Frame.Text(encodeSignaling(SignalingMessage.Error("Invalid client data"))))
            return
        }

        // H-1: Rate limiting on PIN attempts (keyed by client IP to prevent reconnection bypass)
        val tracker = pinAttemptTrackers.computeIfAbsent(clientIp) { PinAttemptTracker(MAX_PIN_FAILURES_PER_SESSION, PIN_FAILURE_LOCKOUT_MS) }
        if (tracker.isLockedOut()) {
            session.send(
                Frame.Text(encodeSignaling(SignalingMessage.Error("Too many PIN attempts. Please wait and try again.")))
            )
            return
        }

        if (!pinRegex.matches(message.pin)) {
            tracker.recordFailure()
            session.send(
                Frame.Text(
                    encodeSignaling(
                        SignalingMessage.Error(
                            "Invalid PIN format."
                        )
                    )
                )
            )
            return
        }

        // V-005: Global rate limiting per target host (prevents distributed brute-force)
        val targetHostId = pinToHostId[message.pin]
        if (targetHostId != null) {
            val hostTracker = pinAttemptsPerHost.computeIfAbsent(targetHostId) {
                PinAttemptTracker(MAX_PIN_ATTEMPTS_PER_HOST, PIN_HOST_LOCKOUT_MS)
            }
            if (hostTracker.isLockedOut()) {
                session.send(
                    Frame.Text(encodeSignaling(SignalingMessage.Error("Too many PIN attempts for this host. Please ask host to rotate PIN.")))
                )
                return
            }
        }

        val host = resolveHostByPin(message.pin)
        if (host == null) {
            tracker.recordFailure()
            // V-005: Also track per-host failures
            if (targetHostId != null) {
                pinAttemptsPerHost[targetHostId]?.recordFailure()
            }
            session.send(
                Frame.Text(
                    encodeSignaling(
                        SignalingMessage.Error("PIN is invalid or expired. Please ask host for a new PIN.")
                    )
                )
            )
            return
        }

        // Successful PIN match — reset tracker for this IP
        pinAttemptTrackers.remove(clientIp)

        registerClient(
            sessionId = sessionId,
            session = session,
            hostId = host.hostId,
            clientId = message.clientId,
            clientName = sanitizeName(message.clientName),
        )
    }

    private suspend fun registerClient(
        sessionId: String,
        session: DefaultWebSocketSession,
        hostId: String,
        clientId: String,
        clientName: String,
    ) {
        // F-001: Enforce client capacity limit
        if (!clients.containsKey(clientId) && clients.size >= MAX_TOTAL_CLIENTS) {
            session.send(Frame.Text(encodeSignaling(SignalingMessage.Error("Server at capacity"))))
            return
        }

        val host = hosts[hostId]
        if (host == null) {
            session.send(
                Frame.Text(
                    encodeSignaling(
                        SignalingMessage.Error("Host not found: $hostId")
                    )
                )
            )
            return
        }

        val clientConnection = ClientConnection(
            clientId = clientId,
            clientName = clientName,
            connectedHostId = hostId,
            session = session,
        )

        clients[clientId] = clientConnection
        sessions[sessionId] = ConnectionInfo.Client(clientId, hostId)
        host.connectedClients.add(clientId)

        println("Client $clientId ($clientName) joined host $hostId")

        // Notify host about new client
        val relayToHost = SignalingMessage.Relay(
            from = clientId,
            to = hostId,
            payload = SignalingMessage.Hello(
                clientId = clientId,
                clientName = clientName
            )
        )
        host.session.send(Frame.Text(encodeSignaling(relayToHost)))

        // Send confirmation to client
        session.send(
            Frame.Text(
                encodeSignaling(
                    SignalingMessage.Hello(
                        clientId = hostId,
                        clientName = host.hostName
                    )
                )
            )
        )
    }

    private suspend fun relayMessage(sessionId: String, message: SignalingMessage.Relay) {
        when (val source = sessions[sessionId]) {
            is ConnectionInfo.Client -> {
                val host = hosts[source.hostId]
                if (host == null) {
                    println("Relay target host not found: ${source.hostId}")
                    return
                }
                val relay = SignalingMessage.Relay(
                    from = source.clientId,
                    to = source.hostId,
                    payload = message.payload,
                )
                host.session.send(Frame.Text(encodeSignaling(relay)))
                println("Relayed client message from ${source.clientId} to host ${source.hostId}")
            }

            is ConnectionInfo.Host -> {
                val targetClient = clients[message.to]
                if (targetClient == null) {
                    println("Relay target client not found: ${message.to}")
                    return
                }
                val relay = SignalingMessage.Relay(
                    from = source.hostId,
                    to = targetClient.clientId,
                    payload = message.payload,
                )
                targetClient.session.send(Frame.Text(encodeSignaling(relay)))
                println("Relayed host message from ${source.hostId} to client ${targetClient.clientId}")
            }

            null -> {
                println("Relay sender session not registered: $sessionId")
            }
        }
    }

    private suspend fun relaySignalingMessage(sessionId: String, message: SignalingMessage) {
        val connectionInfo = sessions[sessionId] ?: return

        when (connectionInfo) {
            is ConnectionInfo.Host -> {
                // Host is sending to a client
                val hostId = connectionInfo.hostId
                val host = hosts[hostId] ?: return

                // Send to all connected clients (or implement per-client routing)
                host.connectedClients.forEach { clientId ->
                    val client = clients[clientId]
                    client?.session?.send(
                        Frame.Text(
                            encodeSignaling(
                                SignalingMessage.Relay(
                                    from = hostId,
                                    to = clientId,
                                    payload = message
                                )
                            )
                        )
                    )
                }
            }

            is ConnectionInfo.Client -> {
                // Client is sending to host
                val clientId = connectionInfo.clientId
                val hostId = connectionInfo.hostId
                val host = hosts[hostId]

                host?.session?.send(
                    Frame.Text(
                        encodeSignaling(
                            SignalingMessage.Relay(
                                from = clientId,
                                to = hostId,
                                payload = message
                            )
                        )
                    )
                )
            }
        }
    }

    private suspend fun sendHostsList(session: DefaultWebSocketSession) {
        cleanupExpiredPins()
        val hostsList = hosts.values.map { host ->
            RemoteHostInfo(
                hostId = host.hostId,
                hostName = host.hostName,
                platform = host.platform,
                connectedClients = host.connectedClients.size
            )
        }

        session.send(
            Frame.Text(
                encodeSignaling(
                    SignalingMessage.HostsList(hostsList)
                )
            )
        )
    }

    private suspend fun broadcastHostsList() {
        cleanupExpiredPins()
        val hostsList = hosts.values.map { host ->
            RemoteHostInfo(
                hostId = host.hostId,
                hostName = host.hostName,
                platform = host.platform,
                connectedClients = host.connectedClients.size
            )
        }

        val message = SignalingMessage.HostsList(hostsList)
        val payload = encodeSignaling(message)

        // Send to all connected clients who are not yet joined to a host
        clients.values.forEach { client ->
            try {
                client.session.send(Frame.Text(payload))
            } catch (e: Exception) {
                println("Failed to send hosts list to client ${client.clientId}: ${e.message}")
            }
        }
    }

    private fun encodeSignaling(message: SignalingMessage): String {
        return json.encodeToString<SignalingMessage>(message)
    }

    private fun decodeSignaling(payload: String): SignalingMessage {
        return runCatching {
            json.decodeFromString<SignalingMessage>(payload)
        }.getOrElse { originalError ->
            val legacy = runCatching {
                val element = json.decodeFromString<JsonElement>(payload)
                decodeLegacySignalingElement(element)
            }.getOrNull()
            legacy ?: throw originalError
        }
    }

    private fun decodeLegacySignalingElement(element: JsonElement): SignalingMessage? {
        val typed = runCatching {
            json.decodeFromString<SignalingMessage>(element.toString())
        }.getOrNull()
        if (typed != null) {
            return typed
        }

        val obj = element as? JsonObject ?: return null

        val from = obj.string("from")
        val to = obj.string("to")
        val nestedPayload = obj["payload"]
        if (from != null && to != null && nestedPayload != null) {
            val nested = decodeLegacySignalingElement(nestedPayload) ?: return null
            return SignalingMessage.Relay(from = from, to = to, payload = nested)
        }

        val hostsElement = obj["hosts"]
        if (hostsElement != null) {
            val hosts = runCatching {
                json.decodeFromString<List<RemoteHostInfo>>(hostsElement.toString())
            }.getOrNull()
            if (hosts != null) {
                return SignalingMessage.HostsList(hosts = hosts)
            }
        }

        obj.string("message")?.let { message ->
            return SignalingMessage.Error(message = message)
        }

        val hostId = obj.string("hostId")
        val hostName = obj.string("hostName")
        val platform = obj.string("platform")
        if (hostId != null && hostName != null && platform != null) {
            return SignalingMessage.RegisterHost(
                hostId = hostId,
                hostName = hostName,
                platform = platform,
                pin = obj.string("pin"),
                pinExpiresAtEpochMs = obj.long("pinExpiresAtEpochMs"),
            )
        }

        val clientId = obj.string("clientId")
        val clientName = obj.string("clientName")
        if (hostId != null && clientId != null && clientName != null) {
            return SignalingMessage.JoinHost(
                hostId = hostId,
                clientId = clientId,
                clientName = clientName,
            )
        }

        val pin = obj.string("pin")
        if (pin != null && clientId != null && clientName != null) {
            return SignalingMessage.JoinHostByPin(
                pin = pin,
                clientId = clientId,
                clientName = clientName,
            )
        }

        if (clientId != null && clientName != null) {
            return SignalingMessage.Hello(
                clientId = clientId,
                clientName = clientName,
            )
        }

        return null
    }

    private fun JsonObject.string(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.long(name: String): Long? {
        return this[name]?.jsonPrimitive?.longOrNull
    }

    private suspend fun handleDisconnection(sessionId: String) {
        // IP-based trackers persist across connections — do not remove here
        val connectionInfo = sessions.remove(sessionId) ?: return

        when (connectionInfo) {
            is ConnectionInfo.Host -> {
                val hostId = connectionInfo.hostId
                val host = hosts[hostId]
                // Only clean up if this session still owns the host entry.
                // A replaced stale session must not remove the new host connection.
                if (host == null || host.sessionId == sessionId) {
                    clearHostPin(hostId)
                    hosts.remove(hostId)
                    println("Host disconnected: $hostId")

                    notifyClientsHostGone(host)
                    broadcastHostsList()
                }
            }

            is ConnectionInfo.Client -> {
                val clientId = connectionInfo.clientId
                val hostId = connectionInfo.hostId
                clients.remove(clientId)
                val host = hosts[hostId]
                host?.connectedClients?.remove(clientId)
                println("Client disconnected: $clientId from host $hostId")
                host?.let {
                    runCatching {
                        it.session.send(
                            Frame.Text(
                                encodeSignaling(
                                    SignalingMessage.Relay(
                                        from = clientId,
                                        to = hostId,
                                        payload = SignalingMessage.ClientDisconnected,
                                    )
                                )
                            )
                        )
                    }.onFailure { error ->
                        println("Failed to notify host $hostId about client $clientId disconnect: ${error.message}")
                    }
                }
            }
        }
    }

    private fun resolveHostByPin(pin: String): HostConnection? {
        val hostId = pinToHostId[pin] ?: return null
        val host = hosts[hostId] ?: run {
            pinToHostId.remove(pin)
            return null
        }
        if (host.connectionPin != pin) {
            pinToHostId.remove(pin)
            return null
        }
        val expiresAt = host.pinExpiresAtEpochMs ?: run {
            clearHostPin(host.hostId)
            return null
        }
        if (System.currentTimeMillis() >= expiresAt) {
            clearHostPin(host.hostId)
            return null
        }
        return host
    }

    private suspend fun updateHostPin(
        hostConnection: HostConnection,
        pin: String?,
        pinExpiresAtEpochMs: Long?,
    ) {
        if (pin.isNullOrBlank() || pinExpiresAtEpochMs == null) {
            clearHostPin(hostConnection.hostId)
            return
        }
        if (!pinRegex.matches(pin)) {
            println("Ignoring invalid host pin format for host=${hostConnection.hostId}")
            clearHostPin(hostConnection.hostId)
            return
        }
        if (pinExpiresAtEpochMs <= System.currentTimeMillis()) {
            println("Ignoring expired pin for host=${hostConnection.hostId}")
            clearHostPin(hostConnection.hostId)
            return
        }

        val existingHostId = pinToHostId[pin]
        if (existingHostId != null && existingHostId != hostConnection.hostId) {
            println("PIN collision detected for host=${hostConnection.hostId}; keeping previous owner=$existingHostId")
            runCatching {
                hostConnection.session.send(
                    Frame.Text(
                        encodeSignaling(
                            SignalingMessage.Error(
                                message = "$PIN_COLLISION_SIGNAL_PREFIX:$pin"
                            )
                        )
                    )
                )
            }.onFailure { error ->
                println("Failed to notify host ${hostConnection.hostId} about pin collision: ${error.message}")
            }
            clearHostPin(hostConnection.hostId)
            return
        }

        clearHostPin(hostConnection.hostId)
        pinToHostId[pin] = hostConnection.hostId
        hostConnection.connectionPin = pin
        hostConnection.pinExpiresAtEpochMs = pinExpiresAtEpochMs
    }

    private fun clearHostPin(hostId: String) {
        val host = hosts[hostId] ?: return
        val pin = host.connectionPin
        if (!pin.isNullOrBlank()) {
            pinToHostId.remove(pin)
        }
        host.connectionPin = null
        host.pinExpiresAtEpochMs = null
    }

    private fun cleanupExpiredPins() {
        val now = System.currentTimeMillis()
        hosts.values.forEach { host ->
            val pin = host.connectionPin ?: return@forEach
            val expiresAt = host.pinExpiresAtEpochMs ?: return@forEach
            if (now >= expiresAt) {
                pinToHostId.remove(pin)
                host.connectionPin = null
                host.pinExpiresAtEpochMs = null
            }
        }

        // F-016: Prune expired PIN attempt trackers using the isExpired() method
        pinAttemptTrackers.entries.removeIf { (_, tracker) -> tracker.isExpired() }
        pinAttemptsPerHost.entries.removeIf { (_, tracker) -> tracker.isExpired() }
    }

    private suspend fun notifyClientsHostGone(host: HostConnection?) {
        if (host == null) return
        val errorPayload = encodeSignaling(SignalingMessage.Error("Host disconnected"))
        host.connectedClients.forEach { clientId ->
            try {
                clients[clientId]?.session?.send(Frame.Text(errorPayload))
            } catch (e: Exception) {
                println("Failed to notify client $clientId of host disconnection: ${e.message}")
            }
        }
    }

    private fun generateSessionId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        // Format as UUID-style hex string
        return buildString(36) {
            for (i in bytes.indices) {
                if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                append(String.format("%02x", bytes[i]))
            }
        }
    }
}

data class HostConnection(
    val hostId: String,
    val sessionId: String,
    var hostName: String,
    var platform: String,
    var session: DefaultWebSocketSession,
    val connectedClients: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    var connectionPin: String? = null,
    var pinExpiresAtEpochMs: Long? = null,
)

data class ClientConnection(
    val clientId: String,
    val clientName: String,
    val connectedHostId: String,
    val session: DefaultWebSocketSession
)

sealed class ConnectionInfo {
    data class Host(val hostId: String) : ConnectionInfo()
    data class Client(val clientId: String, val hostId: String) : ConnectionInfo()
}

class PinAttemptTracker(
    private val maxFailures: Int,
    private val lockoutMs: Long,
) {
    private val failureCount = AtomicInteger(0)
    private val lockoutUntil = AtomicLong(0L)
    private val lastActivityTime = AtomicLong(System.currentTimeMillis())

    fun recordFailure() {
        lastActivityTime.set(System.currentTimeMillis())
        val count = failureCount.incrementAndGet()
        if (count >= maxFailures) {
            lockoutUntil.set(System.currentTimeMillis() + lockoutMs)
        }
    }

    fun isLockedOut(): Boolean {
        val deadline = lockoutUntil.get()
        if (deadline == 0L) return false
        if (System.currentTimeMillis() >= deadline) {
            // Reset after lockout period
            failureCount.set(0)
            lockoutUntil.set(0)
            return false
        }
        return true
    }

    /** True when this tracker has been idle long enough to discard. */
    fun isExpired(): Boolean {
        val now = System.currentTimeMillis()
        val deadline = lockoutUntil.get()
        val idle = now - lastActivityTime.get()
        // Prune if lockout expired and idle for 5x the lockout duration
        if (deadline > 0L && now >= deadline && idle > lockoutMs * 5) return true
        // Prune if never locked out, no failures, and idle for 5x lockout duration
        if (deadline == 0L && failureCount.get() == 0 && idle > lockoutMs * 5) return true
        return false
    }
}

/** F-005: Token bucket rate limiter for per-session message throttling. */
class TokenBucketRateLimiter(
    private val tokensPerSecond: Int,
    private val maxBurst: Int,
) {
    private var tokens: Double = maxBurst.toDouble()
    private var lastRefillTime: Long = System.currentTimeMillis()

    @Synchronized
    fun tryConsume(): Boolean {
        refill()
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastRefillTime) / 1000.0
        tokens = (tokens + elapsed * tokensPerSecond).coerceAtMost(maxBurst.toDouble())
        lastRefillTime = now
    }
}
