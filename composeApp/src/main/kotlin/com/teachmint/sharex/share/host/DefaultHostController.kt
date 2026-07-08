package com.teachmint.sharex.share.host

import com.teachmint.sharex.share.shared.*
import com.teachmint.sharex.utils.sharedpreference.SharedPreferenceConstants
import com.teachmint.sharex.utils.sharedpreference.SharedPreferenceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

// V-004: Increased from 4 to 6 digits (10K -> 1M combinations) to resist brute-force
private const val HOST_PIN_LENGTH: Int = 6
private const val HOST_PIN_RANGE_UPPER_BOUND: Int = 1_000_000
private const val HOST_PIN_TTL_MS: Long = 3 * 60 * 1000L
private const val HOST_PIN_ROTATION_CHECK_INTERVAL_MS: Long = 1_000L
private const val HOST_BLOCKED_TOAST_DURATION_MS: Long = 4_000L
private const val HOST_NAME_SUFFIX_LENGTH: Int = 4
private const val HOST_NAME_SUFFIX_RANGE_UPPER_BOUND: Int = 10_000
private const val HOST_SCREEN_CAPTURE_SERVICE_RETRY_LIMIT: Int = 1
private const val HOST_SCREEN_CAPTURE_SERVICE_RETRY_DELAY_MS: Long = 500L
private const val HOST_REMOTE_CONTROL_CONSENT_DENIED_TOAST_MESSAGE = "Can't enable remote control on host side"
private const val REMOTE_RECONNECT_BASE_DELAY_MS: Long = 2_000L
private const val REMOTE_RECONNECT_MAX_DELAY_MS: Long = 30_000L
private const val REMOTE_CONTROL_DATA_CHANNEL_LABEL: String = "remote-input"
private val HOST_NAME_WITH_SUFFIX_REGEX = Regex(""".*\s\d{4,6}$""")

