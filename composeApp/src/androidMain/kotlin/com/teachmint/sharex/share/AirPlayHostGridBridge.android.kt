package com.teachmint.sharex.share.shared

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.teachmint.sharex.airplay.AirPlayReceiver
import com.teachmint.sharex.airplay.AirPlayReceiverHolder

private const val DEFAULT_AIRPLAY_ASPECT_RATIO = 16f / 9f

@Composable
actual fun rememberAirPlayActiveShares(): List<ActiveShare> {
    val receiver = AirPlayReceiverHolder.receiver ?: return emptyList()
    val status by receiver.status.collectAsState()
    if (status != AirPlayReceiver.Status.Receiving) return emptyList()
    val connectedClients by receiver.connectedClients.collectAsState()
    val activeClientIds by receiver.activeMirroringClientIds.collectAsState()
    return activeClientIds.map { clientId ->
        val clientName = connectedClients.firstOrNull { it.clientId == clientId }
            ?.name?.ifBlank { "AirPlay" }
            ?: "AirPlay"
        ActiveShare(
            clientId = clientId,
            clientName = clientName,
            videoTrack = null,
        )
    }
}

@Composable
actual fun rememberAirPlayPendingRequests(): List<PendingShareRequest> {
    val receiver = AirPlayReceiverHolder.receiver ?: return emptyList()
    val requests by receiver.pendingConnectionRequests.collectAsState()
    return requests
}

@Composable
actual fun rememberAirPlayConnectedClientCount(): Int {
    val receiver = AirPlayReceiverHolder.receiver ?: return 0
    val connectedCount by receiver.connectedClientCount.collectAsState()
    return connectedCount
}

@Composable
actual fun rememberAirPlayConnectedClients(): List<ClientInfo> {
    val receiver = AirPlayReceiverHolder.receiver ?: return emptyList()
    val connectedClients by receiver.connectedClients.collectAsState()
    return connectedClients
}

@Composable
actual fun rememberAirPlayClientPolicies(): Map<String, ClientCastingPolicy> {
    val receiver = AirPlayReceiverHolder.receiver ?: return emptyMap()
    val policies by receiver.clientPolicies.collectAsState()
    return policies
}

actual fun approveAirPlayRequest(clientId: String) {
    AirPlayReceiverHolder.receiver?.approveConnectionRequest(clientId)
}

actual fun rejectAirPlayRequest(clientId: String) {
    AirPlayReceiverHolder.receiver?.rejectConnectionRequest(clientId)
}

actual fun isAirPlayRequestClient(clientId: String): Boolean {
    return clientId.startsWith(AIRPLAY_REQUEST_CLIENT_ID_PREFIX)
}

private fun resolveRoutableAirPlayClientId(clientId: String): String? {
    AirPlayReceiverHolder.receiver ?: return null
    return if (clientId.startsWith(AIRPLAY_REQUEST_CLIENT_ID_PREFIX)) clientId else null
}

actual fun updateAirPlayClientPolicy(clientId: String, policy: ClientCastingPolicy) {
    val resolvedClientId = resolveRoutableAirPlayClientId(clientId) ?: return
    AirPlayReceiverHolder.receiver?.updateClientPolicy(resolvedClientId, policy)
}

actual fun setAirPlayClientAudioMuted(clientId: String, muted: Boolean) {
    val resolvedClientId = resolveRoutableAirPlayClientId(clientId) ?: return
    AirPlayReceiverHolder.receiver?.setClientAudioMuted(resolvedClientId, muted)
}

actual fun disconnectAirPlayClient(clientId: String) {
    val resolvedClientId = resolveRoutableAirPlayClientId(clientId) ?: return
    AirPlayReceiverHolder.receiver?.disconnectClient(resolvedClientId)
}

@Composable
actual fun AirPlayTileRenderer(
    share: ActiveShare,
    modifier: Modifier,
) {
    val receiver = AirPlayReceiverHolder.receiver
    val decoder = receiver?.getDecoderForClient(share.clientId)
    val streamAspectRatio = decoder?.videoAspectRatio
        ?.collectAsState()?.value?.takeIf { it > 0f }
    var lastKnownAspectRatio by remember { mutableFloatStateOf(DEFAULT_AIRPLAY_ASPECT_RATIO) }
    if (streamAspectRatio != null) {
        lastKnownAspectRatio = streamAspectRatio
    }
    val resolvedAspectRatio = streamAspectRatio ?: lastKnownAspectRatio

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (decoder != null) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val containerAspect = if (maxHeight.value > 0f) {
                    maxWidth.value / maxHeight.value
                } else {
                    1f
                }
                val surfaceModifier = if (resolvedAspectRatio >= containerAspect) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(resolvedAspectRatio, matchHeightConstraintsFirst = false)
                } else {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(resolvedAspectRatio, matchHeightConstraintsFirst = true)
                }

                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            var textureSurfaceGen = 0L
                            fun attachSurface(st: SurfaceTexture) {
                                textureSurfaceGen = decoder.setSurface(Surface(st))
                                tag = textureSurfaceGen
                            }
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    attachSurface(st)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    decoder.clearSurface(textureSurfaceGen)
                                    textureSurfaceGen = 0L
                                    tag = 0L
                                    return true
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                            if (isAvailable) {
                                surfaceTexture?.let { st ->
                                    attachSurface(st)
                                }
                            }
                        }
                    },
                    update = { textureView ->
                        if (!textureView.isAvailable) return@AndroidView
                        val boundGeneration = (textureView.tag as? Long) ?: 0L
                        if (decoder.isActiveSurfaceGeneration(boundGeneration)) return@AndroidView
                        textureView.surfaceTexture?.let { st ->
                            textureView.tag = decoder.setSurface(Surface(st))
                        }
                    },
                    modifier = surfaceModifier,
                )
            }
        } else {
            Text(
                text = "Waiting for AirPlay stream...",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
