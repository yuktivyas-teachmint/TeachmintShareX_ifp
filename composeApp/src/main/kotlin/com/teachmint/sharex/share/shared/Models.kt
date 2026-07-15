package com.teachmint.sharex.share.shared

import kotlinx.serialization.Serializable

enum class AppRole {
    Host,
    SubscriptionRequired,
}

@Serializable
data class DiscoveryAnnouncement(
    val hostId: String,
    val name: String,
    val port: Int,
    val hostAddress: String? = null,
)

data class DiscoveredHost(
    val hostId: String,
    val name: String,
    val address: String,
    val port: Int,
    val lastSeenEpochMs: Long,
    val isRemote: Boolean = false, // True if discovered via remote signaling server
    val remoteServerUrl: String? = null, // URL of remote signaling server if isRemote=true
) {
    val wsUrl: String
        get() = if (isRemote && remoteServerUrl != null) {
            remoteServerUrl
        } else {
            "ws://$address:$port/ws"
        }
}

data class HostInfo(
    val hostId: String,
    val name: String,
    val port: Int,
    val address: String? = null,
)

data class ClientInfo(
    val clientId: String,
    val name: String,
    /** Client OS as reported in the signaling handshake ("android", "ios", "macos", "windows"). Null for older clients. */
    val platform: String? = null,
)

data class ActiveShare(
    val clientId: String,
    val clientName: String,
    val videoTrack: PlatformVideoTrack?,
    /**
     * Physical display rotation of the sharing device in degrees (0/90/180/270).
     * Reported by the client over signaling when it detects the device rotated.
     * The host renderer applies this rotation to compensate for landscape content
     * that the Android compositor letterboxed into a portrait capture buffer.
     */
    val displayRotation: Int = 0,
)

data class ClientCastingPolicy(
    val isScreenExclusiveEnabled: Boolean = false,
    val isScreenCastEnabled: Boolean = true,
    val isReverseCastEnabled: Boolean = false,
    val isAudioEnabled: Boolean = false,
    val isRemoteControlEnabled: Boolean = false,
)

data class HostConnectionSettings(
    val isMultipleDeviceCastEnabled: Boolean = true,
    val isDirectConnectionEnabled: Boolean = false,
    val remoteSignalingUrl: String? = RemoteServerConfig.REMOTE_SERVER_URL,
)

enum class ClientDeviceType {
    Mobile,
    Laptop,
}

/**
 * Resolves a client's device type from the platform it reported in the signaling
 * handshake, falling back to a device-name heuristic for older clients that
 * don't send a platform.
 */
fun resolveClientDeviceType(platform: String?, clientName: String): ClientDeviceType {
    when (platform?.trim()?.lowercase()) {
        "android", "ios", "ipados" -> return ClientDeviceType.Mobile
        "macos", "mac", "osx", "windows", "linux" -> return ClientDeviceType.Laptop
    }
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

data class PendingShareRequest(
    val clientId: String,
    val clientName: String,
    val deviceType: ClientDeviceType,
)


sealed class ScreenCaptureState {
    data object Idle : ScreenCaptureState()
    data object PermissionRequired : ScreenCaptureState()
    data class AwaitingConfirmation(val permissionData: ScreenCapturePermissionData) : ScreenCaptureState()
    data object Capturing : ScreenCaptureState()
    data class Error(val message: String) : ScreenCaptureState()
}

sealed class FileTransferState {
    data object Idle : FileTransferState()
    data class Transferring(val progress: Float = 0f) : FileTransferState()
    data object Success : FileTransferState()
    data class Error(val message: String) : FileTransferState()
}

data class HostUiState(
    val serverRunning: Boolean = false,
    val serverPort: Int? = null,
    val serverAddress: String? = null,
    val deviceName: String = "",
    val connectionPin: String = "",
    val pinExpiresAtEpochMs: Long = 0L,
    val clients: List<ClientInfo> = emptyList(),
    val clientCastingPolicies: Map<String, ClientCastingPolicy> = emptyMap(),
    val hostConnectionSettings: HostConnectionSettings = HostConnectionSettings(),
    val activeShares: List<ActiveShare> = emptyList(),
    val pendingShareRequests: List<PendingShareRequest> = emptyList(),
    val screenCaptureState: ScreenCaptureState = ScreenCaptureState.Idle,
    val lastError: String? = null,
    val blockedToastMessage: String? = null,
    /** Client IDs that currently have active remote-control sessions. */
    val remoteControlClients: Set<String> = emptySet(),
    /** Whether the InputInjector (AccessibilityService) is available on this device. */
    val isInputInjectorAvailable: Boolean = false,
    /** When true, the UI should prompt the user to enable the AccessibilityService. */
    val isAccessibilityServicePromptRequired: Boolean = false,
    /** When true, show consent dialog before redirecting to accessibility settings for remote control. */
    val isRemoteControlConsentRequired: Boolean = false,
    /** Client IDs that currently have an active BYOM (camera + mic) session. */
    val activeByomClientIds: Set<String> = emptySet(),
)
