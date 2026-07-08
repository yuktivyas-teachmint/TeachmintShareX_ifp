package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.teachmint.sharex.airplay.AirPlayReceiver
import com.teachmint.sharex.airplay.AirPlayReceiverHolder
import com.teachmint.sharex.airplay.AirPlayReceiverScreen

@Composable
fun AirPlayMirrorOverlay() {
    val receiver = AirPlayReceiverHolder.receiver ?: return
    val status by receiver.status.collectAsState()
    if (status == AirPlayReceiver.Status.Receiving) {
        AirPlayReceiverScreen(
            receiver = receiver,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
