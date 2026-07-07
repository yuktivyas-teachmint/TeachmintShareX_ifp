package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay

expect fun getLocalIpAddress(): String?
expect fun getConnectedWifiName(): String?
expect fun generateClientId(): String
expect fun getPlatformName(): String
expect fun getSavedDeviceName(): String?
expect fun getHostDisplayName(): String
expect fun createHttpClient(): HttpClient
expect fun isNetworkConnectionAvailable(): Boolean
expect fun isSecureOrigin(): Boolean
expect fun isScreenCaptureSupported(): Boolean
expect fun currentTimeMillis(): Long
expect fun secureRandomInt(bound: Int): Int

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
