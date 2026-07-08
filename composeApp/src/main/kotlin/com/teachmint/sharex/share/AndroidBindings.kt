package com.teachmint.sharex.share.shared

import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.teachmint.sharex.share.host.DefaultHostController
import com.teachmint.sharex.airplay.AirPlayReceiver
import com.teachmint.sharex.airplay.AirPlayReceiverHolder
import com.example.teachmintsharex.share.miracast.MiracastDiscoveryService
import com.example.teachmintsharex.share.miracast.MiracastMiceServer
import com.example.teachmintsharex.share.miracast.MiracastPlaybackManager
import com.example.teachmintsharex.share.miracast.MiracastPorts
import com.example.teachmintsharex.share.miracast.MiracastSsdpService
import com.example.teachmintsharex.share.miracast.MiracastRtspServer
import com.example.teachmintsharex.share.dial.DialHttpServer
import com.example.teachmintsharex.share.wifidirect.WifiDirectManager
import com.example.teachmintsharex.share.wifidirect.WifiDirectPermissionHelper
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val MIRACAST_PREF_FILE = "miracast"
private const val MIRACAST_DEVICE_UUID_KEY = "device_uuid"
private const val MIRACAST_DEVICE_SOURCE_ID_KEY = "device_uuid_source_id"
private const val MIRACAST_DEVICE_ADVERTISED_NAME_KEY = "device_uuid_advertised_name"

fun getAppRole(): AppRole {
    return when (getAndroidHostAccessState()) {
        AndroidHostAccessState.HOST_ALLOWED -> AppRole.Host
        AndroidHostAccessState.HOST_REQUIRES_SUBSCRIPTION -> AppRole.SubscriptionRequired
        // Host-only app: non-IFP hardware is gated behind the subscription screen.
        AndroidHostAccessState.CLIENT -> AppRole.SubscriptionRequired
    }
}

