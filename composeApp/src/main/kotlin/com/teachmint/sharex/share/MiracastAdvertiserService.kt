package com.teachmint.sharex.share.shared

import com.teachmint.sharex.R
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.teachmintsharex.share.dial.DialHttpServer
import com.example.teachmintsharex.share.miracast.MiracastDiscoveryService
import com.example.teachmintsharex.share.miracast.MiracastMiceServer
import com.example.teachmintsharex.share.miracast.MiracastPlaybackManager
import com.example.teachmintsharex.share.miracast.MiracastPorts
import com.example.teachmintsharex.share.miracast.MiracastRtspServer
import com.example.teachmintsharex.share.miracast.MiracastSsdpService
import com.example.teachmintsharex.share.wifidirect.WifiDirectManager
import com.example.teachmintsharex.share.wifidirect.WifiDirectPermissionHelper
import com.teachmint.sharex.airplay.AirPlayReceiver
import com.teachmint.sharex.airplay.AirPlayReceiverHolder
import com.teachmint.sharex.utils.sharedpreference.SharedPreferenceConstants
import com.teachmint.sharex.utils.sharedpreference.SharedPreferenceUtils
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Lightweight background Miracast advertiser.
 *
 * Goal:
 * - Keep host visible in Windows+K even when UI is not open.
 * - Keep memory/bandwidth low by running passive listeners + low-frequency announcements.
 */
