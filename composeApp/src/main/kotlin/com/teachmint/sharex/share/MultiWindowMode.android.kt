package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow

private val multiWindowModeState = MutableStateFlow(false)

fun updateMultiWindowModeState(isInMultiWindowMode: Boolean) {
    multiWindowModeState.value = isInMultiWindowMode
}

@Composable
fun rememberIsInMultiWindowModeState(): State<Boolean> {
    return multiWindowModeState.collectAsState()
}