fun createHostController(
    scope: CoroutineScope,
    discoveryService: DiscoveryService,
    webRtcEngine: WebRtcEngine,
    iceServers: List<IceServerConfig>,
): HostController {
    // Create Miracast over Infrastructure discovery for Windows+K/Windows+P support
    val context = AndroidContextHolder.applicationContext
    val miracastMdnsService = if (context != null) {
        MiracastDiscoveryService(context)
    } else {
        null
    }

    // Optional SSDP discovery compatibility for devices scanning UPnP endpoints.
    val miracastSsdpService = if (context != null) {
        MiracastSsdpService(context)
    } else {
        null
    }

    // Create RTSP server for Miracast handshake
    lateinit var miracastRtspServer: MiracastRtspServer
    var sourceRtspRetryJob: Job? = null
    var sourceRtspRetryTarget: String? = null

    fun cancelSourceRtspRetry(reason: String) {
        val target = sourceRtspRetryTarget
        val job = sourceRtspRetryJob
        if (job == null && target == null) return

        println("MIRACAST: 🧹 Clearing RTSP fallback target ${target ?: "unknown"} ($reason)")
        job?.cancel()
        sourceRtspRetryJob = null
        sourceRtspRetryTarget = null
    }

    suspend fun waitForRtspHandshake(timeoutMs: Long = 3_500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (MiracastPlaybackManager.state.value.isSessionActive ||
                miracastRtspServer.hasRecentRtspActivity(withinMs = timeoutMs + 1_000)
            ) {
                return true
            }
            delay(250)
        }

        return MiracastPlaybackManager.state.value.isSessionActive ||
            miracastRtspServer.hasRecentRtspActivity(withinMs = timeoutMs + 1_000)
    }

    fun scheduleSourceRtspRetry(
        sourceAddress: String,
        sourcePort: Int,
        reason: String,
        initialDelayMs: Long = 0L,
        maxAttempts: Int = 12,
        replaceExisting: Boolean = true,
    ) {
        val normalizedAddress = sourceAddress.substringBefore('%').trim()
        if (normalizedAddress.isBlank()) return

        val boundedPort = sourcePort.coerceIn(1, 0xFFFF)
        val targetKey = "$normalizedAddress:$boundedPort"
        val existingJob = sourceRtspRetryJob
        val existingTarget = sourceRtspRetryTarget

        if (existingJob?.isActive == true) {
            if (existingTarget == targetKey) {
                println("MIRACAST: ℹ️ RTSP fallback already scheduled for $targetKey ($reason)")
                return
            }
            if (!replaceExisting) {
                println(
                    "MIRACAST: ℹ️ Keeping existing RTSP fallback target " +
                        "${existingTarget ?: "unknown"} instead of switching to $targetKey ($reason)",
                )
                return
            }

            println(
                "MIRACAST: 🔄 Switching RTSP fallback target " +
                    "${existingTarget ?: "unknown"} -> $targetKey ($reason)",
            )
            existingJob.cancel()
        }

        val job = scope.launch(Dispatchers.IO) {
            if (initialDelayMs > 0) {
                delay(initialDelayMs)
            }

            repeat(maxAttempts) { attemptIndex ->
                val hasActiveStream =
                    MiracastPlaybackManager.hasRecentPlaybackActivity(withinMs = 8_000L)
                val hasActiveControl =
                    miracastRtspServer.hasActiveControlConnection() ||
                        miracastRtspServer.hasRecentRtspActivity(withinMs = 8_000L)
                if (hasActiveStream || hasActiveControl) {
                    println(
                        "MIRACAST: ✅ Stream/control session already active; " +
                            "skipping RTSP fallback to $targetKey",
                    )
                    return@launch
                }

                val attempt = attemptIndex + 1
                println("MIRACAST: 🔄 RTSP fallback attempt $attempt/$maxAttempts to $targetKey ($reason)")

                val result = miracastRtspServer.connectToSource(
                    sourceAddress = normalizedAddress,
                    sourcePort = boundedPort,
                )

                if (result.isSuccess) {
                    if (waitForRtspHandshake()) {
                        println("MIRACAST: ✅ RTSP negotiation started with $targetKey")
                        return@launch
                    }

                    println(
                        "MIRACAST: ⚠️ TCP connect succeeded for $targetKey, " +
                            "but no RTSP requests arrived yet",
                    )
                } else {
                    val error = result.exceptionOrNull()
                    val errorSummary = buildString {
                        append(error?.javaClass?.simpleName ?: "UnknownError")
                        error?.message?.takeIf { it.isNotBlank() }?.let {
                            append(": ").append(it)
                        }
                    }
                    println(
                        "MIRACAST: ⚠️ RTSP fallback attempt $attempt/$maxAttempts " +
                            "failed for $targetKey: $errorSummary",
                    )
                }

                if (attempt < maxAttempts) {
                    delay(1_000L + (attemptIndex * 250L))
                }
            }

            println("MIRACAST: ❌ RTSP fallback exhausted for $targetKey ($reason)")
        }

        sourceRtspRetryJob = job
        sourceRtspRetryTarget = targetKey
        job.invokeOnCompletion {
            if (sourceRtspRetryJob === job) {
                sourceRtspRetryJob = null
            }
            if (sourceRtspRetryTarget == targetKey) {
                sourceRtspRetryTarget = null
            }
        }
    }

    miracastRtspServer = MiracastRtspServer(
        onStreamReady = { streamInfo ->
            cancelSourceRtspRetry("stream ready from ${streamInfo.clientAddress}")
            println(
                "MIRACAST: 🎥 Stream ready from ${streamInfo.clientAddress} " +
                    "on RTP port ${streamInfo.rtpPort}",
            )
            if (context != null) {
                MiracastPlaybackManager.startSession(context, streamInfo)
            }
        },
        onStreamStopped = { streamInfo ->
            println(
                "MIRACAST: 🛑 Stream stopped for " +
                    "${streamInfo?.clientAddress ?: "unknown source"}",
            )
            MiracastPlaybackManager.stopSession(closeViewer = true)
        }
    )

    // MS-MICE control channel for Miracast over infrastructure.
    val miracastMiceServer = MiracastMiceServer(
        onSourceReady = { sourceReady ->
            scheduleSourceRtspRetry(
                sourceAddress = sourceReady.sourceAddress,
                sourcePort = sourceReady.rtspPort,
                reason = "MS-MICE SOURCE_READY from ${sourceReady.friendlyName ?: sourceReady.sourceAddress}",
                initialDelayMs = 0L,
                maxAttempts = 8,
            )
        },
        onStopProjection = { sourceId ->
            println("MIRACAST_MICE: 🛑 Stop projection requested by sourceId=${sourceId ?: "unknown"}")
            cancelSourceRtspRetry("source requested projection stop")
            MiracastPlaybackManager.stopSession(closeViewer = true)
        },
    )

    // Create DIAL HTTP server for device description
    val dialHttpServer = DialHttpServer(
        onScreenMirrorRequest = {
            println("MIRACAST: 📺 Screen mirror requested via DIAL")
        },
        onWfdDescriptionRequest = { requesterHost ->
            scheduleSourceRtspRetry(
                sourceAddress = requesterHost,
                sourcePort = MiracastPorts.WFD_RTSP_PORT,
                reason = "WFD description requested by $requesterHost",
                initialDelayMs = 750L,
                maxAttempts = 8,
                replaceExisting = false,
            )
        },
    )

    // Create Wi-Fi Direct manager lazily; expensive initialization is deferred.
    val wifiDirectManager = context?.let { WifiDirectManager(it) }
    var wifiDirectInitialized = false

    var controllerOwnsMiracastStack = false
    var controllerOwnsMiracastLease = false
    var airPlayReceiver: AirPlayReceiver? = null

    suspend fun ensureAirPlayReceiverRunning(deviceName: String) {
        val existing = airPlayReceiver
        if (existing == null) {
            val receiver = AirPlayReceiver(displayName = deviceName)
            airPlayReceiver = receiver
            AirPlayReceiverHolder.receiver = receiver
            runCatching { receiver.start() }
                .onSuccess { println("AirPlay: ✅ Receiver started as '$deviceName'") }
                .onFailure { error ->
                    println("AirPlay: ❌ Receiver start failed: ${error.message}")
                    runCatching { receiver.stop() }
                    if (airPlayReceiver === receiver) {
                        airPlayReceiver = null
                    }
                    if (AirPlayReceiverHolder.receiver === receiver) {
                        AirPlayReceiverHolder.receiver = null
                    }
                }
            return
        }

        if (AirPlayReceiverHolder.receiver !== existing) {
            AirPlayReceiverHolder.receiver = existing
        }
        if (existing.status.value == AirPlayReceiver.Status.Idle) {
            runCatching { existing.start() }
                .onSuccess { println("AirPlay: ✅ Receiver restarted as '$deviceName'") }
                .onFailure { error ->
                    println("AirPlay: ❌ Receiver restart failed: ${error.message}")
                    runCatching { existing.stop() }
                    if (airPlayReceiver === existing) {
                        airPlayReceiver = null
                    }
                    if (AirPlayReceiverHolder.receiver === existing) {
                        AirPlayReceiverHolder.receiver = null
                    }
                }
        }
    }

    return DefaultHostController(
        scope = scope,
        discoveryService = discoveryService,
        webRtcEngine = webRtcEngine,
        iceServers = iceServers,
        remoteServerUrl = RemoteServerConfig.REMOTE_SERVER_URL,
        onLocalClientTransportActivityChanged = { hasActiveLocalClient ->
            val manager = wifiDirectManager ?: return@DefaultHostController
            scope.launch {
                if (hasActiveLocalClient) {
                    manager.suspendPeerDiscoveryForAppTraffic("local app client connected")
                } else {
                    manager.resumePeerDiscoveryAfterAppTraffic("no local app clients connected")
                }
            }
        },
        miracastStart = { deviceName, miceControlPort ->
            // Android host mode uses the background advertiser as the single Miracast owner.
            // This avoids stop/start churn and port bind conflicts when the app UI opens.
            if (context != null) {
                MiracastAdvertiserService.ensureRunning(
                    context = context,
                    reason = "host_controller_start",
                    preferredDeviceName = deviceName,
                )
                if (controllerOwnsMiracastLease) {
                    MiracastStackLease.release(MiracastStackLease.OWNER_IN_PROCESS)
                    controllerOwnsMiracastLease = false
                }
                controllerOwnsMiracastStack = false
                val leaseOwner = MiracastStackLease.currentOwner()
                println(
                    "MIRACAST: ℹ️ Background advertiser is authoritative; " +
                        "skipping in-process startup (lease=${leaseOwner ?: "none"}).",
                )

                // Start AirPlay receiver alongside Miracast
                ensureAirPlayReceiverRunning(deviceName)
                return@DefaultHostController
            }

            if (!MiracastStackLease.tryAcquire(MiracastStackLease.OWNER_IN_PROCESS)) {
                println(
                    "MIRACAST: ℹ️ Stack ownership currently held by " +
                        "${MiracastStackLease.currentOwner() ?: "unknown"}; " +
                        "skipping in-process startup.",
                )
                controllerOwnsMiracastStack = false
                controllerOwnsMiracastLease = false
                return@DefaultHostController
            }
            controllerOwnsMiracastLease = true

            // Start all Miracast components
            try {
                val localIp = getLocalIpAddress() ?: run {
                    println("HOST_CONTROLLER: ⚠️ No local IP resolved — Miracast may not work")
                    "0.0.0.0"
                }
                val deviceUuid = resolveMiracastDeviceUuid(context, deviceName)
                var wifiDirectReady = false
                var wifiDisplayPermissionDenied = false
                var wfdSinkInfoConfigured = false

                // 1. Start RTSP sink server for classic WFD session negotiation.
                miracastRtspServer.start(MiracastPorts.WFD_RTSP_PORT)

                // 2. Start MS-MICE control channel (Miracast over Infrastructure).
                miracastMiceServer.start(miceControlPort)

                // 3. Advertise mDNS _display._tcp for MS-MICE discovery.
                miracastMdnsService?.startAdvertisement(
                    deviceName = deviceName,
                    controlPort = miceControlPort,
                    containerId = deviceUuid,
                )

                // 4. Start HTTP server for SSDP LOCATION device description.
                val httpPort = dialHttpServer.start(
                    port = 8080,
                    deviceName = deviceName,
                    deviceUuid = deviceUuid,
                    manufacturer = "Teachmint",
                    modelName = "ShareX Display",
                    localIp = localIp,
                )

                // 5. Start SSDP responses and NOTIFY broadcasts.
                miracastSsdpService?.startDiscovery(
                    deviceName = deviceName,
                    deviceUuid = deviceUuid,
                    localIp = localIp,
                    rtspPort = httpPort,
                )

                // 6. Enable Wi-Fi Direct signaling for classic Win+K discovery mode.
                if (context != null && wifiDirectManager != null) {
                    if (!wifiDirectInitialized) {
                        val initResult = runCatching { wifiDirectManager.initialize() }
                        initResult.onFailure {
                            println(
                                "WIFI_DIRECT: ⚠️ Initialization failed, continuing without P2P startup: " +
                                    "${it::class.java.simpleName}: ${it.message}",
                            )
                        }
                        wifiDirectInitialized = initResult.isSuccess
                    }

                    if (!wifiDirectInitialized) {
                        println(
                            "WIFI_DIRECT: ⚠️ Skipping Win+K Wi-Fi Direct signaling because initialization failed.",
                        )
                    } else {
                        WifiDirectPermissionHelper.logPermissionStatus(context)
                        if (WifiDirectPermissionHelper.hasPermissions(context)) {
                            runCatching {
                                wifiDirectManager.resetForMiracastStartup()
                            }.onSuccess {
                                println(
                                    "WIFI_DIRECT: 🔄 Cleared stale P2P state before Win+K advertising",
                                )
                            }

                            val sinkConfigResult = wifiDirectManager.configureMiracastSink(
                                deviceName = deviceName,
                                rtspPort = MiracastPorts.WFD_RTSP_PORT,
                            )
                            sinkConfigResult.onFailure {
                                println("WIFI_DIRECT: ⚠️ configureMiracastSink failed: ${it.message}")
                                if (it.containsWifiDisplayPermissionDenial()) {
                                    wifiDisplayPermissionDenied = true
                                    println(
                                        "WIFI_DIRECT: ⚠️ CONFIGURE_WIFI_DISPLAY denied. " +
                                            "Win+K Wi-Fi Direct sink mode requires signature/known-signer grant.",
                                    )
                                }
                            }.onSuccess {
                                wfdSinkInfoConfigured = true
                            }

                            if (wifiDisplayPermissionDenied) {
                                println(
                                    "WIFI_DIRECT: ⚠️ Continuing with best-effort Win+K discovery " +
                                        "without privileged WFD sink metadata.",
                                )
                            }

                            runCatching {
                                wifiDirectManager.registerReceiver { info ->
                                    println(
                                        "WIFI_DIRECT: 🔗 Connection info groupFormed=${info.groupFormed} " +
                                            "isGroupOwner=${info.isGroupOwner} " +
                                            "owner=${info.groupOwnerAddress?.hostAddress}",
                                    )

                                    // Fallback path:
                                    // Some Windows builds complete P2P but never open RTSP toward sink.
                                    // Keep probing the source until it starts listening on RTSP.
                                    if (info.groupFormed && !info.isGroupOwner) {
                                        val sourceIp = info.groupOwnerAddress?.hostAddress
                                        if (!sourceIp.isNullOrBlank()) {
                                            scheduleSourceRtspRetry(
                                                sourceAddress = sourceIp,
                                                sourcePort = MiracastPorts.WFD_RTSP_PORT,
                                                reason = "Wi-Fi Direct group owner $sourceIp",
                                                initialDelayMs = 1_000L,
                                                maxAttempts = 14,
                                            )
                                        }
                                    }
                                }
                            }
                            val advertiseResult = wifiDirectManager.advertiseService(
                                deviceName = deviceName,
                                rtspPort = MiracastPorts.WFD_RTSP_PORT,
                            )
                            advertiseResult.onFailure {
                                println("WIFI_DIRECT: ❌ advertiseService failed: ${it.message}")
                            }.onSuccess {
                                println("WIFI_DIRECT: ✅ Miracast DNS-SD service advertised")
                            }

                            val discoveryResult = wifiDirectManager.startDiscovery(
                                deviceName = deviceName,
                                onConnectionRequest = { peer ->
                                    println(
                                        "WIFI_DIRECT: 📩 Incoming request from " +
                                            "${peer.deviceName} (${peer.deviceAddress})",
                                    )
                                },
                            )
                            discoveryResult.onFailure {
                                println("WIFI_DIRECT: ❌ startDiscovery failed: ${it.message}")
                            }.onSuccess {
                                println("WIFI_DIRECT: ✅ Peer discovery started for Win+K")
                            }

                            println(
                                "WIFI_DIRECT: ℹ️ Waiting for Windows invitation " +
                                    "(preferring source-led group negotiation).",
                            )

                            if (wfdSinkInfoConfigured) {
                                wifiDirectReady = true
                            }
                        } else {
                            val missing = WifiDirectPermissionHelper.getMissingPermissions(context)
                            println(
                                "WIFI_DIRECT: ⚠️ Missing runtime permissions for Win+K discovery: " +
                                    missing.joinToString(),
                            )
                        }
                    }
                }

                println(
                    "MIRACAST: 📡 Infrastructure mode ready " +
                        "(mDNS + MS-MICE + RTSP + SSDP)",
                )
                if (wifiDirectReady) {
                    println("MIRACAST: ✅ Wi-Fi Direct sink signaling configured for Win+K")
                } else {
                    if (wifiDisplayPermissionDenied || !hasWifiDisplayPermission(context)) {
                        println(
                            "MIRACAST: ⚠️ Win+K classic Miracast likely unavailable on this build " +
                                "because CONFIGURE_WIFI_DISPLAY is not granted.",
                        )
                    }
                    println(
                        "MIRACAST: ⚠️ Wi-Fi Direct sink signaling not fully configured. " +
                            "Infra mode can still work if Windows uses MS-MICE.",
                    )
                }
                controllerOwnsMiracastStack = true

                // Start AirPlay receiver alongside in-process Miracast stack
                ensureAirPlayReceiverRunning(deviceName)
            } catch (e: Exception) {
                println("MIRACAST: ❌ Failed to start Miracast stack: ${e.message}")
                controllerOwnsMiracastStack = false
                if (controllerOwnsMiracastLease) {
                    MiracastStackLease.release(MiracastStackLease.OWNER_IN_PROCESS)
                    controllerOwnsMiracastLease = false
                }
                cancelSourceRtspRetry("Miracast startup failed")
                MiracastPlaybackManager.stopSession(closeViewer = true)
                runCatching { miracastMiceServer.stop() }
                runCatching { miracastRtspServer.stop() }
                runCatching { miracastMdnsService?.stopAdvertisement() }
                runCatching { miracastSsdpService?.stopDiscovery() }
                runCatching { dialHttpServer.stop() }
            }
        },
        miracastStop = {
            // Stop AirPlay receiver
            airPlayReceiver?.let { receiver ->
                runCatching { receiver.stop() }
                    .onFailure { println("AirPlay: ⚠️ Receiver stop failed: ${it.message}") }
                airPlayReceiver = null
                AirPlayReceiverHolder.receiver = null
            }

            if (!controllerOwnsMiracastStack) {
                if (controllerOwnsMiracastLease) {
                    MiracastStackLease.release(MiracastStackLease.OWNER_IN_PROCESS)
                    controllerOwnsMiracastLease = false
                }
                return@DefaultHostController
            }

            cancelSourceRtspRetry("Miracast host stopped")
            MiracastPlaybackManager.stopSession(closeViewer = true)
            dialHttpServer.stop()
            miracastMiceServer.stop()
            miracastRtspServer.stop()
            miracastMdnsService?.stopAdvertisement()
            miracastSsdpService?.stopDiscovery()
            wifiDirectManager?.cleanup()
            controllerOwnsMiracastStack = false
            if (controllerOwnsMiracastLease) {
                MiracastStackLease.release(MiracastStackLease.OWNER_IN_PROCESS)
                controllerOwnsMiracastLease = false
            }
        }
    )
}

