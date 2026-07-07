package com.teachmint.sharex.androidapp

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.teachmintsharex.share.miracast.MiracastPlaybackManager
import com.teachmint.sharex.App
import com.teachmint.sharex.share.shared.AndroidContextHolder
import com.teachmint.sharex.share.shared.AndroidRoleHolder
import com.teachmint.sharex.share.shared.DeviceRole
import com.teachmint.sharex.share.shared.MiracastAdvertiserService
import com.teachmint.sharex.crashlytics.CrashlyticsLogger
import com.teachmint.sharex.share.shared.detectIfpDevice
import com.teachmint.sharex.share.shared.getDeviceRole
import com.teachmint.sharex.share.shared.notifyHostScreenCaptureDetected
import com.teachmint.sharex.share.shared.updateMultiWindowModeState

class MainActivity : ComponentActivity() {
    private var shouldHideBottomBar: Boolean = false
    private var screenCaptureCallback: android.app.Activity.ScreenCaptureCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        AndroidContextHolder.init(this)
        AndroidContextHolder.setCurrentActivity(this)
        updateMultiWindowModeState(isInMultiWindowMode)
        AndroidRoleHolder.isIfpDevice = detectIfpDevice(intent)
        shouldHideBottomBar = getDeviceRole() == DeviceRole.HOST
        CrashlyticsLogger.setAppRole(if (shouldHideBottomBar) "host" else "client")
        val requestedPermissions = requestStartupPermissionsIfNeeded()
        if (!requestedPermissions) {
            MiracastAdvertiserService.ensureRunning(
                context = this,
                reason = "main_activity_create",
            )
        }
        registerScreenCaptureCallbackIfSupported()
        applyFullscreenMode()

        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        AndroidContextHolder.setCurrentActivity(this)
        updateMultiWindowModeState(isInMultiWindowMode)
        MiracastPlaybackManager.ensureViewerVisibleFromHost(this)
        if (!MiracastAdvertiserService.isRunningOrRequested()) {
            MiracastAdvertiserService.ensureRunning(
                context = this,
                reason = "main_activity_resume",
            )
        }
        applyFullscreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyFullscreenMode()
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
        if (AndroidContextHolder.currentActivity === this) {
            AndroidContextHolder.setCurrentActivity(null)
        }
        updateMultiWindowModeState(false)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STARTUP_PERMISSION_REQUEST_CODE) return

        // If the service is not active yet, start it after permissions settle.
        // Avoid forcing a stop/start cycle here because foreground host mode may
        // already own Miracast listeners.
        if (!MiracastAdvertiserService.isRunningOrRequested()) {
            MiracastAdvertiserService.ensureRunning(
                context = this,
                reason = "startup_permissions_updated",
            )
        }
    }

    private fun applyFullscreenMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
            if (shouldHideBottomBar) {
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    private fun requestStartupPermissionsIfNeeded(): Boolean {
        val missingPermissions = buildList {
            if (
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.CAMERA)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                STARTUP_PERMISSION_REQUEST_CODE,
            )
            return true
        }

        return false
    }

    private fun registerScreenCaptureCallbackIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (screenCaptureCallback != null) return

        val callback = android.app.Activity.ScreenCaptureCallback {
            notifyHostScreenCaptureDetected(source = "activity_callback")
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

    private companion object {
        const val STARTUP_PERMISSION_REQUEST_CODE = 1001
    }
}
