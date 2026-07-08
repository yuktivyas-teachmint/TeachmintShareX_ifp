package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

const val AIRPLAY_HOST_SHARE_CLIENT_ID = "__airplay_host_share__"
const val AIRPLAY_REQUEST_CLIENT_ID_PREFIX = "airplay:"

fun isAirPlayClientId(clientId: String): Boolean =
    clientId == AIRPLAY_HOST_SHARE_CLIENT_ID ||
        clientId.startsWith(AIRPLAY_REQUEST_CLIENT_ID_PREFIX)

fun ActiveShare.isAirPlayShare(): Boolean =
    isAirPlayClientId(clientId)

