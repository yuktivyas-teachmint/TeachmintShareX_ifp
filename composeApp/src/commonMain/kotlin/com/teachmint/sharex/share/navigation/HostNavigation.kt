package com.teachmint.sharex.share.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.teachmint.sharex.share.analytics.AnalyticsEventId
import com.teachmint.sharex.share.analytics.ShareXAnalytics
import com.teachmint.sharex.crashlytics.CrashlyticsLogger
import com.teachmint.sharex.share.host.HostActiveSharingScreen
import com.teachmint.sharex.share.host.HostWaitingScreen
import com.teachmint.sharex.share.shared.HostController
import com.teachmint.sharex.share.shared.HOST_CONNECTION_LIMIT_TOAST_MESSAGE
import com.teachmint.sharex.share.shared.approveAirPlayRequest
import com.teachmint.sharex.share.shared.disconnectAirPlayClient
import com.teachmint.sharex.share.shared.isAirPlayClientId
import com.teachmint.sharex.share.shared.isAirPlayRequestClient
import com.teachmint.sharex.share.shared.isAirPlayShare
import com.teachmint.sharex.share.shared.isMiracastClientId
import com.teachmint.sharex.share.shared.isMiracastShare
import com.teachmint.sharex.share.shared.rejectAirPlayRequest
import com.teachmint.sharex.share.shared.rememberAirPlayActiveShares
import com.teachmint.sharex.share.shared.rememberAirPlayClientPolicies
import com.teachmint.sharex.share.shared.rememberAirPlayConnectedClients
import com.teachmint.sharex.share.shared.rememberAirPlayPendingRequests
import com.teachmint.sharex.share.shared.rememberMiracastActiveShare
import com.teachmint.sharex.share.shared.rememberMiracastConnectedClients
import com.teachmint.sharex.share.shared.rememberMiracastClientPolicies
import com.teachmint.sharex.share.shared.setMiracastClientAudioMuted
import com.teachmint.sharex.share.shared.disconnectMiracastClient
import com.teachmint.sharex.share.shared.AccessibilityServicePromptHandler
import com.teachmint.sharex.share.shared.ScreenCapturePermissionHandler
import com.teachmint.sharex.share.shared.ScreenCaptureState
import com.teachmint.sharex.share.shared.setAirPlayClientAudioMuted
import com.teachmint.sharex.share.shared.updateAirPlayClientPolicy
import com.teachmint.sharex.share.shared.getHostDisplayName
import com.teachmint.sharex.share.shared.rememberIsInMultiWindowModeState
import com.teachmint.sharex.share.shared.rememberConnectedWifiNameState
import com.teachmint.sharex.share.shared.rememberLocalIpAddressState
import com.teachmint.sharex.share.shared.rememberNetworkConnectedState
import com.teachmint.sharex.ui.theme.AppBackground
import com.teachmint.sharex.ui.theme.AppDimens
import com.teachmint.sharex.uiComponents.BlockedScreenCastingToast
import com.teachmint.sharex.uiComponents.BYOMConsentDialog
import com.teachmint.sharex.uiComponents.RemoteControlConsentDialog
import com.teachmint.sharex.uiComponents.ScreenShareRequestPopup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import teachmintsharex.composeapp.generated.resources.Res
import teachmintsharex.composeapp.generated.resources.wifi_fallback_name

@Serializable
data object HostWaitingRoute

@Serializable
data object HostActiveRoute

