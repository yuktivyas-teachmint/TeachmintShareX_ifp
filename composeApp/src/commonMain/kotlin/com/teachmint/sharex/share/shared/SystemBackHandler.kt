package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable

@Composable
expect fun SystemBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
)
