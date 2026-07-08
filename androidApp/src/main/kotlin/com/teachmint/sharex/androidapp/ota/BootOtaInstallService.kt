package com.teachmint.sharex.androidapp.ota

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.teachmint.ota.Ota
import com.teachmint.ota.model.UpdateState
import com.teachmint.sharex.remoteconfig.RemoteConfigManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lightweight foreground service that runs only on boot / app update to check
 * for and silently install a pending OTA update. Stops itself in every exit
 * path so it never leaves UI or a sticky process behind.
 *
 * Ported from chakra's BootOtaInstallService.
 */
class BootOtaInstallService : LifecycleService() {
    companion object {
        private const val TAG = "BootOtaInstall"
        private const val CHANNEL_ID = "boot_ota_install"
        private const val NOTIFICATION_ID = 2001
        private const val OTA_TIMEOUT_MS = 10_000L
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.e(TAG, "Cannot start foreground service from background", e)
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            stopSelf()
            return
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)

        if (!RemoteConfigManager.config.enableOtaUpdates) {
            Log.d(TAG, "OTA updates disabled via remote config, skipping boot check")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "Boot OTA check started, waiting for OTA state...")
        lifecycleScope.launch {
            try {
                val state =
                    withTimeoutOrNull(OTA_TIMEOUT_MS) {
                        Ota.state.first { it is UpdateState.Available }
                    }
                if (state is UpdateState.Available) {
                    Log.d(TAG, "Installing pending update: ${state.updateInfo.versionName}")
                    try {
                        Ota.install()
                        Log.d(TAG, "Update installation started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Boot install failed", e)
                    }
                } else {
                    Log.d(TAG, "No pending update found after timeout")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OTA check failed unexpectedly", e)
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotification(): Notification {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "OTA Update Check",
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Checking for updates")
            .setContentText("Looking for pending OTA update…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