@Composable
fun HostNavigation(controller: HostController) {
    val state by controller.state.collectAsState()
    val navController = rememberNavController()

    val isNetworkConnected by rememberNetworkConnectedState()
    val isInMultiWindowMode by rememberIsInMultiWindowModeState()
    val connectedWifiName by rememberConnectedWifiNameState()
    val connectedIpAddress by rememberLocalIpAddressState()
    val ipAddress = connectedIpAddress ?: state.serverAddress ?: "0.0.0.0"
    val fallbackNetworkName = stringResource(Res.string.wifi_fallback_name)
    val networkName = connectedWifiName?.takeIf { it.isNotBlank() } ?: fallbackNetworkName
    val airPlayPendingRequests = rememberAirPlayPendingRequests()
    val pendingShareRequests = remember(state.pendingShareRequests, airPlayPendingRequests) {
        (state.pendingShareRequests + airPlayPendingRequests).distinctBy { it.clientId }
    }
    val airPlayConnectedClients = rememberAirPlayConnectedClients()
    val miracastConnectedClients = rememberMiracastConnectedClients()
    val connectedClients = remember(state.clients, airPlayConnectedClients, miracastConnectedClients) {
        (state.clients + airPlayConnectedClients + miracastConnectedClients).distinctBy { it.clientId }
    }
    val airPlayClientPolicies = rememberAirPlayClientPolicies()
    val miracastClientPolicies = rememberMiracastClientPolicies()
    val mergedClientCastingPolicies = remember(state.clientCastingPolicies, airPlayClientPolicies, miracastClientPolicies) {
        state.clientCastingPolicies + airPlayClientPolicies + miracastClientPolicies
    }
    val connectedCount = connectedClients.size
    val miracastShare = rememberMiracastActiveShare()
    val airPlayShares = rememberAirPlayActiveShares()
    val activeShares = remember(state.activeShares, miracastShare, airPlayShares) {
        buildList {
            addAll(state.activeShares.filterNot { it.isMiracastShare() || it.isAirPlayShare() })
            if (miracastShare != null) {
                add(miracastShare)
            }
            addAll(airPlayShares)
        }
    }
    val activeScreenCount = activeShares.size
    val showActiveScreen = isNetworkConnected && activeShares.isNotEmpty()
    val activeShareClientIds = remember(activeShares) {
        activeShares.map { it.clientId }.toSet()
    }
    var uiPinnedClientId by remember { mutableStateOf<String?>(null) }
    val displayClientCastingPolicies = remember(
        mergedClientCastingPolicies,
        activeShareClientIds,
        uiPinnedClientId,
    ) {
        val pinnedId = uiPinnedClientId
        val shouldAllowPin = activeShareClientIds.size > 1
        val updatedPolicies = mergedClientCastingPolicies.toMutableMap()
        activeShareClientIds.forEach { clientId ->
            val current = updatedPolicies[clientId] ?: com.teachmint.sharex.share.shared.ClientCastingPolicy()
            updatedPolicies[clientId] = current.copy(
                isScreenExclusiveEnabled = shouldAllowPin && clientId == pinnedId,
            )
        }
        updatedPolicies.toMap()
    }
    val deviceName = state.deviceName.ifBlank { getHostDisplayName() }
    val analyticsTracker = remember { ShareXAnalytics.tracker() }
    val scope = rememberCoroutineScope()
    val trackedMirroringClientIds = remember { mutableSetOf<String>() }
    var wasConnectionLimitToastVisible by remember { mutableStateOf(false) }
    val seenClientsForScreenCastOn = remember { mutableSetOf<String>() }
    var isScreenCastOnObserverInitialized by remember { mutableStateOf(false) }
    val onDeviceNameChangeWithAnalytics: (String) -> Unit = { updatedName ->
        scope.launch {
            analyticsTracker.trackEvent(
                eventId = AnalyticsEventId.SHAREX_DEVICE_RENAMED,
                deviceName = updatedName,
            )
        }
        controller.updateHostDeviceName(updatedName)
    }
    val onConnectionSettingsChangeWithAnalytics: (com.teachmint.sharex.share.shared.HostConnectionSettings) -> Unit =
        { updatedSettings ->
            val wasDirectConnectionEnabled = state.hostConnectionSettings.isDirectConnectionEnabled
            val isTurningDirectConnectionOn =
                !wasDirectConnectionEnabled && updatedSettings.isDirectConnectionEnabled
            if (isTurningDirectConnectionOn) {
                scope.launch {
                    analyticsTracker.trackEvent(
                        eventId = AnalyticsEventId.SHAREX_DIRECT_CASTING_ON,
                        deviceName = deviceName,
                    )
                }
            }
            controller.updateHostConnectionSettings(updatedSettings)
        }
    val onClientCastingPolicyChangeWithAnalytics: (String, com.teachmint.sharex.share.shared.ClientCastingPolicy) -> Unit =
        { clientId, updatedPolicy ->
            val previousPolicy = displayClientCastingPolicies[clientId]
                ?: com.teachmint.sharex.share.shared.ClientCastingPolicy()
            val wasPinned = uiPinnedClientId == clientId
            val wantsPinned = updatedPolicy.isScreenExclusiveEnabled
            if (wantsPinned && activeShareClientIds.size > 1) {
                uiPinnedClientId = clientId
            } else if (!wantsPinned && wasPinned) {
                uiPinnedClientId = null
            }

            val isPinAction = !wasPinned && wantsPinned
            val isScreenCastTurnedOn = !previousPolicy.isScreenCastEnabled &&
                updatedPolicy.isScreenCastEnabled
            val isRemoteControlTurnedOn = !previousPolicy.isRemoteControlEnabled &&
                updatedPolicy.isRemoteControlEnabled
            val resolvedClientName = connectedClients
                .firstOrNull { it.clientId == clientId }
                ?.name
                ?.takeIf { it.isNotBlank() }
                ?: clientId
            if (isPinAction) {
                scope.launch {
                    analyticsTracker.trackEvent(
                        eventId = AnalyticsEventId.SHAREX_DEVICE_PINNED,
                        deviceName = resolvedClientName,
                        hostDeviceName = deviceName,
                    )
                }
            }
            if (isScreenCastTurnedOn) {
                scope.launch {
                    analyticsTracker.trackEvent(
                        eventId = AnalyticsEventId.SHAREX_SCREEN_CAST_ON,
                        deviceName = resolvedClientName,
                        hostDeviceName = deviceName,
                    )
                }
            }
            if (isRemoteControlTurnedOn) {
                scope.launch {
                    analyticsTracker.trackEvent(
                        eventId = AnalyticsEventId.SHAREX_CONTROL_ON,
                        deviceName = resolvedClientName,
                        hostDeviceName = deviceName,
                    )
                }
            }
            // Pin/unpin is a host-UI layout concern and should include synthetic
            // shares (AirPlay/Miracast). Keep controller-side policy unchanged.
            val backendPolicy = mergedClientCastingPolicies[clientId]
                ?: com.teachmint.sharex.share.shared.ClientCastingPolicy()
            val routedPolicy = updatedPolicy.copy(
                isScreenExclusiveEnabled = backendPolicy.isScreenExclusiveEnabled,
            )
            if (isAirPlayClientId(clientId)) {
                updateAirPlayClientPolicy(clientId, routedPolicy)
            } else if (isMiracastClientId(clientId)) {
                // Miracast does not support cast/reverse-cast policy changes from host
            } else {
                controller.updateClientCastingPolicy(clientId, routedPolicy)
            }
        }
    val onStopSharingForClientWithRouting: (String) -> Unit = { clientId ->
        CrashlyticsLogger.logHostStopSharingForClient(clientId)
        if (isAirPlayClientId(clientId)) {
            disconnectAirPlayClient(clientId)
        } else if (isMiracastClientId(clientId)) {
            disconnectMiracastClient(clientId)
        } else {
            controller.stopSharingForClient(clientId)
        }
    }
    val routeClientAudioMute: (String, Boolean) -> Unit = { clientId, muted ->
        if (isAirPlayClientId(clientId)) {
            setAirPlayClientAudioMuted(clientId, muted)
        } else if (isMiracastClientId(clientId)) {
            setMiracastClientAudioMuted(clientId, muted)
        } else {
            controller.setClientAudioMuted(clientId, muted)
        }
    }
    val onToggleClientAudioMutedWithRouting: (String, Boolean) -> Unit = { clientId, muted ->
        CrashlyticsLogger.logHostAudioMuteToggle(clientId, muted)
        // Keep host playback exclusive across all transport types (WebRTC + AirPlay).
        if (!muted) {
            mergedClientCastingPolicies.forEach { (otherClientId, otherPolicy) ->
                if (otherClientId != clientId && otherPolicy.isAudioEnabled) {
                    routeClientAudioMute(otherClientId, true)
                }
            }
        }
        routeClientAudioMute(clientId, muted)
    }
    val onTransferredFilesClickWithAnalytics: () -> Unit = {
        scope.launch {
            analyticsTracker.trackEvent(
                eventId = AnalyticsEventId.SHAREX_RECIEVED_FILES_TAB,
                deviceName = deviceName,
                connectedDeviceCount = connectedCount,
            )
        }
    }

    LaunchedEffect(Unit) {
        CrashlyticsLogger.setCurrentScreen("HostWaitingScreen")
        analyticsTracker.trackEvent(
            eventId = AnalyticsEventId.SHAREX_RECEIVER_LOADED,
            shareCode = "****",
            connectedDeviceCount = connectedCount,
        )
    }

    LaunchedEffect(connectedCount) {
        CrashlyticsLogger.setConnectedClients(connectedCount)
    }

    LaunchedEffect(activeShareClientIds) {
        if (activeShareClientIds.size <= 1) {
            uiPinnedClientId = null
            return@LaunchedEffect
        }
        if (uiPinnedClientId != null && uiPinnedClientId !in activeShareClientIds) {
            uiPinnedClientId = null
        }
    }

    LaunchedEffect(connectedClients, mergedClientCastingPolicies, deviceName) {
        val connectedClientIds = connectedClients.map { it.clientId }.toSet()
        seenClientsForScreenCastOn.retainAll(connectedClientIds)

        if (!isScreenCastOnObserverInitialized) {
            seenClientsForScreenCastOn.addAll(connectedClientIds)
            isScreenCastOnObserverInitialized = true
            return@LaunchedEffect
        }

        connectedClients.forEach { client ->
            if (seenClientsForScreenCastOn.contains(client.clientId)) {
                return@forEach
            }
            val clientPolicy = mergedClientCastingPolicies[client.clientId]
                ?: com.teachmint.sharex.share.shared.ClientCastingPolicy()
            if (clientPolicy.isScreenCastEnabled) {
                analyticsTracker.trackEvent(
                    eventId = AnalyticsEventId.SHAREX_SCREEN_CAST_ON,
                    deviceName = client.name.ifBlank { client.clientId },
                    hostDeviceName = deviceName,
                )
            }
            seenClientsForScreenCastOn.add(client.clientId)
        }
    }

    LaunchedEffect(activeShares, deviceName) {
        val activeClientIds = activeShares.map { it.clientId }.toSet()
        trackedMirroringClientIds.retainAll(activeClientIds)

        val newlyMirroringShares = activeShares.filter { share ->
            !trackedMirroringClientIds.contains(share.clientId)
        }

        newlyMirroringShares.forEach { share ->
            analyticsTracker.trackEvent(
                eventId = AnalyticsEventId.SHAREX_SCREEN_CASTING_STARTED,
                numberOfScreens = activeScreenCount,
                deviceName = deviceName,
                senderDeviceId = share.clientId,
                senderDeviceName = share.clientName,
                status = "mirroring screen",
            )
            trackedMirroringClientIds.add(share.clientId)
        }
    }

    val isConnectionLimitToastVisible = state.blockedToastMessage == HOST_CONNECTION_LIMIT_TOAST_MESSAGE
    LaunchedEffect(isConnectionLimitToastVisible, connectedCount, deviceName) {
        if (isConnectionLimitToastVisible && !wasConnectionLimitToastVisible) {
            analyticsTracker.trackEvent(
                eventId = AnalyticsEventId.SHAREX_CONNECTION_LIMIT_REACHED,
                deviceName = deviceName,
                connectedDeviceCount = connectedCount,
            )
        }
        wasConnectionLimitToastVisible = isConnectionLimitToastVisible
    }

    LaunchedEffect(showActiveScreen) {
        val targetRoute = if (showActiveScreen) {
            CrashlyticsLogger.setCurrentScreen("HostActiveSharingScreen")
            HostActiveRoute
        } else {
            CrashlyticsLogger.setCurrentScreen("HostWaitingScreen")
            HostWaitingRoute
        }
        navigateReplacingRootIfNeeded(navController, targetRoute)
    }

    val latestIsNetworkConnected by rememberUpdatedState(isNetworkConnected)
    val latestConnectedClients by rememberUpdatedState(connectedClients)
    LaunchedEffect(isNetworkConnected) {
        if (isNetworkConnected) return@LaunchedEffect

        // Avoid tearing down active streams on transient connectivity blips.
        // The effect is cancelled automatically if network reconnects.
        delay(5_000)
        if (latestIsNetworkConnected) return@LaunchedEffect

        latestConnectedClients
            .map { it.clientId }
            .distinct()
            .forEach { clientId ->
                onStopSharingForClientWithRouting(clientId)
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = HostWaitingRoute,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<HostWaitingRoute> {
                HostWaitingScreen(
                    deviceName = deviceName,
                    networkName = networkName,
                    ipAddress = ipAddress,
                    isNetworkConnected = isNetworkConnected,
                    connectedClients = connectedClients,
                    clientCastingPolicies = displayClientCastingPolicies,
                    connectionSettings = state.hostConnectionSettings,
                    connectedCount = connectedCount,
                    isInMultiWindowMode = isInMultiWindowMode,
                    pin = state.connectionPin.ifBlank { "------" },
                    lastError = state.lastError,
                    activeByomClientIds = state.activeByomClientIds,
                    onStopSharingForClient = onStopSharingForClientWithRouting,
                    onClientCastingPolicyChange = onClientCastingPolicyChangeWithAnalytics,
                    onDeviceNameChange = onDeviceNameChangeWithAnalytics,
                    onConnectionSettingsChange = onConnectionSettingsChangeWithAnalytics,
                    onTransferredFilesClick = onTransferredFilesClickWithAnalytics,
                    onByomToggle = { clientId, enabled ->
                        if (enabled) controller.enableBYOM(clientId) else controller.disableBYOM(clientId)
                    },
                )
            }

            composable<HostActiveRoute> {
                HostActiveSharingScreen(
                    activeShares = activeShares,
                    connectedClients = connectedClients,
                    clientCastingPolicies = displayClientCastingPolicies,
                    connectionSettings = state.hostConnectionSettings,
                    deviceName = deviceName,
                    networkName = networkName,
                    ipAddress = ipAddress,
                    isNetworkConnected = isNetworkConnected,
                    connectedCount = connectedCount,
                    pin = state.connectionPin.ifBlank { "------" },
                    activeByomClientIds = state.activeByomClientIds,
                    onStopSharingForClient = onStopSharingForClientWithRouting,
                    onClientCastingPolicyChange = onClientCastingPolicyChangeWithAnalytics,
                    onDeviceNameChange = onDeviceNameChangeWithAnalytics,
                    onConnectionSettingsChange = onConnectionSettingsChangeWithAnalytics,
                    onToggleClientAudioMuted = onToggleClientAudioMutedWithRouting,
                    onTransferredFilesClick = onTransferredFilesClickWithAnalytics,
                    onByomToggle = { clientId, enabled ->
                        if (enabled) controller.enableBYOM(clientId) else controller.disableBYOM(clientId)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (
            isNetworkConnected &&
            pendingShareRequests.isNotEmpty() &&
            (
                !state.hostConnectionSettings.isDirectConnectionEnabled ||
                    airPlayPendingRequests.isNotEmpty()
                )
        ) {
            val onAcceptRequest: (String) -> Unit = { clientId ->
                CrashlyticsLogger.logHostAcceptShareRequest(clientId)
                val requesterName = pendingShareRequests
                    .firstOrNull { it.clientId == clientId }
                    ?.clientName
                scope.launch {
                    analyticsTracker.trackEvent(
                        eventId = AnalyticsEventId.SHAREX_CONNECTION_REQUESTED,
                        deviceName = requesterName,
                        status = "accepted",
                    )
                    analyticsTracker.trackEvent(
                        eventId = AnalyticsEventId.SHAREX_SCREEN_CASTING_STARTED,
                        numberOfScreens = activeScreenCount + 1,
                        deviceName = deviceName,
                        senderDeviceId = clientId,
                        senderDeviceName = requesterName,
                        status = "connecting",
                    )
                }
                if (isAirPlayRequestClient(clientId)) {
                    approveAirPlayRequest(clientId)
                } else {
                    controller.acceptShareRequest(clientId)
                }
            }
            val onRejectRequest: (String) -> Unit = { clientId ->
                CrashlyticsLogger.logHostRejectShareRequest(clientId)
                val requesterName = pendingShareRequests
                    .firstOrNull { it.clientId == clientId }
                    ?.clientName
                scope.launch {
                    analyticsTracker.trackEvent(
                        eventId = AnalyticsEventId.SHAREX_CONNECTION_REQUESTED,
                        deviceName = requesterName,
                        status = "declined",
                    )
                }
                if (isAirPlayRequestClient(clientId)) {
                    rejectAirPlayRequest(clientId)
                } else {
                    controller.rejectShareRequest(clientId)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground.copy(alpha = 0.6f))
                    .padding(horizontal = AppDimens.dp24),
                contentAlignment = Alignment.Center,
            ) {
                ScreenShareRequestPopup(
                    requests = pendingShareRequests,
                    onAcceptRequest = onAcceptRequest,
                    onRejectRequest = onRejectRequest,
                )
            }
        }

        ScreenCapturePermissionHandler(
            isPermissionRequired = state.screenCaptureState is ScreenCaptureState.PermissionRequired,
            onPermissionGranted = controller::onScreenCapturePermissionGranted,
            onPermissionDenied = controller::onScreenCapturePermissionDenied,
        )

        AccessibilityServicePromptHandler(
            isPromptRequired = state.isAccessibilityServicePromptRequired,
            onServiceEnabled = controller::onAccessibilityServiceEnabled,
            onDismissed = controller::onAccessibilityServicePromptDismissed,
        )

        if (state.isRemoteControlConsentRequired) {
            RemoteControlConsentDialog(
                onConsentGranted = controller::onRemoteControlConsentGranted,
                onConsentDenied = controller::onRemoteControlConsentDenied,
            )
        }

        state.blockedToastMessage?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = AppDimens.dp16, bottom = AppDimens.dp16),
                contentAlignment = AbsoluteAlignment.BottomRight,
            ) {
                BlockedScreenCastingToast(message = message)
            }
        }
    }
}

private fun navigateReplacingRootIfNeeded(navController: NavHostController, route: Any) {
    // Avoid clearing/re-adding the same route at startup, which causes a visible blink.
    if (route == HostWaitingRoute && navController.currentBackStackEntry == null) {
        return
    }

    val destination = navController.currentBackStackEntry?.destination
    val isAlreadyOnTargetRoute = when (route) {
        HostWaitingRoute -> destination?.hasRoute<HostWaitingRoute>() == true
        HostActiveRoute -> destination?.hasRoute<HostActiveRoute>() == true
        else -> false
    }
    if (isAlreadyOnTargetRoute) {
        return
    }

    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) {
            inclusive = true
        }
        launchSingleTop = true
    }
}