class DefaultHostController(
    private val scope: CoroutineScope,
    private val discoveryService: DiscoveryService,
    private val webRtcEngine: WebRtcEngine,
    private val iceServers: List<IceServerConfig>,
    remoteServerUrl: String? = null, // Optional remote server URL for web clients
    private val miracastStart: (suspend (deviceName: String, controlPort: Int) -> Unit)? = null, // Optional Miracast/mDNS advertisement
    private val miracastStop: (suspend () -> Unit)? = null, // Optional Miracast cleanup
    private val onLocalClientTransportActivityChanged: ((Boolean) -> Unit)? = null,
) : HostController {
    private val hostId = generateClientId()
    private var hostName = readOrInitializeHostName()
    private var configuredRemoteServerUrl: String? = RemoteServerConfig.normalizeRemoteServerUrl(remoteServerUrl)
    private val initialHostConnectionSettings = readOrInitializeHostConnectionSettings().also {
        configuredRemoteServerUrl = it.remoteSignalingUrl
    }
    private var currentConnectionPin: String = generateConnectionPin()
    private var currentPinExpiresAtEpochMs: Long = nextPinExpiryEpochMs()
    private val _state = MutableStateFlow(
        HostUiState(
            deviceName = hostName,
            hostConnectionSettings = initialHostConnectionSettings,
            connectionPin = currentConnectionPin,
            pinExpiresAtEpochMs = currentPinExpiresAtEpochMs,
        )
    )
    override val state: StateFlow<HostUiState> = _state

    private val sessions = mutableMapOf<String, HostPeerSession>()
    private val earlyIceByClient = mutableMapOf<String, MutableList<IceCandidateData>>()
    private val pendingOffersByClient = mutableMapOf<String, PendingOffer>()
    private val pendingReverseRequests = mutableSetOf<String>()
    private val pendingReverseOffersByClient = mutableMapOf<String, PendingOffer>()
    private val blockedReverseOffersByClient = mutableSetOf<String>()
    private val pendingConnectionRequestsByClient = mutableMapOf<String, PendingConnectionRequest>()
    private val knownClientsById = mutableMapOf<String, ClientInfo>()
    private val approvedClientIds = mutableSetOf<String>()
    private val clientCastingPolicies = mutableMapOf<String, ClientCastingPolicy>()
    private val activeReverseClients = mutableSetOf<String>()
    private val handleOfferJobs = mutableMapOf<String, Job>()
    /** Buffers for in-progress chunked file uploads keyed by uploadId. */
    private val chunkedUploadBuffers = mutableMapOf<String, ChunkedUploadBuffer>()
    private val hostCaptureMutex = Mutex()
    private var hostScreenTrack: PlatformVideoTrack? = null
    // Audio track captured alongside the host screen. Shared across all reverse-sharing sessions
    // so every attached client hears the same audio stream in sync with the video.
    private var hostScreenAudioTrack: PlatformAudioTrack? = null
    private var lastLocalClientTransportActivity: Boolean? = null

    // Callback handlers for both local and remote clients
    private val onClientConnected: (ClientInfo) -> Unit = { client ->
        if (approvedClientIds.contains(client.clientId) && sessions.containsKey(client.clientId)) {
            println("HOST_CONTROLLER: Ignoring duplicate connect for already-active client: ${client.clientId}")
        } else {
            println("HOST_CONTROLLER: 🔌 Client connected: ${client.clientId} (${client.name})")
            knownClientsById[client.clientId] = client
            notifyLocalClientTransportActivityChanged()
            queueConnectionRequest(
                client = client,
                isRemoteClient = isRemoteClient(client.clientId),
            )
        }
    }

    private val onClientDisconnected: (String) -> Unit = { clientId ->
        println("HOST_CONTROLLER: 🔌 Client disconnected: $clientId")
        cleanupRemoteControl(clientId)
        cleanupBYOM(clientId)
        val wasRemoteClient = remoteClientIds.contains(clientId)
        val wasApprovedClient = approvedClientIds.remove(clientId)
        knownClientsById.remove(clientId)
        notifyLocalClientTransportActivityChanged()
        clientCastingPolicies.remove(clientId)
        handleOfferJobs.remove(clientId)?.cancel()
        val removedSession = sessions.remove(clientId)
        removedSession?.close()
        earlyIceByClient.remove(clientId)
        pendingConnectionRequestsByClient.remove(clientId)
        pendingOffersByClient.remove(clientId)
        pendingReverseRequests.remove(clientId)
        pendingReverseOffersByClient.remove(clientId)
        blockedReverseOffersByClient.remove(clientId)
        if (removedSession?.mode == SessionMode.HostToClient || activeReverseClients.remove(clientId)) {
            maybeStopHostScreenCapture()
        }
        var hasAnyClientsAfterDisconnect = true
        _state.update { state ->
            val updatedClients = state.clients.filterNot { it.clientId == clientId }
            hasAnyClientsAfterDisconnect = updatedClients.isNotEmpty()
            state.copy(
                clients = updatedClients,
                clientCastingPolicies = state.clientCastingPolicies - clientId,
                activeShares = state.activeShares.filterNot { it.clientId == clientId },
                pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
            )
        }
        println("HOST_CONTROLLER: Total sessions after disconnect: ${sessions.size}")
        if (wasRemoteClient && wasApprovedClient && configuredRemoteServerUrl != null && !hasAnyClientsAfterDisconnect) {
            rotateConnectionPin(
                reason = "all-remote-clients-disconnected",
                force = true,
            )
        }
        remoteClientIds.remove(clientId)
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private val onMessage: (String, SignalingMessage) -> Unit = { clientId, message ->
        when (message) {
            is SignalingMessage.Offer -> queueShareRequest(clientId, message.sdp, isRemoteClient = isRemoteClient(clientId))
            is SignalingMessage.Ice -> handleIce(clientId, message.candidate)
            is SignalingMessage.StopShare -> stopShare(clientId)
            is SignalingMessage.ClientDisconnected -> onClientDisconnected(clientId)
            is SignalingMessage.RequestReverseShare -> handleReverseShareRequest(clientId)
            is SignalingMessage.CancelReverseShare -> cancelReverseShare(clientId)
            is SignalingMessage.DisplayRotationChanged -> updateClientDisplayRotation(clientId, message.rotation)
            is SignalingMessage.RequestRemoteControl -> handleRemoteControlRequest(clientId)
            is SignalingMessage.StopRemoteControl -> handleStopRemoteControl(clientId)
            is SignalingMessage.BYOMAnswer -> scope.launch { handleBYOMAnswer(clientId, message.sdp) }
            is SignalingMessage.BYOMIce -> scope.launch { handleBYOMIce(clientId, message.candidate) }
            is SignalingMessage.StopBYOM -> handleStopBYOM(clientId)
            is SignalingMessage.FileUploadData -> {
                scope.launch {
                    handleRelayedFileUpload(clientId, message)
                }
            }
            is SignalingMessage.FileUploadStart -> {
                handleChunkedUploadStart(clientId, message)
            }
            is SignalingMessage.FileUploadChunk -> {
                handleChunkedUploadChunk(clientId, message)
            }
            is SignalingMessage.FileUploadEnd -> {
                scope.launch {
                    handleChunkedUploadEnd(clientId, message)
                }
            }
            is SignalingMessage.Error -> {
                if (message.message == CLIENT_DISCONNECTED_SIGNAL_MESSAGE) {
                    onClientDisconnected(clientId)
                } else {
                    handleClientError(clientId, message.message)
                }
            }
            is SignalingMessage.DiagnosticLog -> {
                val clientName = knownClientsById[clientId]?.name?.ifBlank { clientId } ?: clientId
                println("CLIENT_DIAG [$clientName]: ${message.message}")
            }
            else -> Unit
        }
    }

    private fun updateClientDisplayRotation(clientId: String, rotation: Int) {
        val normalized = ((rotation % 360) + 360) % 360
        println("HOST_CONTROLLER: Client $clientId reported display rotation ${normalized}°")
        _state.update { current ->
            val updated = current.activeShares.map { share ->
                if (share.clientId == clientId) share.copy(displayRotation = normalized) else share
            }
            if (updated == current.activeShares) current else current.copy(activeShares = updated)
        }
    }

    // Local signaling server for Android/iOS/Desktop clients (UDP discovery)
    private val signalingServer = HostSignalingServer(
        onClientConnected = { client ->
            // Local LAN transport should take precedence over stale remote registration.
            remoteClientIds.remove(client.clientId)
            onClientConnected(client)
        },
        onClientDisconnected = onClientDisconnected,
        onMessage = onMessage,
        hostNameProvider = { hostName },
    )

    // Remote signaling service for web clients (remote server)
    private var remoteSignalingService: RemoteSignalingService? = null
    private val remoteClientIds = mutableSetOf<String>() // Track which clients are remote

    private var startJob: Job? = null
    private var pinRotationJob: Job? = null
    private var remoteStateJob: Job? = null
    private var remoteReconnectJob: Job? = null
    private var remoteReconnectAttempt: Int = 0
    private var blockedToastClearJob: Job? = null
    private var deferredMiracastStartJob: Job? = null
    private var captureRestartObserverJob: Job? = null

    override fun start() {
        if (startJob != null) return
        // Observe stuck-capture restart requests from the platform engine. On
        // Android 14+ certain overlay-window transitions (screenshot animation,
        // PiP enter/exit, FLAG_SECURE windows) leave the VirtualDisplay mirror
        // in a state where receivers see frozen frames and no app-side
        // workaround recovers it without fresh MediaProjection consent. The
        // engine emits to this flow in those cases; we clean up active
        // reverse sessions and prompt for fresh consent so capture rebuilds
        // from scratch.
        captureRestartObserverJob?.cancel()
        captureRestartObserverJob = scope.launch {
            webRtcEngine.captureRestartRequested.collect {
                handleCaptureStuckRestart()
            }
        }
        startJob = scope.launch {
            try {
                // Start local signaling server for UDP-discovered clients
                val port = signalingServer.start(SIGNALING_PORT)
                val address = getLocalIpAddress()
                _state.update {
                    it.copy(
                        serverRunning = true,
                        serverPort = port,
                        serverAddress = address,
                        connectionPin = currentConnectionPin,
                        pinExpiresAtEpochMs = currentPinExpiresAtEpochMs,
                    )
                }
                if (configuredRemoteServerUrl != null) {
                    rotateConnectionPin(reason = "remote-code-sync-on-start", force = true)
                }
                discoveryService.startBroadcast(
                    HostInfo(
                        hostId = hostId,
                        name = hostName,
                        port = port,
                        address = address,
                    )
                )

                // Defer heavy Miracast startup a bit so first Compose frames render smoothly.
                // `Dispatchers.Default` is public in commonMain; `IO` is not.
                deferredMiracastStartJob?.cancel()
                deferredMiracastStartJob = scope.launch(Dispatchers.Default) {
                    delay(350)
                    runCatching {
                        miracastStart?.invoke(hostName, 7250)
                    }.onFailure { error ->
                        println("HOST_CONTROLLER: ❌ Miracast startup failed: ${error.message}")
                        _state.update {
                            it.copy(lastError = error.message ?: "Failed to start Miracast services")
                        }
                    }
                }

                // Start remote signaling service for web clients if URL is provided
                val currentRemoteServerUrl = configuredRemoteServerUrl
                if (currentRemoteServerUrl != null) {
                    if (isLoopbackRemoteUrl(currentRemoteServerUrl) && isAndroidHost()) {
                        val message =
                            "Remote signaling URL is $currentRemoteServerUrl. " +
                                "On Android, 'localhost' points to the phone/tablet itself. " +
                                "Use ws://<your-laptop-lan-ip>:8090/ws or a public wss URL."
                        println("HOST_CONTROLLER: ❌ $message")
                        _state.update { it.copy(lastError = message) }
                    } else {
                    println(
                        "HOST_CONTROLLER: Starting remote signaling service for web clients " +
                            "(remoteServerUrl=$currentRemoteServerUrl)",
                    )
                    val remote = RemoteSignalingService(
                        scope = scope,
                        remoteServerUrl = currentRemoteServerUrl,
                        hostId = hostId,
                        hostName = hostName,
                        onClientConnected = { client ->
                            remoteClientIds.add(client.clientId)
                            onClientConnected(client)
                        },
                        onClientDisconnected = { clientId ->
                            onClientDisconnected(clientId)
                            remoteClientIds.remove(clientId)
                        },
                        onMessage = onMessage,
                    )
                    remoteSignalingService = remote
                    remote.connect(
                        pin = currentConnectionPin,
                        pinExpiresAtEpochMs = currentPinExpiresAtEpochMs,
                    )
                    remoteStateJob?.cancel()
                    remoteStateJob = scope.launch {
                        remote.state.collectLatest { remoteState ->
                            when (remoteState) {
                                is RemoteSignalingService.ConnectionState.Connected -> {
                                    remoteReconnectJob?.cancel()
                                    remoteReconnectJob = null
                                    remoteReconnectAttempt = 0
                                    _state.update { it.copy(lastError = null) }
                                }
                                is RemoteSignalingService.ConnectionState.Error -> {
                                    if (isServerPinCollisionSignal(remoteState.message)) {
                                        val collidingPin = extractCollidingPinFromSignal(remoteState.message) ?: "unknown"
                                        println(
                                            "HOST_CONTROLLER: Remote signaling reported PIN collision for pin=$collidingPin; rotating."
                                        )
                                        rotateConnectionPin(
                                            reason = "remote-pin-collision:$collidingPin",
                                            force = true,
                                        )
                                        _state.update { it.copy(lastError = null) }
                                    } else {
                                        _state.update { it.copy(lastError = remoteState.message) }
                                        scheduleRemoteReconnect(
                                            remote = remote,
                                            reason = "remote-error:${remoteState.message}",
                                        )
                                    }
                                }
                                is RemoteSignalingService.ConnectionState.Idle -> {
                                    scheduleRemoteReconnect(
                                        remote = remote,
                                        reason = "remote-idle",
                                    )
                                }
                                else -> Unit
                            }
                        }
                    }
                    println("HOST_CONTROLLER: Remote signaling connect initiated")
                    }
                }
                startPinRotationWatcher()
            } catch (e: Exception) {
                println("HOST_CONTROLLER: ❌ Error starting host: ${e.message}")
                e.printStackTrace()
                _state.update { it.copy(lastError = e.message ?: "Failed to start") }
            }
        }
    }

    override fun stop() {
        startJob?.cancel()
        startJob = null
        pinRotationJob?.cancel()
        pinRotationJob = null
        remoteStateJob?.cancel()
        remoteStateJob = null
        remoteReconnectJob?.cancel()
        remoteReconnectJob = null
        remoteReconnectAttempt = 0
        blockedToastClearJob?.cancel()
        blockedToastClearJob = null
        deferredMiracastStartJob?.cancel()
        deferredMiracastStartJob = null
        captureRestartObserverJob?.cancel()
        captureRestartObserverJob = null
        // Use a detached scope for shutdown so cleanup still runs even if the UI/composition
        // scope is already being cancelled during activity teardown.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            discoveryService.stopBroadcast()
            miracastStop?.invoke()
            signalingServer.stop()
            remoteSignalingService?.disconnect()
            remoteSignalingService = null
            remoteClientIds.clear()
            remoteControlJobs.values.forEach { it.cancel() }
            remoteControlJobs.clear()
            remoteControlDataChannels.values.forEach { it.close() }
            remoteControlDataChannels.clear()
            approvedClientIds.clear()
            knownClientsById.clear()
            lastLocalClientTransportActivity = null
            onLocalClientTransportActivityChanged?.invoke(false)
            clientCastingPolicies.clear()
            hostScreenTrack = null
            webRtcEngine.stopScreenCapture()
            hostScreenAudioTrack = null
            // Stop audio push before tearing down peer connections to avoid
            // racing native sink detachment against CustomAudioSource.pushAudio().
            runCatching { webRtcEngine.stopAudioCapture() }
            byomIceJobs.values.forEach { it.cancel() }
            byomIceJobs.clear()
            byomSessions.values.forEach { it.close() }
            byomSessions.clear()
            byomIceBuffers.clear()
            runCatching { webRtcEngine.stopCameraCapture() }
            runCatching { webRtcEngine.stopMicrophoneCapture() }
            hostCameraTrack = null
            hostMicTrack = null
            sessions.values.forEach { it.close() }
            sessions.clear()
            earlyIceByClient.clear()
            pendingConnectionRequestsByClient.clear()
            pendingOffersByClient.clear()
            pendingReverseRequests.clear()
            pendingReverseOffersByClient.clear()
            blockedReverseOffersByClient.clear()
            activeReverseClients.clear()
            _state.update {
                it.copy(
                    clients = emptyList(),
                    clientCastingPolicies = emptyMap(),
                    activeShares = emptyList(),
                    pendingShareRequests = emptyList(),
                    screenCaptureState = ScreenCaptureState.Idle,
                    blockedToastMessage = null,
                )
            }
        }
    }

    override fun connectToClient(clientId: String) {
        if (!approvedClientIds.contains(clientId)) {
            println("HOST_CONTROLLER: Ignoring connectToClient for unapproved client: $clientId")
            return
        }
        scope.launch {
            if (isRemoteClient(clientId)) {
                remoteSignalingService?.send(clientId, SignalingMessage.StartShare(clientId))
            } else {
                signalingServer.send(clientId, SignalingMessage.StartShare(clientId))
            }
        }
    }

    override fun updateHostDeviceName(name: String) {
        val normalizedName = ensureHostNameHasSuffix(name.trim())
        if (normalizedName.isBlank() || normalizedName == hostName) {
            return
        }

        hostName = normalizedName
        SharedPreferenceUtils.writeString(
            key = SharedPreferenceConstants.HOST_DEVICE_NAME,
            value = hostName,
        )
        val connectedClients = _state.value.clients.distinctBy { it.clientId }
        _state.update { it.copy(deviceName = normalizedName) }

        scope.launch {
            refreshHostIdentity()
            connectedClients.forEach { client ->
                sendToClient(
                    clientId = client.clientId,
                    isRemoteClient = isRemoteClient(client.clientId),
                    message = SignalingMessage.Hello(
                        clientId = hostId,
                        clientName = hostName,
                    ),
                )
            }
        }
    }

    override fun updateHostConnectionSettings(settings: HostConnectionSettings) {
        val normalizedSettings = settings.copy(
            remoteSignalingUrl = RemoteServerConfig.normalizeRemoteServerUrl(settings.remoteSignalingUrl),
        )
        val previous = _state.value.hostConnectionSettings
        if (previous == normalizedSettings) {
            return
        }
        val remoteUrlChanged = previous.remoteSignalingUrl != normalizedSettings.remoteSignalingUrl

        persistHostConnectionSettings(normalizedSettings)
        _state.update {
            it.copy(
                hostConnectionSettings = normalizedSettings,
                lastError = if (remoteUrlChanged) {
                    "Remote signaling URL updated. Restart host to apply this change."
                } else {
                    it.lastError
                },
            )
        }
        if (remoteUrlChanged) {
            configuredRemoteServerUrl = normalizedSettings.remoteSignalingUrl
        }

        if (!previous.isDirectConnectionEnabled && normalizedSettings.isDirectConnectionEnabled) {
            // Auto-accept all pending connection requests when direct connection is enabled.
            pendingConnectionRequestsByClient.keys.toList().forEach { clientId ->
                acceptShareRequest(clientId)
            }
        }

        if (previous.isMultipleDeviceCastEnabled && !normalizedSettings.isMultipleDeviceCastEnabled) {
            enforceSingleActiveCastIfRequired()
        }
    }

    override fun acceptShareRequest(clientId: String) {
        val pendingConnection = pendingConnectionRequestsByClient.remove(clientId)
        if (pendingConnection != null) {
            if (hasReachedConnectionLimit() && !approvedClientIds.contains(clientId)) {
                rejectClientBecauseConnectionLimit(
                    clientId = clientId,
                    isRemoteClient = pendingConnection.isRemoteClient,
                )
                return
            }
            approvedClientIds.add(clientId)
            val policy = clientCastingPolicies[clientId] ?: ClientCastingPolicy()
            clientCastingPolicies[clientId] = policy
            _state.update { state ->
                state.copy(
                    clients = (state.clients + pendingConnection.clientInfo).distinctBy { it.clientId },
                    clientCastingPolicies = state.clientCastingPolicies + (clientId to policy),
                    pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
                )
            }
            if (pendingConnection.isRemoteClient && configuredRemoteServerUrl != null) {
                rotateConnectionPin(
                    reason = "remote-client-approved:$clientId",
                    force = true,
                )
            }
            scope.launch {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = pendingConnection.isRemoteClient,
                    message = SignalingMessage.Hello(
                        clientId = hostId,
                        clientName = hostName,
                    ),
                )
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = pendingConnection.isRemoteClient,
                    message = SignalingMessage.ConnectionApproved,
                )
                val queuedOffer = pendingOffersByClient.remove(clientId)
                if (queuedOffer != null) {
                    handleOffer(
                        clientId = clientId,
                        sdp = queuedOffer.sdp,
                        isRemoteClient = queuedOffer.isRemoteClient,
                        mode = queuedOffer.mode,
                    )
                }
            }
            return
        }

        val pending = pendingOffersByClient.remove(clientId) ?: return
        _state.update { state ->
            state.copy(
                pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
            )
        }
        handleOffer(
            clientId = clientId,
            sdp = pending.sdp,
            isRemoteClient = pending.isRemoteClient,
            mode = pending.mode,
        )
    }

    override fun rejectShareRequest(clientId: String) {
        val pendingConnection = pendingConnectionRequestsByClient.remove(clientId)
        if (pendingConnection != null) {
            pendingOffersByClient.remove(clientId)
            pendingReverseRequests.remove(clientId)
            pendingReverseOffersByClient.remove(clientId)
            approvedClientIds.remove(clientId)
            clientCastingPolicies.remove(clientId)
            _state.update { state ->
                state.copy(
                    clients = state.clients.filterNot { it.clientId == clientId },
                    clientCastingPolicies = state.clientCastingPolicies - clientId,
                    pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
                )
            }
            scope.launch {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = pendingConnection.isRemoteClient,
                    message = SignalingMessage.ConnectionRejected(
                        message = "Host denied the screen share connection request.",
                    ),
                )
                runCatching {
                    sendToClient(
                        clientId = clientId,
                        isRemoteClient = pendingConnection.isRemoteClient,
                        message = SignalingMessage.StopShare,
                    )
                }
            }
            return
        }

        val pending = pendingOffersByClient.remove(clientId)
        if (pending?.mode == SessionMode.HostToClient) {
            pendingReverseRequests.remove(clientId)
        }
        _state.update { state ->
            state.copy(
                pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
            )
        }
        scope.launch {
            val remote = pending?.isRemoteClient ?: isRemoteClient(clientId)
            sendToClient(clientId, remote, SignalingMessage.StopShare)
        }
    }

    override fun stopSharingForClient(clientId: String) {
        scope.launch {
            val remote = isRemoteClient(clientId)
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = remote,
                    message = SignalingMessage.ConnectionRejected(CLIENT_REMOVED_BY_HOST_MESSAGE),
                )
            }.onFailure { error ->
                println("HOST_CONTROLLER: Failed to send removal message to $clientId: ${error.message}")
            }
            stopShare(clientId)
            removeClientConnection(clientId)
        }
    }

    /**
     * Mute/unmute audio coming from a specific client locally on the host.
     *
     * Implementation notes:
     * - We only toggle the receiver-side track `enabled` flag via the peer
     *   connection; the sending client is not notified and keeps streaming.
     * - UI reflects the mute state through [ClientCastingPolicy.isAudioEnabled] so the
     *   speaker icon can show its crossed variant.
     * - No-op if the client has no active session (e.g. already disconnected).
     */
    override fun setClientAudioMuted(clientId: String, muted: Boolean) {
        val session = sessions[clientId]
        if (session == null) {
            println("HOST_CONTROLLER: setClientAudioMuted($clientId, $muted) ignored — no session")
            return
        }

        val enabling = !muted
        if (enabling) {
            // Exclusive audio: disable audio for all other clients before enabling this one.
            clientCastingPolicies.forEach { (otherClientId, otherPolicy) ->
                if (otherClientId != clientId && otherPolicy.isAudioEnabled) {
                    clientCastingPolicies[otherClientId] = otherPolicy.copy(isAudioEnabled = false)
                    sessions[otherClientId]?.let { otherSession ->
                        runCatching { otherSession.peerConnection.setRemoteAudioEnabled(false) }
                    }
                    println("HOST_CONTROLLER: 🔇 Client $otherClientId audio MUTED (exclusive switch)")
                }
            }
        }

        runCatching { session.peerConnection.setRemoteAudioEnabled(enabling) }
            .onFailure { error ->
                println("HOST_CONTROLLER: ❌ Failed to toggle audio for $clientId: ${error.message}")
            }

        val existingPolicy = clientCastingPolicies[clientId] ?: ClientCastingPolicy()
        clientCastingPolicies[clientId] = existingPolicy.copy(isAudioEnabled = enabling)

        _state.update { state ->
            val updatedPolicies = state.clientCastingPolicies.toMutableMap()
            if (enabling) {
                // Disable audio for all other clients in state
                updatedPolicies.forEach { (otherClientId, otherPolicy) ->
                    if (otherClientId != clientId && otherPolicy.isAudioEnabled) {
                        updatedPolicies[otherClientId] = otherPolicy.copy(isAudioEnabled = false)
                    }
                }
            }
            val policy = updatedPolicies[clientId] ?: ClientCastingPolicy()
            updatedPolicies[clientId] = policy.copy(isAudioEnabled = enabling)
            state.copy(clientCastingPolicies = updatedPolicies)
        }
        println("HOST_CONTROLLER: 🔊 Client $clientId audio ${if (muted) "MUTED" else "UNMUTED"}")
    }

    override fun updateClientCastingPolicy(clientId: String, policy: ClientCastingPolicy) {
        val canEnableScreenExclusive = isClientCurrentlySharing(clientId) && hasMultipleActiveShares()
        val normalizedPolicy = if (policy.isScreenExclusiveEnabled && !canEnableScreenExclusive) {
            policy.copy(isScreenExclusiveEnabled = false)
        } else {
            policy
        }

        val updatedPolicies = clientCastingPolicies.toMutableMap()
        if (normalizedPolicy.isScreenExclusiveEnabled) {
            updatedPolicies.forEach { (otherClientId, otherPolicy) ->
                if (otherClientId != clientId && otherPolicy.isScreenExclusiveEnabled) {
                    updatedPolicies[otherClientId] = otherPolicy.copy(isScreenExclusiveEnabled = false)
                }
            }
        }
        // Only one client can have remote control at a time
        if (normalizedPolicy.isRemoteControlEnabled) {
            updatedPolicies.forEach { (otherClientId, otherPolicy) ->
                if (otherClientId != clientId && otherPolicy.isRemoteControlEnabled) {
                    updatedPolicies[otherClientId] = otherPolicy.copy(isRemoteControlEnabled = false)
                    stopRemoteControl(otherClientId)
                }
            }
        }
        updatedPolicies[clientId] = normalizedPolicy
        clientCastingPolicies.clear()
        clientCastingPolicies.putAll(updatedPolicies)

        _state.update { state ->
            val statePolicies = state.clientCastingPolicies.toMutableMap()
            if (normalizedPolicy.isScreenExclusiveEnabled) {
                statePolicies.forEach { (otherClientId, otherPolicy) ->
                    if (otherClientId != clientId && otherPolicy.isScreenExclusiveEnabled) {
                        statePolicies[otherClientId] = otherPolicy.copy(isScreenExclusiveEnabled = false)
                    }
                }
            }
            if (normalizedPolicy.isRemoteControlEnabled) {
                statePolicies.forEach { (otherClientId, otherPolicy) ->
                    if (otherClientId != clientId && otherPolicy.isRemoteControlEnabled) {
                        statePolicies[otherClientId] = otherPolicy.copy(isRemoteControlEnabled = false)
                    }
                }
            }
            statePolicies[clientId] = normalizedPolicy
            state.copy(clientCastingPolicies = statePolicies)
        }

        if (!normalizedPolicy.isScreenCastEnabled) {
            pendingOffersByClient.remove(clientId)

            if (sessionModeByClient(clientId) == SessionMode.ClientToHost) {
                blockScreenCastingForClient(clientId = clientId, isRemoteClient = isRemoteClient(clientId))
            }
        }

        if (!normalizedPolicy.isReverseCastEnabled) {
            pendingReverseRequests.remove(clientId)
            pendingReverseOffersByClient.remove(clientId)
            blockedReverseOffersByClient.remove(clientId)

            if (sessionModeByClient(clientId) == SessionMode.HostToClient) {
                blockReverseCastingForClient(clientId = clientId, isRemoteClient = isRemoteClient(clientId))
            }
        }

        if (!normalizedPolicy.isRemoteControlEnabled) {
            stopRemoteControl(clientId)
        }

        // Show consent dialog before redirecting to AccessibilitySettings if remote control was toggled on
        if (normalizedPolicy.isRemoteControlEnabled && !inputInjector.isAvailable) {
            remoteControlConsentPendingClientId = clientId
            _state.update { it.copy(isRemoteControlConsentRequired = true) }
        }
    }

    override fun onScreenCapturePermissionGranted(permission: ScreenCapturePermissionData) {
        webRtcEngine.setScreenCapturePermission(permission)
        _state.update { it.copy(screenCaptureState = ScreenCaptureState.Idle, lastError = null) }
        retryPendingReverseOffers()
    }

    override fun onScreenCapturePermissionDenied(message: String) {
        val normalizedMessage = message.ifBlank { HOST_SCREEN_CAPTURE_PERMISSION_DENIED_MESSAGE }
        failPendingReverseOffers(normalizedMessage)
    }

    // ── Remote Control ──────────────────────────────────────────────────

    private val inputInjector: InputInjector by lazy { createInputInjector() }
    private val remoteControlDataChannels = mutableMapOf<String, WebRtcDataChannel>()
    private val remoteControlJobs = mutableMapOf<String, Job>()
    private var remoteControlConsentPendingClientId: String? = null

    private fun handleRemoteControlRequest(clientId: String) {
        println("HOST_CONTROLLER: Received remote-control request from client: $clientId")
        val policy = clientCastingPolicies[clientId]
        if (policy?.isRemoteControlEnabled != true) {
            println("HOST_CONTROLLER: Remote control blocked by policy for client: $clientId")
            scope.launch {
                runCatching {
                    sendToClient(
                        clientId = clientId,
                        isRemoteClient = isRemoteClient(clientId),
                        message = SignalingMessage.RemoteControlDenied("Remote control is not enabled"),
                    )
                }
            }
            return
        }

        if (!inputInjector.isAvailable) {
            println("HOST_CONTROLLER: Remote control denied — AccessibilityService not enabled")
            scope.launch {
                runCatching {
                    sendToClient(
                        clientId = clientId,
                        isRemoteClient = isRemoteClient(clientId),
                        message = SignalingMessage.RemoteControlDenied("Accessibility service not enabled on host"),
                    )
                }
            }
            return
        }

        // Use the pre-created data channel
        val dataChannel = remoteControlDataChannels[clientId]
        if (dataChannel == null) {
            println("HOST_CONTROLLER: No pre-created data channel for client: $clientId")
            return
        }

        // Listen for incoming input events
        val job = scope.launch {
            dataChannel.incoming.collect { bytes ->
                runCatching {
                    val json = bytes.decodeToString()
                    val event = ShareXJson.decodeFromString<RemoteInputEvent>(json)
                    inputInjector.inject(event)
                }.onFailure { error ->
                    println("HOST_CONTROLLER: Failed to process remote input: ${error.message}")
                }
            }
        }
        remoteControlJobs[clientId] = job

        _state.update { it.copy(remoteControlClients = it.remoteControlClients + clientId) }

        scope.launch {
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient(clientId),
                    message = SignalingMessage.RemoteControlApproved,
                )
            }
        }
        println("HOST_CONTROLLER: Remote control approved for client: $clientId")
    }

    private fun handleStopRemoteControl(clientId: String) {
        println("HOST_CONTROLLER: Client $clientId stopped remote control")
        cleanupRemoteControl(clientId)
    }

    override fun onAccessibilityServiceEnabled() {
        remoteControlConsentPendingClientId = null
        _state.update { it.copy(isAccessibilityServicePromptRequired = false) }
    }

    override fun onAccessibilityServicePromptDismissed() {
        val clientId = remoteControlConsentPendingClientId
        remoteControlConsentPendingClientId = null
        if (clientId != null) {
            val current = clientCastingPolicies[clientId] ?: ClientCastingPolicy()
            clientCastingPolicies[clientId] = current.copy(isRemoteControlEnabled = false)
            _state.update { state ->
                val updatedPolicies = state.clientCastingPolicies.toMutableMap()
                updatedPolicies[clientId] = (updatedPolicies[clientId] ?: ClientCastingPolicy()).copy(isRemoteControlEnabled = false)
                state.copy(
                    clientCastingPolicies = updatedPolicies,
                    isAccessibilityServicePromptRequired = false,
                )
            }
        } else {
            _state.update { it.copy(isAccessibilityServicePromptRequired = false) }
        }
    }

    override fun onRemoteControlConsentGranted() {
        _state.update { it.copy(isRemoteControlConsentRequired = false, isAccessibilityServicePromptRequired = true) }
    }

    override fun onRemoteControlConsentDenied() {
        val clientId = remoteControlConsentPendingClientId ?: return
        remoteControlConsentPendingClientId = null
        val current = clientCastingPolicies[clientId] ?: ClientCastingPolicy()
        clientCastingPolicies[clientId] = current.copy(isRemoteControlEnabled = false)
        _state.update { state ->
            val updatedPolicies = state.clientCastingPolicies.toMutableMap()
            updatedPolicies[clientId] = (updatedPolicies[clientId] ?: ClientCastingPolicy()).copy(isRemoteControlEnabled = false)
            state.copy(
                clientCastingPolicies = updatedPolicies,
                isRemoteControlConsentRequired = false,
                blockedToastMessage = HOST_REMOTE_CONTROL_CONSENT_DENIED_TOAST_MESSAGE,
            )
        }
        blockedToastClearJob?.cancel()
        blockedToastClearJob = scope.launch {
            delay(HOST_BLOCKED_TOAST_DURATION_MS)
            _state.update { current ->
                if (current.blockedToastMessage == HOST_REMOTE_CONTROL_CONSENT_DENIED_TOAST_MESSAGE) {
                    current.copy(blockedToastMessage = null)
                } else {
                    current
                }
            }
        }
    }

    override fun approveRemoteControl(clientId: String) {
        // Update policy to enable remote control for this client
        val current = clientCastingPolicies[clientId] ?: ClientCastingPolicy()
        clientCastingPolicies[clientId] = current.copy(isRemoteControlEnabled = true)
        // If there's a pending request, handle it now
        handleRemoteControlRequest(clientId)
    }

    override fun denyRemoteControl(clientId: String) {
        scope.launch {
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient(clientId),
                    message = SignalingMessage.RemoteControlDenied(),
                )
            }
        }
    }

    override fun stopRemoteControl(clientId: String) {
        cleanupRemoteControl(clientId)
        scope.launch {
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient(clientId),
                    message = SignalingMessage.StopRemoteControl,
                )
            }
        }
    }

    private fun cleanupRemoteControl(clientId: String) {
        remoteControlJobs.remove(clientId)?.cancel()
        // Do NOT close the data channel here — it is pre-created with the
        // peer connection and lives for the duration of the session.
        // Closing/disposing it while the peer connection is active causes
        // a native crash in the WebRTC library.
        _state.update { it.copy(remoteControlClients = it.remoteControlClients - clientId) }
    }

    // ── BYOM (Bring Your Own Meeting) ──────────────────────────────────────

    private val byomSessions = mutableMapOf<String, WebRtcPeerConnection>()
    private val byomIceJobs = mutableMapOf<String, Job>()
    private val byomIceBuffers = mutableMapOf<String, MutableList<IceCandidateData>>()
    private var hostCameraTrack: PlatformVideoTrack? = null
    private var hostMicTrack: PlatformAudioTrack? = null

    override fun enableBYOM(clientId: String) {
        _state.update { it.copy(activeByomClientIds = it.activeByomClientIds + clientId) }
        scope.launch {
            runCatching {
                startBYOMSession(clientId)
            }.onFailure { error ->
                println("HOST_CONTROLLER: BYOM enable failed for $clientId: ${error.message}")
                _state.update { it.copy(activeByomClientIds = it.activeByomClientIds - clientId) }
            }
        }
    }

    override fun disableBYOM(clientId: String) {
        _state.update { it.copy(activeByomClientIds = it.activeByomClientIds - clientId) }
        scope.launch {
            runCatching {
                sendToClient(clientId = clientId, isRemoteClient = isRemoteClient(clientId), message = SignalingMessage.StopBYOM)
            }
        }
        cleanupBYOM(clientId)
    }

    private suspend fun startBYOMSession(clientId: String) {
        val cameraTrack = getOrStartCameraTrack()
        if (cameraTrack == null) {
            println("HOST_CONTROLLER: BYOM camera unavailable for $clientId")
            _state.update { it.copy(activeByomClientIds = it.activeByomClientIds - clientId) }
            sendToClient(clientId, isRemoteClient(clientId), SignalingMessage.BYOMDenied("Camera not available on this device"))
            return
        }

        val pc = webRtcEngine.createByomPeerConnection(iceServers)
        byomSessions[clientId] = pc
        byomIceBuffers[clientId] = mutableListOf()

        byomIceJobs[clientId] = scope.launch {
            pc.iceCandidates.collect { candidate ->
                runCatching { sendToClient(clientId, isRemoteClient(clientId), SignalingMessage.BYOMIce(candidate)) }
            }
        }

        pc.addLocalVideoTrack(cameraTrack)

        val micTrack = getOrStartMicTrack()
        if (micTrack != null) {
            pc.addLocalAudioTrack(micTrack)
        } else {
            println("HOST_CONTROLLER: BYOM continuing video-only — microphone unavailable")
        }

        val offer = pc.createOffer()
        pc.setLocalDescription(offer)
        sendToClient(clientId, isRemoteClient(clientId), SignalingMessage.BYOMOffer(offer))
        println("HOST_CONTROLLER: BYOM offer sent to client: $clientId")
    }

    private suspend fun getOrStartCameraTrack(): PlatformVideoTrack? {
        val existing = hostCameraTrack
        if (existing != null) return existing
        val track = webRtcEngine.startCameraCapture() ?: return null
        hostCameraTrack = track
        return track
    }

    private suspend fun getOrStartMicTrack(): PlatformAudioTrack? {
        val existing = hostMicTrack
        if (existing != null) return existing
        val track = webRtcEngine.startMicrophoneCapture() ?: return null
        hostMicTrack = track
        return track
    }

    private suspend fun handleBYOMAnswer(clientId: String, sdp: SessionDescriptionData) {
        val pc = byomSessions[clientId] ?: return
        pc.setRemoteDescription(sdp)
        byomIceBuffers.remove(clientId)?.forEach { candidate ->
            runCatching { pc.addIceCandidate(candidate) }
        }
        println("HOST_CONTROLLER: BYOM session active for client: $clientId")
    }

    private suspend fun handleBYOMIce(clientId: String, candidate: IceCandidateData) {
        val pc = byomSessions[clientId]
        if (pc == null) {
            byomIceBuffers.getOrPut(clientId) { mutableListOf() }.add(candidate)
            return
        }
        runCatching { pc.addIceCandidate(candidate) }
    }

    private fun handleStopBYOM(clientId: String) {
        println("HOST_CONTROLLER: Client $clientId stopped BYOM")
        cleanupBYOM(clientId)
    }

    private fun cleanupBYOM(clientId: String) {
        byomIceJobs.remove(clientId)?.cancel()
        byomSessions.remove(clientId)?.close()
        byomIceBuffers.remove(clientId)
        _state.update { it.copy(activeByomClientIds = it.activeByomClientIds - clientId) }
        if (byomSessions.isEmpty()) {
            runCatching { webRtcEngine.stopCameraCapture() }
            runCatching { webRtcEngine.stopMicrophoneCapture() }
            hostCameraTrack = null
            hostMicTrack = null
        }
    }

    private fun isRemoteClient(clientId: String): Boolean {
        return remoteClientIds.contains(clientId)
    }

    private fun notifyLocalClientTransportActivityChanged() {
        val hasActiveLocalClientTransport = knownClientsById.keys.any { clientId ->
            !isRemoteClient(clientId)
        }
        if (lastLocalClientTransportActivity == hasActiveLocalClientTransport) return
        lastLocalClientTransportActivity = hasActiveLocalClientTransport
        onLocalClientTransportActivityChanged?.invoke(hasActiveLocalClientTransport)
    }

    private fun queueConnectionRequest(client: ClientInfo, isRemoteClient: Boolean) {
        if (hasReachedConnectionLimit() && !approvedClientIds.contains(client.clientId)) {
            println(
                "HOST_CONTROLLER: Blocking new client ${client.clientId}; " +
                    "${_state.value.clients.size} already connected"
            )
            rejectClientBecauseConnectionLimit(
                clientId = client.clientId,
                isRemoteClient = isRemoteClient,
            )
            return
        }

        pendingConnectionRequestsByClient[client.clientId] = PendingConnectionRequest(
            clientInfo = client,
            isRemoteClient = isRemoteClient,
        )

        if (_state.value.hostConnectionSettings.isDirectConnectionEnabled) {
            println("HOST_CONTROLLER: Direct connection enabled, auto-approving client: ${client.clientId}")
            acceptShareRequest(client.clientId)
            return
        }

        _state.update { state ->
            val resolvedName = client.name.ifBlank { client.clientId }
            val request = PendingShareRequest(
                clientId = client.clientId,
                clientName = resolvedName,
                deviceType = detectClientDeviceType(resolvedName),
            )
            state.copy(
                pendingShareRequests = state.pendingShareRequests
                    .filterNot { it.clientId == client.clientId } + request,
            )
        }
    }

    private fun hasReachedConnectionLimit(): Boolean {
        val connectedCount = _state.value.clients.distinctBy { it.clientId }.size
        return connectedCount >= MAX_CONNECTED_CLIENTS
    }

    private fun rejectClientBecauseConnectionLimit(clientId: String, isRemoteClient: Boolean) {
        pendingConnectionRequestsByClient.remove(clientId)
        pendingOffersByClient.remove(clientId)
        pendingReverseRequests.remove(clientId)
        pendingReverseOffersByClient.remove(clientId)
        blockedReverseOffersByClient.remove(clientId)
        approvedClientIds.remove(clientId)
        knownClientsById.remove(clientId)
        clientCastingPolicies.remove(clientId)

        _state.update { state ->
            state.copy(
                pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
                clientCastingPolicies = state.clientCastingPolicies - clientId,
            )
        }

        showConnectionLimitToast()

        scope.launch {
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient,
                    message = SignalingMessage.ConnectionRejected(CLIENT_CONNECTION_LIMIT_MESSAGE),
                )
            }
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient,
                    message = SignalingMessage.StopShare,
                )
            }
        }
    }

    private fun showConnectionLimitToast() {
        blockedToastClearJob?.cancel()
        _state.update { it.copy(blockedToastMessage = HOST_CONNECTION_LIMIT_TOAST_MESSAGE) }
        blockedToastClearJob = scope.launch {
            delay(HOST_BLOCKED_TOAST_DURATION_MS)
            _state.update { current ->
                if (current.blockedToastMessage == HOST_CONNECTION_LIMIT_TOAST_MESSAGE) {
                    current.copy(blockedToastMessage = null)
                } else {
                    current
                }
            }
        }
    }

    private fun queueShareRequest(clientId: String, sdp: SessionDescriptionData, isRemoteClient: Boolean) {
        println("HOST_CONTROLLER: Received offer from client: $clientId (remote: $isRemoteClient)")
        pendingReverseOffersByClient.remove(clientId)
        if (blockedReverseOffersByClient.remove(clientId)) {
            println("HOST_CONTROLLER: Ignoring blocked reverse offer from client: $clientId")
            blockReverseCastingForClient(
                clientId = clientId,
                isRemoteClient = isRemoteClient,
                stopExistingShare = false,
            )
            return
        }
        val requestedMode = if (pendingReverseRequests.remove(clientId)) {
            SessionMode.HostToClient
        } else {
            SessionMode.ClientToHost
        }

        val existingSession = sessions[clientId]
        if (existingSession != null && existingSession.mode != requestedMode) {
            normalizeScreenExclusivePolicyAfterShareRemoval(clientId)
            _state.update { state ->
                removeActiveShareFromState(state = state, clientId = clientId)
            }
            existingSession.close()
            sessions.remove(clientId)
            if (existingSession.mode == SessionMode.HostToClient) {
                activeReverseClients.remove(clientId)
                maybeStopHostScreenCapture()
            }
        }

        if (requestedMode == SessionMode.ClientToHost && !approvedClientIds.contains(clientId)) {
            println("HOST_CONTROLLER: Offer queued until host approves connection for client: $clientId")
            pendingOffersByClient[clientId] = PendingOffer(
                sdp = sdp,
                isRemoteClient = isRemoteClient,
                mode = requestedMode,
            )
            val clientInfo = knownClientsById[clientId] ?: ClientInfo(clientId = clientId, name = clientId)
            queueConnectionRequest(client = clientInfo, isRemoteClient = isRemoteClient)
            return
        }

        if (requestedMode == SessionMode.ClientToHost && !isMultipleDeviceCastAllowedForClient(clientId)) {
            println("HOST_CONTROLLER: Multi-device cast disabled, blocking cast for client: $clientId")
            blockMultiDeviceCastingForClient(clientId = clientId, isRemoteClient = isRemoteClient)
            return
        }

        if (requestedMode == SessionMode.ClientToHost && !isScreenCastingEnabledForClient(clientId)) {
            println("HOST_CONTROLLER: Screen cast blocked by host policy for client: $clientId")
            blockScreenCastingForClient(clientId = clientId, isRemoteClient = isRemoteClient)
            return
        }

        if (sessions.containsKey(clientId)) {
            handleOffer(
                clientId = clientId,
                sdp = sdp,
                isRemoteClient = isRemoteClient,
                mode = requestedMode,
            )
            return
        }

        // Mirroring offers from already approved clients and reverse-share offers are applied directly.
        handleOffer(
            clientId = clientId,
            sdp = sdp,
            isRemoteClient = isRemoteClient,
            mode = requestedMode,
        )
    }

    private fun handleOffer(
        clientId: String,
        sdp: SessionDescriptionData,
        isRemoteClient: Boolean = false,
        mode: SessionMode,
        retryCount: Int = 0,
    ) {
        println(
            "HOST_CONTROLLER: Accepting offer from client: $clientId (remote: $isRemoteClient, mode=$mode)"
        )
        handleOfferJobs[clientId]?.cancel()
        handleOfferJobs[clientId] = scope.launch {
            try {
                val session = sessions.getOrPut(clientId) {
                    println("HOST_CONTROLLER: Creating new peer connection for client: $clientId")
                    val pc = webRtcEngine.createPeerConnection(iceServers)
                    val peerTag = "pc@${pc.hashCode()}"
                    println("HOST_CONTROLLER: Client $clientId mapped to $peerTag")
                    val job = launch {
                        pc.iceCandidates.collect { candidate ->
                            val candidateType = when {
                                candidate.candidate.contains("typ relay") -> "relay"
                                candidate.candidate.contains("typ srflx") -> "srflx"
                                candidate.candidate.contains("typ prflx") -> "prflx"
                                candidate.candidate.contains("typ host") -> "host"
                                else -> "unknown"
                            }
                            println("HOST_CONTROLLER: Sending ICE candidate to client=$clientId type=$candidateType mid=${candidate.sdpMid}")
                            sendToClient(clientId, isRemoteClient, SignalingMessage.Ice(candidate))
                        }
                    }
                    val trackJob = launch {
                        println("HOST_CONTROLLER: 📺 Started collecting remote video tracks for client: $clientId")
                        pc.remoteVideoTracks.collect { track ->
                            if (sessionModeByClient(clientId) != SessionMode.ClientToHost) {
                                return@collect
                            }
                            println("HOST_CONTROLLER: ✅ Received remote video track from client: $clientId")
                            val clientName = _state.value.clients.find { it.clientId == clientId }?.name ?: clientId
                            _state.update { state ->
                                println("HOST_CONTROLLER: Current activeShares before update: ${state.activeShares.map { it.clientId }}")
                                val updated = state.activeShares.filterNot { it.clientId == clientId } +
                                    ActiveShare(
                                        clientId = clientId,
                                        clientName = clientName,
                                        videoTrack = track,
                                    )
                                println("HOST_CONTROLLER: ✅ Added to activeShares. Total shares: ${updated.size}")
                                println("HOST_CONTROLLER: ActiveShares now contains: ${updated.map { it.clientId }}")
                                state.copy(activeShares = updated)
                            }
                            // Apply the audio state from ClientCastingPolicy. Audio is disabled
                            // by default for new clients (isAudioEnabled = false), so we mute
                            // unless the user has explicitly enabled it.
                            val isAudioEnabled = clientCastingPolicies[clientId]?.isAudioEnabled ?: false
                            if (!isAudioEnabled) {
                                runCatching { pc.setRemoteAudioEnabled(false) }
                            }
                        }
                    }
                    // Create the negotiated data channel BEFORE the answer so SCTP
                    // transport is included in the SDP.
                    val remoteInputChannel = pc.createDataChannel(REMOTE_CONTROL_DATA_CHANNEL_LABEL)
                    remoteControlDataChannels[clientId] = remoteInputChannel
                    println("HOST_CONTROLLER: Pre-created remote-input data channel for client: $clientId")

                    println("HOST_CONTROLLER: ✅ Created session with trackJob for client: $clientId")
                    HostPeerSession(peerConnection = pc, jobs = listOf(job, trackJob), mode = mode)
                }
                session.mode = mode

                println("HOST_CONTROLLER: Setting remote description for client: $clientId (Total sessions: ${sessions.size})")
                session.peerConnection.setRemoteDescription(sdp)
                println("HOST_CONTROLLER: ✅ Remote description set for client: $clientId")
                session.bufferedRemoteIce.addAll(earlyIceByClient.remove(clientId).orEmpty())
                session.remoteDescriptionSet = true
                flushBufferedIce(session)

                if (mode == SessionMode.HostToClient) {
                    val track = getHostScreenTrack()
                    if (!session.hostTrackAttached) {
                        session.peerConnection.addLocalVideoTrack(track)
                        session.hostTrackAttached = true
                    }
                    // Attach host-side audio (microphone/system audio) so remote clients receive audio.
                    // Missing audio is non-fatal: we keep the video-only stream working.
                    val hostAudio = getHostScreenAudioTrack()
                    if (hostAudio != null && !session.hostAudioTrackAttached) {
                        session.peerConnection.addLocalAudioTrack(hostAudio)
                        session.hostAudioTrackAttached = true
                    }
                    activeReverseClients.add(clientId)
                    normalizeScreenExclusivePolicyAfterShareRemoval(clientId)
                    _state.update { state ->
                        removeActiveShareFromState(state = state, clientId = clientId)
                    }
                } else {
                    if (activeReverseClients.remove(clientId)) {
                        maybeStopHostScreenCapture()
                    }
                    normalizeScreenExclusivePolicyAfterShareRemoval(clientId)
                    _state.update { state ->
                        removeActiveShareFromState(state = state, clientId = clientId)
                    }
                }

                println("HOST_CONTROLLER: Creating answer for client: $clientId")
                val answer = session.peerConnection.createAnswer()
                val negotiatedAnswer = applyHighQualityVideoSdpHints(
                    sdp = answer,
                    platformName = getPlatformName(),
                    logPrefix = "HOST_CONTROLLER",
                )
                session.peerConnection.setLocalDescription(negotiatedAnswer)

                println("HOST_CONTROLLER: Sending answer to client: $clientId")
                sendToClient(clientId, isRemoteClient, SignalingMessage.Answer(negotiatedAnswer))
                println("HOST_CONTROLLER: ✅ Answer sent successfully to client: $clientId")
            } catch (_: kotlinx.coroutines.CancellationException) {
                println("HOST_CONTROLLER: handleOffer cancelled for client: $clientId")
                throw kotlinx.coroutines.CancellationException("handleOffer cancelled")
            } catch (permissionError: ScreenCapturePermissionRequired) {
                println("HOST_CONTROLLER: ❌ Host screen capture permission required: ${permissionError.message}")
                if (mode == SessionMode.HostToClient) {
                    queuePendingReverseOffer(
                        clientId = clientId,
                        sdp = sdp,
                        isRemoteClient = isRemoteClient,
                        retryCount = retryCount,
                    )
                    requestHostScreenCapturePermission()
                } else {
                    sendToClient(
                        clientId = clientId,
                        isRemoteClient = isRemoteClient,
                        message = SignalingMessage.Error("Host screen capture permission is required."),
                    )
                    stopShare(clientId)
                }
            } catch (e: Exception) {
                println("HOST_CONTROLLER: ❌ Error handling offer from client $clientId: ${e.message}")
                e.printStackTrace()
                if (mode == SessionMode.HostToClient) {
                    val errorMessage = e.message
                    when {
                        requiresFreshHostScreenCapturePermission(errorMessage) -> {
                            queuePendingReverseOffer(
                                clientId = clientId,
                                sdp = sdp,
                                isRemoteClient = isRemoteClient,
                                retryCount = retryCount,
                            )
                            requestHostScreenCapturePermission()
                        }

                        isHostScreenCaptureServiceNotReady(errorMessage) &&
                            retryCount < HOST_SCREEN_CAPTURE_SERVICE_RETRY_LIMIT -> {
                            queuePendingReverseOffer(
                                clientId = clientId,
                                sdp = sdp,
                                isRemoteClient = isRemoteClient,
                                retryCount = retryCount + 1,
                            )
                            scope.launch {
                                delay(HOST_SCREEN_CAPTURE_SERVICE_RETRY_DELAY_MS)
                                retryPendingReverseOffers()
                            }
                        }

                        else -> {
                            sendToClient(
                                clientId = clientId,
                                isRemoteClient = isRemoteClient,
                                message = SignalingMessage.Error(
                                    "Failed to start reverse mirroring: ${e.message ?: "unknown error"}",
                                ),
                            )
                        }
                    }
                }
            } finally {
                handleOfferJobs.remove(clientId)
            }
        }
    }

    private fun handleIce(clientId: String, candidate: IceCandidateData) {
        val candidateType = candidate.extractCandidateType()
        println(
            "HOST_CONTROLLER: Received ICE candidate from client=$clientId " +
                "type=$candidateType mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex}"
        )
        val session = sessions[clientId]
        if (session == null) {
            println("HOST_CONTROLLER: Buffering ICE candidate (no active session) for client=$clientId")
            earlyIceByClient.getOrPut(clientId) { mutableListOf() }.add(candidate)
            return
        }
        if (!session.remoteDescriptionSet) {
            println("HOST_CONTROLLER: Buffering ICE candidate (remote description not set yet) for client=$clientId")
            session.bufferedRemoteIce.add(candidate)
            return
        }
        scope.launch {
            // Re-check the session is still active before touching the native peer
            // connection.  Between the outer lookup and this coroutine dispatch, another
            // thread may have called stopShare / onClientDisconnected which closes the
            // native PeerConnection.  Calling addIceCandidate on a closed native object
            // triggers a fatal native crash that Kotlin try/catch cannot intercept.
            if (sessions[clientId] !== session) {
                println(
                    "HOST_CONTROLLER: Dropping ICE candidate for client=$clientId " +
                        "type=$candidateType (session closed before dispatch)"
                )
                return@launch
            }
            try {
                session.peerConnection.addIceCandidate(candidate)
                println(
                    "HOST_CONTROLLER: Applied ICE candidate for client=$clientId " +
                        "type=$candidateType"
                )
            } catch (error: Exception) {
                println(
                    "HOST_CONTROLLER: Failed to apply ICE candidate for client=$clientId " +
                        "type=$candidateType: ${error.message}"
                )
                if (sessions[clientId] === session) {
                    session.bufferedRemoteIce.add(candidate)
                    flushBufferedIce(session)
                }
            }
        }
    }

    private fun stopShare(clientId: String) {
        cleanupRemoteControl(clientId)
        handleOfferJobs.remove(clientId)?.cancel()
        val removedSession = sessions.remove(clientId)
        removedSession?.close()
        earlyIceByClient.remove(clientId)
        pendingOffersByClient.remove(clientId)
        pendingReverseRequests.remove(clientId)
        pendingReverseOffersByClient.remove(clientId)
        blockedReverseOffersByClient.remove(clientId)
        if (removedSession?.mode == SessionMode.HostToClient || activeReverseClients.remove(clientId)) {
            maybeStopHostScreenCapture()
        }
        normalizeScreenExclusivePolicyAfterShareRemoval(clientId)
        _state.update { state ->
            removeActiveShareFromState(state = state, clientId = clientId).copy(
                pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
            )
        }
    }

    private fun cleanupSessionForReverseRetry(clientId: String) {
        val removedSession = sessions.remove(clientId)
        if (removedSession != null && removedSession.bufferedRemoteIce.isNotEmpty()) {
            earlyIceByClient.getOrPut(clientId) { mutableListOf() }.addAll(removedSession.bufferedRemoteIce)
        }
        removedSession?.close()
        pendingOffersByClient.remove(clientId)
        pendingReverseRequests.remove(clientId)
        if (removedSession?.mode == SessionMode.HostToClient || activeReverseClients.remove(clientId)) {
            maybeStopHostScreenCapture()
        }
        normalizeScreenExclusivePolicyAfterShareRemoval(clientId)
        _state.update { state ->
            removeActiveShareFromState(state = state, clientId = clientId).copy(
                pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
            )
        }
    }

    private fun handleReverseShareRequest(clientId: String) {
        println("HOST_CONTROLLER: Received reverse-share request from client: $clientId")
        if (!isReverseCastingEnabledForClient(clientId)) {
            println("HOST_CONTROLLER: Reverse cast blocked by host policy for client: $clientId")
            blockedReverseOffersByClient.add(clientId)
            blockReverseCastingForClient(
                clientId = clientId,
                isRemoteClient = isRemoteClient(clientId),
                stopExistingShare = false,
            )
            return
        }

        // A client entering reverse mode must not continue contributing a forward-share tile.
        if (sessionModeByClient(clientId) == SessionMode.ClientToHost) {
            stopShare(clientId)
        }

        pendingReverseRequests.add(clientId)
        scope.launch {
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient(clientId),
                    message = SignalingMessage.ReverseShareApproved,
                )
            }
        }
    }

    private fun cancelReverseShare(clientId: String) {
        val session = sessions[clientId]
        if (session?.mode == SessionMode.HostToClient) {
            stopShare(clientId)
            return
        }

        pendingReverseRequests.remove(clientId)
        val removedPendingOffer = pendingReverseOffersByClient.remove(clientId)
        blockedReverseOffersByClient.remove(clientId)
        if (removedPendingOffer != null && pendingReverseOffersByClient.isEmpty()) {
            hostScreenTrack = null
            webRtcEngine.stopScreenCapture()
            hostScreenAudioTrack = null
            runCatching { webRtcEngine.stopAudioCapture() }
            _state.update {
                it.copy(screenCaptureState = ScreenCaptureState.Idle)
            }
        }
    }

    private fun handleClientError(clientId: String, message: String) {
        val normalizedMessage = message.ifBlank { "Client reported an unknown casting error." }
        val clientName = knownClientsById[clientId]?.name?.ifBlank { clientId } ?: clientId
        _state.update { it.copy(lastError = "$clientName: $normalizedMessage") }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private suspend fun handleRelayedFileUpload(clientId: String, message: SignalingMessage.FileUploadData) {
        try {
            val fileName = message.fileName
                .replace(Regex("[/\\\\]"), "_") // sanitize path separators
            val fileBytes = kotlin.io.encoding.Base64.decode(message.fileDataBase64)
            val dirPath = com.teachmint.sharex.filetransfer.getShareXDirectoryPath()
            val targetPath = "$dirPath/$fileName"
            com.teachmint.sharex.filetransfer.writeFileBytes(targetPath, fileBytes)
            println("FILE_UPLOAD_RELAY: Saved ${fileBytes.size} bytes as $targetPath from client $clientId")
        } catch (e: Exception) {
            println("FILE_UPLOAD_RELAY: Error saving file from client $clientId: ${e.message}")
            e.printStackTrace()
        }
    }

    // -- Chunked file upload reassembly --

    private class ChunkedUploadBuffer(
        val fileName: String,
        val fileSize: Long,
        val totalChunks: Int,
        val clientId: String,
    ) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        var receivedCount = 0
    }

    private fun handleChunkedUploadStart(clientId: String, message: SignalingMessage.FileUploadStart) {
        val buffer = ChunkedUploadBuffer(
            fileName = message.fileName.replace(Regex("[/\\\\]"), "_"),
            fileSize = message.fileSize,
            totalChunks = message.totalChunks,
            clientId = clientId,
        )
        chunkedUploadBuffers[message.uploadId] = buffer
        println("FILE_UPLOAD_CHUNKED: Started upload ${message.uploadId} " +
            "from $clientId: ${buffer.fileName} (${message.fileSize} bytes, ${message.totalChunks} chunks)")
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun handleChunkedUploadChunk(clientId: String, message: SignalingMessage.FileUploadChunk) {
        val buffer = chunkedUploadBuffers[message.uploadId]
        if (buffer == null) {
            println("FILE_UPLOAD_CHUNKED: Unknown uploadId ${message.uploadId}, ignoring chunk ${message.chunkIndex}")
            return
        }
        if (message.chunkIndex < 0 || message.chunkIndex >= buffer.totalChunks) {
            println("FILE_UPLOAD_CHUNKED: Invalid chunk index ${message.chunkIndex} for upload ${message.uploadId}")
            return
        }
        val chunkBytes = kotlin.io.encoding.Base64.decode(message.chunkDataBase64)
        buffer.chunks[message.chunkIndex] = chunkBytes
        buffer.receivedCount++
        println("FILE_UPLOAD_CHUNKED: Received chunk ${message.chunkIndex + 1}/${buffer.totalChunks} " +
            "for upload ${message.uploadId} (${chunkBytes.size} bytes)")
    }

    private suspend fun handleChunkedUploadEnd(clientId: String, message: SignalingMessage.FileUploadEnd) {
        val buffer = chunkedUploadBuffers.remove(message.uploadId)
        if (buffer == null) {
            println("FILE_UPLOAD_CHUNKED: Unknown uploadId ${message.uploadId} on end, ignoring")
            return
        }
        try {
            if (buffer.receivedCount != buffer.totalChunks) {
                println("FILE_UPLOAD_CHUNKED: Incomplete upload ${message.uploadId}: " +
                    "received ${buffer.receivedCount}/${buffer.totalChunks} chunks")
                return
            }
            // Reassemble chunks into a single byte array
            val totalSize = buffer.chunks.sumOf { it?.size ?: 0 }
            val assembled = ByteArray(totalSize)
            var offset = 0
            for (chunk in buffer.chunks) {
                if (chunk != null) {
                    chunk.copyInto(assembled, offset)
                    offset += chunk.size
                }
            }
            val dirPath = com.teachmint.sharex.filetransfer.getShareXDirectoryPath()
            val targetPath = "$dirPath/${buffer.fileName}"
            com.teachmint.sharex.filetransfer.writeFileBytes(targetPath, assembled)
            println("FILE_UPLOAD_CHUNKED: Saved ${assembled.size} bytes as $targetPath from client $clientId")
        } catch (e: Exception) {
            println("FILE_UPLOAD_CHUNKED: Error assembling file from client $clientId: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun requestHostScreenCapturePermission() {
        if (_state.value.screenCaptureState is ScreenCaptureState.PermissionRequired) {
            _state.update { it.copy(screenCaptureState = ScreenCaptureState.Idle) }
        }
        _state.update { it.copy(screenCaptureState = ScreenCaptureState.PermissionRequired) }
    }

    /**
     * Recover from a stuck screen-capture pipeline by tearing down current
     * reverse sessions and requesting fresh MediaProjection consent.
     *
     * Triggered by [WebRtcEngine.captureRestartRequested] when the platform
     * engine detects that the capture is in an unrecoverable stuck state —
     * typically Android 14+ devices after an overlay window (screenshot
     * animation, PiP, FLAG_SECURE) leaves the VirtualDisplay's mirror
     * detached. Since Android 14+ requires fresh user consent to rebuild
     * MediaProjection, the recovery surfaces a system-dialog prompt on the
     * host. After consent is granted, the existing
     * [onScreenCapturePermissionGranted] flow processes any reconnecting
     * clients normally.
     */
    private suspend fun handleCaptureStuckRestart() {
        val reverseSessionClients = sessions
            .filterValues { it.mode == SessionMode.HostToClient }
            .keys
            .toSet()
        val toCleanup = buildSet {
            addAll(activeReverseClients)
            addAll(reverseSessionClients)
            addAll(pendingReverseOffersByClient.keys)
        }.toList()

        if (toCleanup.isEmpty() && hostScreenTrack == null) {
            // Nothing to recover; ignore spurious signal.
            return
        }
        println(
            "HOST_CONTROLLER: 🔄 Capture pipeline stuck — tearing down " +
                "${toCleanup.size} reverse session(s) and requesting fresh consent"
        )
        // Send HostCaptureInterrupted and AWAIT delivery before closing peer
        // connections. If we fire-and-forget the sends and immediately call
        // cleanupSessionForReverseRetry (which closes the PeerConnection), the
        // PeerConnection ICE close races the WebSocket signaling message and
        // typically wins — the client's state transitions out of Receiving
        // before the signal arrives, so wasInReverseFlow is false and no toast
        // or auto-retry fires. Awaiting the sends keeps the peer connection
        // open long enough for the client to process the signal first.
        runCatching {
            withTimeout(2_000L) {
                coroutineScope {
                    toCleanup.forEach { clientId ->
                        launch {
                            runCatching {
                                sendToClient(
                                    clientId = clientId,
                                    isRemoteClient = isRemoteClient(clientId),
                                    message = SignalingMessage.HostCaptureInterrupted,
                                )
                            }
                        }
                    }
                }
            }
        }
        toCleanup.forEach { clientId ->
            cleanupRemoteControl(clientId)
            cleanupSessionForReverseRetry(clientId)
        }
        activeReverseClients.clear()
        hostCaptureMutex.withLock {
            hostScreenTrack = null
            hostScreenAudioTrack = null
            runCatching { webRtcEngine.stopScreenCapture() }
            runCatching { webRtcEngine.stopAudioCapture() }
        }
        requestHostScreenCapturePermission()
    }

    private fun failPendingReverseOffers(message: String) {
        val pendingClients = pendingReverseOffersByClient.keys.toList()
        pendingReverseOffersByClient.clear()
        pendingClients.forEach { clientId ->
            cleanupSessionForReverseRetry(clientId)
        }
        if (activeReverseClients.isEmpty()) {
            hostScreenTrack = null
            webRtcEngine.stopScreenCapture()
            hostScreenAudioTrack = null
            runCatching { webRtcEngine.stopAudioCapture() }
        }
        _state.update {
            it.copy(
                screenCaptureState = ScreenCaptureState.Idle,
                lastError = message,
            )
        }
        pendingClients.forEach { clientId ->
            scope.launch {
                runCatching {
                    sendToClient(
                        clientId = clientId,
                        isRemoteClient = isRemoteClient(clientId),
                        message = SignalingMessage.Error(message),
                    )
                }
            }
        }
    }

    private fun retryPendingReverseOffers() {
        if (pendingReverseOffersByClient.isEmpty()) return
        val offers = pendingReverseOffersByClient.toMap()
        pendingReverseOffersByClient.clear()
        offers.forEach { (clientId, offer) ->
            handleOffer(
                clientId = clientId,
                sdp = offer.sdp,
                isRemoteClient = offer.isRemoteClient,
                mode = SessionMode.HostToClient,
                retryCount = offer.retryCount,
            )
        }
    }

    private fun queuePendingReverseOffer(
        clientId: String,
        sdp: SessionDescriptionData,
        isRemoteClient: Boolean,
        retryCount: Int,
    ) {
        cleanupSessionForReverseRetry(clientId)
        pendingReverseOffersByClient[clientId] = PendingOffer(
            sdp = sdp,
            isRemoteClient = isRemoteClient,
            mode = SessionMode.HostToClient,
            retryCount = retryCount,
        )
    }

    private fun requiresFreshHostScreenCapturePermission(message: String?): Boolean {
        val normalized = message?.lowercase().orEmpty()
        return normalized.contains("screen capture permission expired") ||
            normalized.contains("don't re-use the resultdata") ||
            normalized.contains("token that has timed out") ||
            normalized.contains("multiple captures") ||
            normalized.contains("createvirtualdisplay")
    }

    private fun isHostScreenCaptureServiceNotReady(message: String?): Boolean {
        val normalized = message?.lowercase().orEmpty()
        return normalized.contains("foreground mediaprojection service is not ready")
    }

    private suspend fun getHostScreenTrack(): PlatformVideoTrack {
        hostCaptureMutex.withLock {
            val existing = hostScreenTrack
            if (existing != null) {
                return existing
            }
            val created = webRtcEngine.startScreenCapture()
            hostScreenTrack = created
            return created
        }
    }

    /**
     * Lazily start and cache the host-side audio track. Returns `null` if the platform
     * cannot capture audio right now; callers should treat this as "video-only".
     */
    private suspend fun getHostScreenAudioTrack(): PlatformAudioTrack? {
        hostCaptureMutex.withLock {
            hostScreenAudioTrack?.let { return it }
            val audioOptions = if (getPlatformName().startsWith("Desktop", ignoreCase = true)) {
                AudioCaptureOptions(
                    microphone = false,
                    systemAudio = true,
                )
            } else {
                AudioCaptureOptions.Default
            }
            val created = runCatching { webRtcEngine.startAudioCapture(audioOptions) }.getOrNull() ?: return null
            hostScreenAudioTrack = created
            return created
        }
    }

    private fun maybeStopHostScreenCapture() {
        if (activeReverseClients.isNotEmpty()) return
        hostScreenTrack = null
        webRtcEngine.stopScreenCapture()
        hostScreenAudioTrack = null
        runCatching { webRtcEngine.stopAudioCapture() }
    }

    private fun removeClientConnection(clientId: String) {
        val wasRemoteClient = isRemoteClient(clientId)
        val wasApprovedClient = approvedClientIds.remove(clientId)

        knownClientsById.remove(clientId)
        clientCastingPolicies.remove(clientId)
        pendingConnectionRequestsByClient.remove(clientId)
        pendingOffersByClient.remove(clientId)
        pendingReverseRequests.remove(clientId)
        pendingReverseOffersByClient.remove(clientId)
        blockedReverseOffersByClient.remove(clientId)
        remoteClientIds.remove(clientId)

        var hasAnyClientsAfterRemoval = true
        _state.update { state ->
            val updatedClients = state.clients.filterNot { it.clientId == clientId }
            hasAnyClientsAfterRemoval = updatedClients.isNotEmpty()
            state.copy(
                clients = updatedClients,
                clientCastingPolicies = state.clientCastingPolicies - clientId,
                activeShares = state.activeShares.filterNot { it.clientId == clientId },
                pendingShareRequests = state.pendingShareRequests.filterNot { it.clientId == clientId },
            )
        }

        if (wasRemoteClient && wasApprovedClient && configuredRemoteServerUrl != null && !hasAnyClientsAfterRemoval) {
            rotateConnectionPin(
                reason = "remote-client-removed:$clientId",
                force = true,
            )
        }
    }

    private fun isScreenCastingEnabledForClient(clientId: String): Boolean {
        return clientCastingPolicies[clientId]?.isScreenCastEnabled == true
    }

    private fun isClientCurrentlySharing(clientId: String): Boolean {
        return _state.value.activeShares.any { it.clientId == clientId }
    }

    private fun hasMultipleActiveShares(): Boolean {
        return _state.value.activeShares
            .map { it.clientId }
            .distinct()
            .size > 1
    }

    private fun normalizeScreenExclusivePolicyAfterShareRemoval(clientId: String) {
        clearScreenExclusivePolicyForClient(clientId)
        if (remainingActiveShareCountAfterRemoving(clientId) <= 1) {
            clearAllScreenExclusivePoliciesForClientMap()
        }
    }

    private fun remainingActiveShareCountAfterRemoving(clientId: String): Int {
        return _state.value.activeShares
            .asSequence()
            .map { it.clientId }
            .filter { it != clientId }
            .distinct()
            .count()
    }

    private fun clearScreenExclusivePolicyForClient(clientId: String) {
        val existingPolicy = clientCastingPolicies[clientId] ?: return
        if (!existingPolicy.isScreenExclusiveEnabled) return
        clientCastingPolicies[clientId] = existingPolicy.copy(isScreenExclusiveEnabled = false)
    }

    private fun clearAllScreenExclusivePoliciesForClientMap() {
        val pinnedClientIds = clientCastingPolicies
            .filterValues { it.isScreenExclusiveEnabled }
            .keys
        if (pinnedClientIds.isEmpty()) return
        pinnedClientIds.forEach { pinnedClientId ->
            val pinnedPolicy = clientCastingPolicies[pinnedClientId] ?: return@forEach
            clientCastingPolicies[pinnedClientId] = pinnedPolicy.copy(isScreenExclusiveEnabled = false)
        }
    }

    private fun removeActiveShareFromState(state: HostUiState, clientId: String): HostUiState {
        val updatedActiveShares = state.activeShares.filterNot { it.clientId == clientId }
        var updatedPolicies = clearScreenExclusivePolicyInState(
            policies = state.clientCastingPolicies,
            clientId = clientId,
        )
        val hasMultipleActiveShares = updatedActiveShares
            .map { it.clientId }
            .distinct()
            .size > 1
        if (!hasMultipleActiveShares) {
            updatedPolicies = clearAllScreenExclusivePoliciesInState(updatedPolicies)
        }
        return state.copy(
            clientCastingPolicies = updatedPolicies,
            activeShares = updatedActiveShares,
        )
    }

    private fun clearScreenExclusivePolicyInState(
        policies: Map<String, ClientCastingPolicy>,
        clientId: String,
    ): Map<String, ClientCastingPolicy> {
        val existingPolicy = policies[clientId] ?: return policies
        if (!existingPolicy.isScreenExclusiveEnabled) return policies
        return policies + (clientId to existingPolicy.copy(isScreenExclusiveEnabled = false))
    }

    private fun clearAllScreenExclusivePoliciesInState(
        policies: Map<String, ClientCastingPolicy>,
    ): Map<String, ClientCastingPolicy> {
        if (policies.values.none { it.isScreenExclusiveEnabled }) return policies
        return policies.mapValues { (_, policy) ->
            if (policy.isScreenExclusiveEnabled) {
                policy.copy(isScreenExclusiveEnabled = false)
            } else {
                policy
            }
        }
    }

    private fun isMultipleDeviceCastAllowedForClient(clientId: String): Boolean {
        if (_state.value.hostConnectionSettings.isMultipleDeviceCastEnabled) {
            return true
        }
        val hasAnotherActiveShare = _state.value.activeShares.any { it.clientId != clientId }
        val hasAnotherCastingSession = sessions.any { (otherClientId, session) ->
            otherClientId != clientId && session.mode == SessionMode.ClientToHost
        }
        return !hasAnotherActiveShare && !hasAnotherCastingSession
    }

    private fun enforceSingleActiveCastIfRequired() {
        if (_state.value.hostConnectionSettings.isMultipleDeviceCastEnabled) return
        val activeClientIds = _state.value.activeShares.map { it.clientId }.distinct()
        if (activeClientIds.size <= 1) return
        activeClientIds.drop(1).forEach { clientId ->
            blockMultiDeviceCastingForClient(
                clientId = clientId,
                isRemoteClient = isRemoteClient(clientId),
            )
        }
    }

    private fun isReverseCastingEnabledForClient(clientId: String): Boolean {
        return clientCastingPolicies[clientId]?.isReverseCastEnabled == true
    }

    private fun blockMultiDeviceCastingForClient(clientId: String, isRemoteClient: Boolean) {
        stopShare(clientId)
        scope.launch {
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient,
                    message = SignalingMessage.Error(MULTI_DEVICE_CAST_DISABLED_MESSAGE),
                )
            }
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient,
                    message = SignalingMessage.StopShare,
                )
            }
        }
    }

    private fun blockScreenCastingForClient(clientId: String, isRemoteClient: Boolean) {
        stopShare(clientId)
        scope.launch {
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient,
                    message = SignalingMessage.Error(SCREEN_CASTING_BLOCKED_MESSAGE),
                )
            }
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient,
                    message = SignalingMessage.StopShare,
                )
            }
        }
    }

    private fun blockReverseCastingForClient(
        clientId: String,
        isRemoteClient: Boolean,
        stopExistingShare: Boolean = true,
    ) {
        if (stopExistingShare) {
            stopShare(clientId)
        }
        scope.launch {
            runCatching {
                sendToClient(
                    clientId = clientId,
                    isRemoteClient = isRemoteClient,
                    message = SignalingMessage.Error(REVERSE_CASTING_BLOCKED_MESSAGE),
                )
            }
            if (stopExistingShare) {
                runCatching {
                    sendToClient(
                        clientId = clientId,
                        isRemoteClient = isRemoteClient,
                        message = SignalingMessage.StopShare,
                    )
                }
            }
        }
    }

    private fun sessionModeByClient(clientId: String): SessionMode? = sessions[clientId]?.mode

    private suspend fun sendToClient(clientId: String, isRemoteClient: Boolean, message: SignalingMessage) {
        if (isRemoteClient) {
            remoteSignalingService?.send(clientId, message)
        } else {
            signalingServer.send(clientId, message)
        }
    }

    private suspend fun refreshHostIdentity() {
        runCatching {
            miracastStart?.invoke(hostName, 7250)
        }.onFailure {
            println("MIRACAST: ⚠️ Failed to refresh advertised host name: ${it.message}")
        }

        val currentState = _state.value
        val serverPort = currentState.serverPort ?: return
        if (!currentState.serverRunning) return

        val hostInfo = HostInfo(
            hostId = hostId,
            name = hostName,
            port = serverPort,
            address = currentState.serverAddress,
        )

        discoveryService.stopBroadcast()
        discoveryService.startBroadcast(hostInfo)
        remoteSignalingService?.updateHostName(hostName)
    }

    private fun detectClientDeviceType(clientName: String): ClientDeviceType {
        val normalizedName = clientName.lowercase()
        return when {
            normalizedName.contains("android") ||
                normalizedName.contains("iphone") ||
                normalizedName.contains("ios") ||
                normalizedName.contains("ipad") ||
                normalizedName.contains("phone") ||
                normalizedName.contains("mobile") -> ClientDeviceType.Mobile
            else -> ClientDeviceType.Laptop
        }
    }

    private fun startPinRotationWatcher() {
        pinRotationJob?.cancel()
        pinRotationJob = scope.launch {
            while (isActive) {
                delay(HOST_PIN_ROTATION_CHECK_INTERVAL_MS)
                if (currentTimeMillis() >= currentPinExpiresAtEpochMs) {
                    val reason = if (_state.value.clients.isNotEmpty()) {
                        "pin-expired-with-active-clients"
                    } else {
                        "pin-expired"
                    }
                    rotateConnectionPin(reason = reason, force = true)
                }
            }
        }
    }

    private fun rotateConnectionPin(reason: String, force: Boolean = false) {
        if (!force && _state.value.clients.isNotEmpty()) {
            return
        }

        val nextPin = generateConnectionPin()
        val nextExpiry = nextPinExpiryEpochMs()
        currentConnectionPin = nextPin
        currentPinExpiresAtEpochMs = nextExpiry
        println("HOST_CONTROLLER: Connection pin rotated (reason=$reason)")
        _state.update {
            it.copy(
                connectionPin = nextPin,
                pinExpiresAtEpochMs = nextExpiry,
            )
        }

        scope.launch {
            remoteSignalingService?.updatePin(nextPin, nextExpiry)
            val remote = remoteSignalingService
            if (
                remote != null &&
                remote.state.value !is RemoteSignalingService.ConnectionState.Connected
            ) {
                scheduleRemoteReconnect(
                    remote = remote,
                    reason = "pin-rotated:$reason",
                )
            }
        }
    }

    private fun scheduleRemoteReconnect(remote: RemoteSignalingService, reason: String) {
        if (!_state.value.serverRunning || configuredRemoteServerUrl == null) return
        if (remoteSignalingService !== remote) return
        if (remoteReconnectJob?.isActive == true) return

        remoteReconnectAttempt = (remoteReconnectAttempt + 1).coerceAtMost(8)
        var delayMs = REMOTE_RECONNECT_BASE_DELAY_MS
        repeat((remoteReconnectAttempt - 1).coerceAtLeast(0)) {
            delayMs = (delayMs * 2).coerceAtMost(REMOTE_RECONNECT_MAX_DELAY_MS)
        }

        println(
            "HOST_CONTROLLER: Scheduling remote signaling reconnect in ${delayMs}ms " +
                "(attempt=$remoteReconnectAttempt, reason=$reason)"
        )

        remoteReconnectJob = scope.launch {
            delay(delayMs)
            if (!_state.value.serverRunning || configuredRemoteServerUrl == null) return@launch
            if (remoteSignalingService !== remote) return@launch

            runCatching {
                remote.connect(
                    pin = currentConnectionPin,
                    pinExpiresAtEpochMs = currentPinExpiresAtEpochMs,
                )
            }.onFailure { error ->
                println(
                    "HOST_CONTROLLER: Remote signaling reconnect failed: ${error.message}"
                )
            }
        }
    }

    private fun generateConnectionPin(): String {
        return secureRandomInt(HOST_PIN_RANGE_UPPER_BOUND).toString().padStart(HOST_PIN_LENGTH, '0')
    }

    private fun nextPinExpiryEpochMs(): Long {
        return currentTimeMillis() + HOST_PIN_TTL_MS
    }

    private fun isAndroidHost(): Boolean = getPlatformName().startsWith("Android")

    private fun isLoopbackRemoteUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("localhost") || normalized.contains("127.0.0.1")
    }

    private fun readOrInitializeHostName(): String {
        val defaultHostName = getHostDisplayName().ifBlank { "Host Device" }
        val key = SharedPreferenceConstants.HOST_DEVICE_NAME

        if (!SharedPreferenceUtils.contains(key)) {
            val generatedHostName = ensureHostNameHasSuffix(defaultHostName)
            SharedPreferenceUtils.writeString(key = key, value = generatedHostName)
            return generatedHostName
        }

        val persistedHostName = SharedPreferenceUtils.readString(
            key = key,
            defaultValue = defaultHostName,
        )?.trim().orEmpty()

        if (persistedHostName.isBlank()) {
            val regeneratedHostName = ensureHostNameHasSuffix(defaultHostName)
            SharedPreferenceUtils.writeString(key = key, value = regeneratedHostName)
            return regeneratedHostName
        }

        val migratedHostName = ensureHostNameHasSuffix(persistedHostName)
        if (migratedHostName != persistedHostName) {
            SharedPreferenceUtils.writeString(key = key, value = migratedHostName)
        }
        return migratedHostName
    }

    private fun appendRandomSuffix(baseName: String): String {
        val suffix = Random.nextInt(0, HOST_NAME_SUFFIX_RANGE_UPPER_BOUND).toString().padStart(HOST_NAME_SUFFIX_LENGTH, '0')
        return "$baseName $suffix"
    }

    private fun ensureHostNameHasSuffix(name: String): String {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return trimmedName
        return if (trimmedName.matches(HOST_NAME_WITH_SUFFIX_REGEX)) {
            trimmedName
        } else {
            appendRandomSuffix(trimmedName)
        }
    }

    private fun readOrInitializeHostConnectionSettings(): HostConnectionSettings {
        val defaultSettings = HostConnectionSettings(
            remoteSignalingUrl = configuredRemoteServerUrl,
        )

        val isMultipleDeviceCastEnabled = readOrInitializeBoolean(
            key = SharedPreferenceConstants.HOST_MULTIPLE_DEVICE_CAST_ENABLED,
            defaultValue = defaultSettings.isMultipleDeviceCastEnabled,
        )
        val isDirectConnectionEnabled = readOrInitializeBoolean(
            key = SharedPreferenceConstants.HOST_DIRECT_CONNECTION_ENABLED,
            defaultValue = defaultSettings.isDirectConnectionEnabled,
        )
        return HostConnectionSettings(
            isMultipleDeviceCastEnabled = isMultipleDeviceCastEnabled,
            isDirectConnectionEnabled = isDirectConnectionEnabled,
        )
    }

    private fun persistHostConnectionSettings(settings: HostConnectionSettings) {
        SharedPreferenceUtils.writeBoolean(
            key = SharedPreferenceConstants.HOST_MULTIPLE_DEVICE_CAST_ENABLED,
            value = settings.isMultipleDeviceCastEnabled,
        )
        SharedPreferenceUtils.writeBoolean(
            key = SharedPreferenceConstants.HOST_DIRECT_CONNECTION_ENABLED,
            value = settings.isDirectConnectionEnabled,
        )
    }

    private fun readOrInitializeBoolean(key: String, defaultValue: Boolean): Boolean {
        if (!SharedPreferenceUtils.contains(key)) {
            SharedPreferenceUtils.writeBoolean(key = key, value = defaultValue)
        }
        return SharedPreferenceUtils.readBoolean(key = key, defaultValue = defaultValue)
    }


    private class HostPeerSession(
        val peerConnection: WebRtcPeerConnection,
        val jobs: List<Job>,
        var mode: SessionMode,
    ) {
        var remoteDescriptionSet: Boolean = false
        val bufferedRemoteIce: MutableList<IceCandidateData> = mutableListOf()
        var hostTrackAttached: Boolean = false
        var hostAudioTrackAttached: Boolean = false

        fun close() {
            jobs.forEach { it.cancel() }
            peerConnection.close()
        }
    }

    private data class PendingOffer(
        val sdp: SessionDescriptionData,
        val isRemoteClient: Boolean,
        val mode: SessionMode,
        val retryCount: Int = 0,
    )

    private data class PendingConnectionRequest(
        val clientInfo: ClientInfo,
        val isRemoteClient: Boolean,
    )

    private enum class SessionMode {
        ClientToHost,
        HostToClient,
    }

    private suspend fun flushBufferedIce(session: HostPeerSession) {
        if (!session.remoteDescriptionSet) return
        if (session.bufferedRemoteIce.isEmpty()) return

        val candidates = session.bufferedRemoteIce.toList()
        session.bufferedRemoteIce.clear()
        println("HOST_CONTROLLER: Flushing ${candidates.size} buffered ICE candidates")
        for (candidate in candidates) {
            try {
                session.peerConnection.addIceCandidate(candidate)
            } catch (error: Exception) {
                println(
                    "HOST_CONTROLLER: Re-buffering ICE candidate after add failure " +
                        "(type=${candidate.extractCandidateType()}): ${error.message}"
                )
                session.bufferedRemoteIce.add(candidate)
            }
        }
    }

    private fun IceCandidateData.extractCandidateType(): String {
        val raw = candidate
        val marker = " typ "
        val markerIndex = raw.indexOf(marker)
        if (markerIndex < 0) return "unknown"
        val start = markerIndex + marker.length
        val end = raw.indexOf(' ', start).let { index ->
            if (index < 0) raw.length else index
        }
        if (start >= end) return "unknown"
        return raw.substring(start, end)
    }
}