private fun Throwable.containsWifiDisplayPermissionDenial(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SecurityException &&
            current.message.orEmpty().contains("Wifi Display Permission denied", ignoreCase = true)
        ) {
            return true
        }
        if (current.message.orEmpty().contains("Wifi Display Permission denied", ignoreCase = true)) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun hasWifiDisplayPermission(context: Context?): Boolean {
    if (context == null) return false
    return context.checkSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY) ==
        PackageManager.PERMISSION_GRANTED
}

fun createWebRtcEngine(): WebRtcEngine = AndroidWebRtcEngine()

private fun isAppDebuggable(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets) {
        pingInterval = 15.seconds
    }
    install(ContentNegotiation) {
        json(ShareXJson)
    }

    engine {
        config {
            // Block cleartext traffic to non-LAN hosts (defense-in-depth for network_security_config).
            // OkHttp normalises ws:// → http:// and wss:// → https:// before interceptors run,
            // so we only check for "https" (covers both https:// and wss:// origins).
            // Only raw IP LAN addresses are permitted for cleartext; hostname-based LAN
            // addresses (e.g. mDNS .local) are intentionally blocked here.
            addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isSecure = request.url.scheme == "https"
                if (!isSecure && !isPrivateIpv4(host) && host != "localhost" && host != "127.0.0.1") {
                    throw java.io.IOException("Cleartext traffic blocked to non-LAN host: $host")
                }
                chain.proceed(request)
            }

            val appContext = AndroidContextHolder.applicationContext
            when {
                appContext == null -> {
                    println("CHUCKER: Android context unavailable, skipping Chucker interceptor")
                }
                isAppDebuggable(appContext) -> {
                    addInterceptor(ChuckerInterceptor(appContext))
                }
                else -> {
                    println("CHUCKER: Disabled for non-debug build")
                }
            }

            // Screen sharing keeps the signaling websocket open for a long time, often with
            // only heartbeat traffic after the initial SDP/ICE exchange.
            connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)

            // Retry on connection failure
            retryOnConnectionFailure(true)
        }
    }
}

