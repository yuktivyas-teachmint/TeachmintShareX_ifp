package com.teachmint.sharex.androidapp.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts [BootOtaInstallService] after boot and app updates so a pending OTA
 * update is installed before the user opens the app.
 *
 * Lives in androidApp (not composeApp's MiracastAdvertiserBootReceiver) because
 * composeApp cannot reference androidApp classes; both receivers listen to the
 * same boot broadcasts independently.
 */
class OtaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != ACTION_HTC_QUICKBOOT_POWERON
        ) {
            return
        }

        val serviceIntent = Intent(context, BootOtaInstallService::class.java)
        try {
            context.startForegroundService(serviceIntent)
            Log.d(TAG, "BootOtaInstallService started for $action")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BootOtaInstallService", e)
        }
    }

    private companion object {
        const val TAG = "OtaBootReceiver"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
