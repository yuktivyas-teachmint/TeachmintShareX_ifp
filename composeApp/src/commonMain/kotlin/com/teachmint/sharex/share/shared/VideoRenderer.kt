package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun VideoRenderer(
    track: PlatformVideoTrack?,
    modifier: Modifier = Modifier,
    debugLabel: String? = null,
    onFrameAspectRatioChanged: ((Float) -> Unit)? = null,
)

/**
 * Controls top inset for web DOM video overlay. No-op on non-web platforms.
 */
expect fun setVideoOverlayViewportInsetTop(px: Int)

/**
 * Controls bottom inset for web DOM video overlay (e.g. to leave room for a control bar).
 * No-op on non-web platforms.
 */
expect fun setVideoOverlayViewportInsetBottom(px: Int)
