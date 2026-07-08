package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope

interface HostController {
    val state: kotlinx.coroutines.flow.StateFlow<HostUiState>
    fun start()
    fun stop()
    fun connectToClient(clientId: String)
    fun updateHostDeviceName(name: String)
    fun updateHostConnectionSettings(settings: HostConnectionSettings)
    fun acceptShareRequest(clientId: String)
    fun rejectShareRequest(clientId: String)
    fun stopSharingForClient(clientId: String)
    fun updateClientCastingPolicy(clientId: String, policy: ClientCastingPolicy)

    /**
     * Locally mute or unmute the incoming audio coming from the given client.
     * Mutes only on the host side — the sending client keeps streaming audio,
     * we just stop playing it, so there is no signaling cost and it is fast to
     * toggle from the UI.
     */
    fun setClientAudioMuted(clientId: String, muted: Boolean)
    fun onScreenCapturePermissionGranted(permission: ScreenCapturePermissionData)
    fun onScreenCapturePermissionDenied(message: String)

    /** Called when the user has enabled the AccessibilityService for remote control. */
    fun onAccessibilityServiceEnabled()
    /** Called when the user dismissed the AccessibilityService prompt without enabling. */
    fun onAccessibilityServicePromptDismissed()

    /** Called when the user grants consent to redirect to accessibility settings for remote control. */
    fun onRemoteControlConsentGranted()
    /** Called when the user denies consent for enabling remote control accessibility permission. */
    fun onRemoteControlConsentDenied()

    /** Approve or deny a remote-control request from a client. */
    fun approveRemoteControl(clientId: String)
    fun denyRemoteControl(clientId: String)
    fun stopRemoteControl(clientId: String)

    /** Host-initiated BYOM: start camera + mic streaming to the given client. */
    fun enableBYOM(clientId: String)
    /** Host-initiated BYOM: stop the active BYOM session for the given client. */
    fun disableBYOM(clientId: String)
}

data class AppControllers(
    val role: AppRole,
    val hostController: HostController?,
    val webRtcEngine: WebRtcEngine?,
)

@Composable
fun rememberAppControllers(): AppControllers {
    val scope = rememberCoroutineScope()
    val role = remember { getAppRole() }
    val discoveryService = remember { DiscoveryService() }

    val controllers = remember(role) {
        when (role) {
            AppRole.Host -> {
                val engine = createWebRtcEngine()
                val hostController = createHostController(
                    scope = scope,
                    discoveryService = discoveryService,
                    webRtcEngine = engine,
                    iceServers = IceServerConfigDefaults.configured,
                )
                AppControllers(role = role, hostController = hostController, webRtcEngine = engine)
            }
            AppRole.SubscriptionRequired -> {
                AppControllers(role = role, hostController = null, webRtcEngine = null)
            }
        }
    }

    DisposableEffect(controllers.role) {
        controllers.hostController?.start()
        onDispose {
            controllers.hostController?.stop()
            controllers.webRtcEngine?.release()
        }
    }

    return controllers
}

