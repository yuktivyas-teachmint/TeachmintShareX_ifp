package com.teachmint.sharex.airplay

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import com.dd.plist.PropertyListParser
import com.dd.plist.NSDictionary
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSNumber

/**
 * Unified AirPlay server — handles HTTP pairing and RTSP session management on one TCP port.
 *
 * AirPlay uses a single persistent TCP connection for ALL communication:
 *   - GET  /info       (RTSP/1.0) → device capabilities plist
 *   - POST /pair-setup (RTSP/1.0) → SRP6a pairing phases
 *   - POST /pair-verify(RTSP/1.0) → Curve25519 session key
 *   - ANNOUNCE / SETUP / RECORD / TEARDOWN (RTSP/1.0) → streaming session
 *
 * All requests arrive on the same TCP connection in sequence. The protocol version
 * in the request line is dynamically echoed back to the client to prevent connection drops.
 */
class AirPlayUnifiedServer(
    private val port: Int = AirPlayProtocol.HTTP_PORT,
    private val deviceInfo: AirPlayDeviceInfo,
    private val pairingHandler: AirPlayPairingHandler,
    private val onSessionReady: (RtspSession) -> Unit,
    private val onSessionEnded: (String) -> Unit,
    private val onMirrorStream: (clientId: String, clientName: String, nalFlow: SharedFlow<ByteArray>) -> Unit =
        { _, _, _ -> },
    private val onConnectionApprovalRequested: suspend (AirPlayConnectionRequest) -> Boolean = { true },
    private val onConnectionClosed: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val nextRtpPort = AtomicInteger(AirPlayProtocol.RTP_PORT_RANGE_START)
    /** TCP server for AirPlay 2 mirror data (type 110). macOS connects here after stream-level SETUP. */
    private var mirrorServerSocket: ServerSocket? = null

    /** FairPlay encryption params extracted from SETUP — needed for mirror decryption. */
    @Volatile private var mirrorEkey: ByteArray? = null
    @Volatile private var mirrorEiv: ByteArray? = null
    @Volatile private var mirrorEt: Int = 0
    @Volatile private var mirrorStreamConnectionID: Long = 0L
    /** Shared key (shk) from stream SETUP — used directly for et=3 mode (no FairPlay derivation). */
    @Volatile private var mirrorShk: ByteArray? = null
    /**
     * Per-connection snapshots captured at SETUP time — prevents race conditions when
     * multiple TCP connections do pair-verify/fp-setup concurrently (overwriting the
     * global pairingHandler.ecdhSharedSecret / AirPlayFairPlay.m3Data).
     */
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorM3Data: ByteArray? = null
    /** Cached raw FairPlay decrypted key from a matching ekey. */
    @Volatile private var cachedRawFairPlayKey: ByteArray? = null
    /** ekey associated with [cachedRawFairPlayKey]; prevents stale reuse across reconnects. */
    @Volatile private var cachedRawFairPlayKeySourceEkey: ByteArray? = null
    private val connectionTrackingLock = Any()
    private val socketsByClientId = mutableMapOf<String, MutableSet<Socket>>()
    private val addressesByClientId = mutableMapOf<String, MutableSet<String>>()
    private val mirrorSocketsByAddress = mutableMapOf<String, MutableSet<Socket>>()
    private val mirrorSocketsByClientId = mutableMapOf<String, MutableSet<Socket>>()
    private val activeClientNameById = mutableMapOf<String, String>()

    // ── per-connection session builder ─────────────────────────────────────────

    private data class SessionBuilder(
        val sessionId: String,
        val clientAddress: String,
        val clientId: String = "",
        val clientName: String = "",
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
            clientId      = clientId,
            clientName    = clientName,
            videoSdp      = videoSdp,
            audioSdp      = audioSdp,
        )
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

    suspend fun start() = withContext(Dispatchers.IO) {
        serverSocket = ServerSocket(port)
        // Mirror data TCP server — AirPlay 2 type 110 sends H.264 over TCP, not UDP RTP.
        mirrorServerSocket = ServerSocket(0) // OS-assigned port
        Log.d("AirPlay", "Server: listening on port $port, mirrorTCP=${mirrorServerSocket!!.localPort}")
        scope.launch {
            // Accept mirror-data TCP connections from macOS/iOS
            while (isActive) {
                val client = try { mirrorServerSocket?.accept() ?: break }
                    catch (_: SocketException) { break }
                val mirrorAddress = client.inetAddress.hostAddress ?: "unknown"
                registerMirrorSocket(clientAddress = mirrorAddress, socket = client)
                Log.d("AirPlay", "Mirror: TCP connection from $mirrorAddress")
                launch {
                    var mirrorClientId: String? = null
                    try {
                        val et = mirrorEt
                        Log.d("AirPlay", "Mirror: creating parser with et=$et, connID=$mirrorStreamConnectionID")
                        // Derive one or more candidate keys. The parser probes the first
                        // frame with fallbacks when the primary key is wrong.
                        val decryptionKeys: List<ByteArray> = when (et) {
                            0 -> {
                                Log.d("AirPlay", "Mirror: et=0 — no encryption")
                                emptyList()
                            }
                            3 -> {
                                // et=3: shared key mode — shk is the AES key directly
                                val shk = mirrorShk
                                if (shk != null) {
                                    Log.d("AirPlay", "Mirror: et=3 — using shk directly (${shk.size}B)")
                                    listOf(shk.copyOf(16.coerceAtMost(shk.size)))
                                } else {
                                    Log.w("AirPlay", "Mirror: et=3 but no shk available, trying FairPlay derivation")
                                    deriveStreamDecryptionKeyCandidates()
                                }
                            }
                            else -> deriveStreamDecryptionKeyCandidates()
                        }
                        val decryptionKey = decryptionKeys.firstOrNull()
                        val fallbackDecryptionKeys = if (decryptionKeys.size > 1) decryptionKeys.drop(1) else emptyList()
                        if (fallbackDecryptionKeys.isNotEmpty()) {
                            Log.d("AirPlay", "Mirror: prepared ${fallbackDecryptionKeys.size} fallback decrypt keys")
                        }
                        // Use per-connection ECDH snapshot; fall back to global handler if not captured
                        val ecdh = mirrorEcdhSecret ?: pairingHandler.ecdhSharedSecret
                        Log.d("AirPlay", "Mirror: ECDH source=${if (mirrorEcdhSecret != null) "per-conn snapshot" else "global handler"} (${ecdh?.size ?: 0}B)")
                        val parser = AirPlayMirrorStreamParser(
                            input = client.getInputStream(),
                            ecdhSecret = ecdh,
                            streamConnectionID = mirrorStreamConnectionID,
                            fairplayAesKey = decryptionKey,
                            fallbackFairplayAesKeys = fallbackDecryptionKeys,
                            fairplayStreamIv = mirrorEiv?.copyOf(),
                            encryptionType = et,
                        )
                        val (clientId, clientName) = resolveClientForMirrorAddress(mirrorAddress)
                        mirrorClientId = clientId
                        registerMirrorSocketForClient(clientId = clientId, socket = client)
                        onMirrorStream(clientId, clientName, parser.nalUnits)
                        parser.parse()   // blocks until stream ends
                        Log.d("AirPlay", "Mirror: connection ended")
                    } catch (e: Exception) {
                        Log.e("AirPlay", "Mirror: error: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        mirrorClientId?.let { clientId ->
                            unregisterMirrorSocketForClient(clientId = clientId, socket = client)
                        }
                        unregisterMirrorSocket(clientAddress = mirrorAddress, socket = client)
                        client.runCatching { close() }
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (e: SocketException) {
                    if (isActive) Log.e("AirPlay", "Server: accept error: ${e.message}")
                    break
                }
                launch { handleConnection(client) }
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        scope.cancel()
        val trackedSockets = synchronized(connectionTrackingLock) {
            val allSockets = buildSet {
                socketsByClientId.values.forEach { addAll(it) }
                mirrorSocketsByAddress.values.forEach { addAll(it) }
                mirrorSocketsByClientId.values.forEach { addAll(it) }
            }
            socketsByClientId.clear()
            addressesByClientId.clear()
            mirrorSocketsByAddress.clear()
            mirrorSocketsByClientId.clear()
            activeClientNameById.clear()
            allSockets
        }
        trackedSockets.forEach { socket ->
            socket.runCatching { close() }
        }
        serverSocket?.close()
        serverSocket = null
        mirrorServerSocket?.close()
        mirrorServerSocket = null
        Log.d("AirPlay", "Server: stopped")
    }

    fun disconnectClient(clientId: String) {
        val socketsToClose = synchronized(connectionTrackingLock) {
            val directSockets = socketsByClientId[clientId].orEmpty().toSet()
            val clientAddresses = addressesByClientId[clientId].orEmpty().toSet()
            val mirrorSockets = clientAddresses.flatMap { address ->
                mirrorSocketsByAddress[address].orEmpty()
            }.toSet()
            val mirrorSocketsByClient = mirrorSocketsByClientId[clientId].orEmpty().toSet()
            directSockets + mirrorSockets + mirrorSocketsByClient
        }
        socketsToClose.forEach { socket ->
            socket.runCatching { close() }
        }
        Log.d("AirPlay", "Server: requested disconnect for $clientId (sockets=${socketsToClose.size})")
    }

    // ── unified connection handler ─────────────────────────────────────────────

    private suspend fun handleConnection(socket: Socket) = withContext(Dispatchers.IO) {
        val clientAddr = socket.inetAddress.hostAddress ?: "unknown"
        runCatching {
            // Keep control sockets stable for long-running macOS extend-display sessions.
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.soTimeout = 0
        }.onFailure { error ->
            Log.w("AirPlay", "Server: socket tune failed for $clientAddr: ${error.message}")
        }
        val input  = socket.getInputStream()
        val output = socket.getOutputStream()
        val writer = PrintWriter(OutputStreamWriter(output, Charsets.UTF_8), true)
        var builder: SessionBuilder? = null
        var approvedConnection: AirPlayConnectionRequest? = null

        // Per-connection snapshots — captured after pair-verify and fp-setup so that
        // concurrent connections don't overwrite the values needed by THIS connection's SETUP.
        var connEcdhSecret: ByteArray? = null
        var connM3Data: ByteArray? = null

        suspend fun ensureConnectionApproved(
            headers: Map<String, String>,
            cseq: String,
            protocol: String,
        ): Boolean {
            if (approvedConnection != null) return true
            val request = resolveConnectionRequest(clientAddr = clientAddr, headers = headers)
            val approved = runCatching {
                onConnectionApprovalRequested(request)
            }.onFailure { error ->
                Log.e(
                    "AirPlay",
                    "Server: approval callback failed for ${request.clientId}: ${error.message}",
                )
            }.getOrDefault(false)

            if (approved) {
                approvedConnection = request
                registerApprovedConnection(
                    clientId = request.clientId,
                    clientName = request.clientName,
                    clientAddress = request.clientAddress,
                    socket = socket,
                )
                Log.d(
                    "AirPlay",
                    "Server: connection approved for ${request.clientId} (${request.clientName})",
                )
                return true
            }

            Log.d(
                "AirPlay",
                "Server: connection rejected for ${request.clientId} (${request.clientName})",
            )
            sendRtsp(writer, protocol, 403, "Forbidden", cseq)
            return false
        }

        Log.d("AirPlay", "Server: client connected: $clientAddr")
        try {
            // Read first non-empty request line
            var requestLine = ""
            while (requestLine.isEmpty() && socket.isConnected && !socket.isClosed) {
                requestLine = readRawLine(input).trim()
            }
            Log.d("AirPlay", "Server: first request line: '$requestLine'")

            while (requestLine.isNotEmpty() && socket.isConnected && !socket.isClosed) {
                val parts  = requestLine.split(" ")
                if (parts.size < 2) { Log.w("AirPlay", "Server: malformed request line: '$requestLine'"); break }
                val method   = parts[0]
                val uri      = parts.getOrElse(1) { "/" }.substringBefore("?")
                val protocol = parts.getOrElse(2) { "RTSP/1.0" }.trim() // Extract requested protocol

                val headers       = readRawHeaders(input)
                val cseq          = headers["cseq"] ?: "0"
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val contentType   = headers["content-type"] ?: "none"

                Log.d("AirPlay", "Server: >>> $method $uri ($protocol) CSeq=$cseq Content-Length=$contentLength Content-Type=$contentType")
                val isPairing = uri.startsWith("/pair") || uri == "/info" || uri == "/server-info"
                // Log all headers for every request to help diagnose protocol issues
                if (headers.isNotEmpty()) {
                    Log.d("AirPlay", "Server: headers for $method $uri:")
                    for ((k, v) in headers) Log.d("AirPlay", "  $k: $v")
                }
                val bodyBytes = readExactBytes(input, contentLength)
                if (contentLength > 0) {
                    // Full hex for pairing + small bodies; truncated for large RTSP bodies
                    if (isPairing || contentLength <= 256) {
                        Log.d("AirPlay", "Server: request body FULL (${bodyBytes.size}B): ${bodyBytes.toHex()}")
                    } else {
                        Log.d("AirPlay", "Server: body first 128B: ${bodyBytes.toHex(128)}")
                    }
                }

                when {
                    // ── info ───────────────────────────────────────────────────
                    method == "GET" && (uri == "/info" || uri == "/server-info") ->
                        sendHttpInfo(output, cseq, protocol)

                    // ── pairing ───────────────────────────────────────────────
                    method == "POST" && uri == "/pair-setup" -> {
                        val resp = pairingHandler.handlePairSetup(bodyBytes)
                        Log.d("AirPlay", "Server: pair-setup response FULL (${resp.size}B): ${resp.toHex()}")
                        sendHttpBinary(output, cseq, protocol, "application/octet-stream", resp)
                        Log.d("AirPlay", "Server: pair-setup response sent ✓")
                    }

                    method == "POST" && uri == "/pair-verify" -> {
                        val resp = pairingHandler.handlePairVerify(bodyBytes)
                        // Snapshot the ECDH secret for THIS connection before another connection can overwrite it
                        connEcdhSecret = pairingHandler.ecdhSharedSecret?.copyOf()
                        Log.d("AirPlay", "Server: pair-verify response FULL (${resp.size}B): ${resp.toHex()}")
                        Log.d("AirPlay", "Server: pair-verify → captured ECDH secret for $clientAddr (${connEcdhSecret?.size ?: 0}B)")
                        sendHttpBinary(output, cseq, protocol, "application/octet-stream", resp)
                        Log.d("AirPlay", "Server: pair-verify response sent ✓")
                    }

                    method == "POST" && uri == "/fp-setup" -> {
                        val resp = AirPlayFairPlay.handle(bodyBytes)
                        // Snapshot M3 only for phase 2 packets (164B body).
                        if (bodyBytes.size == 164) {
                            AirPlayFairPlay.m3Data?.let { m3 ->
                                connM3Data = m3.copyOf()
                                Log.d("AirPlay", "Server: fp-setup → captured M3 for $clientAddr (${m3.size}B)")
                            }
                        }
                        if (resp != null) {
                            Log.d("AirPlay", "Server: fp-setup → ${resp.size}B")
                            sendHttpBinary(output, cseq, protocol, "application/octet-stream", resp)
                        } else {
                            Log.w("AirPlay", "Server: fp-setup fallback → 200 empty")
                            sendHttpOk(output, cseq, protocol)
                        }
                    }

                    method == "POST" && uri in listOf("/pair-pin-start", "/feedback") -> {
                        Log.d("AirPlay", "Server: stub POST $uri → 200 OK")
                        sendHttpOk(output, cseq, protocol)
                    }

                    // ── RTSP session ──────────────────────────────────────────
                    method == AirPlayProtocol.RTSP_OPTIONS ->
                        sendRtsp(writer, protocol, 200, "OK", cseq, mapOf(
                            "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, " +
                                    "TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
                        ))

                    method == AirPlayProtocol.RTSP_ANNOUNCE -> {
                        if (!ensureConnectionApproved(headers = headers, cseq = cseq, protocol = protocol)) {
                            break
                        }
                        val (vSdp, aSdp) = parseSdpBoth(String(bodyBytes, Charsets.UTF_8))
                        val sid = generateSessionId()
                        builder = SessionBuilder(
                            sessionId     = sid,
                            clientAddress = clientAddr,
                            clientId      = approvedConnection?.clientId.orEmpty(),
                            clientName    = approvedConnection?.clientName.orEmpty(),
                            videoSdp      = vSdp,
                            audioSdp      = aSdp,
                        )
                        // New RTSP session: drop any previous mirror crypto state so we
                        // never decrypt with stale keys/connIDs after reconnects.
                        resetMirrorCryptoState("RTSP ANNOUNCE")
                        Log.d("AirPlay", "RTSP: ANNOUNCE video PT=${vSdp?.videoPayloadType} " +
                                "SPS=${vSdp?.sps?.size ?: 0}B  audio=${aSdp?.codec ?: "none"}")
                        sendRtsp(writer, protocol, 200, "OK", cseq)
                    }

                    method == AirPlayProtocol.RTSP_SETUP -> {
                        if (!ensureConnectionApproved(headers = headers, cseq = cseq, protocol = protocol)) {
                            break
                        }
                        val b = builder ?: SessionBuilder(
                            sessionId = generateSessionId(),
                            clientAddress = clientAddr,
                            clientId = approvedConnection?.clientId.orEmpty(),
                            clientName = approvedConnection?.clientName.orEmpty(),
                        ).also { builder = it }

                        if (contentType.contains("binary-plist", ignoreCase = true)) {
                            // AirPlay 2 / modern macOS sends two SETUP requests:
                            //   1st SETUP (session-level): large body, no "streams" key → eventPort + timingPort
                            //   2nd SETUP (stream-level): smaller body, has "streams" key → streams with dataPort
                            // Per UxPlay: eventPort=0 (unused in mirror mode), timingPort=0.
                            // Type 110 (mirroring) uses TCP for data, not UDP RTP.
                            // Parse plist once and classify SETUP by top-level structure.
                            // A raw hex substring search for "streams" is fragile and can
                            // misclassify session-level SETUPs, leading to stale mirror keys.
                            val parsedSetupDict = runCatching {
                                PropertyListParser.parse(bodyBytes) as? NSDictionary
                            }.getOrNull()
                            val isStreamSetup = parsedSetupDict?.get("streams") is NSArray

                            val mirrorPort = mirrorServerSocket?.localPort?.toLong() ?: 0L

                            val plist: ByteArray
                            if (!isStreamSetup) {
                                // Some macOS flows reconnect without sending ANNOUNCE first.
                                // On first session-level SETUP for this connection, drop stale
                                // crypto state from prior sessions before capturing new keys.
                                if (!b.videoSetup && !b.audioSetup) {
                                    resetMirrorCryptoState("RTSP SETUP(session) new connection")
                                }
                                // ── Session-level SETUP (1st) ──
                                // Also check for ekey/streamConnectionID here (some versions send it in session SETUP)
                                try {
                                    val sessDict = parsedSetupDict ?: (PropertyListParser.parse(bodyBytes) as? NSDictionary)
                                    Log.d("AirPlay", "RTSP: SETUP session plist keys: ${sessDict?.allKeys()?.toList()}")
                                    val ekeyData = sessDict?.get("ekey") as? NSData
                                    if (ekeyData != null) {
                                        mirrorEkey = ekeyData.bytes()
                                        // Snapshot per-connection ECDH + M3 at the point we receive ekey,
                                        // before another connection's pair-verify can overwrite them.
                                        mirrorEcdhSecret = connEcdhSecret ?: pairingHandler.ecdhSharedSecret?.copyOf()
                                        mirrorM3Data = connM3Data ?: AirPlayFairPlay.m3Data?.copyOf()
                                        Log.d("AirPlay", "RTSP: SETUP (session) extracted ekey (${mirrorEkey!!.size}B)")
                                        Log.d("AirPlay", "RTSP: SETUP (session) captured ECDH(${mirrorEcdhSecret?.size}B) M3(${mirrorM3Data?.size}B) for mirror decryption")
                                    }
                                    val eivData = sessDict?.get("eiv") as? NSData
                                    if (eivData != null) {
                                        mirrorEiv = eivData.bytes()
                                        Log.d("AirPlay", "RTSP: SETUP (session) extracted eiv (${mirrorEiv!!.size}B): ${mirrorEiv!!.joinToString("") { "%02x".format(it) }}")
                                    }
                                    val etNum = sessDict?.get("et") as? NSNumber
                                    if (etNum != null) {
                                        mirrorEt = etNum.intValue()
                                        Log.d("AirPlay", "RTSP: SETUP (session) encryption type et=$mirrorEt")
                                    }
                                } catch (e: Exception) {
                                    Log.w("AirPlay", "RTSP: session SETUP plist parse: ${e.message}")
                                }
                                b.videoSetup = true
                                Log.d("AirPlay", "RTSP: SETUP session-level → session=${b.sessionId}")
                                plist = AirPlayBinaryPlist.encode(
                                    mapOf(
                                        "eventPort"  to 0L,
                                        "timingPort" to 0L,
                                    )
                                )
                            } else {
                                // ── Stream-level SETUP (2nd) ──
                                // macOS wants to activate the video stream via TCP.
                                // Parse the binary plist to extract:
                                // 1) mirror-video crypto metadata (ekey/shk/connID), and
                                // 2) any additional audio-like stream entries so host audio can start.
                                val responseStreams = mutableListOf<Map<String, Any>>()
                                try {
                                    val setupDict = parsedSetupDict ?: (PropertyListParser.parse(bodyBytes) as? NSDictionary)
                                    Log.d("AirPlay", "RTSP: SETUP stream plist top keys: ${setupDict?.allKeys()?.toList()}")
                                    val streams = setupDict?.get("streams") as? NSArray
                                    if (streams != null && streams.count() > 0) {
                                        for (streamIndex in 0 until streams.count()) {
                                            val stream = streams.objectAtIndex(streamIndex) as? NSDictionary ?: continue
                                            Log.d("AirPlay", "RTSP: SETUP stream[$streamIndex] keys: ${stream.allKeys()?.toList()}")
                                            // Log all values for debugging
                                            for (key in stream.allKeys()) {
                                                val v = stream[key]
                                                when (v) {
                                                    is NSData -> Log.d("AirPlay", "  stream[$streamIndex][$key] = NSData(${v.bytes().size}B)")
                                                    is NSNumber -> Log.d("AirPlay", "  stream[$streamIndex][$key] = ${v.longValue()}")
                                                    else -> Log.d("AirPlay", "  stream[$streamIndex][$key] = $v (${v?.javaClass?.simpleName})")
                                                }
                                            }

                                            // Extract mirror-crypto keys if present.
                                            val ekeyData = stream.get("ekey") as? NSData
                                            if (ekeyData != null) {
                                                mirrorEkey = ekeyData.bytes()
                                                // Also capture ECDH + M3 snapshot if not already captured at session SETUP
                                                if (mirrorEcdhSecret == null) {
                                                    mirrorEcdhSecret = connEcdhSecret ?: pairingHandler.ecdhSharedSecret?.copyOf()
                                                    mirrorM3Data = connM3Data ?: AirPlayFairPlay.m3Data?.copyOf()
                                                }
                                                Log.d("AirPlay", "RTSP: SETUP stream extracted ekey (${mirrorEkey!!.size}B)")
                                            }
                                            // Extract shk (shared key) separately — used for et=3 mode
                                            val shkData = stream.get("shk") as? NSData
                                            if (shkData != null) {
                                                mirrorShk = shkData.bytes()
                                                Log.d("AirPlay", "RTSP: SETUP stream extracted shk (${mirrorShk!!.size}B): ${mirrorShk!!.joinToString("") { "%02x".format(it) }}")
                                            }
                                            if (ekeyData == null && shkData == null) {
                                                Log.d("AirPlay", "RTSP: SETUP stream no ekey/shk (using session-level ekey if available)")
                                            }
                                            val connId = stream.get("streamConnectionID") as? NSNumber
                                            if (connId != null) {
                                                mirrorStreamConnectionID = connId.longValue()
                                                Log.d("AirPlay", "RTSP: SETUP extracted streamConnectionID=$mirrorStreamConnectionID (unsigned=${mirrorStreamConnectionID.toULong()})")
                                            }
                                            // et may also appear in stream dict
                                            val streamEt = stream.get("et") as? NSNumber
                                            if (streamEt != null) {
                                                mirrorEt = streamEt.intValue()
                                                Log.d("AirPlay", "RTSP: SETUP stream encryption type et=$mirrorEt")
                                            }
                                            Log.d("AirPlay", "RTSP: SETUP summary: et=$mirrorEt, ekey=${mirrorEkey?.size}B, shk=${mirrorShk?.size}B, eiv=${mirrorEiv?.size}B, connID=$mirrorStreamConnectionID")

                                            val streamType = (stream.get("type") as? NSNumber)?.longValue() ?: -1L
                                            if (streamType == 110L) {
                                                b.videoSetup = true
                                                responseStreams += mapOf(
                                                    "type" to 110L,
                                                    "dataPort" to mirrorPort,
                                                )
                                                Log.d("AirPlay", "RTSP: stream[$streamIndex] type=110 (mirror video over TCP) dataPort=$mirrorPort")
                                                continue
                                            }

                                            // Non-110 streams are treated as audio-like RTP channels.
                                            // This covers clients that negotiate audio via stream plist
                                            // instead of legacy Transport headers.
                                            if (streamType > 0) {
                                                val serverRtp: Int
                                                val serverRtcp: Int
                                                if (!b.audioSetup) {
                                                    serverRtp = allocateRtpPort()
                                                    serverRtcp = serverRtp + 1
                                                    b.audioRtpPort = serverRtp
                                                    b.audioRtcpPort = serverRtcp
                                                    b.audioSetup = true
                                                    Log.d("AirPlay", "RTSP: stream[$streamIndex] type=$streamType mapped to audio RTP=$serverRtp")
                                                } else {
                                                    // Keep RTP ports stable across repeated SETUP retries.
                                                    serverRtp = b.audioRtpPort
                                                    serverRtcp = b.audioRtcpPort
                                                }

                                                if (b.audioSdp == null) {
                                                    b.audioSdp = inferAudioSdpFromStream(stream)
                                                    Log.d(
                                                        "AirPlay",
                                                        "RTSP: inferred audio SDP codec=${b.audioSdp?.codec} " +
                                                            "sr=${b.audioSdp?.sampleRate} ch=${b.audioSdp?.channels} " +
                                                            "encrypted=${(b.audioSdp?.aesKey != null)} et=${b.audioSdp?.encryptionType}",
                                                    )
                                                }
                                                // Keep the stream-level SETUP response minimal for audio:
                                                // macOS retries aggressively if extra/ambiguous fields are
                                                // echoed back for dynamic stream negotiation.
                                                val responseAudioStream = mutableMapOf<String, Any>(
                                                    "type" to streamType,
                                                    "dataPort" to serverRtp.toLong(),
                                                    "controlPort" to serverRtcp.toLong(),
                                                )
                                                val supportsDynamicStreamId =
                                                    (stream.get("supportsDynamicStreamID") as? NSNumber)?.intValue() == 1
                                                if (supportsDynamicStreamId) {
                                                    responseAudioStream["supportsDynamicStreamID"] = 1L
                                                    // Echo streamID only when the sender provided one.
                                                    // Fabricating a default stream ID can break macOS audio route setup.
                                                    (stream.get("streamID") as? NSNumber)?.longValue()?.let { streamId ->
                                                        responseAudioStream["streamID"] = streamId
                                                    }
                                                }
                                                responseStreams += responseAudioStream
                                            }
                                        }
                                    } else {
                                        Log.w("AirPlay", "RTSP: SETUP no 'streams' array in plist")
                                    }
                                } catch (e: Exception) {
                                    Log.w("AirPlay", "RTSP: SETUP plist parse error: ${e.message}")
                                }

                                // Only add the mirror-video stream when the sender didn't ask for
                                // any stream entries at all. Injecting type=110 into an audio-only
                                // SETUP response can make macOS retry audio route activation.
                                if (responseStreams.isEmpty()) {
                                    responseStreams += mapOf(
                                        "type" to 110L,
                                        "dataPort" to mirrorPort,
                                    )
                                }

                                Log.d("AirPlay", "RTSP: SETUP stream-level → mirrorTCP=$mirrorPort session=${b.sessionId}")

                                // Trigger incremental pipeline updates when stream-level setup
                                // allocates audio RTP ports. onSessionReady() is idempotent.
                                if (b.audioSetup && b.audioSdp != null) {
                                    onSessionReady(b.toSession())
                                }

                                plist = AirPlayBinaryPlist.encode(
                                    mapOf(
                                        "streams" to responseStreams,
                                    )
                                )
                            }
                            Log.d("AirPlay", "RTSP: SETUP bplist response ${plist.size}B (stream=$isStreamSetup)")
                            sendHttpRaw(output, cseq, protocol, 200, "OK",
                                "application/x-apple-binary-plist", plist,
                                mapOf("Session" to b.sessionId))
                        } else {
                            // Legacy RTSP SETUP with Transport header
                            val transport   = headers["transport"] ?: ""
                            val clientPorts = parseClientPorts(transport)
                            val isAudio     = uri.contains("audio", ignoreCase = true) ||
                                    uri.contains("stream=1", ignoreCase = true)
                            val serverRtp  = allocateRtpPort()
                            val serverRtcp = serverRtp + 1
                            if (isAudio && !b.audioSetup) {
                                b.audioRtpPort  = serverRtp
                                b.audioRtcpPort = serverRtcp
                                b.audioSetup    = true
                                Log.d("AirPlay", "RTSP: SETUP audio → server RTP=$serverRtp")
                                if (b.videoSetup) {
                                    // Audio may be negotiated after video; notify receiver so it
                                    // can start the missing audio pipeline for this existing session.
                                    onSessionReady(b.toSession())
                                }
                            } else if (!b.videoSetup) {
                                b.videoRtpPort  = serverRtp
                                b.videoRtcpPort = serverRtcp
                                b.videoSetup    = true
                                Log.d("AirPlay", "RTSP: SETUP video → server RTP=$serverRtp session=${b.sessionId}")
                                onSessionReady(b.toSession())
                            }
                            sendRtsp(writer, protocol, 200, "OK", cseq, mapOf(
                                "Session"   to b.sessionId,
                                "Transport" to "RTP/AVP/UDP;unicast;" +
                                        "client_port=$clientPorts;" +
                                        "server_port=$serverRtp-$serverRtcp",
                            ))
                        }
                    }

                    method == AirPlayProtocol.RTSP_RECORD -> {
                        if (!ensureConnectionApproved(headers = headers, cseq = cseq, protocol = protocol)) {
                            break
                        }
                        Log.d("AirPlay", "RTSP: RECORD → streaming (session=${builder?.sessionId})")
                        if (contentType.contains("binary-plist", ignoreCase = true)) {
                            val plist = AirPlayBinaryPlist.encode(emptyMap<String, Any>())
                            sendHttpRaw(output, cseq, protocol, 200, "OK",
                                "application/x-apple-binary-plist", plist,
                                mapOf("Session" to (builder?.sessionId ?: "")))
                        } else {
                        sendRtsp(writer, protocol, 200, "OK", cseq,
                            mapOf("Session" to (builder?.sessionId ?: "")))
                        }
                    }

                    method == AirPlayProtocol.RTSP_GET_PARAMETER -> {
                        val bodyStr = bodyBytes.toString(Charsets.UTF_8).trim()
                        if (bodyStr.contains("volume", ignoreCase = true)) {
                            // Sender fetches initial volume; return a valid value so Stage 2 proceeds.
                            val volumeBody = "volume: -20.0\r\n".toByteArray(Charsets.UTF_8)
                            sendHttpRaw(output, cseq, protocol, 200, "OK",
                                "text/parameters", volumeBody)
                        } else {
                            sendRtsp(writer, protocol, 200, "OK", cseq)
                        }
                    }

                    method == AirPlayProtocol.RTSP_SET_PARAMETER ->
                        sendRtsp(writer, protocol, 200, "OK", cseq)

                    method == AirPlayProtocol.RTSP_FLUSH ->
                        sendRtsp(writer, protocol, 200, "OK", cseq,
                            mapOf("Session" to (builder?.sessionId ?: "")))

                    method == AirPlayProtocol.RTSP_TEARDOWN -> {
                        val teardownInfo = parseTeardownInfo(contentType, bodyBytes)
                        val teardownStreamTypes = teardownInfo.streamTypes
                        val isStreamScopedTeardown = teardownStreamTypes.isNotEmpty() && (
                            teardownInfo.onlyTypeFields ||
                                teardownStreamTypes.none { it == 110L }
                            )
                        if (isStreamScopedTeardown) {
                            Log.d(
                                "AirPlay",
                                "RTSP: TEARDOWN stream-scoped types=$teardownStreamTypes " +
                                    "(session=${builder?.sessionId}) — keeping session alive",
                            )
                            sendRtsp(
                                writer,
                                protocol,
                                200,
                                "OK",
                                cseq,
                                mapOf("Session" to (builder?.sessionId ?: "")),
                            )
                        } else {
                            Log.d("AirPlay", "RTSP: TEARDOWN (session=${builder?.sessionId})")
                            sendRtsp(writer, protocol, 200, "OK", cseq)
                            builder?.sessionId?.let { onSessionEnded(it) }
                            builder = null
                            break
                        }
                    }

                    else -> {
                        // Different macOS versions can send optional control-plane commands
                        // (especially in Extend Display mode). Treat unknown commands as
                        // no-op stubs to avoid sender-side teardown cascades.
                        Log.w("AirPlay", "Server: unhandled request '$method $uri' ($protocol) → 200 stub")
                        if (protocol.startsWith("RTSP", ignoreCase = true)) {
                            sendRtsp(writer, protocol, 200, "OK", cseq)
                        } else {
                            sendHttpOk(output, cseq, protocol)
                        }
                    }
                }

                Log.d("AirPlay", "Server: waiting for next request from $clientAddr …")
                // Read next request line — readRawLine throws EOFException on disconnect
                do {
                    requestLine = readRawLine(input).trim()
                } while (requestLine.isEmpty() && socket.isConnected && !socket.isClosed)
                Log.d("AirPlay", "Server: next request line: '$requestLine'")
            }
            Log.d("AirPlay", "Server: request loop exited for $clientAddr (requestLine='$requestLine' connected=${socket.isConnected} closed=${socket.isClosed})")
        } catch (e: EOFException) {
            Log.d("AirPlay", "Server: $clientAddr closed connection (EOF): ${e.message}")
        } catch (e: SocketException) {
            if (e.message?.contains("closed") == false)
                Log.e("AirPlay", "Server: socket error from $clientAddr: ${e.message}")
            else
                Log.d("AirPlay", "Server: $clientAddr socket closed")
        } catch (e: Exception) {
            Log.e("AirPlay", "Server: error from $clientAddr: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            builder?.sessionId?.let { onSessionEnded(it) }
            approvedConnection?.let { request ->
                unregisterApprovedConnection(
                    clientId = request.clientId,
                    clientAddress = request.clientAddress,
                    socket = socket,
                )
                onConnectionClosed(request.clientId)
            }
            socket.runCatching { close() }
            Log.d("AirPlay", "Server: client disconnected: $clientAddr")
        }
    }

    // ── FairPlay key derivation ────────────────────────────────────────────────

    /**
     * Derives candidate 16-byte AES keys from FairPlay handshake data.
     *
     * Primary candidate uses the per-connection M3 snapshot. Additional candidates include
     * the global M3 singleton and a raw ekey prefix fallback for compatibility probing.
     */
    private fun deriveStreamDecryptionKeyCandidates(): List<ByteArray> {
        val ekey = mirrorEkey
        if (ekey == null) {
            Log.w("AirPlay", "Mirror: no ekey — stream SETUP incomplete, cannot decrypt")
            return emptyList()
        }

        val candidates = mutableListOf<ByteArray>()

        fun addCandidateChunk(chunk: ByteArray, label: String) {
            if (chunk.size != 16) return
            val alreadyPresent = candidates.any { it.contentEquals(chunk) }
            if (!alreadyPresent) {
                candidates += chunk
                Log.d("AirPlay", "Mirror: decrypt-key candidate[$label] ${chunk.size}B ${chunk.joinToString("") { "%02x".format(it) }}")
            }
        }

        fun addCandidate(blob: ByteArray?, label: String) {
            if (blob == null || blob.isEmpty()) return
            if (blob.size <= 16) {
                addCandidateChunk(blob.copyOf(16), "$label@0")
                return
            }

            var offset = 0
            while (offset + 16 <= blob.size) {
                addCandidateChunk(blob.copyOfRange(offset, offset + 16), "$label@$offset")
                offset += 16
            }
            // Also try the tail window for non-aligned blobs.
            val tailOffset = blob.size - 16
            addCandidateChunk(blob.copyOfRange(tailOffset, blob.size), "$label@$tailOffset")
        }

        // Reuse cached FairPlay key only when it was derived from the same ekey.
        val cached = cachedRawFairPlayKey
        val cachedForEkey = cachedRawFairPlayKeySourceEkey
        if (cached != null && cachedForEkey != null && cachedForEkey.contentEquals(ekey)) {
            Log.d("AirPlay", "Mirror: reusing cached raw FairPlay key (${cached.size}B) ${cached.joinToString("") { "%02x".format(it) }}")
            addCandidate(cached, "cached-fp")
        } else {
            if (cached != null) {
                Log.d("AirPlay", "Mirror: dropping stale cached FairPlay key (ekey changed across reconnect)")
            }
            cachedRawFairPlayKey = null
            cachedRawFairPlayKeySourceEkey = null

            val m3Snapshot = mirrorM3Data
            if (m3Snapshot != null) {
                Log.d("AirPlay", "Mirror: deriving FairPlay AES key from snapshot M3(${m3Snapshot.size}B) + ekey(${ekey.size}B)")
                val decrypted = PlayfairNative.decrypt(m3Snapshot, ekey)
                Log.d("AirPlay", "Mirror: snapshot-m3 decrypt result bytes=${decrypted?.size ?: 0}")
                if (decrypted != null && decrypted.isNotEmpty()) {
                    cachedRawFairPlayKey = decrypted.copyOf()
                    cachedRawFairPlayKeySourceEkey = ekey.copyOf()
                    Log.d("AirPlay", "Mirror: cached raw FairPlay key for reuse")
                }
                addCandidate(decrypted, "snapshot-m3")
            } else {
                Log.w("AirPlay", "Mirror: no per-connection M3 snapshot available")
            }

            val globalM3 = AirPlayFairPlay.m3Data
            if (globalM3 != null && (m3Snapshot == null || !globalM3.contentEquals(m3Snapshot))) {
                Log.d("AirPlay", "Mirror: deriving FairPlay AES key from global M3(${globalM3.size}B) + ekey(${ekey.size}B)")
                val decrypted = PlayfairNative.decrypt(globalM3, ekey)
                Log.d("AirPlay", "Mirror: global-m3 decrypt result bytes=${decrypted?.size ?: 0}")
                if (cachedRawFairPlayKey == null && decrypted != null && decrypted.isNotEmpty()) {
                    cachedRawFairPlayKey = decrypted.copyOf()
                    cachedRawFairPlayKeySourceEkey = ekey.copyOf()
                    Log.d("AirPlay", "Mirror: cached raw FairPlay key from global M3 derivation")
                }
                addCandidate(decrypted, "global-m3")
            }
        }

        // Compatibility fallback: some senders may effectively use ekey-derived bytes directly.
        if (ekey.size >= 16) {
            addCandidate(ekey, "ekey")
        }

        if (candidates.isEmpty()) {
            Log.w("AirPlay", "Mirror: failed to derive any decryption key candidates")
        }
        return candidates
    }

    private data class TeardownInfo(
        val streamTypes: Set<Long>,
        val onlyTypeFields: Boolean,
    )

    private fun parseTeardownInfo(
        contentType: String,
        bodyBytes: ByteArray,
    ): TeardownInfo {
        if (!contentType.contains("binary-plist", ignoreCase = true) || bodyBytes.isEmpty()) {
            return TeardownInfo(emptySet(), onlyTypeFields = false)
        }
        return runCatching {
            val dict = PropertyListParser.parse(bodyBytes) as? NSDictionary
                ?: return@runCatching TeardownInfo(emptySet(), onlyTypeFields = false)
            val streams = dict.get("streams") as? NSArray
                ?: return@runCatching TeardownInfo(emptySet(), onlyTypeFields = false)
            val types = mutableSetOf<Long>()
            var onlyTypeFields = true
            for (idx in 0 until streams.count()) {
                val stream = streams.objectAtIndex(idx) as? NSDictionary ?: continue
                val type = (stream.get("type") as? NSNumber)?.longValue() ?: continue
                types += type
                val keys = stream.allKeys()?.toList().orEmpty()
                if (keys.any { key -> key != "type" }) {
                    onlyTypeFields = false
                }
            }
            TeardownInfo(types, onlyTypeFields)
        }.getOrElse { error ->
            Log.w("AirPlay", "RTSP: TEARDOWN plist parse failed: ${error.message}")
            TeardownInfo(emptySet(), onlyTypeFields = false)
        }
    }

    private fun resetMirrorCryptoState(reason: String) {
        mirrorEkey = null
        mirrorEiv = null
        mirrorShk = null
        mirrorEcdhSecret = null
        mirrorM3Data = null
        cachedRawFairPlayKey = null
        cachedRawFairPlayKeySourceEkey = null
        mirrorEt = 0
        mirrorStreamConnectionID = 0L
        Log.d("AirPlay", "Mirror: reset crypto state ($reason)")
    }

    // ── HTTP-style response helpers ────────────────────────────────────────────

    private fun sendHttpInfo(output: OutputStream, cseq: String, protocol: String) {
        val infoMap = mapOf(
            "deviceid"    to deviceInfo.deviceId,
            "features"    to AirPlayProtocol.FEATURES_LOW,
            "featuresEx"  to AirPlayProtocol.FEATURES_HIGH,
            "name"        to deviceInfo.name,
            "model"       to AirPlayProtocol.MODEL,
            "pi"          to deviceInfo.pi,
            // pk as hex string intentionally: correct <data> format triggers encrypted system-pairing
            // which we don't implement; hex string causes the Mac to fall back to transient pair-setup.
            "pk"          to deviceInfo.publicKeyHex,
            "protovers"   to AirPlayProtocol.PROTO_VERS,
            "srcvers"     to AirPlayProtocol.SRC_VERS,
            // Additional AirPlay 2 capability hints used by macOS when bringing up
            // remote audio I/O for mirrored sessions.
            "manufacturer" to AirPlayProtocol.MANUFACTURER,
            "sourceVersion" to AirPlayProtocol.SRC_VERS,
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to true,
            "nameIsFactoryDefault" to false,
            "vv"          to AirPlayProtocol.VV.toInt(),
            "statusFlags" to 4,   // 0x4 = unlocked/available; 0 = locked/requires PIN prompt
            "audioLatencies" to listOf(
                mapOf(
                    "type" to 100,
                    "audioType" to "default",
                    "inputLatencyMicros" to 0L,
                    "outputLatencyMicros" to 400_000L,
                ),
                mapOf(
                    "type" to 100,
                    "audioType" to "media",
                    "inputLatencyMicros" to 0L,
                    "outputLatencyMicros" to 400_000L,
                ),
            ),
            "audioFormats" to listOf(
                mapOf(
                    // Bitmask of supported AirPlay audio formats.
                    // Includes AAC-LC and AAC-ELD variants used by screen mirroring.
                    "type" to 100,
                    "audioInputFormats" to 67_108_860L,
                    "audioOutputFormats" to 67_108_860L,
                ),
            ),
            // macOS reads display capabilities from /info to set up the screen stream.
            // Without this, APEndpointDescriptionAirPlay reports "Display description count: 0"
            // and screen activation fails with error -16760.
            "displays"    to listOf(
                mapOf(
                    "uuid"           to AirPlayProtocol.DISPLAY_UUID,
                    "width"          to 1920,
                    "height"         to 1080,
                    "widthPhysical"  to 0,
                    "heightPhysical" to 0,
                    "widthPixels"    to 1920,
                    "heightPixels"   to 1080,
                    "refreshRate"    to 60.0,
                    "features"       to 14,
                    "overscanned"    to false,
                )
            ),
        )
        val plist = AirPlayBinaryPlist.encode(infoMap)
        Log.d("AirPlay", "Server: GET /info → 200 (bplist ${plist.size}B)")
        sendHttpRaw(output, cseq, protocol, 200, "OK", "application/x-apple-binary-plist", plist)
    }

    private fun sendHttpBinary(
        output: OutputStream,
        cseq: String,
        protocol: String,
        contentType: String,
        body: ByteArray,
    ) {
        if (body.isEmpty()) {
            sendHttpRaw(output, cseq, protocol, 200, "OK", null, ByteArray(0))
        } else {
            sendHttpRaw(output, cseq, protocol, 200, "OK", contentType, body)
        }
    }

    private fun sendHttpOk(output: OutputStream, cseq: String, protocol: String) =
        sendHttpRaw(output, cseq, protocol, 200, "OK", null, ByteArray(0))

    private fun sendHttpRaw(
        output: OutputStream,
        cseq: String,
        protocol: String,
        code: Int,
        reason: String,
        contentType: String?,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val sb = StringBuilder()
        sb.append("$protocol $code $reason\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("Server: AirTunes/${AirPlayProtocol.SRC_VERS}\r\n")
        for ((k, v) in extraHeaders) sb.append("$k: $v\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        // Keep-alive so RTSP requests can follow on the same connection
        sb.append("Connection: keep-alive\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    // ── RTSP response helper ──────────────────────────────────────────────────

    private fun sendRtsp(
        writer: PrintWriter,
        protocol: String,
        code: Int,
        reason: String,
        cseq: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        writer.print("$protocol $code $reason\r\n")
        writer.print("CSeq: $cseq\r\n")
        writer.print("Server: AirTunes/${AirPlayProtocol.SRC_VERS}\r\n")
        for ((k, v) in headers) writer.print("$k: $v\r\n")
        writer.print("\r\n")
        writer.flush()
    }

    // ── plist builder ─────────────────────────────────────────────────────────

    private fun buildXmlPlist(values: Map<String, Any>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        append("<plist version=\"1.0\">\n")
        appendPlistDict(values, "")
        append("</plist>")
    }

    private fun StringBuilder.appendPlistDict(values: Map<String, Any>, indent: String) {
        append("$indent<dict>\n")
        for ((key, value) in values) {
            append("$indent    <key>$key</key>\n")
            appendPlistValue(value, "$indent    ")
        }
        append("$indent</dict>\n")
    }

    private fun StringBuilder.appendPlistValue(value: Any, indent: String) {
        when (value) {
            is ByteArray -> append("$indent<data>${Base64.getEncoder().encodeToString(value)}</data>\n")
            is String    -> append("$indent<string>$value</string>\n")
            is Int       -> append("$indent<integer>$value</integer>\n")
            is Long      -> append("$indent<integer>$value</integer>\n")
            is Double    -> append("$indent<real>$value</real>\n")
            is Float     -> append("$indent<real>$value</real>\n")
            is Boolean   -> append("$indent<${if (value) "true" else "false"}/>\n")
            is List<*>   -> {
                append("$indent<array>\n")
                for (item in value) { if (item != null) appendPlistValue(item, "$indent    ") }
                append("$indent</array>\n")
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                appendPlistDict(value as Map<String, Any>, indent)
            }
            else         -> append("$indent<string>$value</string>\n")
        }
    }

    // ── SDP parser ────────────────────────────────────────────────────────────

    private fun parseSdpBoth(sdpBody: String): Pair<AirPlaySdp?, AirPlayAudioSdp?> {
        var inVideo = false; var inAudio = false
        var vPT = 96; var vClock = 90000
        var sps: ByteArray? = null; var pps: ByteArray? = null
        var aPT = 96; var aSR = 44100; var aCh = 2
        var aCodec = "MPEG4-GENERIC"; var aConfig: ByteArray? = null

        for (raw in sdpBody.lines()) {
            val line = raw.trim()
            if (line.startsWith("m=video")) { inVideo = true;  inAudio = false; continue }
            if (line.startsWith("m=audio")) { inAudio = true;  inVideo = false; continue }
            when {
                inVideo && line.startsWith("a=rtpmap:") && line.contains("H264", ignoreCase = true) ->
                    Regex("""a=rtpmap:(\d+)\s+H264/(\d+)""", RegexOption.IGNORE_CASE).find(line)?.let {
                        vPT = it.groupValues[1].toInt(); vClock = it.groupValues[2].toInt()
                    }

                inVideo && line.startsWith("a=fmtp:") && line.contains("sprop-parameter-sets") -> {
                    val sets = line.substringAfter("sprop-parameter-sets=").substringBefore(";").split(",")
                    sps = sets.getOrNull(0)?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
                    pps = sets.getOrNull(1)?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }
                }

                inAudio && line.startsWith("a=rtpmap:") ->
                    Regex("""a=rtpmap:(\d+)\s+(\S+)/(\d+)(?:/(\d+))?""").find(line)?.let {
                        aPT    = it.groupValues[1].toInt()
                        aCodec = it.groupValues[2]
                        aSR    = it.groupValues[3].toIntOrNull() ?: 44100
                        aCh    = it.groupValues[4].toIntOrNull() ?: 2
                    }

                inAudio && line.startsWith("a=fmtp:") && line.contains("config=") -> {
                    val hex = line.substringAfter("config=").substringBefore(";").trim()
                    if (hex.isNotEmpty())
                        aConfig = runCatching {
                            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        }.getOrNull()
                }
            }
        }

        val videoSdp = AirPlaySdp(vPT, vClock, sps, pps)
        val audioSdp = if (inAudio) AirPlayAudioSdp(aPT, aSR, aCh, aCodec, aConfig) else null
        return videoSdp to audioSdp
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun registerApprovedConnection(
        clientId: String,
        clientName: String,
        clientAddress: String,
        socket: Socket,
    ) {
        synchronized(connectionTrackingLock) {
            val clientSockets = socketsByClientId.getOrPut(clientId) { mutableSetOf() }
            clientSockets.add(socket)
            val clientAddresses = addressesByClientId.getOrPut(clientId) { mutableSetOf() }
            clientAddresses.add(clientAddress)
            activeClientNameById[clientId] = clientName
        }
    }

    private fun unregisterApprovedConnection(
        clientId: String,
        clientAddress: String,
        socket: Socket,
    ) {
        synchronized(connectionTrackingLock) {
            socketsByClientId[clientId]?.let { sockets ->
                sockets.remove(socket)
                if (sockets.isEmpty()) {
                    socketsByClientId.remove(clientId)
                }
            }
            addressesByClientId[clientId]?.let { addresses ->
                addresses.remove(clientAddress)
                if (addresses.isEmpty()) {
                    addressesByClientId.remove(clientId)
                    activeClientNameById.remove(clientId)
                }
            }
        }
    }

    private fun registerMirrorSocket(clientAddress: String, socket: Socket) {
        synchronized(connectionTrackingLock) {
            mirrorSocketsByAddress
                .getOrPut(clientAddress) { mutableSetOf() }
                .add(socket)
        }
    }

    private fun unregisterMirrorSocket(clientAddress: String, socket: Socket) {
        synchronized(connectionTrackingLock) {
            mirrorSocketsByAddress[clientAddress]?.let { sockets ->
                sockets.remove(socket)
                if (sockets.isEmpty()) {
                    mirrorSocketsByAddress.remove(clientAddress)
                }
            }
        }
    }

    private fun registerMirrorSocketForClient(clientId: String, socket: Socket) {
        synchronized(connectionTrackingLock) {
            mirrorSocketsByClientId
                .getOrPut(clientId) { mutableSetOf() }
                .add(socket)
        }
    }

    private fun unregisterMirrorSocketForClient(clientId: String, socket: Socket) {
        synchronized(connectionTrackingLock) {
            mirrorSocketsByClientId[clientId]?.let { sockets ->
                sockets.remove(socket)
                if (sockets.isEmpty()) {
                    mirrorSocketsByClientId.remove(clientId)
                }
            }
        }
    }

    private fun resolveClientForMirrorAddress(clientAddress: String): Pair<String, String> {
        synchronized(connectionTrackingLock) {
            val match = addressesByClientId.entries.firstOrNull { (_, addresses) ->
                addresses.contains(clientAddress)
            } ?: return "airplay:$clientAddress" to "AirPlay ($clientAddress)"
            val clientId = match.key
            val clientName = activeClientNameById[clientId].orEmpty()
            return clientId to if (clientName.isNotBlank()) clientName else "AirPlay ($clientAddress)"
        }
    }

    private fun resolveConnectionRequest(
        clientAddr: String,
        headers: Map<String, String>,
    ): AirPlayConnectionRequest {
        val deviceIdHeader = headers["x-apple-device-id"]?.trim()?.takeIf { it.isNotBlank() }
        val userAgent = headers["user-agent"]?.trim()?.takeIf { it.isNotBlank() }
        val clientId = buildAirPlayClientId(clientAddr = clientAddr, deviceId = deviceIdHeader)
        val clientName = buildAirPlayClientName(clientAddr = clientAddr, userAgent = userAgent)
        return AirPlayConnectionRequest(
            clientId = clientId,
            clientName = clientName,
            clientAddress = clientAddr,
            userAgent = userAgent,
        )
    }

    private fun buildAirPlayClientId(clientAddr: String, deviceId: String?): String {
        val normalizedDeviceId = deviceId
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9:_-]"), "")
            ?.takeIf { it.isNotBlank() }
        if (normalizedDeviceId != null) {
            return "airplay:$normalizedDeviceId"
        }
        val normalizedAddress = clientAddr
            .lowercase()
            .replace(Regex("[^a-z0-9:_-]"), "-")
            .ifBlank { "unknown" }
        return "airplay:$normalizedAddress"
    }

    private fun buildAirPlayClientName(clientAddr: String, userAgent: String?): String {
        val normalizedAgent = userAgent?.lowercase().orEmpty()
        val deviceLabel = when {
            normalizedAgent.contains("iphone") -> "iPhone"
            normalizedAgent.contains("ipad") -> "iPad"
            normalizedAgent.contains("mac") -> "Mac"
            normalizedAgent.contains("android") -> "Android"
            else -> null
        }
        return deviceLabel?.let { "AirPlay ($it)" } ?: "AirPlay ($clientAddr)"
    }

    private fun parseClientPorts(transport: String): String =
        Regex("""client_port=(\d+-\d+)""").find(transport)?.groupValues?.get(1) ?: "0-1"

    /**
     * Build a best-effort audio SDP from stream-level SETUP metadata when ANNOUNCE
     * does not include an m=audio section (common in modern AirPlay mirror mode).
     */
    private fun inferAudioSdpFromStream(stream: NSDictionary): AirPlayAudioSdp {
        val payloadType = (stream.get("type") as? NSNumber)?.intValue() ?: 96
        val sampleRate = (stream.get("sr") as? NSNumber)?.intValue() ?: 44100
        val compressionType = (stream.get("ct") as? NSNumber)?.intValue() ?: -1
        val samplesPerFrame = (stream.get("spf") as? NSNumber)?.intValue() ?: 480

        val streamShk = (stream.get("shk") as? NSData)?.bytes()
        val streamEncryptionType = (stream.get("et") as? NSNumber)?.intValue()
        val streamEkey = (stream.get("ekey") as? NSData)?.bytes()
        // Only inherit mirrorEt if the audio stream actually declares at least one
        // encryption-related field (et, shk, ekey).  When none are present the
        // audio is unencrypted — do NOT inherit the video's et=32.
        val hasOwnEncryptionInfo =
            streamEncryptionType != null || streamShk != null || streamEkey != null
        val encryptionType = if (hasOwnEncryptionInfo) (streamEncryptionType ?: mirrorEt) else 0
        Log.d(
            "AirPlay",
            "AudioSDP: stream dict keys=${stream.allKeys().map { it.toString() }} " +
                "hasOwnCrypto=$hasOwnEncryptionInfo streamEt=$streamEncryptionType",
        )

        // Always build key candidates from the session's FairPlay context.
        // UxPlay confirmed: audio uses the RAW FairPlay key for AES-CBC, NOT the
        // ECDH-mixed eaeskey. Put raw FP key first (index 0) for forced CBC.
        val rawKeyCandidates: List<ByteArray> = buildList {
                fun addUnique(key: ByteArray, label: String) {
                    val chunk = key.copyOf(16)
                    if (none { it.contentEquals(chunk) }) {
                        add(chunk)
                        Log.d("AirPlay", "AudioSDP: key candidate [$label] ${chunk.joinToString("") { "%02x".format(it) }}")
                    }
                }

                // Derive FairPlay key candidates from M3+ekey
                val fairplayKeys = deriveStreamDecryptionKeyCandidates()
                val rawFpKey = fairplayKeys.firstOrNull()
                val ecdh = mirrorEcdhSecret ?: pairingHandler.ecdhSharedSecret

                // 1. Raw FairPlay key — highest priority for audio.
                //    UxPlay uses aeskey (= fairplay_decrypt(ekey)) directly for
                //    AES-CBC audio decryption. NOT the SHA-512 derived eaeskey.
                if (rawFpKey != null && rawFpKey.size >= 16) {
                    addUnique(rawFpKey, "rawFpKey")
                }

                // 2. ECDH-mixed key (eaeskey) as fallback.
                //    eaeskey = SHA512(rawKey || ecdhSecret)[0..15]
                if (rawFpKey != null && ecdh != null && ecdh.isNotEmpty()) {
                    try {
                        val sha512 = java.security.MessageDigest.getInstance("SHA-512")
                        sha512.update(rawFpKey, 0, rawFpKey.size.coerceAtMost(16))
                        sha512.update(ecdh, 0, ecdh.size.coerceAtMost(32))
                        val eaeskey = sha512.digest().copyOf(16)
                        addUnique(eaeskey, "eaeskey")

                        // 3. Stream-key derivation = SHA512("AirPlayStreamKey{connID}" || eaeskey)[0..15]
                        val connIdStr = mirrorStreamConnectionID.toULong().toString()
                        sha512.reset()
                        sha512.update("AirPlayStreamKey$connIdStr".toByteArray(Charsets.UTF_8))
                        sha512.update(eaeskey, 0, 16)
                        addUnique(sha512.digest().copyOf(16), "streamKey")
                    } catch (e: Exception) {
                        Log.w("AirPlay", "AudioSDP: failed to derive ECDH-mixed keys: ${e.message}")
                    }
                }

                // 3. Stream's own shk (for et=3 mode)
                if (streamShk != null && streamShk.size >= 16) {
                    addUnique(streamShk, "stream-shk")
                }
                // 4. Session-level shk
                val sessionShk = mirrorShk
                if (sessionShk != null && sessionShk.size >= 16) {
                    addUnique(sessionShk, "session-shk")
                }
                // 5. Raw FairPlay key and ekey-derived fallback chunks
                for ((i, key) in fairplayKeys.withIndex()) {
                    addUnique(key, "fairplay-$i")
                }
        }
        // UxPlay confirms: screen mirroring audio IS encrypted with AES-CBC using the
        // raw FairPlay key + raw eiv. Inherit mirrorEt when key candidates exist.
        val effectiveEt = if (rawKeyCandidates.isNotEmpty() && (mirrorEt ?: 0) > 0) (mirrorEt ?: 0) else encryptionType
        Log.d("AirPlay", "AudioSDP: et=$encryptionType effectiveEt=$effectiveEt hasOwnCrypto=$hasOwnEncryptionInfo " +
            "streamEt=$streamEncryptionType mirrorEt=$mirrorEt hasShk=${streamShk != null} " +
            "candidates=${rawKeyCandidates.size}")

        val derivedAesKey = rawKeyCandidates.firstOrNull()
        val rawEiv = mirrorEiv?.takeIf { it.size >= 16 }?.copyOf(16)

        // Build IV candidates. UxPlay confirms audio uses raw eiv (session SETUP),
        // NOT the derived streamIV (which is for video CTR mode).
        // Put rawEiv first since that's what UxPlay uses for AES-CBC audio.
        val ivCandidates: List<ByteArray> = buildList {
            fun addIvUnique(iv: ByteArray, label: String) {
                if (iv.size < 16) return
                val chunk = iv.copyOf(16)
                if (none { it.contentEquals(chunk) }) {
                    add(chunk)
                    Log.d("AirPlay", "AudioSDP: IV candidate [$label] ${chunk.joinToString("") { "%02x".format(it) }}")
                }
            }

            // 1. Raw eiv from session SETUP — primary for audio (per UxPlay)
            if (rawEiv != null) {
                addIvUnique(rawEiv, "rawEiv")
            }

            // 2. Derived streamIV as fallback
            val rawFpKey = deriveStreamDecryptionKeyCandidates().firstOrNull()
            val ecdh = mirrorEcdhSecret ?: pairingHandler.ecdhSharedSecret
            if (rawFpKey != null && ecdh != null && ecdh.isNotEmpty()) {
                try {
                    val sha512 = java.security.MessageDigest.getInstance("SHA-512")
                    sha512.update(rawFpKey, 0, rawFpKey.size.coerceAtMost(16))
                    sha512.update(ecdh, 0, ecdh.size.coerceAtMost(32))
                    val eaeskey = sha512.digest().copyOf(16)

                    val connIdStr = mirrorStreamConnectionID.toULong().toString()
                    sha512.reset()
                    sha512.update("AirPlayStreamIV$connIdStr".toByteArray(Charsets.UTF_8))
                    sha512.update(eaeskey, 0, 16)
                    addIvUnique(sha512.digest().copyOf(16), "streamIV")
                } catch (e: Exception) {
                    Log.w("AirPlay", "AudioSDP: failed to derive streamIV: ${e.message}")
                }
            }
        }
        val derivedAesIv = ivCandidates.firstOrNull() ?: rawEiv
        Log.d("AirPlay", "AudioSDP: IV candidates=${ivCandidates.size} primary=${derivedAesIv?.joinToString("") { "%02x".format(it) }}")

        val channelsHint = (stream.get("channels") as? NSNumber)?.intValue()
            ?: (stream.get("ch") as? NSNumber)?.intValue()
            ?: 2
        val channels = when (channelsHint) {
            1 -> 1
            else -> 2
        }
        val audioFormat = (stream.get("audioFormat") as? NSNumber)?.longValue() ?: 0L
        // AirPlay stream-level SETUP carries an audioFormat bitfield. The value 0x1000000
        // (16777216) is AAC-ELD 44.1k/2ch, not ALAC.
        val (codec, mappedSampleRate, mappedChannels) = when (audioFormat) {
            // ALAC variants
            0x0004_0000L, // ALAC / 44.1k / 16-bit / 2ch
            0x0008_0000L, // ALAC / 44.1k / 24-bit / 2ch
            0x0010_0000L, // ALAC / 48k   / 16-bit / 2ch
            0x0020_0000L, // ALAC / 48k   / 24-bit / 2ch
            -> Triple("AppleLossless", sampleRate, channels)

            // AAC-LC variants
            0x0040_0000L -> Triple("MPEG4-GENERIC", 44_100, 2)
            0x0080_0000L -> Triple("MPEG4-GENERIC", 48_000, 2)

            // AAC-ELD variants (used by AirPlay mirroring on macOS/iOS)
            0x0100_0000L -> Triple("AAC-ELD", 44_100, 2)
            0x0200_0000L -> Triple("AAC-ELD", 48_000, 2)
            0x0400_0000L -> Triple("AAC-ELD", 16_000, 1)
            0x0800_0000L -> Triple("AAC-ELD", 24_000, 1)
            0x1000_0000L -> Triple("AAC-ELD", 48_000, 1)
            0x2000_0000L -> Triple("AAC-ELD", 32_000, 1)
            0x4000_0000L -> Triple("AAC-ELD", 44_100, 1)
            0x8000_0000L -> Triple("AAC-ELD", 44_100, 1)

            // Unknown/legacy values: fall back to compression-type hint.
            else -> {
                val hintedCodec = when (compressionType) {
                    // ct=8 is AAC-ELD in AirPlay metadata.
                    8 -> "AAC-ELD"
                    // ct=2 is Apple Lossless in AirPlay metadata.
                    2 -> "AppleLossless"
                    else -> "MPEG4-GENERIC"
                }
                Triple(hintedCodec, sampleRate, channels)
            }
        }
        return AirPlayAudioSdp(
            payloadType = payloadType,
            sampleRate = mappedSampleRate,
            channels = mappedChannels,
            codec = codec,
            codecConfig = null,
            aesKey = derivedAesKey,
            aesKeyCandidates = rawKeyCandidates,
            aesIv = derivedAesIv,
            aesIvCandidates = ivCandidates,
            encryptionType = effectiveEt,
            samplesPerFrame = samplesPerFrame,
        )
    }

    private fun allocateRtpPort(): Int {
        val p = nextRtpPort.getAndAdd(2)
        if (p > AirPlayProtocol.RTP_PORT_RANGE_END) nextRtpPort.set(AirPlayProtocol.RTP_PORT_RANGE_START)
        return p
    }

    private fun generateSessionId(): String =
        (System.currentTimeMillis() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

    private fun readRawLine(input: InputStream): String {
        val buf = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1) {
                // Remote closed the connection — throw so the caller exits cleanly
                // instead of looping forever on repeated empty reads.
                if (buf.isEmpty()) throw EOFException("Connection closed by remote")
                break
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.add(b.toByte())
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    private fun readRawHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readRawLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    private fun readExactBytes(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = input.read(buf, read, n - read)
            if (got < 0) break
            read += got
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    /** Returns up to [maxBytes] bytes as a hex string for logging. Returns full hex when maxBytes >= size. */
    private fun ByteArray.toHex(maxBytes: Int = size): String =
        take(maxBytes).joinToString("") { "%02x".format(it) } +
                if (size > maxBytes) "…(${size}B total)" else ""
}
