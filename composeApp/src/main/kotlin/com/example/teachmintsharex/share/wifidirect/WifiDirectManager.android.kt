package com.example.teachmintsharex.share.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * WifiDirectManager handles Wi-Fi Direct P2P for Miracast.
 * This is required for Windows+P discovery and connection.
 */
class WifiDirectManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var p2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    private val _groupInfo = MutableStateFlow<WifiP2pGroup?>(null)
    val groupInfo: StateFlow<WifiP2pGroup?> = _groupInfo

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private var receiver: BroadcastReceiver? = null
    private var deviceName: String = ""
    private var onConnectionRequest: ((WifiP2pDevice) -> Unit)? = null
    private val invitationInFlight = mutableSetOf<String>()
    private var discoveryKeepAliveJob: Job? = null
    private var pendingDisconnectValidationJob: Job? = null
    private var isP2pConnected: Boolean = false
    private var discoveryRefreshPausedUntilMs: Long = 0L
    private var peerDiscoverySuppressedForAppTraffic: Boolean = false
    private var lastPeerListRequestAtMs: Long = 0L
    private var lastDiscoveryRefreshAttemptAtMs: Long = 0L
    private var preferListenMode: Boolean = false

    /**
     * Initializes Wi-Fi Direct P2P manager
     */
    @SuppressLint("MissingPermission")
    fun initialize() {
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            p2pManager = null
            channel = null
            println("WIFI_DIRECT: ⚠️ WifiP2pManager unavailable on this device/build")
            return
        }

        p2pManager = manager
        channel = runCatching {
            manager.initialize(context, context.mainLooper, null)
        }.onFailure { throwable ->
            val rootCause = throwable.cause ?: throwable
            println(
                "WIFI_DIRECT: ⚠️ Failed to initialize P2P manager: " +
                    "${rootCause::class.java.simpleName}: ${rootCause.message}",
            )
            if (rootCause is SecurityException) {
                println(
                    "WIFI_DIRECT: ⚠️ Missing required permission for P2P initialize. " +
                        "Ensure android.permission.CHANGE_WIFI_STATE is granted.",
                )
            }
        }.getOrNull()

        if (channel == null) {
            // Keep state consistent so later operations fail gracefully instead of crashing.
            p2pManager = null
            return
        }

        println("WIFI_DIRECT: 🔄 Initialized P2P manager")
    }

    /**
     * Configures this device as a Miracast/WFD sink.
     *
     * This uses hidden APIs on many Android builds, so reflection is used and failures are non-fatal.
     */
    @SuppressLint("MissingPermission")
    suspend fun configureMiracastSink(deviceName: String, rtspPort: Int): Result<Unit> =
        suspendCoroutine { continuation ->
            val manager = p2pManager
            val ch = channel

            if (manager == null || ch == null) {
                continuation.resume(Result.failure(Exception("P2P manager not initialized")))
                return@suspendCoroutine
            }

            var setWfdInfoError: Throwable? = null

            // Some vendor stacks require explicit sink mode to surface in Windows+K.
            setMiracastSinkModeBestEffort(manager, ch)

            // Pre-emptively write the device name to Settings.Global so the P2P stack
            // picks it up on next init even if the runtime setDeviceName call fails.
            setDeviceNameViaSettings(deviceName)

            // 1) Attempt to set friendly P2P device name.
            runCatching {
                val setDeviceNameMethod = manager.javaClass.getMethod(
                    "setDeviceName",
                    WifiP2pManager.Channel::class.java,
                    String::class.java,
                    WifiP2pManager.ActionListener::class.java,
                )
                setDeviceNameMethod.invoke(
                    manager,
                    ch,
                    deviceName,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            println("WIFI_DIRECT: ✅ P2P device name set to '$deviceName'")
                        }

                        override fun onFailure(reason: Int) {
                            println(
                                "WIFI_DIRECT: ⚠️ setDeviceName failed: " +
                                    getP2pErrorMessage(reason),
                            )
                            setDeviceNameViaSettings(deviceName)
                        }
                    },
                )
            }.onFailure {
                println(
                    "WIFI_DIRECT: ℹ️ setDeviceName unavailable: " +
                        "${it::class.java.simpleName}: ${it.message} cause=${it.cause?.javaClass?.simpleName}:${it.cause?.message}",
                )
                setDeviceNameViaSettings(deviceName)
            }

            // 2) Attempt to publish WFD sink information.
            runCatching {
                val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
                val wfdInfo = wfdInfoClass.getDeclaredConstructor().newInstance()

                wfdInfoClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(wfdInfo, true)
                // Primary sink device type.
                wfdInfoClass.getMethod("setDeviceType", Int::class.javaPrimitiveType)
                    .invoke(wfdInfo, 1)
                wfdInfoClass.getMethod("setSessionAvailable", Boolean::class.javaPrimitiveType)
                    .invoke(wfdInfo, true)
                wfdInfoClass.getMethod("setControlPort", Int::class.javaPrimitiveType)
                    .invoke(wfdInfo, rtspPort)
                wfdInfoClass.getMethod("setMaxThroughput", Int::class.javaPrimitiveType)
                    .invoke(wfdInfo, 300)

                val setWfdInfoMethod = resolveSetWfdInfoMethod(
                    managerClass = manager.javaClass,
                    wfdInfoClass = wfdInfoClass,
                ) ?: throw NoSuchMethodException(
                    "No compatible setWfdInfo/setWFDInfo method found",
                )

                setWfdInfoMethod.invoke(
                    manager,
                    ch,
                    wfdInfo,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            println("WIFI_DIRECT: ✅ WFD sink info configured (rtspPort=$rtspPort)")
                        }

                        override fun onFailure(reason: Int) {
                            println(
                                "WIFI_DIRECT: ⚠️ setWFDInfo failed: " +
                                    getP2pErrorMessage(reason),
                            )
                        }
                    },
                )
            }.onFailure {
                setWfdInfoError = it.cause ?: it
                val wfdMethodNames = collectWfdRelatedMethodNames(manager.javaClass)
                println(
                    "WIFI_DIRECT: ℹ️ setWFDInfo unavailable: " +
                        "${it::class.java.simpleName}: ${it.message} cause=${it.cause?.javaClass?.simpleName}:${it.cause?.message}",
                )
                if (wfdMethodNames.isNotEmpty()) {
                    println("WIFI_DIRECT: ℹ️ Available Wi-Fi Direct WFD methods: ${wfdMethodNames.joinToString()}")
                } else {
                    println("WIFI_DIRECT: ℹ️ No WFD-related methods exposed on WifiP2pManager")
                }
            }

            if (setWfdInfoError != null) {
                val wfdError: Throwable = setWfdInfoError
                continuation.resume(
                    Result.failure(
                        IllegalStateException(
                            "WFD sink info configuration failed: " +
                                "${wfdError.javaClass.simpleName}: ${wfdError.message}",
                            wfdError,
                        ),
                    ),
                )
            } else {
                continuation.resume(Result.success(Unit))
            }
        }

    /**
     * Makes device discoverable for Windows+P to find
     */
    @SuppressLint("MissingPermission")
    suspend fun startDiscovery(
        deviceName: String,
        onConnectionRequest: (WifiP2pDevice) -> Unit
    ): Result<Unit> = suspendCoroutine { continuation ->
        // Check permissions first
        if (!WifiDirectPermissionHelper.hasPermissions(context)) {
            val missing = WifiDirectPermissionHelper.getMissingPermissions(context)
            println("WIFI_DIRECT: ❌ Missing permissions: ${missing.joinToString()}")
            println("WIFI_DIRECT: 📱 Please grant permissions in Settings > Apps > TeachmintShareX > Permissions")
            continuation.resume(Result.failure(Exception("Missing permissions: ${missing.joinToString()}")))
            return@suspendCoroutine
        }

        this.deviceName = deviceName
        this.onConnectionRequest = onConnectionRequest

        val manager = p2pManager
        val ch = channel

        if (manager == null || ch == null) {
            continuation.resume(Result.failure(Exception("P2P manager not initialized")))
            return@suspendCoroutine
        }

        println("WIFI_DIRECT: 🔄 Starting peer discovery")

        // Prefer listen mode for sink discoverability; fallback to active peer scans.
        val startedListening = startListeningBestEffort(
            manager = manager,
            ch = ch,
            reason = "startDiscovery",
        )
        if (startedListening) {
            preferListenMode = true
            _isDiscovering.value = true
            lastDiscoveryRefreshAttemptAtMs = System.currentTimeMillis()
            startDiscoveryKeepAlive()
            continuation.resume(Result.success(Unit))
            return@suspendCoroutine
        }

        preferListenMode = false
        // Start peer discovery
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                println("WIFI_DIRECT: ✅ Peer discovery initiated")
                _isDiscovering.value = true
                lastDiscoveryRefreshAttemptAtMs = System.currentTimeMillis()
                startDiscoveryKeepAlive()
                continuation.resume(Result.success(Unit))
            }

            override fun onFailure(reason: Int) {
                val error = getP2pErrorMessage(reason)
                println("WIFI_DIRECT: ❌ Failed to start discovery: $error")
                _isDiscovering.value = false
                continuation.resume(Result.failure(Exception("Failed to start discovery: $error")))
            }
        })
    }

    /**
     * Polls for group info until available or timeout
     * More reliable than fixed delays
     *
     * @param timeoutMs Maximum time to wait for group
     * @param pollIntervalMs How often to check for group
     * @return WifiP2pGroup if found within timeout, null otherwise
     */
    @SuppressLint("MissingPermission")
    private suspend fun pollForGroup(
        timeoutMs: Long = 10_000,
        pollIntervalMs: Long = 500
    ): WifiP2pGroup? {
        val manager = p2pManager
        val ch = channel

        if (manager == null || ch == null) {
            return null
        }

        val startTime = System.currentTimeMillis()
        var attempts = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attempts++

            val group = suspendCoroutine<WifiP2pGroup?> { cont ->
                manager.requestGroupInfo(ch) { group ->
                    cont.resume(group)
                }
            }

            if (group != null) {
                println("WIFI_DIRECT: ✅ Group found after ${attempts} attempts (${System.currentTimeMillis() - startTime}ms)")
                return group
            }

            kotlinx.coroutines.delay(pollIntervalMs)
        }

        println("WIFI_DIRECT: ⚠️ Group not found after ${timeoutMs}ms ($attempts attempts)")
        return null
    }

    /**
     * Accepts incoming connection request and creates group
     * Uses polling instead of fixed delay for better reliability
     */
    @SuppressLint("MissingPermission")
    suspend fun acceptConnection(device: WifiP2pDevice): Result<WifiP2pGroup> = suspendCoroutine { continuation ->
        val manager = p2pManager
        val ch = channel

        if (manager == null || ch == null) {
            continuation.resume(Result.failure(Exception("P2P manager not initialized")))
            return@suspendCoroutine
        }

        println("WIFI_DIRECT: 🔄 Accepting connection from ${device.deviceName}")

        // For Miracast source->sink sessions, prefer the source (Windows) as Group Owner.
        // Forcing GO on sink side can result in "Couldn't connect" after user approval.
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }

        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                println("WIFI_DIRECT: ✅ Connection initiated")

                // Poll for group formation instead of fixed delay
                scope.launch {
                    val group = pollForGroup(timeoutMs = 10_000, pollIntervalMs = 500)

                    if (group != null) {
                        _groupInfo.value = group
                        println("WIFI_DIRECT: 📡 Group formed: ${group.networkName}")
                        println("WIFI_DIRECT: 📡 Group Owner: ${group.isGroupOwner}")
                        continuation.resume(Result.success(group))
                    } else {
                        continuation.resume(Result.failure(Exception("Group formation timed out")))
                    }
                }
            }

            override fun onFailure(reason: Int) {
                val error = getP2pErrorMessage(reason)
                println("WIFI_DIRECT: ❌ Failed to connect: $error")
                continuation.resume(Result.failure(Exception("Failed to connect: $error")))
            }
        })
    }

    /**
     * Accepts connection with retry logic
     * More reliable than acceptConnection() for flaky connections
     *
     * @param device Device to connect to
     * @param maxAttempts Maximum number of retry attempts
     * @return Result containing WifiP2pGroup on success
     */
    @SuppressLint("MissingPermission")
    suspend fun acceptConnectionWithRetry(
        device: WifiP2pDevice,
        maxAttempts: Int = 3
    ): Result<WifiP2pGroup> {
        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < maxAttempts) {
            attempt++
            println("WIFI_DIRECT: 🔄 Accepting connection (attempt $attempt/$maxAttempts)")

            val result = acceptConnection(device)
            if (result.isSuccess) {
                println("WIFI_DIRECT: ✅ Connection accepted on attempt $attempt")
                return result
            }

            lastError = result.exceptionOrNull()
            println("WIFI_DIRECT: ⚠️ Attempt $attempt failed: ${lastError?.message}")

            if (attempt < maxAttempts) {
                println("WIFI_DIRECT: ⏳ Waiting 2s before retry...")
                kotlinx.coroutines.delay(2000)
            }
        }

        return Result.failure(
            Exception(
                "Failed to accept connection after $maxAttempts attempts. Last error: ${lastError?.message}",
                lastError
            )
        )
    }

    /**
     * Creates Wi-Fi Direct Group with retry logic
     * More reliable than createGroup() for Windows+K connections
     *
     * @param deviceName Device name for the group
     * @param maxAttempts Maximum number of retry attempts
     * @param initialDelayMs Initial delay between retries (doubles each attempt)
     * @return Result containing WifiP2pGroup on success
     */
    @SuppressLint("MissingPermission")
    suspend fun createGroupWithRetry(
        deviceName: String,
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1000
    ): Result<WifiP2pGroup> {
        // Check permissions first
        if (!WifiDirectPermissionHelper.hasPermissions(context)) {
            val missing = WifiDirectPermissionHelper.getMissingPermissions(context)
            println("WIFI_DIRECT: ❌ Missing permissions: ${missing.joinToString()}")
            println("WIFI_DIRECT: 📱 Please grant permissions in Settings > Apps > TeachmintShareX > Permissions")
            return Result.failure(Exception("Missing permissions: ${missing.joinToString()}"))
        }

        val manager = p2pManager
        val ch = channel

        if (manager == null || ch == null) {
            return Result.failure(Exception("P2P manager not initialized"))
        }

        var attempt = 0
        var lastError: Throwable? = null
        var delayMs = initialDelayMs

        while (attempt < maxAttempts) {
            val existingGroup = _groupInfo.value
            if (existingGroup != null && existingGroup.isGroupOwner) {
                println("WIFI_DIRECT: ✅ Reusing existing P2P group: ${existingGroup.networkName}")
                return Result.success(existingGroup)
            }

            attempt++
            println("WIFI_DIRECT: 🔄 Creating P2P group (attempt $attempt/$maxAttempts)")

            val result = createGroup(deviceName)
            if (result.isSuccess) {
                println("WIFI_DIRECT: ✅ Group created successfully on attempt $attempt")
                return result
            }

            // Group info can arrive slightly after createGroup callback success/failure.
            // Treat an observed stable GO group as success to avoid teardown/recreate churn.
            val observedGroup = _groupInfo.value ?: pollForGroup(timeoutMs = 4000, pollIntervalMs = 400)
            if (observedGroup != null && observedGroup.isGroupOwner) {
                _groupInfo.value = observedGroup
                println(
                    "WIFI_DIRECT: ✅ Group became available after callback race " +
                        "(attempt $attempt): ${observedGroup.networkName}",
                )
                return Result.success(observedGroup)
            }

            lastError = result.exceptionOrNull()
            println("WIFI_DIRECT: ⚠️ Attempt $attempt failed: ${lastError?.message}")

            if (attempt < maxAttempts) {
                println("WIFI_DIRECT: ⏳ Waiting ${delayMs}ms before retry...")
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2 // Exponential backoff
            }
        }

        return Result.failure(
            Exception(
                "Failed to create group after $maxAttempts attempts. Last error: ${lastError?.message}",
                lastError
            )
        )
    }

    /**
     * Creates Wi-Fi Direct Group as Group Owner (legacy method, kept for compatibility)
     */
    @SuppressLint("MissingPermission")
    suspend fun createGroup(deviceName: String): Result<WifiP2pGroup> = suspendCoroutine { continuation ->
        // Check permissions first
        if (!WifiDirectPermissionHelper.hasPermissions(context)) {
            val missing = WifiDirectPermissionHelper.getMissingPermissions(context)
            println("WIFI_DIRECT: ❌ Missing permissions: ${missing.joinToString()}")
            println("WIFI_DIRECT: 📱 Please grant permissions in Settings > Apps > TeachmintShareX > Permissions")
            continuation.resume(Result.failure(Exception("Missing permissions: ${missing.joinToString()}")))
            return@suspendCoroutine
        }

        val manager = p2pManager
        val ch = channel

        if (manager == null || ch == null) {
            continuation.resume(Result.failure(Exception("P2P manager not initialized")))
            return@suspendCoroutine
        }

        manager.requestGroupInfo(ch) { existingGroup ->
            if (existingGroup != null && existingGroup.isGroupOwner) {
                _groupInfo.value = existingGroup
                println("WIFI_DIRECT: ✅ Existing GO group already active: ${existingGroup.networkName}")
                continuation.resume(Result.success(existingGroup))
                return@requestGroupInfo
            }

            println("WIFI_DIRECT: 🔄 Creating P2P group as Group Owner")

            // Remove existing group first
            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    println("WIFI_DIRECT: ✅ Removed existing group")
                    createNewGroup(manager, ch, deviceName, continuation)
                }

                override fun onFailure(reason: Int) {
                    // No existing group, proceed to create new one
                    createNewGroup(manager, ch, deviceName, continuation)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        deviceName: String,
        continuation: kotlin.coroutines.Continuation<Result<WifiP2pGroup>>
    ) {
        // Create group configuration
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiP2pConfig.Builder()
                .setNetworkName("DIRECT-$deviceName")
                .setPassphrase("12345678") // WPA2 requires 8+ chars
                .enablePersistentMode(false)
                .build()
        } else {
            null
        }

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                println("WIFI_DIRECT: ✅ P2P group created successfully")
                scope.launch {
                    val group = pollForGroup(timeoutMs = 8000, pollIntervalMs = 400)
                    if (group != null) {
                        _groupInfo.value = group
                        println("WIFI_DIRECT: 📡 Group Owner: ${group.isGroupOwner}")
                        println("WIFI_DIRECT: 📡 Network: ${group.networkName}")
                        println("WIFI_DIRECT: 📡 Passphrase: ${group.passphrase}")
                        continuation.resume(Result.success(group))
                    } else {
                        continuation.resume(Result.failure(Exception("Failed to get group info")))
                    }
                }
            }

            override fun onFailure(reason: Int) {
                val error = getP2pErrorMessage(reason)
                println("WIFI_DIRECT: ❌ Failed to create group: $error")
                continuation.resume(Result.failure(Exception("Failed to create group: $error")))
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && config != null) {
            manager.createGroup(ch, config, listener)
        } else {
            @Suppress("DEPRECATION")
            manager.createGroup(ch, listener)
        }
    }

    /**
     * Advertises Wi-Fi Direct service with Miracast/WFD capabilities
     */
    @SuppressLint("MissingPermission")
    suspend fun advertiseService(deviceName: String, rtspPort: Int): Result<Unit> = suspendCoroutine { continuation ->
        val manager = p2pManager
        val ch = channel

        if (manager == null || ch == null) {
            continuation.resume(Result.failure(Exception("P2P manager not initialized")))
            return@suspendCoroutine
        }

        // Wi-Fi Display (WFD) service info.
        // Format for wfd_devinfo: <2-byte device-info><2-byte rtsp-port><2-byte max-throughput>
        // Primary sink + session available => 0x0011.
        val wfdDeviceInfoFlags = 0x0011
        val boundedRtspPort = rtspPort.coerceIn(1, 0xFFFF)
        val maxThroughputMbps = 300
        val wfdDevInfo = String.format(
            Locale.US,
            "%04X%04X%04X",
            wfdDeviceInfoFlags,
            boundedRtspPort,
            maxThroughputMbps,
        )

        val record = mapOf(
            "txtvers" to "1",
            // WFD Device Information (Primary Sink/Display + control port + throughput)
            "wfd_devinfo" to wfdDevInfo,
            // WFD Video formats (H.264)
            "wfd_vformats" to "00000001",
            // WFD Audio formats (AAC)
            "wfd_aformats" to "00000001",
            // RTSP port
            "wfd_rtspport" to boundedRtspPort.toString(),
            // Friendly name
            "fn" to deviceName
        )

        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            deviceName,
            "_display._tcp", // Standard wireless display service type
            record
        )

        println(
            "WIFI_DIRECT: 🔄 Adding local service: $deviceName " +
                "(wfd_devinfo=$wfdDevInfo, rtspPort=$boundedRtspPort)",
        )

        manager.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                println("WIFI_DIRECT: ✅ Local service added successfully")
                continuation.resume(Result.success(Unit))
            }

            override fun onFailure(reason: Int) {
                val error = getP2pErrorMessage(reason)
                println("WIFI_DIRECT: ❌ Failed to add service: $error")
                continuation.resume(Result.failure(Exception("Failed to add service: $error")))
            }
        })
    }

    /**
     * Registers broadcast receiver for P2P events
     */
    fun registerReceiver(onConnectionChanged: (WifiP2pInfo) -> Unit) {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        println("WIFI_DIRECT: 📡 P2P state: ${if (enabled) "Enabled" else "Disabled"}")
                        if (enabled && _isDiscovering.value) {
                            startDiscoveryKeepAlive()
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val now = System.currentTimeMillis()
                        if (now - lastPeerListRequestAtMs < PEER_LIST_REQUEST_THROTTLE_MS) {
                            return
                        }
                        lastPeerListRequestAtMs = now

                        // Peers available - request peer list
                        p2pManager?.requestPeers(channel) { peers ->
                            if (peers.deviceList.isNotEmpty()) {
                                println("WIFI_DIRECT: 👥 Found ${peers.deviceList.size} peer(s)")
                                pauseDiscoveryRefresh(
                                    windowMs = PEER_VISIBLE_DISCOVERY_PAUSE_MS,
                                    reason = "peer list populated (${peers.deviceList.size} peer(s))",
                                )
                                peers.deviceList.forEach { peer ->
                                    println("WIFI_DIRECT: 📱 Peer: ${peer.deviceName} (${peer.deviceAddress})")

                                    // If this is an invitation (Windows trying to connect), handle it once per peer.
                                    if (peer.status == WifiP2pDevice.INVITED) {
                                        val address = peer.deviceAddress.orEmpty()
                                        val alreadyInGroup = _groupInfo.value
                                            ?.clientList
                                            ?.any { client -> client.deviceAddress.equals(address, ignoreCase = true) }
                                            ?: false
                                        if (alreadyInGroup) return@forEach

                                        pauseDiscoveryRefresh(
                                            windowMs = 20_000,
                                            reason = "incoming invitation from ${peer.deviceName} ($address)",
                                        )

                                        if (!invitationInFlight.add(address)) {
                                            println(
                                                "WIFI_DIRECT: ⏳ Invitation already in progress for " +
                                                    "${peer.deviceName} ($address), skipping duplicate event",
                                            )
                                            return@forEach
                                        }

                                        println("WIFI_DIRECT: 📩 Incoming connection request from ${peer.deviceName}")
                                        onConnectionRequest?.invoke(peer)

                                        // Let the platform complete invitation flow; avoid manual connect()
                                        // here because it can race with framework-owned negotiation and
                                        // produce "P2P framework busy".
                                        scope.launch {
                                            try {
                                                println(
                                                    "WIFI_DIRECT: ℹ️ Waiting for system-level invitation handling " +
                                                        "for ${peer.deviceName}",
                                                )
                                            } finally {
                                                kotlinx.coroutines.delay(12_000)
                                                invitationInFlight.remove(address)
                                            }
                                        }
                                    } else if (peer.status == WifiP2pDevice.CONNECTED) {
                                        pauseDiscoveryRefresh(
                                            windowMs = 10_000,
                                            reason = "peer reports connected state (${peer.deviceName})",
                                        )
                                    }
                                }
                            } else {
                                println("WIFI_DIRECT: 👥 No peers found")
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_NETWORK_INFO,
                                android.net.NetworkInfo::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }

                        if (networkInfo?.isConnected == true) {
                            pendingDisconnectValidationJob?.cancel()
                            pendingDisconnectValidationJob = null
                            isP2pConnected = true
                            discoveryKeepAliveJob?.cancel()
                            discoveryKeepAliveJob = null
                            pauseDiscoveryRefresh(windowMs = 20_000, reason = "P2P connection established")

                            println("WIFI_DIRECT: 🔗 P2P connection established")

                            // Request connection info
                            p2pManager?.requestConnectionInfo(channel) { info ->
                                if (info != null) {
                                    println("WIFI_DIRECT: 📡 Group Owner IP: ${info.groupOwnerAddress?.hostAddress}")
                                    println("WIFI_DIRECT: 📡 Is Group Owner: ${info.groupFormed && info.isGroupOwner}")

                                    // Request group info to get network details
                                    p2pManager?.requestGroupInfo(channel) { group ->
                                        if (group != null) {
                                            _groupInfo.value = group
                                            println("WIFI_DIRECT: 📡 Network: ${group.networkName}")
                                            println("WIFI_DIRECT: 📡 Passphrase: ${group.passphrase}")

                                            // Discover client IPs using ARP (after short delay for ARP to populate)
                                            scope.launch {
                                                kotlinx.coroutines.delay(1000) // Wait for ARP cache
                                                val clientIps = withContext(Dispatchers.IO) {
                                                    discoverClientIps()
                                                }
                                                if (clientIps.isNotEmpty()) {
                                                    clientIps.forEach { (mac, ip) ->
                                                        println("WIFI_DIRECT: 🎯 Client discovered: $mac -> $ip")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    onConnectionChanged(info)
                                }
                            }
                        } else {
                            pendingDisconnectValidationJob?.cancel()
                            pendingDisconnectValidationJob = scope.launch {
                                delay(1200)

                                val manager = p2pManager
                                val ch = channel
                                if (manager == null || ch == null) return@launch

                                manager.requestConnectionInfo(ch) { info ->
                                    if (info?.groupFormed == true) {
                                        println(
                                            "WIFI_DIRECT: ℹ️ Ignoring transient disconnect broadcast " +
                                                "(group still formed)",
                                        )
                                        return@requestConnectionInfo
                                    }

                                    val hadConnection = isP2pConnected
                                    isP2pConnected = false
                                    _groupInfo.value = null

                                    if (hadConnection) {
                                        println("WIFI_DIRECT: 🔌 P2P connection lost")
                                        pauseDiscoveryRefresh(windowMs = 4_000, reason = "post-disconnect stabilization")
                                        if (_isDiscovering.value) {
                                            scope.launch {
                                                delay(800)
                                                triggerDiscoveryRefresh("post-disconnect")
                                                startDiscoveryKeepAlive()
                                            }
                                        }
                                    } else {
                                        println(
                                            "WIFI_DIRECT: ℹ️ Ignoring disconnect before " +
                                                "connection is fully established",
                                        )
                                    }
                                }
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                                WifiP2pDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }

                        println("WIFI_DIRECT: 📱 Device name: ${device?.deviceName}")
                        println("WIFI_DIRECT: 📱 Device status: ${getDeviceStatus(device?.status)}")
                    }
                }
            }
        }

        context.registerReceiver(receiver, intentFilter)
        println("WIFI_DIRECT: ✅ Broadcast receiver registered")
    }

    private fun getDeviceStatus(status: Int?): String {
        return when (status) {
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown"
        }
    }

    /**
     * Stops peer discovery
     */
    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val manager = p2pManager
        val ch = channel

        _isDiscovering.value = false
        discoveryKeepAliveJob?.cancel()
        discoveryKeepAliveJob = null

        if (manager != null && ch != null) {
            if (preferListenMode) {
                stopListeningBestEffort(manager, ch, reason = "stopDiscovery")
                preferListenMode = false
                return
            }

            println("WIFI_DIRECT: 🔄 Stopping peer discovery")

            manager.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    println("WIFI_DIRECT: ✅ Peer discovery stopped")
                }

                override fun onFailure(reason: Int) {
                    println("WIFI_DIRECT: ⚠️ Failed to stop discovery: ${getP2pErrorMessage(reason)}")
                }
            })
        }
    }

    fun suspendPeerDiscoveryForAppTraffic(reason: String) {
        if (peerDiscoverySuppressedForAppTraffic) return
        peerDiscoverySuppressedForAppTraffic = true
        println("WIFI_DIRECT: ⏸️ Suspending peer discovery for app traffic ($reason)")
        stopDiscovery()
    }

    fun resumePeerDiscoveryAfterAppTraffic(reason: String) {
        if (!peerDiscoverySuppressedForAppTraffic) return
        peerDiscoverySuppressedForAppTraffic = false
        println("WIFI_DIRECT: ▶️ Resuming peer discovery after app traffic ($reason)")

        val discoveryCallback = onConnectionRequest
        if (deviceName.isBlank() || discoveryCallback == null) return

        scope.launch {
            val result = startDiscovery(
                deviceName = deviceName,
                onConnectionRequest = discoveryCallback,
            )
            result.onFailure {
                println(
                    "WIFI_DIRECT: ⚠️ Failed to resume peer discovery after app traffic: ${it.message}",
                )
            }
        }
    }

    /**
     * Removes Wi-Fi Direct group
     */
    @SuppressLint("MissingPermission")
    fun removeGroup() {
        val manager = p2pManager
        val ch = channel

        if (manager != null && ch != null) {
            println("WIFI_DIRECT: 🔄 Removing P2P group")

            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    println("WIFI_DIRECT: ✅ P2P group removed")
                    _groupInfo.value = null
                }

                override fun onFailure(reason: Int) {
                    println("WIFI_DIRECT: ⚠️ Failed to remove group: ${getP2pErrorMessage(reason)}")
                }
            })

            manager.clearLocalServices(ch, null)
            stopListeningBestEffort(manager, ch, reason = "removeGroup")
            preferListenMode = false
        }
    }

    /**
     * Performs best-effort cleanup before starting Miracast advertising/discovery.
     * This helps recover from stale persistent network entries that can break later negotiations.
     */
    @SuppressLint("MissingPermission")
    fun resetForMiracastStartup() {
        println("WIFI_DIRECT: 🔄 Resetting P2P state for Miracast startup")
        pendingDisconnectValidationJob?.cancel()
        pendingDisconnectValidationJob = null
        isP2pConnected = false
        invitationInFlight.clear()
        _groupInfo.value = null
        pauseDiscoveryRefresh(windowMs = 6_000, reason = "startup reset")
        clearPersistentGroupsBestEffort()
        removeGroup()
    }

    /**
     * Hidden-API best effort to delete stale persistent groups.
     * Some vendor stacks fail new invitations when stale network IDs linger in supplicant.
     */
    @SuppressLint("MissingPermission")
    fun clearPersistentGroupsBestEffort() {
        val manager = p2pManager
        val ch = channel
        if (manager == null || ch == null) return

        runCatching {
            val requestMethod = (manager.javaClass.methods + manager.javaClass.declaredMethods)
                .firstOrNull { method ->
                    method.name == "requestPersistentGroupInfo" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == WifiP2pManager.Channel::class.java
                }
            val deleteMethod = (manager.javaClass.methods + manager.javaClass.declaredMethods)
                .firstOrNull { method ->
                    method.name == "deletePersistentGroup" &&
                        method.parameterTypes.size == 3 &&
                        method.parameterTypes[0] == WifiP2pManager.Channel::class.java &&
                        method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                        method.parameterTypes[2] == WifiP2pManager.ActionListener::class.java
                }

            if (requestMethod == null || deleteMethod == null) {
                println("WIFI_DIRECT: ℹ️ Persistent group cleanup API unavailable on this build")
                return@runCatching
            }

            val listenerClass = requestMethod.parameterTypes[1]
            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name.equals("onPersistentGroupInfoAvailable", ignoreCase = true)) {
                    val ids = extractPersistentGroupNetworkIds(args?.firstOrNull())
                    if (ids.isEmpty()) {
                        println("WIFI_DIRECT: ℹ️ No persistent P2P groups found")
                    } else {
                        println(
                            "WIFI_DIRECT: 🔄 Deleting ${ids.size} persistent P2P group(s): " +
                                ids.joinToString(),
                        )
                        ids.forEach { networkId ->
                            deleteMethod.invoke(
                                manager,
                                ch,
                                networkId,
                                object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        println("WIFI_DIRECT: ✅ Deleted persistent group networkId=$networkId")
                                    }

                                    override fun onFailure(reason: Int) {
                                        println(
                                            "WIFI_DIRECT: ⚠️ Failed deleting persistent group networkId=$networkId: " +
                                                getP2pErrorMessage(reason),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                null
            }

            requestMethod.isAccessible = true
            deleteMethod.isAccessible = true
            requestMethod.invoke(manager, ch, proxy)
            println("WIFI_DIRECT: 🔄 Requested persistent group list for cleanup")
        }.onFailure {
            println(
                "WIFI_DIRECT: ⚠️ Persistent group cleanup failed: " +
                    "${it::class.java.simpleName}: ${it.message}",
            )
        }
    }

    /**
     * Requests current Wi-Fi Direct stack state.
     *
     * Returns null if the API is unavailable on the current framework build.
     */
    @SuppressLint("MissingPermission")
    suspend fun requestP2pStateBestEffort(timeoutMs: Long = 1_500L): Int? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val manager = p2pManager
                val ch = channel
                if (manager == null || ch == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val requestMethod = resolveRequestP2pStateMethod(manager.javaClass)
                if (requestMethod == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val listenerClass = requestMethod.parameterTypes.getOrNull(1)
                if (listenerClass == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val resumed = AtomicBoolean(false)
                val resumeOnce: (Int?) -> Unit = { state ->
                    if (resumed.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(state)
                    }
                }

                val listenerProxy = Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                ) { _, method, args ->
                    if (method.name.equals("onP2pStateAvailable", ignoreCase = true)) {
                        resumeOnce(args?.firstOrNull() as? Int)
                    }
                    null
                }

                runCatching {
                    requestMethod.isAccessible = true
                    requestMethod.invoke(manager, ch, listenerProxy)
                }.onFailure {
                    println(
                        "WIFI_DIRECT: ⚠️ requestP2pState failed: " +
                            "${it::class.java.simpleName}: ${it.message}",
                    )
                    resumeOnce(null)
                }
            }
        }
    }

    fun describeP2pState(state: Int?): String {
        return when (state) {
            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> "enabled"
            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> "disabled"
            null -> "unknown"
            else -> "unknown($state)"
        }
    }

    /**
     * Unregisters broadcast receiver
     */
    fun unregisterReceiver() {
        receiver?.let {
            context.unregisterReceiver(it)
            receiver = null
            println("WIFI_DIRECT: ✅ Broadcast receiver unregistered")
        }
    }

    /**
     * Gets client IP address from MAC address using ARP cache
     * This is useful for WiFi Direct where we know the peer's MAC but need their IP
     *
     * @param macAddress MAC address in format "aa:bb:cc:dd:ee:ff"
     * @return IP address if found in ARP cache, null otherwise
     */
    fun getClientIpFromMac(macAddress: String): String? {
        try {
            val normalizedMac = macAddress.lowercase().replace("-", ":")

            // Read /proc/net/arp to find IP-to-MAC mapping
            val arpEntries = java.io.File("/proc/net/arp").readText()

            // ARP file format:
            // IP address    HW type    Flags  HW address           Mask  Device
            // 192.168.49.2  0x1        0x2    aa:bb:cc:dd:ee:ff    *     p2p-p2p0-0

            arpEntries.lines().forEach { line ->
                if (line.contains(normalizedMac, ignoreCase = true)) {
                    // Extract IP address (first column)
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.isNotEmpty()) {
                        val ip = parts[0]
                        // Validate IP format
                        if (ip.matches(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))) {
                            println("WIFI_DIRECT: 🔍 Found IP $ip for MAC $macAddress via ARP")
                            return ip
                        }
                    }
                }
            }

            println("WIFI_DIRECT: ⚠️ No ARP entry found for MAC $macAddress")
            return null
        } catch (e: Exception) {
            println("WIFI_DIRECT: ❌ Failed to read ARP cache: ${e.message}")
            return null
        }
    }

    /**
     * Gets all connected peer MAC addresses from current P2P group
     * @return List of MAC addresses for connected peers
     */
    fun getConnectedPeerMacs(): List<String> {
        val group = _groupInfo.value
        if (group == null) {
            println("WIFI_DIRECT: ⚠️ No active P2P group")
            return emptyList()
        }

        return group.clientList.map { it.deviceAddress }
    }

    /**
     * Discovers client IP addresses from ARP cache for all connected peers
     * Useful for finding Windows source IP after WiFi Direct connection
     *
     * @return Map of MAC address to IP address for all found clients
     */
    fun discoverClientIps(): Map<String, String> {
        val peerMacs = getConnectedPeerMacs()
        if (peerMacs.isEmpty()) {
            println("WIFI_DIRECT: ⚠️ No connected peers to discover")
            return emptyMap()
        }

        val result = mutableMapOf<String, String>()
        peerMacs.forEach { mac ->
            val ip = getClientIpFromMac(mac)
            if (ip != null) {
                result[mac] = ip
            }
        }

        if (result.isNotEmpty()) {
            println("WIFI_DIRECT: ✅ Discovered ${result.size} client IP(s) via ARP")
            result.forEach { (mac, ip) ->
                println("WIFI_DIRECT: 📍 $mac -> $ip")
            }
        }

        return result
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        stopDiscovery()
        removeGroup()
        unregisterReceiver()
        pendingDisconnectValidationJob?.cancel()
        pendingDisconnectValidationJob = null
        isP2pConnected = false
        discoveryRefreshPausedUntilMs = 0L
        peerDiscoverySuppressedForAppTraffic = false
        lastPeerListRequestAtMs = 0L
        lastDiscoveryRefreshAttemptAtMs = 0L
        channel = null
        p2pManager = null
        onConnectionRequest = null
        invitationInFlight.clear()
        preferListenMode = false
        println("WIFI_DIRECT: 🧹 Cleanup complete")
    }

    @SuppressLint("MissingPermission")
    private fun triggerDiscoveryRefresh(reason: String) {
        val manager = p2pManager
        val ch = channel
        if (manager == null || ch == null || !_isDiscovering.value) return
        if (preferListenMode) {
            startListeningBestEffort(manager, ch, reason = reason)
            return
        }
        val now = System.currentTimeMillis()
        if (now < discoveryRefreshPausedUntilMs) {
            val remainingMs = discoveryRefreshPausedUntilMs - now
            println(
                "WIFI_DIRECT: ℹ️ Peer discovery refresh skipped ($reason): " +
                    "negotiation guard active for ${remainingMs}ms",
            )
            return
        }
        if (invitationInFlight.isNotEmpty()) {
            println(
                "WIFI_DIRECT: ℹ️ Peer discovery refresh skipped ($reason): " +
                    "invitation in progress for ${invitationInFlight.size} peer(s)",
            )
            return
        }
        if (isP2pConnected) {
            println("WIFI_DIRECT: ℹ️ Peer discovery refresh skipped ($reason): active P2P session")
            return
        }
        val elapsedSinceLastAttempt = now - lastDiscoveryRefreshAttemptAtMs
        if (elapsedSinceLastAttempt < DISCOVERY_REFRESH_MIN_INTERVAL_MS) {
            println(
                "WIFI_DIRECT: ℹ️ Peer discovery refresh skipped ($reason): " +
                    "throttled (${elapsedSinceLastAttempt}ms since last attempt)",
            )
            return
        }
        lastDiscoveryRefreshAttemptAtMs = now

        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                println("WIFI_DIRECT: 🔄 Peer discovery refresh succeeded ($reason)")
            }

            override fun onFailure(reasonCode: Int) {
                val message = getP2pErrorMessage(reasonCode)
                if (reasonCode == WifiP2pManager.BUSY) {
                    println("WIFI_DIRECT: ℹ️ Peer discovery refresh skipped ($reason): $message")
                } else {
                    println("WIFI_DIRECT: ⚠️ Peer discovery refresh failed ($reason): $message")
                }
            }
        })
    }

    private fun startDiscoveryKeepAlive() {
        if (!_isDiscovering.value) return
        if (discoveryKeepAliveJob?.isActive == true) return

        discoveryKeepAliveJob = scope.launch {
            while (isActive && _isDiscovering.value) {
                delay(DISCOVERY_KEEPALIVE_INTERVAL_MS)
                if (!_isDiscovering.value) break
                triggerDiscoveryRefresh("keepalive")
            }
        }
    }

    private fun pauseDiscoveryRefresh(windowMs: Long, reason: String) {
        if (windowMs <= 0L) return
        val until = System.currentTimeMillis() + windowMs
        if (until > discoveryRefreshPausedUntilMs) {
            discoveryRefreshPausedUntilMs = until
            println(
                "WIFI_DIRECT: ⏸️ Discovery refresh paused for ${windowMs}ms " +
                    "($reason)",
            )
        }
    }

    private fun getP2pErrorMessage(reason: Int): String {
        return when (reason) {
            WifiP2pManager.ERROR -> "Internal error"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported on this device"
            WifiP2pManager.BUSY -> "P2P framework busy"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "No service requests"
            else -> "Unknown error: $reason"
        }
    }

    private fun resolveSetWfdInfoMethod(
        managerClass: Class<*>,
        wfdInfoClass: Class<*>,
    ): java.lang.reflect.Method? {
        val methods = (managerClass.methods + managerClass.declaredMethods)
            .distinctBy { method ->
                buildString {
                    append(method.name)
                    append('#')
                    append(method.parameterTypes.joinToString { it.name })
                }
            }

        val selected = methods.firstOrNull { method ->
            method.name.equals("setWFDInfo", ignoreCase = true) &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == WifiP2pManager.Channel::class.java &&
                method.parameterTypes[1].name == wfdInfoClass.name &&
                method.parameterTypes[2] == WifiP2pManager.ActionListener::class.java
        }

        selected?.isAccessible = true
        return selected
    }

    private fun resolveRequestP2pStateMethod(
        managerClass: Class<*>,
    ): java.lang.reflect.Method? {
        val methods = (managerClass.methods + managerClass.declaredMethods)
            .distinctBy { method ->
                buildString {
                    append(method.name)
                    append('#')
                    append(method.parameterTypes.joinToString { it.name })
                }
            }

        val selected = methods.firstOrNull { method ->
            method.name == "requestP2pState" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == WifiP2pManager.Channel::class.java
        }

        selected?.isAccessible = true
        return selected
    }

    private fun setMiracastSinkModeBestEffort(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
    ) {
        val method = resolveMiracastModeMethod(manager.javaClass)
        if (method == null) {
            println("WIFI_DIRECT: ℹ️ Miracast mode API unavailable on this build")
            return
        }

        runCatching {
            method.isAccessible = true
            val params = method.parameterTypes
            when {
                params.size == 1 &&
                    params[0] == Int::class.javaPrimitiveType -> {
                    method.invoke(manager, MIRACAST_MODE_SINK)
                    println("WIFI_DIRECT: ✅ Miracast mode set to SINK")
                }

                params.size == 2 &&
                    params[0] == WifiP2pManager.Channel::class.java &&
                    params[1] == Int::class.javaPrimitiveType -> {
                    method.invoke(manager, ch, MIRACAST_MODE_SINK)
                    println("WIFI_DIRECT: ✅ Miracast mode set to SINK")
                }

                params.size == 2 &&
                    params[0] == Int::class.javaPrimitiveType &&
                    params[1] == WifiP2pManager.ActionListener::class.java -> {
                    method.invoke(
                        manager,
                        MIRACAST_MODE_SINK,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                println("WIFI_DIRECT: ✅ Miracast mode set to SINK")
                            }

                            override fun onFailure(reason: Int) {
                                println(
                                    "WIFI_DIRECT: ⚠️ setMiracastMode(SINK) failed: " +
                                        getP2pErrorMessage(reason),
                                )
                            }
                        },
                    )
                }

                params.size == 3 &&
                    params[0] == WifiP2pManager.Channel::class.java &&
                    params[1] == Int::class.javaPrimitiveType &&
                    params[2] == WifiP2pManager.ActionListener::class.java -> {
                    method.invoke(
                        manager,
                        ch,
                        MIRACAST_MODE_SINK,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                println("WIFI_DIRECT: ✅ Miracast mode set to SINK")
                            }

                            override fun onFailure(reason: Int) {
                                println(
                                    "WIFI_DIRECT: ⚠️ setMiracastMode(SINK) failed: " +
                                        getP2pErrorMessage(reason),
                                )
                            }
                        },
                    )
                }

                else -> println(
                    "WIFI_DIRECT: ℹ️ Miracast mode API signature unsupported " +
                        "(params=${method.parameterTypes.joinToString { it.simpleName }})",
                )
            }
        }.onFailure {
            println(
                "WIFI_DIRECT: ⚠️ setMiracastMode(SINK) unavailable: " +
                    "${it::class.java.simpleName}: ${it.message}",
            )
        }
    }

    private fun startListeningBestEffort(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        reason: String,
    ): Boolean {
        val method = resolveListenMethod(manager.javaClass, start = true) ?: return false

        return runCatching {
            method.isAccessible = true
            when (method.parameterTypes.size) {
                1 -> method.invoke(manager, ch)
                2 -> method.invoke(
                    manager,
                    ch,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            println("WIFI_DIRECT: ✅ Listen mode active ($reason)")
                        }

                        override fun onFailure(reasonCode: Int) {
                            println(
                                "WIFI_DIRECT: ⚠️ startListening failed ($reason): " +
                                    getP2pErrorMessage(reasonCode),
                            )
                        }
                    },
                )

                else -> return false
            }
            println("WIFI_DIRECT: 🔄 Requested listen mode ($reason)")
            true
        }.getOrElse {
            println(
                "WIFI_DIRECT: ⚠️ startListening unavailable ($reason): " +
                    "${it::class.java.simpleName}: ${it.message}",
            )
            false
        }
    }

    private fun stopListeningBestEffort(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        reason: String,
    ): Boolean {
        val method = resolveListenMethod(manager.javaClass, start = false) ?: return false

        return runCatching {
            method.isAccessible = true
            when (method.parameterTypes.size) {
                1 -> method.invoke(manager, ch)
                2 -> method.invoke(
                    manager,
                    ch,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            println("WIFI_DIRECT: ✅ Listen mode stopped ($reason)")
                        }

                        override fun onFailure(reasonCode: Int) {
                            println(
                                "WIFI_DIRECT: ⚠️ stopListening failed ($reason): " +
                                    getP2pErrorMessage(reasonCode),
                            )
                        }
                    },
                )

                else -> return false
            }
            println("WIFI_DIRECT: 🔄 Requested stop listen mode ($reason)")
            true
        }.getOrElse {
            println(
                "WIFI_DIRECT: ⚠️ stopListening unavailable ($reason): " +
                    "${it::class.java.simpleName}: ${it.message}",
            )
            false
        }
    }

    private fun resolveMiracastModeMethod(managerClass: Class<*>): Method? {
        return (managerClass.methods + managerClass.declaredMethods)
            .distinctBy { method ->
                buildString {
                    append(method.name)
                    append('#')
                    append(method.parameterTypes.joinToString { it.name })
                }
            }
            .filter { method ->
                method.name.equals("setMiracastMode", ignoreCase = true) &&
                    method.parameterTypes.any { it == Int::class.javaPrimitiveType }
            }
            .sortedBy { it.parameterTypes.size }
            .firstOrNull()
    }

    private fun resolveListenMethod(
        managerClass: Class<*>,
        start: Boolean,
    ): Method? {
        val expectedNames = if (start) {
            setOf("startListening", "startListen")
        } else {
            setOf("stopListening", "stopListen")
        }

        return (managerClass.methods + managerClass.declaredMethods)
            .distinctBy { method ->
                buildString {
                    append(method.name)
                    append('#')
                    append(method.parameterTypes.joinToString { it.name })
                }
            }
            .firstOrNull { method ->
                method.name in expectedNames &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == WifiP2pManager.Channel::class.java &&
                    method.parameterTypes.size <= 2 &&
                    (method.parameterTypes.size == 1 ||
                        method.parameterTypes[1] == WifiP2pManager.ActionListener::class.java)
            }
    }

    /**
     * Fallback: set the Wi-Fi Direct device name via Settings.Global.
     * Requires WRITE_SETTINGS or system priv-app privilege.
     * The P2P stack reads this on the next initialization cycle.
     */
    /**
     * Fallback: set the Wi-Fi Direct device name via Settings.Global + shell command.
     * The P2P stack reads `wifi_p2p_device_name` on the next full initialization
     * (typically at boot). Requires WRITE_SECURE_SETTINGS or priv-app privilege.
     */
    private fun setDeviceNameViaSettings(deviceName: String) {
        // Try Settings.Global API
        runCatching {
            val result = Settings.Global.putString(
                context.contentResolver,
                "wifi_p2p_device_name",
                deviceName,
            )
            if (result) {
                println("WIFI_DIRECT: ✅ P2P device name written to Settings.Global: '$deviceName'")
            } else {
                println("WIFI_DIRECT: ⚠️ Settings.Global.putString returned false")
            }
        }.onFailure {
            println(
                "WIFI_DIRECT: ⚠️ Settings.Global write failed: " +
                    "${it::class.java.simpleName}: ${it.message}",
            )
        }
        // Also try shell command as second fallback
        runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("settings", "put", "global", "wifi_p2p_device_name", deviceName),
            )
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                println("WIFI_DIRECT: ✅ P2P device name written via shell command: '$deviceName'")
            } else {
                println("WIFI_DIRECT: ⚠️ Shell settings command exitCode=$exitCode")
            }
        }.onFailure {
            println(
                "WIFI_DIRECT: ⚠️ Shell settings command failed: " +
                    "${it::class.java.simpleName}: ${it.message}",
            )
        }
    }

    private fun collectWfdRelatedMethodNames(managerClass: Class<*>): List<String> {
        return (managerClass.methods + managerClass.declaredMethods)
            .map { it.name }
            .filter { it.contains("wfd", ignoreCase = true) || it.contains("wifiDisplay", ignoreCase = true) }
            .distinct()
            .sorted()
    }

    private fun extractPersistentGroupNetworkIds(groupListObj: Any?): List<Int> {
        if (groupListObj == null) return emptyList()

        val networkIds = linkedSetOf<Int>()

        runCatching {
            val methods = (groupListObj.javaClass.methods + groupListObj.javaClass.declaredMethods)
                .distinctBy { method ->
                    buildString {
                        append(method.name)
                        append('#')
                        append(method.parameterTypes.joinToString { it.name })
                    }
                }

            val groupCollectionMethod = methods.firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    Collection::class.java.isAssignableFrom(method.returnType)
            }

            val groups = groupCollectionMethod
                ?.apply { isAccessible = true }
                ?.invoke(groupListObj) as? Collection<*>

            groups.orEmpty().forEach { groupObj ->
                val networkId = extractNetworkId(groupObj)
                if (networkId != null && networkId >= 0) {
                    networkIds.add(networkId)
                }
            }
        }

        if (networkIds.isEmpty()) {
            Regex("networkId\\s*=\\s*(-?\\d+)")
                .findAll(groupListObj.toString())
                .forEach { match ->
                    match.groupValues.getOrNull(1)?.toIntOrNull()?.let { parsedId ->
                        if (parsedId >= 0) networkIds.add(parsedId)
                    }
                }
        }

        return networkIds.toList()
    }

    private fun extractNetworkId(groupObj: Any?): Int? {
        if (groupObj == null) return null

        return runCatching {
            val getter = (groupObj.javaClass.methods + groupObj.javaClass.declaredMethods)
                .firstOrNull { method ->
                    method.parameterTypes.isEmpty() &&
                        method.name.equals("getNetworkId", ignoreCase = true)
                }
                ?.apply { isAccessible = true }
                ?: return@runCatching null

            (getter.invoke(groupObj) as? Int)
        }.getOrNull()
    }

    private companion object {
        const val PEER_LIST_REQUEST_THROTTLE_MS = 1_500L
        const val PEER_VISIBLE_DISCOVERY_PAUSE_MS = 20_000L
        const val DISCOVERY_REFRESH_MIN_INTERVAL_MS = 30_000L
        const val DISCOVERY_KEEPALIVE_INTERVAL_MS = 30_000L
        const val MIRACAST_MODE_SINK = 2
    }
}
