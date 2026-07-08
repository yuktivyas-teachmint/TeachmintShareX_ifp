package com.teachmint.sharex.share.shared

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext


@Composable
fun ScreenCapturePermissionHandler(
    isPermissionRequired: Boolean,
    onPermissionGranted: (ScreenCapturePermissionData) -> Unit,
    onPermissionDenied: (String) -> Unit,
    onSingleAppPermissionGranted: ((ScreenCapturePermissionData) -> Unit)? = null,
) {
    println("🔒 ScreenCapturePermissionHandler (Android): isPermissionRequired=$isPermissionRequired")

    if (!isPermissionRequired) {
        println("🔒 ScreenCapturePermissionHandler (Android): Permission not required, returning early")
        return
    }

    println("🔒 ScreenCapturePermissionHandler (Android): Setting up permission request")

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        println("🔒 ScreenCapturePermissionHandler (Android): Got result: resultCode=${result.resultCode}, RESULT_OK=${Activity.RESULT_OK}")
        if (result.resultCode == Activity.RESULT_OK) {
            val permissionIntent = result.data
            if (permissionIntent != null) {
                // Keep a stable copy of the permission intent so later reads are reliable.
                val stablePermissionIntent = Intent(permissionIntent)
                // Start the foreground service immediately so the process keeps foreground-service
                // priority during AwaitingConfirmation. Single-app mode on some OEMs (e.g.
                // OxygenOS) pushes the activity to STOPPED state, which lets the OS abort TCP
                // sockets. Starting before the callback guarantees elevation before any system
                // overlay can take focus.
                ScreenCaptureService.start(context)
                // OxygenOS does NOT include a task-ID extra in the result intent for single-app
                // mode, so we cannot distinguish single-app from entire-screen via the intent.
                // When the caller provides onSingleAppPermissionGranted it signals that the
                // confirmation popup should be skipped entirely on Android (the system dialog
                // already captures the user's explicit consent for both modes).
                if (onSingleAppPermissionGranted != null) {
                    println("🔒 ScreenCapturePermissionHandler (Android): Skipping confirmation popup (auto-confirm)")
                    onSingleAppPermissionGranted(stablePermissionIntent)
                } else {
                    println("🔒 ScreenCapturePermissionHandler (Android): Showing confirmation popup")
                    onPermissionGranted(stablePermissionIntent)
                }
            } else {
                println("🔒 ScreenCapturePermissionHandler (Android): Permission granted but data intent is null")
                onPermissionDenied(SCREEN_CAPTURE_PERMISSION_DENIED_MESSAGE)
            }
        } else {
            println("🔒 ScreenCapturePermissionHandler (Android): Permission denied or cancelled")
            onPermissionDenied(SCREEN_CAPTURE_PERMISSION_DENIED_MESSAGE)
        }
    }

    LaunchedEffect(isPermissionRequired) {
        println("🔒 ScreenCapturePermissionHandler (Android): LaunchedEffect triggered, isPermissionRequired=$isPermissionRequired")
        if (isPermissionRequired) {
            println("🔒 ScreenCapturePermissionHandler (Android): Creating MediaProjection intent")
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent: Intent = manager.createScreenCaptureIntent()
            println("🔒 ScreenCapturePermissionHandler (Android): Launching permission dialog")
            launcher.launch(intent)
            println("🔒 ScreenCapturePermissionHandler (Android): Permission dialog launched")
        }
    }
}
