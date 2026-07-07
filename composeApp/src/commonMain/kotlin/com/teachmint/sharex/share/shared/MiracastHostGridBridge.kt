package com.teachmint.sharex.share.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

const val MIRACAST_HOST_SHARE_CLIENT_ID = "__miracast_host_share__"

fun ActiveShare.isMiracastShare(): Boolean = clientId == MIRACAST_HOST_SHARE_CLIENT_ID

fun isMiracastClientId(clientId: String): Boolean = clientId == MIRACAST_HOST_SHARE_CLIENT_ID

@Composable
expect fun rememberMiracastActiveShare(): ActiveShare?

@Composable
expect fun rememberMiracastConnectedClients(): List<ClientInfo>

@Composable
expect fun rememberMiracastClientPolicies(): Map<String, ClientCastingPolicy>

expect fun setMiracastClientAudioMuted(clientId: String, muted: Boolean)

expect fun disconnectMiracastClient(clientId: String)

@Composable
expect fun MiracastTileRenderer(
    share: ActiveShare,
    modifier: Modifier = Modifier,
)
