package com.teachmint.sharex.share.host

import com.teachmint.sharex.share.shared.ClientInfo
import com.teachmint.sharex.share.shared.SignalingMessage
import kotlinx.coroutines.flow.SharedFlow

expect class HostSignalingServer(
    onClientConnected: (ClientInfo) -> Unit,
    onClientDisconnected: (String) -> Unit,
    onMessage: (String, SignalingMessage) -> Unit,
    hostNameProvider: () -> String,
) {
    val connectedClients: SharedFlow<List<ClientInfo>>

    suspend fun start(port: Int): Int
    suspend fun stop()
    suspend fun send(clientId: String, message: SignalingMessage)
}