fun generateClientId(): String = UUID.randomUUID().toString()

fun getPlatformName(): String = "Android ${Build.MODEL}".trim()

fun getSavedDeviceName(): String? {
    val context = AndroidContextHolder.applicationContext ?: return null
    val modelName = Build.MODEL?.trim().orEmpty()

    return sequenceOf(
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull(),
        runCatching { Settings.System.getString(context.contentResolver, "device_name") }.getOrNull(),
    )
        .mapNotNull { it?.trim()?.removeSurrounding("\"") }
        .firstOrNull { candidate ->
            candidate.isNotBlank() &&
                !candidate.equals("unknown", ignoreCase = true) &&
                !candidate.equals(modelName, ignoreCase = true)
        }
}

fun getHostDisplayName(): String {
    return getSavedDeviceName()
        ?: Build.MODEL?.trim().takeUnless { it.isNullOrBlank() }
        ?: "Android Host"
}

fun getLocalIpAddress(): String? {
    // Prefer the current Wi-Fi address when available.
    val wifiAddress = (AndroidContextHolder.applicationContext
        ?.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.connectionInfo
        ?.ipAddress
        ?.let(::intToIpv4)
        ?.takeIf(::isPrivateIpv4)
    if (wifiAddress != null) return wifiAddress

    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }
        .getOrElse { return null }

    var bestAddress: String? = null
    var bestScore = Int.MIN_VALUE

    for (iface in interfaces) {
        val eligible = runCatching {
            iface.isUp && !iface.isLoopback && !iface.isVirtual && !iface.isPointToPoint
        }.getOrDefault(false)
        if (!eligible) continue

        val interfaceScore = interfacePreferenceScore(
            name = iface.name.orEmpty(),
            displayName = iface.displayName.orEmpty(),
        )
        val addresses = iface.inetAddresses.toList()
        for (address in addresses) {
            if (address.isLoopbackAddress || address !is Inet4Address) continue
            val ip = address.hostAddress ?: continue
            val score = interfaceScore + if (isPrivateIpv4(ip)) 50 else 10
            if (score > bestScore) {
                bestScore = score
                bestAddress = ip
            }
        }
    }

    return bestAddress
}

