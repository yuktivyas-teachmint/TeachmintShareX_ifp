package com.teachmint.sharex.androidapp.ota

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.teachmint.ota.Ota
import com.teachmint.ota.model.UpdateState
import com.teachmint.sharex.appupdate.AppUpdateBridge
import com.teachmint.sharex.appupdate.AppUpdateStatus
import com.teachmint.sharex.appupdate.AppUpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Mirrors [Ota.state] into [AppUpdateBridge] for the shared UI (dot on the home
 * settings icon + a progress/Update row in settings) and services install taps
 * coming back from it.
 *
 * The download is kicked off automatically by [OtaUpdateManager] as soon as a
 * newer version is published, so the settings row is a *progress* surface first
 * (Downloading % → Verifying → Update) and a manual trigger only for the final
 * install step. This is what stops users from tapping Update repeatedly while
 * the 141 MB APK is still downloading — the button only enables once the state
 * is READY.
 *
 * A failed install stays latched as FAILED even though dismissing the error
 * dialog resets the OTA state to Idle, so the retry entry survives until the
 * update actually installs or the process restarts.
 */
object AppUpdateBridgeWiring {
    private const val TAG = "AppUpdateBridge"

    fun start(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        AppUpdateBridge.onInstallRequest = { onInstallRequested(appContext, scope) }
        scope.launch {
            var latched = AppUpdateUiState()
            Ota.state.collect { state ->
                latched = reduce(latched, state)
                AppUpdateBridge.publish(latched)
            }
        }
    }

    private fun reduce(prev: AppUpdateUiState, state: UpdateState): AppUpdateUiState = when (state) {
        is UpdateState.Downloading -> AppUpdateUiState(
            status = AppUpdateStatus.DOWNLOADING,
            versionName = state.versionName,
            downloadPercent = state.percent,
        )

        is UpdateState.Verifying -> prev.copy(status = AppUpdateStatus.VERIFYING)

        is UpdateState.Available -> AppUpdateUiState(
            status = AppUpdateStatus.READY,
            versionName = state.updateInfo.versionName,
        )

        is UpdateState.Installing -> prev.copy(status = AppUpdateStatus.INSTALLING)

        is UpdateState.Error -> if (prev.status == AppUpdateStatus.NONE) {
            prev
        } else {
            prev.copy(status = AppUpdateStatus.FAILED)
        }

        is UpdateState.InstalledSuccess -> AppUpdateUiState()

        // Idle / UpToDate / Checking: a dismissed error dialog resets the pipeline
        // to Idle — keep a latched READY/FAILED entry visible so the manual
        // install/retry option in settings doesn't vanish under the user.
        else -> if (prev.status == AppUpdateStatus.READY || prev.status == AppUpdateStatus.FAILED) {
            prev
        } else {
            AppUpdateUiState()
        }
    }

    private fun onInstallRequested(context: Context, scope: CoroutineScope) {
        val canSilentInstall = context.checkCallingOrSelfPermission(
            Manifest.permission.INSTALL_PACKAGES,
        ) == PackageManager.PERMISSION_GRANTED
        val canConfirmInstall = context.packageManager.canRequestPackageInstalls()

        if (!canSilentInstall && !canConfirmInstall) {
            // Sideloaded (e.g. debug) build with no install path: the OTA library
            // would fail with "Missing install permissions", so send the user to
            // the "Install unknown apps" toggle to enable the confirm-install flow.
            Log.i(TAG, "No install permission; opening unknown-app-sources settings")
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.e(TAG, "Failed to open install-permission settings", it) }
            return
        }

        scope.launch { Ota.install() }
    }
}
