package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable

/**
 * Platform-specific AirPlay mirror overlay.
 * On Android: renders the [AirPlayReceiverScreen] when an AirPlay mirror session is active.
 * On other platforms: no-op.
 */
@Composable
expect fun AirPlayMirrorOverlay()