private fun resolveMiracastDeviceUuid(context: Context?, advertisedDeviceName: String?): String {
    if (context == null) return UUID.randomUUID().toString()

    val preferences = context.getSharedPreferences(MIRACAST_PREF_FILE, Context.MODE_PRIVATE)
    val storedUuid = preferences.getString(MIRACAST_DEVICE_UUID_KEY, null)
        ?.trim()
        ?.takeIf { candidate -> runCatching { UUID.fromString(candidate) }.isSuccess }
    val currentDeviceSourceId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    )
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
    val storedDeviceSourceId = preferences.getString(MIRACAST_DEVICE_SOURCE_ID_KEY, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val normalizedAdvertisedName = advertisedDeviceName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val storedAdvertisedName = preferences.getString(MIRACAST_DEVICE_ADVERTISED_NAME_KEY, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val sourceIdentitySeed = when {
        currentDeviceSourceId != null && normalizedAdvertisedName != null ->
            "${context.packageName}:$currentDeviceSourceId:$normalizedAdvertisedName"
        currentDeviceSourceId != null ->
            "${context.packageName}:$currentDeviceSourceId"
        normalizedAdvertisedName != null ->
            "${context.packageName}:name:$normalizedAdvertisedName"
        else -> null
    }
    val shouldRotateForNameChange = normalizedAdvertisedName != storedAdvertisedName

    val resolvedUuid = when {
        currentDeviceSourceId != null && currentDeviceSourceId != storedDeviceSourceId ->
            UUID.nameUUIDFromBytes((sourceIdentitySeed ?: UUID.randomUUID().toString()).encodeToByteArray())
                .toString()
        shouldRotateForNameChange && sourceIdentitySeed != null ->
            UUID.nameUUIDFromBytes(sourceIdentitySeed.encodeToByteArray()).toString()
        storedUuid != null -> storedUuid
        sourceIdentitySeed != null ->
            UUID.nameUUIDFromBytes(sourceIdentitySeed.encodeToByteArray()).toString()
        else -> UUID.randomUUID().toString()
    }

    val needsWrite = resolvedUuid != storedUuid ||
        storedDeviceSourceId != currentDeviceSourceId ||
        storedAdvertisedName != normalizedAdvertisedName
    if (needsWrite) {
        preferences.edit().apply {
            putString(MIRACAST_DEVICE_UUID_KEY, resolvedUuid)
            if (currentDeviceSourceId != null) {
                putString(MIRACAST_DEVICE_SOURCE_ID_KEY, currentDeviceSourceId)
            } else {
                remove(MIRACAST_DEVICE_SOURCE_ID_KEY)
            }
            if (normalizedAdvertisedName != null) {
                putString(MIRACAST_DEVICE_ADVERTISED_NAME_KEY, normalizedAdvertisedName)
            } else {
                remove(MIRACAST_DEVICE_ADVERTISED_NAME_KEY)
            }
        }.apply()

        val reason = when {
            storedUuid == null -> "initialized"
            currentDeviceSourceId != null && currentDeviceSourceId != storedDeviceSourceId ->
                "rotated-for-device-mismatch"
            shouldRotateForNameChange -> "rotated-for-host-name-change"
            else -> "repaired"
        }
        println(
            "MIRACAST: Using device receiver UUID $resolvedUuid ($reason, " +
                "host='${normalizedAdvertisedName ?: "unknown"}')",
        )
    }

    return resolvedUuid
}

fun getConnectedWifiName(): String? {
    val context = AndroidContextHolder.applicationContext ?: return null
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
    val activeNetwork = connectivityManager.activeNetwork
    val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)

    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val ssidFromTransportInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        (capabilities?.transportInfo as? WifiInfo)?.ssid
    } else {
        null
    }
    val ssidFromWifiManager = wifiManager?.connectionInfo?.ssid
    val wifiName = sanitizeSsid(ssidFromTransportInfo) ?: sanitizeSsid(ssidFromWifiManager)
    if (wifiName != null) return wifiName
    return null
}

