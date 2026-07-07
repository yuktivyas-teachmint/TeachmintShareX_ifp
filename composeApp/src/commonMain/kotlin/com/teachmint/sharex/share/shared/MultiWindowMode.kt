package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

@Composable
expect fun rememberIsInMultiWindowModeState(): State<Boolean>
