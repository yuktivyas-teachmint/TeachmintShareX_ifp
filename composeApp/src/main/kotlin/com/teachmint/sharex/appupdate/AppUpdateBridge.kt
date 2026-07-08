package com.teachmint.sharex.appupdate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared-UI view of the app self-update pipeline. The OTA library is only
 * visible to the androidApp module (which depends on this one), so
 * ShareXApplication mirrors its state in through [publish] and receives
 * manual-install taps back through [onInstallRequest].
 */
enum class AppUpdateStatus {
    /** No update in flight. */
    NONE,

    /** APK is downloading in the background; [AppUpdateUiState.downloadPercent] is valid. */
    DOWNLOADING,

    /** Download finished, signature/version being checked. */
    VERIFYING,

    /** APK downloaded and verified; waiting for the user to tap Update. */
    READY,

    /** Install in progress; the app will be killed and relaunched by the OS. */
    INSTALLING,

    /** Last install attempt failed; the row switches to retry copy. */
    FAILED,
}

data class AppUpdateUiState(
    val status: AppUpdateStatus = AppUpdateStatus.NONE,
    val versionName: String = "",
    val downloadPercent: Int = 0,
) {
    /** Something the user should see on the settings icon / in settings. */
    val hasPendingUpdate: Boolean get() = status != AppUpdateStatus.NONE

    /** Only tappable when the APK is ready or a previous attempt failed. */
    val canInstall: Boolean
        get() = status == AppUpdateStatus.READY || status == AppUpdateStatus.FAILED
}

object AppUpdateBridge {
    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    /** Set by androidApp; invoked when the user taps Update in settings. */
    var onInstallRequest: () -> Unit = {}

    fun publish(state: AppUpdateUiState) {
        _state.value = state
    }

    fun requestInstall() = onInstallRequest()
}
