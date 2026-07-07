package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable

@Composable
expect fun ScreenCapturePermissionHandler(
    isPermissionRequired: Boolean,
    onPermissionGranted: (ScreenCapturePermissionData) -> Unit,
    onPermissionDenied: (String) -> Unit,
    onSingleAppPermissionGranted: ((ScreenCapturePermissionData) -> Unit)? = null,
)
