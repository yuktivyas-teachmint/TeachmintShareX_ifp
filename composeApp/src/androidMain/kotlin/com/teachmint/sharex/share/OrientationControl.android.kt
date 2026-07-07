package com.teachmint.sharex.share.shared

import android.content.pm.ActivityInfo

actual fun setClientReverseOrientationEnabled(enabled: Boolean) {
    val activity = AndroidContextHolder.currentActivity ?: return
    val targetOrientation = if (enabled) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    activity.runOnUiThread {
        if (activity.requestedOrientation != targetOrientation) {
            activity.requestedOrientation = targetOrientation
        }
    }
}
