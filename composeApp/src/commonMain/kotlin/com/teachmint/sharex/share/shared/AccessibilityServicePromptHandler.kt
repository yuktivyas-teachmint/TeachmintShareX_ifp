package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable

/**
 * Platform-specific handler that prompts the user to enable the AccessibilityService
 * required for remote control input injection.
 *
 * On Android this opens Settings > Accessibility.
 * On other platforms this is a no-op since remote control uses different mechanisms.
 */
@Composable
expect fun AccessibilityServicePromptHandler(
    isPromptRequired: Boolean,
    onServiceEnabled: () -> Unit,
    onDismissed: () -> Unit,
)
