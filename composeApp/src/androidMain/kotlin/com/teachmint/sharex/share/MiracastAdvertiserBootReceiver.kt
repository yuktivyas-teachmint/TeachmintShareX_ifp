package com.teachmint.sharex.share.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts background Miracast advertiser after boot and app updates.
 */
class MiracastAdvertiserBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != ACTION_HTC_QUICKBOOT_POWERON
        ) {
            return
        }

        MiracastAdvertiserService.ensureRunning(
            context = context.applicationContext,
            reason = "broadcast:$action",
        )
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
