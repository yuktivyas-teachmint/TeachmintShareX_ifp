package com.teachmint.sharex.airplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Global holder for the [AirPlayReceiver] instance, allowing the Compose UI layer
 * (in [HostNavigation]) to observe AirPlay state and overlay the mirroring screen.
 */
object AirPlayReceiverHolder {
    var receiver: AirPlayReceiver? by mutableStateOf(null)
}
