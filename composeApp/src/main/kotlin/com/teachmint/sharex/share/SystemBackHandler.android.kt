package com.teachmint.sharex.share.shared

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
fun SystemBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}