fun isNetworkConnectionAvailable(): Boolean {
    val context = AndroidContextHolder.applicationContext ?: return false
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

fun isSecureOrigin(): Boolean = false

fun isScreenCaptureSupported(): Boolean = true

fun currentTimeMillis(): Long = System.currentTimeMillis()

fun secureRandomInt(bound: Int): Int = java.security.SecureRandom().nextInt(bound)

private fun interfacePreferenceScore(name: String, displayName: String): Int {
    val interfaceLabel = "$name $displayName".lowercase()
    return when {
        "wlan" in interfaceLabel || "wifi" in interfaceLabel -> 40
        "eth" in interfaceLabel || "en" in interfaceLabel || "lan" in interfaceLabel -> 30
        "rmnet" in interfaceLabel || "cell" in interfaceLabel || "pdp" in interfaceLabel -> -20
        "tun" in interfaceLabel ||
            "utun" in interfaceLabel ||
            "tap" in interfaceLabel ||
            "vpn" in interfaceLabel ||
            "docker" in interfaceLabel ||
            "veth" in interfaceLabel ||
            "bridge" in interfaceLabel ||
            "awdl" in interfaceLabel -> -50
        else -> 0
    }
}

private fun intToIpv4(value: Int): String? {
    if (value == 0) return null
    val b1 = value and 0xFF
    val b2 = (value shr 8) and 0xFF
    val b3 = (value shr 16) and 0xFF
    val b4 = (value shr 24) and 0xFF
    val ip = "$b1.$b2.$b3.$b4"
    return ip.takeUnless { it == "0.0.0.0" }
}

private fun sanitizeSsid(rawSsid: String?): String? {
    val cleaned = rawSsid
        ?.trim()
        ?.removeSurrounding("\"")
        ?: return null
    return cleaned.takeUnless {
        it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) || it == "0x"
    }
}