class MiracastAdvertiserService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var startJob: Job? = null
    private var retryDelayMs = INITIAL_RETRY_DELAY_MS
    private var stackRunning = false
    private var ownsMiracastLease = false

    private var sourceRtspRetryJob: Job? = null
    private var sourceRtspRetryTarget: String? = null

    private var miracastMdnsService: MiracastDiscoveryService? = null
    private var miracastSsdpService: MiracastSsdpService? = null
    private var miracastRtspServer: MiracastRtspServer? = null
    private var miracastMiceServer: MiracastMiceServer? = null
    private var dialHttpServer: DialHttpServer? = null
    private var wifiDirectManager: WifiDirectManager? = null
    private var wifiDirectInitialized = false
    private var wifiDirectReceiverRegistered = false
    private var wifiDirectOperational = false
    private var wifiDirectRecoveryJob: Job? = null
    private var preferredDeviceNameOverride: String? = null
    private var lastKnownDeviceName: String = "TeachmintShareX"
    private var lastKnownDeviceUuid: String = UUID.randomUUID().toString()
    private var wifiDisplayPermissionWarningLogged = false
    private var airPlaySuppressionLogged = false

    override fun onCreate() {
        super.onCreate()
        running = true
        AndroidContextHolder.init(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val role = runCatching { getDeviceRole() }.getOrDefault(DeviceRole.CLIENT)
        if (role != DeviceRole.HOST) {
            println("MIRACAST_BG: ℹ️ Device role is CLIENT, skipping background advertiser.")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!enterForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val preferredName = intent?.getStringExtra(EXTRA_PREFERRED_DEVICE_NAME)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (preferredName != null) {
            preferredDeviceNameOverride = preferredName
        }

        startRequested = false
        if (stackRunning) {
            val desiredName = resolveAdvertisedDeviceName()
            if (desiredName != lastKnownDeviceName) {
                println(
                    "MIRACAST_BG: 🔄 Host name changed '$lastKnownDeviceName' -> '$desiredName'; " +
                        "restarting background stack to refresh Windows discovery name.",
                )
                startJob?.cancel()
                startJob = null
                scope.launch {
                    stopMiracastStack("advertised name changed")
                    ensureMiracastStack()
                }
                return START_STICKY
            }
        }

        ensureMiracastStack()
        return START_STICKY
    }

    override fun onDestroy() {
        startJob?.cancel()
        runBlocking {
            stopMiracastStack("service destroyed")
        }
        scope.cancel()
        running = false
        startRequested = false
        super.onDestroy()
    }

    private fun ensureMiracastStack() {
        if (startJob?.isActive == true) return

        startJob = scope.launch {
            while (true) {
                if (isAirPlaySessionActive()) {
                    if (!airPlaySuppressionLogged) {
                        println(
                            "MIRACAST_BG: ⏸️ AirPlay session active; pausing Miracast background retries " +
                                "to avoid transport churn.",
                        )
                        airPlaySuppressionLogged = true
                    }
                    delay(AIRPLAY_ACTIVE_RETRY_PAUSE_MS)
                    continue
                }
                airPlaySuppressionLogged = false

                val startResult = runCatching { startMiracastStack() }
                if (startResult.isSuccess) {
                    retryDelayMs = INITIAL_RETRY_DELAY_MS
                    return@launch
                }

                val error = startResult.exceptionOrNull()
                if (error is MiracastLeaseBusyException) {
                    println(
                        "MIRACAST_BG: ℹ️ Miracast stack is active in " +
                            "${error.owner ?: "another component"}; retrying in ${LEASE_BUSY_RETRY_MS}ms",
                    )
                    delay(LEASE_BUSY_RETRY_MS)
                    continue
                }
                println(
                    "MIRACAST_BG: ⚠️ Startup failed, retrying in ${retryDelayMs}ms: " +
                        "${error?.javaClass?.simpleName}: ${error?.message}",
                )
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun startMiracastStack() {
        if (stackRunning) return
        if (!ownsMiracastLease) {
            if (!MiracastStackLease.tryAcquire(MiracastStackLease.OWNER_BACKGROUND)) {
                throw MiracastLeaseBusyException(MiracastStackLease.currentOwner())
            }
            ownsMiracastLease = true
        }

        val context = applicationContext
        AndroidContextHolder.init(context)

        val deviceName = resolveAdvertisedDeviceName().ifBlank { "TeachmintShareX" }
        val localIp = getLocalIpAddress() ?: run {
            println("MIRACAST_BG: ⚠️ No local IP resolved — Miracast stack may not work correctly")
            "0.0.0.0"
        }
        val deviceUuid = resolveMiracastDeviceUuid(context, deviceName)
        lastKnownDeviceName = deviceName
        lastKnownDeviceUuid = deviceUuid
        ensureWifiDisplaySystemSettingEnabled(reason = "stack_start")

        val rtspServer = MiracastRtspServer(
            onStreamReady = { streamInfo ->
                cancelSourceRtspRetry("stream ready from ${streamInfo.clientAddress}")
                println(
                    "MIRACAST_BG: 🎥 Stream ready from ${streamInfo.clientAddress} " +
                        "on RTP port ${streamInfo.rtpPort}",
                )
                MiracastPlaybackManager.startSession(context, streamInfo)
            },
            onStreamStopped = { streamInfo ->
                println(
                    "MIRACAST_BG: 🛑 Stream stopped for " +
                        "${streamInfo?.clientAddress ?: "unknown source"}",
                )
                MiracastPlaybackManager.stopSession(closeViewer = true)
            },
        )

        val miceServer = MiracastMiceServer(
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
                println("MIRACAST_BG: 🛑 Stop projection requested by sourceId=${sourceId ?: "unknown"}")
                cancelSourceRtspRetry("source requested projection stop")
                MiracastPlaybackManager.stopSession(closeViewer = true)
            },
        )

        val dialServer = DialHttpServer(
            onScreenMirrorRequest = {
                println("MIRACAST_BG: 📺 Screen mirror requested via DIAL")
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

        val mdnsService = MiracastDiscoveryService(context)
        val ssdpService = MiracastSsdpService(context)

        try {
            // H-9: Bind to the resolved local IP instead of 0.0.0.0 when available,
            // so Miracast control protocols are only exposed on the intended interface.
            val bindAddr = if (localIp != "0.0.0.0") localIp else "0.0.0.0"
            runCatching {
                rtspServer.start(MiracastPorts.WFD_RTSP_PORT, bindAddress = bindAddr)
            }.getOrElse {
                throw IllegalStateException(
                    "Failed to bind RTSP port ${MiracastPorts.WFD_RTSP_PORT}",
                    it,
                )
            }
            runCatching {
                miceServer.start(MiracastPorts.MICE_CONTROL_PORT, bindAddress = bindAddr)
            }.getOrElse {
                throw IllegalStateException(
                    "Failed to bind MS-MICE port ${MiracastPorts.MICE_CONTROL_PORT}",
                    it,
                )
            }
            runCatching {
                mdnsService.startAdvertisement(
                    deviceName = deviceName,
                    controlPort = MiracastPorts.MICE_CONTROL_PORT,
                    containerId = deviceUuid,
                )
            }.getOrElse {
                throw IllegalStateException("Failed to register mDNS advertisement", it)
            }
            val httpPort = runCatching {
                dialServer.start(
                    port = 8080,
                    deviceName = deviceName,
                    deviceUuid = deviceUuid,
                    manufacturer = "Teachmint",
                    modelName = "ShareX Display",
                    localIp = localIp,
                )
            }.getOrElse {
                throw IllegalStateException("Failed to bind DIAL HTTP port 8080", it)
            }
            runCatching {
                ssdpService.startDiscovery(
                    deviceName = deviceName,
                    deviceUuid = deviceUuid,
                    localIp = localIp,
                    rtspPort = httpPort,
                )
            }.getOrElse {
                throw IllegalStateException("Failed to start SSDP discovery", it)
            }

            configureWifiDirectSinkLite(deviceName)
            ensureWifiDirectRecoveryLoop(deviceName)

            miracastRtspServer = rtspServer
            miracastMiceServer = miceServer
            dialHttpServer = dialServer
            miracastMdnsService = mdnsService
            miracastSsdpService = ssdpService
            stackRunning = true

            println(
                "MIRACAST_BG: ✅ Background Miracast advertiser started " +
                    "(passive mode, low-bandwidth)",
            )
        } catch (error: Throwable) {
            try {
                dialServer.stop()
            } catch (_: Throwable) {
            }
            try {
                miceServer.stop()
            } catch (_: Throwable) {
            }
            try {
                rtspServer.stop()
            } catch (_: Throwable) {
            }
            try {
                mdnsService.stopAdvertisement()
            } catch (_: Throwable) {
            }
            try {
                ssdpService.stopDiscovery()
            } catch (_: Throwable) {
            }
            wifiDirectRecoveryJob?.cancel()
            wifiDirectRecoveryJob = null
            wifiDirectOperational = false
            MiracastStackLease.release(MiracastStackLease.OWNER_BACKGROUND)
            ownsMiracastLease = false
            throw error
        }
    }

    private suspend fun configureWifiDirectSinkLite(deviceName: String) {
        // Background mode should remain discoverable for Win+K even when the app UI is closed.
        // Keep sink metadata + DNS-SD advertisement + peer discovery active.
        if (isAirPlaySessionActive()) {
            if (!airPlaySuppressionLogged) {
                println(
                    "MIRACAST_BG: ⏸️ Skipping Wi-Fi Direct setup while AirPlay is active.",
                )
                airPlaySuppressionLogged = true
            }
            return
        }
        airPlaySuppressionLogged = false

        if (!hasWifiDisplayPermission()) {
            if (!wifiDisplayPermissionWarningLogged) {
                println(
                    "MIRACAST_BG: ⚠️ Missing CONFIGURE_WIFI_DISPLAY; " +
                        "skipping privileged Wi-Fi Direct sink configuration.",
                )
                wifiDisplayPermissionWarningLogged = true
            }
            wifiDirectOperational = false
            return
        }
        wifiDisplayPermissionWarningLogged = false

        val manager = wifiDirectManager ?: WifiDirectManager(applicationContext).also {
            wifiDirectManager = it
        }
        ensureWifiDisplaySystemSettingEnabled(reason = "wifi_direct_setup")

        if (!wifiDirectInitialized) {
            val initResult = runCatching { manager.initialize() }
            wifiDirectInitialized = initResult.isSuccess
            initResult.onFailure {
                println(
                    "MIRACAST_BG: ⚠️ Wi-Fi Direct init failed (infra mode still active): " +
                        "${it::class.java.simpleName}: ${it.message}",
                )
                wifiDirectOperational = false
            }
        }

        if (!wifiDirectInitialized) {
            wifiDirectOperational = false
            return
        }

        if (!WifiDirectPermissionHelper.hasPermissions(applicationContext)) {
            val missing = WifiDirectPermissionHelper.getMissingPermissions(applicationContext)
            println(
                "MIRACAST_BG: ⚠️ Missing Wi-Fi Direct runtime permissions, skipping WFD sink setup: " +
                    missing.joinToString(),
            )
            wifiDirectOperational = false
            return
        }

        val p2pStateBeforeSetup = manager.requestP2pStateBestEffort()
        println(
            "MIRACAST_BG: ℹ️ Wi-Fi Direct framework state before setup: " +
                manager.describeP2pState(p2pStateBeforeSetup),
        )
        if (p2pStateBeforeSetup == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
            println(
                "MIRACAST_BG: ⚠️ Framework reports Wi-Fi Direct disabled. " +
                    "Miracast Win+K discovery will be retried until state recovers.",
            )
        }

        try {
            if (!wifiDirectReceiverRegistered) {
                manager.registerReceiver { info ->
                    println(
                        "MIRACAST_BG: 🔗 P2P connection info groupFormed=${info.groupFormed} " +
                            "isGroupOwner=${info.isGroupOwner} " +
                            "owner=${info.groupOwnerAddress?.hostAddress}",
                    )

                    // Some Windows builds complete P2P but delay RTSP toward sink.
                    // Probe source RTSP endpoint after group formation for resilience.
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
                wifiDirectReceiverRegistered = true
            }

            if (!MiracastPlaybackManager.state.value.isSessionActive) {
                manager.resetForMiracastStartup()
            }
            manager.configureMiracastSink(
                deviceName = deviceName,
                rtspPort = MiracastPorts.WFD_RTSP_PORT,
            ).getOrThrow()
            manager.advertiseService(
                deviceName = deviceName,
                rtspPort = MiracastPorts.WFD_RTSP_PORT,
            ).getOrThrow()
            manager.startDiscovery(
                deviceName = deviceName,
                onConnectionRequest = { peer ->
                    println(
                        "MIRACAST_BG: 📩 Incoming request from " +
                            "${peer.deviceName} (${peer.deviceAddress})",
                    )
                },
            ).getOrThrow()
            val p2pStateAfterSetup = manager.requestP2pStateBestEffort()
            wifiDirectOperational = p2pStateAfterSetup != WifiP2pManager.WIFI_P2P_STATE_DISABLED
            println(
                "MIRACAST_BG: ✅ Wi-Fi Direct sink advertised + peer discovery active " +
                    "(state=${manager.describeP2pState(p2pStateAfterSetup)})",
            )
        } catch (error: Throwable) {
            wifiDirectOperational = false
            println("MIRACAST_BG: ⚠️ Wi-Fi Direct sink setup failed: ${error.message}")
        }
    }

    private fun ensureWifiDirectRecoveryLoop(deviceName: String) {
        if (wifiDirectRecoveryJob?.isActive == true) return

        wifiDirectRecoveryJob = scope.launch {
            while (isActive) {
                delay(WIFI_DIRECT_RECOVERY_INTERVAL_MS)
                if (!stackRunning) continue
                if (MiracastPlaybackManager.state.value.isSessionActive) continue
                if (isAirPlaySessionActive()) continue

                val manager = wifiDirectManager ?: continue
                val p2pState = runCatching {
                    manager.requestP2pStateBestEffort()
                }.getOrNull()
                val p2pEnabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                if (wifiDirectOperational && p2pEnabled) continue

                println(
                    "MIRACAST_BG: ⚠️ Wi-Fi Direct not healthy " +
                        "(state=${manager.describeP2pState(p2pState)}, operational=$wifiDirectOperational). " +
                        "Retrying setup.",
                )
                ensureWifiDisplaySystemSettingEnabled(reason = "wifi_direct_recovery")
                runCatching {
                    configureWifiDirectSinkLite(deviceName)
                }.onFailure {
                    wifiDirectOperational = false
                    println(
                        "MIRACAST_BG: ⚠️ Wi-Fi Direct recovery attempt failed: " +
                            "${it::class.java.simpleName}: ${it.message}",
                    )
                }
            }
        }
    }

    private fun isAirPlaySessionActive(): Boolean {
        val receiver = AirPlayReceiverHolder.receiver ?: return false
        return receiver.status.value == AirPlayReceiver.Status.Receiving ||
            receiver.connectedClientCount.value > 0 ||
            receiver.activeMirroringClientIds.value.isNotEmpty()
    }

    private fun hasWifiDisplayPermission(): Boolean {
        return runCatching {
            applicationContext.checkSelfPermission(Manifest.permission.CONFIGURE_WIFI_DISPLAY) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * M-12: Only modify the system setting when the Miracast stack is intentionally running.
     * The [stackRunning] flag is set when a user-initiated action starts the stack,
     * preventing background recovery loops from silently enabling WiFi Display.
     */
    private fun ensureWifiDisplaySystemSettingEnabled(reason: String) {
        if (!stackRunning && reason != "stack_start") return
        runCatching {
            val resolver = applicationContext.contentResolver
            val currentValue = Settings.Global.getInt(
                resolver,
                WIFI_DISPLAY_ON_GLOBAL_KEY,
                0,
            )
            if (currentValue == 1) return@runCatching

            val writeApplied = Settings.Global.putInt(
                resolver,
                WIFI_DISPLAY_ON_GLOBAL_KEY,
                1,
            )
            val effectiveValue = Settings.Global.getInt(
                resolver,
                WIFI_DISPLAY_ON_GLOBAL_KEY,
                0,
            )
            println(
                "MIRACAST_BG: 🔧 Forced wifi_display_on=1 ($reason, " +
                    "writeApplied=$writeApplied, effective=$effectiveValue)",
            )
        }.onFailure {
            println(
                "MIRACAST_BG: ⚠️ Could not set wifi_display_on=1 ($reason): " +
                    "${it::class.java.simpleName}: ${it.message}",
            )
        }
    }

    private suspend fun stopMiracastStack(reason: String) {
        if (!stackRunning &&
            miracastMdnsService == null &&
            miracastSsdpService == null &&
            miracastRtspServer == null &&
            miracastMiceServer == null &&
            dialHttpServer == null &&
            wifiDirectRecoveryJob?.isActive != true &&
            !ownsMiracastLease
        ) {
            return
        }

        println("MIRACAST_BG: 🧹 Stopping background stack ($reason)")
        cancelSourceRtspRetry("service stopping")
        MiracastPlaybackManager.stopSession(closeViewer = true)
        wifiDirectRecoveryJob?.cancel()
        wifiDirectRecoveryJob = null
        try {
            dialHttpServer?.stop()
        } catch (_: Throwable) {
        }
        try {
            miracastMiceServer?.stop()
        } catch (_: Throwable) {
        }
        try {
            miracastRtspServer?.stop()
        } catch (_: Throwable) {
        }
        try {
            miracastMdnsService?.stopAdvertisement()
        } catch (_: Throwable) {
        }
        try {
            miracastSsdpService?.stopDiscovery()
        } catch (_: Throwable) {
        }
        try {
            wifiDirectManager?.cleanup()
        } catch (_: Throwable) {
        }

        dialHttpServer = null
        miracastMiceServer = null
        miracastRtspServer = null
        miracastMdnsService = null
        miracastSsdpService = null
        stackRunning = false
        wifiDirectInitialized = false
        wifiDirectReceiverRegistered = false
        wifiDirectOperational = false
        if (ownsMiracastLease) {
            MiracastStackLease.release(MiracastStackLease.OWNER_BACKGROUND)
            ownsMiracastLease = false
        }
    }

    private fun cancelSourceRtspRetry(reason: String) {
        val target = sourceRtspRetryTarget
        val job = sourceRtspRetryJob
        if (job == null && target == null) return

        println("MIRACAST_BG: 🧹 Clearing RTSP fallback target ${target ?: "unknown"} ($reason)")
        job?.cancel()
        sourceRtspRetryJob = null
        sourceRtspRetryTarget = null
    }

    private fun scheduleSourceRtspRetry(
        sourceAddress: String,
        sourcePort: Int,
        reason: String,
        initialDelayMs: Long = 0L,
        maxAttempts: Int = 8,
        replaceExisting: Boolean = true,
    ) {
        val rtspServer = miracastRtspServer ?: return
        val normalizedAddress = sourceAddress.substringBefore('%').trim()
        if (normalizedAddress.isBlank()) return

        val boundedPort = sourcePort.coerceIn(1, 0xFFFF)
        val targetKey = "$normalizedAddress:$boundedPort"
        val existingJob = sourceRtspRetryJob
        val existingTarget = sourceRtspRetryTarget

        if (existingJob?.isActive == true) {
            if (existingTarget == targetKey) {
                println("MIRACAST_BG: ℹ️ RTSP fallback already scheduled for $targetKey ($reason)")
                return
            }
            if (!replaceExisting) {
                println(
                    "MIRACAST_BG: ℹ️ Keeping existing RTSP fallback target " +
                        "${existingTarget ?: "unknown"} instead of switching to $targetKey ($reason)",
                )
                return
            }

            println(
                "MIRACAST_BG: 🔄 Switching RTSP fallback target " +
                    "${existingTarget ?: "unknown"} -> $targetKey ($reason)",
            )
            existingJob.cancel()
        }

        val job = scope.launch(Dispatchers.IO) {
            if (initialDelayMs > 0) delay(initialDelayMs)

            repeat(maxAttempts) { attemptIndex ->
                val hasActiveStream =
                    MiracastPlaybackManager.hasRecentPlaybackActivity(withinMs = 8_000L)
                val hasActiveControl =
                    rtspServer.hasActiveControlConnection() ||
                        rtspServer.hasRecentRtspActivity(withinMs = 8_000L)
                if (hasActiveStream || hasActiveControl) {
                    println(
                        "MIRACAST_BG: ✅ Stream/control session already active; " +
                            "skipping RTSP fallback to $targetKey",
                    )
                    return@launch
                }

                val attempt = attemptIndex + 1
                println("MIRACAST_BG: 🔄 RTSP fallback attempt $attempt/$maxAttempts to $targetKey ($reason)")

                val result = rtspServer.connectToSource(
                    sourceAddress = normalizedAddress,
                    sourcePort = boundedPort,
                )

                if (result.isSuccess) {
                    println("MIRACAST_BG: ✅ RTSP negotiation started with $targetKey")
                    return@launch
                }

                val error = result.exceptionOrNull()
                val errorSummary = buildString {
                    append(error?.javaClass?.simpleName ?: "UnknownError")
                    error?.message?.takeIf { it.isNotBlank() }?.let {
                        append(": ").append(it)
                    }
                }
                println(
                    "MIRACAST_BG: ⚠️ RTSP fallback attempt $attempt/$maxAttempts " +
                        "failed for $targetKey: $errorSummary",
                )

                if (attempt < maxAttempts) {
                    delay(1_000L + (attemptIndex * 250L))
                }
            }

            println("MIRACAST_BG: ❌ RTSP fallback exhausted for $targetKey ($reason)")
        }

        sourceRtspRetryJob = job
        sourceRtspRetryTarget = targetKey
        job.invokeOnCompletion {
            if (sourceRtspRetryJob === job) sourceRtspRetryJob = null
            if (sourceRtspRetryTarget == targetKey) sourceRtspRetryTarget = null
        }
    }

    private fun enterForeground(): Boolean {
        return runCatching {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            println("MIRACAST_BG: ❌ Failed to enter foreground: ${it.message}")
        }.isSuccess
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Miracast Background Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps TeachmintShareX discoverable in Windows+K"
            }
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TeachmintShareX")
            .setContentText("Miracast host discoverability is active")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun resolveMiracastDeviceUuid(context: Context, advertisedDeviceName: String?): String {
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
                "MIRACAST_BG: Using device receiver UUID $resolvedUuid ($reason, " +
                    "host='${normalizedAdvertisedName ?: "unknown"}')",
            )
        }
        return resolvedUuid
    }

    private fun resolveAdvertisedDeviceName(): String {
        val overrideName = preferredDeviceNameOverride
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (overrideName != null) return overrideName

        val savedHostName = runCatching {
            SharedPreferenceUtils.readString(
                key = SharedPreferenceConstants.HOST_DEVICE_NAME,
                defaultValue = null,
            )
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (savedHostName != null) return savedHostName

        return getHostDisplayName().ifBlank { "TeachmintShareX" }
    }

    companion object {
        private const val ACTION_START = "com.teachmint.sharex.action.MIRACAST_BG_START"
        private const val ACTION_STOP = "com.teachmint.sharex.action.MIRACAST_BG_STOP"
        private const val EXTRA_PREFERRED_DEVICE_NAME =
            "com.teachmint.sharex.extra.PREFERRED_DEVICE_NAME"
        private const val NOTIFICATION_CHANNEL_ID = "sharex_miracast_background"
        private const val NOTIFICATION_ID = 19001
        private const val INITIAL_RETRY_DELAY_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L
        private const val LEASE_BUSY_RETRY_MS = 15_000L
        private const val WIFI_DIRECT_RECOVERY_INTERVAL_MS = 12_000L
        private const val AIRPLAY_ACTIVE_RETRY_PAUSE_MS = 20_000L
        private const val WIFI_DISPLAY_ON_GLOBAL_KEY = "wifi_display_on"
        private const val MIRACAST_PREF_FILE = "miracast"
        private const val MIRACAST_DEVICE_UUID_KEY = "device_uuid"
        private const val MIRACAST_DEVICE_SOURCE_ID_KEY = "device_uuid_source_id"
        private const val MIRACAST_DEVICE_ADVERTISED_NAME_KEY = "device_uuid_advertised_name"

        @Volatile
        private var running: Boolean = false

        @Volatile
        private var startRequested: Boolean = false

        fun isRunning(): Boolean = running

        fun isRunningOrRequested(): Boolean = running || startRequested

        fun ensureRunning(
            context: Context,
            reason: String = "unspecified",
            preferredDeviceName: String? = null,
        ) {
            val appContext = context.applicationContext
            AndroidContextHolder.init(appContext)
            if (runCatching { getDeviceRole() }.getOrDefault(DeviceRole.CLIENT) != DeviceRole.HOST) {
                return
            }

            startRequested = true
            val intent = Intent(appContext, MiracastAdvertiserService::class.java).apply {
                action = ACTION_START
                putExtra("reason", reason)
                preferredDeviceName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { putExtra(EXTRA_PREFERRED_DEVICE_NAME, it) }
            }
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
            }.onFailure {
                startRequested = false
                println("MIRACAST_BG: ❌ Failed to request start ($reason): ${it.message}")
            }
        }

        fun stop(context: Context, reason: String = "unspecified") {
            startRequested = false
            val appContext = context.applicationContext
            val intent = Intent(appContext, MiracastAdvertiserService::class.java).apply {
                action = ACTION_STOP
                putExtra("reason", reason)
            }
            runCatching { appContext.startService(intent) }
                .onFailure {
                    println("MIRACAST_BG: ⚠️ Failed to request stop ($reason): ${it.message}")
                }
        }
    }

    private class MiracastLeaseBusyException(val owner: String?) :
        IllegalStateException("Miracast stack lease held by ${owner ?: "unknown"}")
}
