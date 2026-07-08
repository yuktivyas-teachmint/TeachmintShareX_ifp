package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay

@Composable
fun rememberNetworkConnectedState(
    pollIntervalMs: Long = 1500L,
): State<Boolean> = produceState(initialValue = isNetworkConnectionAvailable()) {
    while (true) {
        value = isNetworkConnectionAvailable()
        delay(pollIntervalMs)
    }
}

@Composable
fun rememberConnectedWifiNameState(
    pollIntervalMs: Long = 1500L,
): State<String?> = produceState(initialValue = getConnectedWifiName()) {
    while (true) {
        value = getConnectedWifiName()
        delay(pollIntervalMs)
    }
}

@Composable
fun rememberLocalIpAddressState(
    pollIntervalMs: Long = 1500L,
): State<String?> = produceState(initialValue = getLocalIpAddress()) {
    while (true) {
        value = getLocalIpAddress()
        delay(pollIntervalMs)
    }
}
