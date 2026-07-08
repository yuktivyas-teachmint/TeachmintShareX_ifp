package com.teachmint.sharex.airplay

import android.content.Context
import android.util.Log
import com.teachmint.sharex.share.shared.AndroidContextHolder
import com.teachmint.sharex.share.shared.ClientCastingPolicy
import com.teachmint.sharex.share.shared.ClientDeviceType
import com.teachmint.sharex.share.shared.ClientInfo
import com.teachmint.sharex.share.shared.PendingShareRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID

/**
 * Android AirPlay receiver — top-level coordinator.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  iPhone / Mac (source)                                           │
 * │   1. mDNS discovery  →  _airplay._tcp port 7000                 │
 * │   2. GET /info       →  device capabilities (plist)             │
 * │   3. POST /pair-setup   (SRP6a 3 phases, PIN shown on screen)   │
 * │   4. POST /pair-verify  (Curve25519 ECDH + Ed25519 auth)        │
 * │   5. RTSP ANNOUNCE   →  SDP: H.264 video + AAC audio            │
 * │   6. RTSP SETUP ×2   →  allocate video UDP port, audio UDP port │
 * │   7. RTSP RECORD     →  start streaming                         │
 * │   8. RTP/UDP video   →  H.264 NAL units → MediaCodec → Surface  │
 * │   9. RTP/UDP audio   →  AAC frames → MediaCodec → AudioTrack    │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * Differences from the jvmMain version:
 *  - Video is decoded by [H264StreamDecoder] (MediaCodec) and rendered
 *    directly to a [android.view.Surface] provided by the UI layer —
 *    there is no SharedFlow<Bitmap> copy.
 *  - Audio is played via [AirPlayAudioPlayer] (AudioTrack).
 *  - mDNS is advertised by [AirPlayMdnsAdvertiser] (JmDNS + MulticastLock).
 *
 * @param displayName  Name shown in the iOS/macOS AirPlay picker.
 * @param pin          4-digit PIN the user must enter on the source device.
 */

