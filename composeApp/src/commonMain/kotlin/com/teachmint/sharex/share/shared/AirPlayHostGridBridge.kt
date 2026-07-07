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

@Composable
expect fun rememberAirPlayActiveShares(): List<ActiveShare>

@Composable
expect fun rememberAirPlayPendingRequests(): List<PendingShareRequest>

@Composable
expect fun rememberAirPlayConnectedClientCount(): Int

@Composable
expect fun rememberAirPlayConnectedClients(): List<ClientInfo>

@Composable
expect fun rememberAirPlayClientPolicies(): Map<String, ClientCastingPolicy>

expect fun approveAirPlayRequest(clientId: String)

expect fun rejectAirPlayRequest(clientId: String)

expect fun isAirPlayRequestClient(clientId: String): Boolean

expect fun updateAirPlayClientPolicy(clientId: String, policy: ClientCastingPolicy)

expect fun setAirPlayClientAudioMuted(clientId: String, muted: Boolean)

expect fun disconnectAirPlayClient(clientId: String)

@Composable
expect fun AirPlayTileRenderer(
    share: ActiveShare,
    modifier: Modifier = Modifier,
)
