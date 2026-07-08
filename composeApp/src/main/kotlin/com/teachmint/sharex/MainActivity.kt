package com.teachmint.sharex

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.teachmint.sharex.share.shared.HostScreenCaptureSignal
import com.teachmint.sharex.share.shared.updateMultiWindowModeState

class MainActivity : ComponentActivity() {
    private var screenCaptureCallback: android.app.Activity.ScreenCaptureCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        updateMultiWindowModeState(isInMultiWindowMode)
        registerScreenCaptureCallbackIfSupported()

        setContent {
            App()
        }
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        updateMultiWindowModeState(isInMultiWindowMode)
    }

    override fun onDestroy() {
        unregisterScreenCaptureCallbackIfSupported()
        super.onDestroy()
    }

    private fun registerScreenCaptureCallbackIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (screenCaptureCallback != null) return

        val callback = android.app.Activity.ScreenCaptureCallback {
            HostScreenCaptureSignal.notifyCaptureDetected(source = "activity_callback")
        }
        screenCaptureCallback = callback
        runCatching {
            registerScreenCaptureCallback(mainExecutor, callback)
            println("SCREEN_CAPTURE_SIGNAL: registerScreenCaptureCallback success")
        }.onFailure { error ->
            screenCaptureCallback = null
            println("SCREEN_CAPTURE_SIGNAL: registerScreenCaptureCallback failed: ${error.message}")
        }
    }

    private fun unregisterScreenCaptureCallbackIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = screenCaptureCallback ?: return
        screenCaptureCallback = null
        runCatching {
            unregisterScreenCaptureCallback(callback)
            println("SCREEN_CAPTURE_SIGNAL: unregisterScreenCaptureCallback success")
        }.onFailure { error ->
            println("SCREEN_CAPTURE_SIGNAL: unregisterScreenCaptureCallback failed: ${error.message}")
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
