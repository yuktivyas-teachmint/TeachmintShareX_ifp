package com.teachmint.sharex.share.shared

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.example.teachmintsharex.share.miracast.MiracastPlaybackManager

@Composable
fun rememberMiracastActiveShare(): ActiveShare? {
    val playbackState by MiracastPlaybackManager.state.collectAsState()
    val session = playbackState.session ?: return null

    val sourceName = session.clientAddress.ifBlank { "Miracast Device" }
    return ActiveShare(
        clientId = MIRACAST_HOST_SHARE_CLIENT_ID,
        clientName = sourceName,
        videoTrack = null,
    )
}

@Composable
fun rememberMiracastConnectedClients(): List<ClientInfo> {
    val playbackState by MiracastPlaybackManager.state.collectAsState()
    val session = playbackState.session ?: return emptyList()
    val name = session.clientAddress.ifBlank { "Miracast Device" }
    return listOf(ClientInfo(clientId = MIRACAST_HOST_SHARE_CLIENT_ID, name = name))
}

@Composable
fun rememberMiracastClientPolicies(): Map<String, ClientCastingPolicy> {
    val playbackState by MiracastPlaybackManager.state.collectAsState()
    if (playbackState.session == null) return emptyMap()
    return mapOf(
        MIRACAST_HOST_SHARE_CLIENT_ID to ClientCastingPolicy(
            isAudioEnabled = !MiracastPlaybackManager.isAudioMuted,
        ),
    )
}

fun setMiracastClientAudioMuted(clientId: String, muted: Boolean) {
    MiracastPlaybackManager.setAudioMuted(muted)
}

fun disconnectMiracastClient(clientId: String) {
    MiracastPlaybackManager.stopSession()
}

@Composable
fun MiracastTileRenderer(
    share: ActiveShare,
    modifier: Modifier = Modifier,
) {
    val playbackState by MiracastPlaybackManager.state.collectAsState()
    val streamAspectRatio = playbackState.videoAspectRatio?.takeIf { it > 0f }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val containerAspect = if (maxHeight.value > 0f) {
                maxWidth.value / maxHeight.value
            } else {
                1f
            }
            val surfaceModifier = streamAspectRatio?.let { videoAspect ->
                if (videoAspect >= containerAspect) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(videoAspect, matchHeightConstraintsFirst = false)
                } else {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(videoAspect, matchHeightConstraintsFirst = true)
                }
            } ?: Modifier.fillMaxSize()

            AndroidView(
                modifier = surfaceModifier,
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    MiracastPlaybackManager.attachSurface(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    MiracastPlaybackManager.attachSurface(holder.surface)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    MiracastPlaybackManager.detachSurface(holder.surface)
                                }
                            },
                        )
                    }
                },
            )
        }

        val statusText = when {
            playbackState.lastError != null -> playbackState.lastError
            playbackState.waitingForSurface -> "Preparing Miracast view..."
            playbackState.decodedFrames <= 0L -> "Waiting for Miracast stream..."
            else -> null
        }

        if (statusText != null) {
            Text(
                text = statusText,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    DisposableEffect(share.clientId) {
        onDispose {
            MiracastPlaybackManager.detachSurface(null)
        }
    }
}