class AirPlayReceiver(
    val displayName: String = "ShareX1",
    val pin: String = AirPlayPairingHandler.DEFAULT_PIN,
    private val httpPort: Int = AirPlayProtocol.HTTP_PORT,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val approvalLock = Any()
    private val pendingApprovalsByClient = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val allowedClientIds = mutableSetOf<String>()
    private val allowedClientAddresses = mutableSetOf<String>()
    private val allowedClientAddressesById = mutableMapOf<String, MutableSet<String>>()
    private val connectedClientRefCounts = mutableMapOf<String, Int>()
    private val connectedClientsById = mutableMapOf<String, ClientInfo>()
    private val clientPoliciesById = mutableMapOf<String, ClientCastingPolicy>()
    private val sessionClientIds = mutableMapOf<String, String>()
    private val audioPlayerClientIds = mutableMapOf<String, String>()

    // ── protocol layers (must be declared first — deviceInfo uses pairingHandler.publicKey) ──

    // The Ed25519 long-term key pair is persisted in SharedPreferences so macOS can match its
    // cached pairing record across restarts. Without this, macOS skips SRP (thinking it's already
    // paired) and does pair-verify against its cached pk — which won't match a freshly generated key.
    private val pairingHandler = AirPlayPairingHandler(pin, loadOrGenerateLtKey())

    // ── device identity ───────────────────────────────────────────────────────

    val deviceInfo: AirPlayDeviceInfo = createDeviceInfo()

    private val mdns   by lazy { AirPlayMdnsAdvertiser(deviceInfo, httpPort) }
    private val server by lazy {
        AirPlayUnifiedServer(
            port           = httpPort,
            deviceInfo     = deviceInfo,
            pairingHandler = pairingHandler,
            onSessionReady = ::onSessionReady,
            onSessionEnded = ::onSessionEnded,
            onMirrorStream = ::onMirrorStreamStarted,
            onConnectionApprovalRequested = ::requestConnectionApproval,
            onConnectionClosed = ::onClientConnectionClosed,
        )
    }

    // ── active sessions ───────────────────────────────────────────────────────

    private val videoReceivers = mutableMapOf<String, AirPlayRtpReceiver>()
    private val audioReceivers = mutableMapOf<String, AirPlayAudioReceiver>()
    private val audioDecoders  = mutableMapOf<String, AacDecoder>()
    private val audioPlayers   = mutableMapOf<String, AirPlayAudioPlayer>()

    // ── public API ────────────────────────────────────────────────────────────

    /** One H.264 decoder per connected AirPlay client. Keyed by clientId. */
    val videoDecoders = mutableMapOf<String, H264StreamDecoder>()

    fun getDecoderForClient(clientId: String): H264StreamDecoder? = videoDecoders[clientId]

    private val _status = MutableStateFlow(Status.Idle)
    val status: StateFlow<Status> = _status
    private val _pendingConnectionRequests = MutableStateFlow<List<PendingShareRequest>>(emptyList())
    val pendingConnectionRequests: StateFlow<List<PendingShareRequest>> = _pendingConnectionRequests
    private val _connectedClientCount = MutableStateFlow(0)
    val connectedClientCount: StateFlow<Int> = _connectedClientCount
    private val _connectedClients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val connectedClients: StateFlow<List<ClientInfo>> = _connectedClients
    private val _clientPolicies = MutableStateFlow<Map<String, ClientCastingPolicy>>(emptyMap())
    val clientPolicies: StateFlow<Map<String, ClientCastingPolicy>> = _clientPolicies
    private val _activeMirroringClientIds = MutableStateFlow<Set<String>>(emptySet())
    val activeMirroringClientIds: StateFlow<Set<String>> = _activeMirroringClientIds

    // ── lifecycle ─────────────────────────────────────────────────────────────

    suspend fun start() {
        check(_status.value == Status.Idle) { "AirPlayReceiver already started" }
        _status.value = Status.Starting

        Log.d("AirPlay", "starting receiver name='$displayName' pin=$pin port=$httpPort  pk=${deviceInfo.publicKeyHex.take(16)}…")
        Log.d("AirPlay", ">> step 1: unified server start() on port $httpPort")
        val serverStartResult = runCatching { server.start() }
        serverStartResult.exceptionOrNull()?.let { error ->
            Log.e("AirPlay", "Server start failed: ${error.javaClass.simpleName}: ${error.message}", error)
            _status.value = Status.Idle
            throw error
        }
        Log.d("AirPlay", ">> step 2: mdns.start()")
        val mdnsStartResult = runCatching { mdns.start() }
        mdnsStartResult.exceptionOrNull()?.let { error ->
            Log.e("AirPlay", "mDNS start failed: ${error.javaClass.simpleName}: ${error.message}", error)
            runCatching { server.stop() }
            _status.value = Status.Idle
            throw error
        }
        Log.d("AirPlay", ">> step 3: all servers started")

        _status.value = Status.Ready
        Log.d("AirPlay", "Ready — '$displayName'  PIN=$pin  port:$httpPort")
    }

    suspend fun stop() {
        if (_status.value == Status.Idle) return
        _status.value = Status.Stopping

        videoDecoders.values.forEach { it.stop() }
        videoDecoders.clear()
        videoReceivers.values.forEach { it.stop() }
        audioReceivers.values.forEach { it.stop() }
        audioDecoders.values.forEach { it.stop() }
        audioPlayers.values.forEach { it.stop() }

        videoReceivers.clear()
        audioReceivers.clear(); audioDecoders.clear(); audioPlayers.clear()
        synchronized(approvalLock) {
            pendingApprovalsByClient.values.forEach { approval ->
                approval.complete(false)
            }
            pendingApprovalsByClient.clear()
            allowedClientIds.clear()
            allowedClientAddresses.clear()
            allowedClientAddressesById.clear()
            connectedClientRefCounts.clear()
            connectedClientsById.clear()
            clientPoliciesById.clear()
            sessionClientIds.clear()
            audioPlayerClientIds.clear()
            _pendingConnectionRequests.value = emptyList()
            _connectedClientCount.value = 0
            _connectedClients.value = emptyList()
            _clientPolicies.value = emptyMap()
            _activeMirroringClientIds.value = emptySet()
        }

        mdns.stop()
        server.stop()
        scope.cancel()

        _status.value = Status.Idle
        Log.d("AirPlay", "Stopped")
    }

    // ── session callbacks ─────────────────────────────────────────────────────

    private fun onSessionReady(session: RtspSession) {
        scope.launch {
            Log.d("AirPlay", "session ready id=${session.sessionId} client=${session.clientAddress} videoRTP=${session.videoRtpPort} audioRTP=${session.audioRtpPort}")
            val clientId = session.clientId.ifBlank { "airplay:${session.clientAddress}" }
            val resolvedClientName = session.clientName.ifBlank { "AirPlay (${session.clientAddress})" }
            val isAudioEnabled = synchronized(approvalLock) {
                sessionClientIds[session.sessionId] = clientId
                connectedClientsById[clientId] = ClientInfo(
                    clientId = clientId,
                    name = resolvedClientName,
                )
                // Keep host-side audio muted by default for new AirPlay clients.
                clientPoliciesById.putIfAbsent(clientId, ClientCastingPolicy())
                _activeMirroringClientIds.value = _activeMirroringClientIds.value + clientId
                publishConnectedClientsLocked()
                publishClientPoliciesLocked()
                clientPoliciesById[clientId]?.isAudioEnabled == true
            }

            var startedAnyPipeline = false

            // ── Video pipeline ────────────────────────────────────────────────
            if (
                session.videoRtpPort > 0 &&
                session.videoSdp != null &&
                !videoReceivers.containsKey(session.sessionId)
            ) {
                val rtpVideo = AirPlayRtpReceiver(session.videoRtpPort, session.videoSdp)
                videoReceivers[session.sessionId] = rtpVideo
                val decoder = videoDecoders.getOrPut(clientId) { H264StreamDecoder() }
                decoder.start(rtpVideo.nalUnits, session.videoSdp)
                rtpVideo.start()
                startedAnyPipeline = true
                Log.d(
                    "AirPlay",
                    "Started video RTP pipeline for session=${session.sessionId} port=${session.videoRtpPort}",
                )
            }

            // ── Audio pipeline (optional) ─────────────────────────────────────
            if (
                session.audioRtpPort > 0 &&
                session.audioSdp != null &&
                !audioReceivers.containsKey(session.sessionId)
            ) {
                val rtpAudio = AirPlayAudioReceiver(
                    rtpPort = session.audioRtpPort,
                    rtcpPort = session.audioRtcpPort,
                    sdp = session.audioSdp,
                )
                val aacDecoder = AacDecoder(session.audioSdp)
                val audioPlayer = AirPlayAudioPlayer()

                audioReceivers[session.sessionId] = rtpAudio
                audioDecoders[session.sessionId] = aacDecoder
                audioPlayers[session.sessionId] = audioPlayer
                synchronized(approvalLock) {
                    audioPlayerClientIds[session.sessionId] = clientId
                }
                audioPlayer.setMuted(!isAudioEnabled)

                aacDecoder.start(rtpAudio.aacFrames)
                audioPlayer.start(aacDecoder.pcmFrames)
                rtpAudio.start()
                startedAnyPipeline = true
                Log.d(
                    "AirPlay",
                    "Started audio RTP pipeline for session=${session.sessionId} port=${session.audioRtpPort}",
                )
            } else {
                // Session updates can arrive after the player is already created;
                // keep runtime mute state in sync with host policy.
                audioPlayers[session.sessionId]?.setMuted(!isAudioEnabled)
            }

            if (startedAnyPipeline || videoReceivers.isNotEmpty() || audioReceivers.isNotEmpty()) {
                _status.value = Status.Receiving
                Log.d("AirPlay", "status=Receiving — session update applied")
            } else {
                Log.d("AirPlay", "session update contained no startable pipelines for id=${session.sessionId}")
            }
        }
    }

    private fun onMirrorStreamStarted(clientId: String, clientName: String, nalFlow: SharedFlow<ByteArray>) {
        // Must be synchronous — videoDecoder.start() must subscribe to nalFlow
        // BEFORE parser.parse() begins emitting, otherwise SPS/PPS are lost
        // from the replay buffer before the decoder can collect them.
        Log.d("AirPlay", "Mirror stream started — connecting to video decoder")
        val decoder = synchronized(approvalLock) {
            if (clientId.isNotBlank()) {
                connectedClientsById[clientId] = ClientInfo(
                    clientId = clientId,
                    name = clientName.ifBlank { clientId },
                )
                clientPoliciesById.putIfAbsent(clientId, ClientCastingPolicy())
                _activeMirroringClientIds.value = _activeMirroringClientIds.value + clientId
                publishConnectedClientsLocked()
                publishClientPoliciesLocked()
            }
            videoDecoders.getOrPut(clientId) { H264StreamDecoder() }
        }
        decoder.start(nalFlow, null)
        _status.value = Status.Receiving
        Log.d("AirPlay", "status=Receiving — mirror pipeline started")
    }

    private fun onSessionEnded(sessionId: String) {
        videoReceivers.remove(sessionId)?.stop()
        audioReceivers.remove(sessionId)?.stop()
        audioDecoders.remove(sessionId)?.stop()
        audioPlayers.remove(sessionId)?.stop()
        synchronized(approvalLock) {
            val endedClientId = sessionClientIds.remove(sessionId)
            audioPlayerClientIds.remove(sessionId)
            if (endedClientId != null) {
                val sessionStillActive = sessionClientIds.values.contains(endedClientId)
                if (!sessionStillActive) {
                    videoDecoders.remove(endedClientId)?.stop()
                    _activeMirroringClientIds.value = _activeMirroringClientIds.value - endedClientId
                }
            }
            if (videoReceivers.isEmpty() && audioReceivers.isEmpty() && connectedClientRefCounts.isEmpty()) {
                _status.value = Status.Ready
            }
        }

        Log.d("AirPlay", "session ended id=$sessionId")
    }

    fun approveConnectionRequest(clientId: String) {
        synchronized(approvalLock) {
            pendingApprovalsByClient[clientId]?.complete(true)
        }
    }

    fun rejectConnectionRequest(clientId: String) {
        synchronized(approvalLock) {
            pendingApprovalsByClient[clientId]?.complete(false)
        }
    }

    fun updateClientPolicy(clientId: String, policy: ClientCastingPolicy) {
        var shouldDisconnectClient = false
        synchronized(approvalLock) {
            if (clientId.isBlank()) return
            val updatedPolicies = clientPoliciesById.toMutableMap()
            updatedPolicies.putIfAbsent(clientId, ClientCastingPolicy())
            var normalizedPolicy = policy
            if (policy.isScreenExclusiveEnabled) {
                updatedPolicies.forEach { (otherClientId, otherPolicy) ->
                    if (otherClientId != clientId && otherPolicy.isScreenExclusiveEnabled) {
                        updatedPolicies[otherClientId] = otherPolicy.copy(isScreenExclusiveEnabled = false)
                    }
                }
            }
            if (policy.isAudioEnabled) {
                updatedPolicies.forEach { (otherClientId, otherPolicy) ->
                    if (otherClientId != clientId && otherPolicy.isAudioEnabled) {
                        updatedPolicies[otherClientId] = otherPolicy.copy(isAudioEnabled = false)
                        setAudioMutedForClientLocked(otherClientId, muted = true)
                    }
                }
            }
            normalizedPolicy = normalizedPolicy.copy(isAudioEnabled = policy.isAudioEnabled)
            updatedPolicies[clientId] = normalizedPolicy
            clientPoliciesById.clear()
            clientPoliciesById.putAll(updatedPolicies)
            setAudioMutedForClientLocked(clientId, muted = !normalizedPolicy.isAudioEnabled)
            publishClientPoliciesLocked()
            shouldDisconnectClient = !normalizedPolicy.isScreenCastEnabled
        }
        if (shouldDisconnectClient) {
            disconnectClient(clientId)
        }
    }

    fun setClientAudioMuted(clientId: String, muted: Boolean) {
        synchronized(approvalLock) {
            if (clientId.isBlank()) return
            clientPoliciesById.putIfAbsent(clientId, ClientCastingPolicy())
            val enabling = !muted
            if (enabling) {
                clientPoliciesById.forEach { (otherClientId, otherPolicy) ->
                    if (otherClientId != clientId && otherPolicy.isAudioEnabled) {
                        clientPoliciesById[otherClientId] = otherPolicy.copy(isAudioEnabled = false)
                        setAudioMutedForClientLocked(otherClientId, muted = true)
                    }
                }
            }
            val existingPolicy = clientPoliciesById[clientId] ?: ClientCastingPolicy()
            clientPoliciesById[clientId] = existingPolicy.copy(isAudioEnabled = enabling)
            setAudioMutedForClientLocked(clientId, muted = muted)
            publishClientPoliciesLocked()
        }
    }

    fun disconnectClient(clientId: String) {
        val sessionIdsToStop = synchronized(approvalLock) {
            sessionClientIds
                .filterValues { it == clientId }
                .keys
                .toList()
        }
        // Stop all active RTP/audio pipelines for this client immediately.
        // Relying only on async socket-close callbacks can leave stale decode/playback alive.
        sessionIdsToStop.forEach { sessionId ->
            videoReceivers.remove(sessionId)?.stop()
            audioReceivers.remove(sessionId)?.stop()
            audioDecoders.remove(sessionId)?.stop()
            audioPlayers.remove(sessionId)?.stop()
        }

        runCatching { videoDecoders.remove(clientId)?.stop() }

        synchronized(approvalLock) {
            pendingApprovalsByClient.remove(clientId)?.complete(false)
            _pendingConnectionRequests.value = _pendingConnectionRequests.value
                .filterNot { it.clientId == clientId }
            allowedClientIds.remove(clientId)
            val addresses = allowedClientAddressesById.remove(clientId).orEmpty()
            addresses.forEach { allowedClientAddresses.remove(it) }
            connectedClientRefCounts.remove(clientId)
            connectedClientsById.remove(clientId)
            clientPoliciesById.remove(clientId)
            sessionClientIds.entries.removeAll { it.value == clientId }
            audioPlayerClientIds.entries.removeAll { it.value == clientId }
            _activeMirroringClientIds.value = _activeMirroringClientIds.value - clientId
            updateConnectedClientCountLocked()
            publishConnectedClientsLocked()
            publishClientPoliciesLocked()
            if (connectedClientRefCounts.isEmpty() && videoReceivers.isEmpty() && audioReceivers.isEmpty()) {
                _status.value = Status.Ready
            }
        }
        server.disconnectClient(clientId)
    }

    private suspend fun requestConnectionApproval(request: AirPlayConnectionRequest): Boolean {
        synchronized(approvalLock) {
            val wasAllowed = allowedClientIds.contains(request.clientId) ||
                allowedClientAddresses.contains(request.clientAddress)
            if (wasAllowed) {
                allowedClientIds.add(request.clientId)
                allowedClientAddresses.add(request.clientAddress)
                allowedClientAddressesById
                    .getOrPut(request.clientId) { mutableSetOf() }
                    .add(request.clientAddress)
                incrementConnectedClientRefCountLocked(request.clientId)
                connectedClientsById[request.clientId] = ClientInfo(
                    clientId = request.clientId,
                    name = request.clientName.ifBlank { request.clientId },
                )
                clientPoliciesById.putIfAbsent(request.clientId, ClientCastingPolicy())
                _pendingConnectionRequests.value = _pendingConnectionRequests.value
                    .filterNot { it.clientId == request.clientId }
                updateConnectedClientCountLocked()
                publishConnectedClientsLocked()
                publishClientPoliciesLocked()
                Log.d(
                    "AirPlay",
                    "Auto-approved known client ${request.clientId} (${request.clientAddress})",
                )
                return true
            }
        }

        val pendingApproval = CompletableDeferred<Boolean>()
        synchronized(approvalLock) {
            pendingApprovalsByClient.remove(request.clientId)?.complete(false)
            pendingApprovalsByClient[request.clientId] = pendingApproval
            val deviceType = detectDeviceType(request.userAgent)
            _pendingConnectionRequests.value = _pendingConnectionRequests.value
                .filterNot { it.clientId == request.clientId } +
                PendingShareRequest(
                    clientId = request.clientId,
                    clientName = request.clientName,
                    deviceType = deviceType,
                )
        }
        Log.d("AirPlay", "Approval requested for ${request.clientId} (${request.clientName})")

        val approved = withTimeoutOrNull(CONNECTION_APPROVAL_TIMEOUT_MS) {
            pendingApproval.await()
        } ?: false

        synchronized(approvalLock) {
            pendingApprovalsByClient.remove(request.clientId)
            _pendingConnectionRequests.value = _pendingConnectionRequests.value
                .filterNot { it.clientId == request.clientId }
            if (approved) {
                allowedClientIds.add(request.clientId)
                allowedClientAddresses.add(request.clientAddress)
                allowedClientAddressesById
                    .getOrPut(request.clientId) { mutableSetOf() }
                    .add(request.clientAddress)
                incrementConnectedClientRefCountLocked(request.clientId)
                connectedClientsById[request.clientId] = ClientInfo(
                    clientId = request.clientId,
                    name = request.clientName.ifBlank { request.clientId },
                )
                clientPoliciesById.putIfAbsent(request.clientId, ClientCastingPolicy())
            } else {
                connectedClientRefCounts.remove(request.clientId)
            }
            updateConnectedClientCountLocked()
            publishConnectedClientsLocked()
            publishClientPoliciesLocked()
        }

        if (!approved) {
            Log.d("AirPlay", "Approval denied or timed out for ${request.clientId}")
        }
        return approved
    }

    private fun onClientConnectionClosed(clientId: String) {
        synchronized(approvalLock) {
            pendingApprovalsByClient.remove(clientId)?.complete(false)
            _pendingConnectionRequests.value = _pendingConnectionRequests.value
                .filterNot { it.clientId == clientId }
            decrementConnectedClientRefCountLocked(clientId)
            if (!connectedClientRefCounts.containsKey(clientId)) {
                // Client is fully disconnected now. Drop allowlist state so the next
                // reconnect asks for approval again instead of auto-approving stale sessions.
                allowedClientIds.remove(clientId)
                val addresses = allowedClientAddressesById.remove(clientId).orEmpty()
                addresses.forEach { allowedClientAddresses.remove(it) }
                connectedClientsById.remove(clientId)
                clientPoliciesById.remove(clientId)
                sessionClientIds.entries.removeAll { it.value == clientId }
                audioPlayerClientIds.entries.removeAll { it.value == clientId }
                videoDecoders.remove(clientId)?.stop()
                _activeMirroringClientIds.value = _activeMirroringClientIds.value - clientId
            }
            updateConnectedClientCountLocked()
            publishConnectedClientsLocked()
            publishClientPoliciesLocked()
            if (connectedClientRefCounts.isEmpty() && videoReceivers.isEmpty() && audioReceivers.isEmpty()) {
                _status.value = Status.Ready
            }
        }
        Log.d("AirPlay", "Connection closed for $clientId")
    }

    private fun detectDeviceType(userAgent: String?): ClientDeviceType {
        val normalized = userAgent?.lowercase().orEmpty()
        return if (
            normalized.contains("iphone") ||
            normalized.contains("ipad") ||
            normalized.contains("ios") ||
            normalized.contains("android")
        ) {
            ClientDeviceType.Mobile
        } else {
            ClientDeviceType.Laptop
        }
    }

    private fun updateConnectedClientCountLocked() {
        _connectedClientCount.value = connectedClientRefCounts.size
    }

    private fun publishConnectedClientsLocked() {
        _connectedClients.value = connectedClientsById.values.toList()
    }

    private fun publishClientPoliciesLocked() {
        _clientPolicies.value = clientPoliciesById.toMap()
    }

    private fun setAudioMutedForClientLocked(clientId: String, muted: Boolean) {
        audioPlayerClientIds.forEach { (sessionId, mappedClientId) ->
            if (mappedClientId == clientId) {
                audioPlayers[sessionId]?.setMuted(muted)
            }
        }
    }

    private fun incrementConnectedClientRefCountLocked(clientId: String) {
        val current = connectedClientRefCounts[clientId] ?: 0
        connectedClientRefCounts[clientId] = current + 1
    }

    private fun decrementConnectedClientRefCountLocked(clientId: String) {
        val current = connectedClientRefCounts[clientId] ?: return
        if (current <= 1) {
            connectedClientRefCounts.remove(clientId)
        } else {
            connectedClientRefCounts[clientId] = current - 1
        }
    }

    // ── device identity ───────────────────────────────────────────────────────

    private fun createDeviceInfo(): AirPlayDeviceInfo {
        val deviceId = readMacAddress() ?: generateFakeMac()
        return AirPlayDeviceInfo(
            deviceId  = deviceId,
            name      = displayName,
            pi        = loadOrGeneratePi(),
            publicKey = pairingHandler.publicKey,  // same key pair used for signing in pair-verify
        )
    }

    private fun readMacAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.firstOrNull { !it.isLoopback && it.hardwareAddress?.size == 6 }
            ?.hardwareAddress
            ?.joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    private fun generateFakeMac(): String =
        (0 until 6).map { SecureRandom().nextInt(256) }
            .joinToString(":") { "%02X".format(it) }

    /**
     * Loads the persisted Ed25519 private key seed from SharedPreferences, or generates a new one
     * and saves it. The same 32-byte seed is reused across restarts so that the advertised `pk`
     * stays constant and macOS can match its cached pairing record.
     */
    private fun loadOrGenerateLtKey(): ByteArray {
        val ctx = AndroidContextHolder.applicationContext ?: return ByteArray(0)
        val prefs = ctx.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_LT_PRIV_KEY, null)
        if (stored != null) {
            val bytes = runCatching {
                stored.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }.getOrNull()
            if (bytes != null && bytes.size == 32) return bytes
        }
        // First run — generate and persist
        val handler = AirPlayPairingHandler(pin)
        val keyHex  = handler.privateKeyBytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_LT_PRIV_KEY, keyHex).apply()
        Log.d("AirPlay", "Generated new Ed25519 identity — stored for reuse across restarts")
        return handler.privateKeyBytes
    }

    enum class Status { Idle, Starting, Ready, Receiving, Stopping }

    /**
     * Loads the persisted pairing identity UUID from SharedPreferences, or generates and saves one.
     * Must stay constant across restarts — macOS uses `pi` to look up its cached pairing record.
     * A changing `pi` causes macOS to report "device found without pairing ID" and drop the device.
     */
    private fun loadOrGeneratePi(): String {
        val ctx = AndroidContextHolder.applicationContext
            ?: return UUID.randomUUID().toString()
        val prefs = ctx.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_PI, null)
        if (!stored.isNullOrEmpty()) return stored
        val newPi = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_PI, newPi).apply()
        Log.d("AirPlay", "Generated new pairing identity (pi=$newPi) — stored for reuse across restarts")
        return newPi
    }

    companion object {
        private const val PREF_LT_PRIV_KEY = "lt_priv_key_hex"
        private const val PREF_PI          = "lt_pi_uuid"
        private const val CONNECTION_APPROVAL_TIMEOUT_MS = 60_000L
    }
}