private fun getConnectedCarrierName(context: Context): String? {
    val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
    val operatorName = sanitizeCarrierName(telephonyManager.networkOperatorName)
    if (operatorName != null) return operatorName
    return sanitizeCarrierName(telephonyManager.simOperatorName)
}

private fun sanitizeCarrierName(rawName: String?): String? {
    val cleaned = rawName?.trim() ?: return null
    return cleaned.takeUnless {
        it.isBlank() || it.equals("unknown", ignoreCase = true) || it.equals("null", ignoreCase = true)
    }
}

internal fun isPrivateIpv4(ip: String): Boolean {
    val octets = ip.split(".")
    if (octets.size != 4) return false
    val values = octets.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
    return when {
        values[0] == 10 -> true                           // 10.0.0.0/8
        values[0] == 172 && values[1] in 16..31 -> true   // 172.16.0.0/12
        values[0] == 192 && values[1] == 168 -> true      // 192.168.0.0/16
        values[0] == 169 && values[1] == 254 -> true      // 169.254.0.0/16 (link-local / APIPA)
        else -> false
    }
}

object AndroidRoleHolder {
    @Volatile var isIfpDevice: Boolean = false
}

fun detectIfpDevice(intent: Intent?): Boolean {
    if (intent?.getBooleanExtra(EXTRA_IFP_DEVICE, false) == true) return true
    val model = Build.MODEL?.lowercase().orEmpty()
    val device = Build.DEVICE?.lowercase().orEmpty()
    val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
    return listOf(model, device, manufacturer).any { value ->
        value.contains("ifp") || value.contains("panel") || value.contains("teachmint")
    }
}

const val EXTRA_IFP_DEVICE: String = "extra_ifp_device"
