package com.teachmint.sharex.airplay

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose UI that renders incoming AirPlay screen-mirror frames (Android).
 *
 * Video is decoded by [H264StreamDecoder] (MediaCodec) and rendered directly
 * to a [TextureView] via a hardware [Surface] — no CPU-side Bitmap copies.
 *
 * Shows a waiting/PIN screen when no stream is active, switching to the live
 * feed as soon as the first decoded frame arrives on the Surface.
 *
 * Example usage:
 * ```kotlin
 * val receiver = remember { AirPlayReceiver("shareX") }
 * LaunchedEffect(Unit) { receiver.start() }
 * DisposableEffect(Unit) { onDispose { scope.launch { receiver.stop() } } }
 *
 * AirPlayReceiverScreen(receiver = receiver)
 * ```
 */
@Composable
fun AirPlayReceiverScreen(
    receiver: AirPlayReceiver,
    modifier: Modifier = Modifier,
) {
    val status by receiver.status.collectAsState()
    val activeClientIds by receiver.activeMirroringClientIds.collectAsState()
    val firstDecoder = activeClientIds.firstOrNull()?.let { receiver.getDecoderForClient(it) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // ── TextureView for hardware-decoded video ────────────────────────────
        // Always in the tree so the Surface is ready before the stream arrives.
        if (firstDecoder != null) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        var textureSurfaceGen = 0L
                        fun attachSurface(st: SurfaceTexture) {
                            textureSurfaceGen = firstDecoder.setSurface(Surface(st))
                            tag = textureSurfaceGen
                        }
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                attachSurface(st)
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                firstDecoder.clearSurface(textureSurfaceGen)
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
                    if (firstDecoder.isActiveSurfaceGeneration(boundGeneration)) return@AndroidView
                    textureView.surfaceTexture?.let { st ->
                        textureView.tag = firstDecoder.setSurface(Surface(st))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── Overlay UI (waiting / PIN) shown until first frame arrives ────────
        if (status != AirPlayReceiver.Status.Receiving) {
            WaitingOverlay(receiver = receiver, status = status)
        }
    }
}

@Composable
private fun WaitingOverlay(
    receiver: AirPlayReceiver,
    status: AirPlayReceiver.Status,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .background(Color(0xCC000000), shape = MaterialTheme.shapes.large)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        when (status) {
            AirPlayReceiver.Status.Starting -> {
                CircularProgressIndicator(color = Color.White)
                Text("Starting AirPlay receiver…", color = Color.White)
            }

            AirPlayReceiver.Status.Ready -> {
                Text(
                    text  = "AirPlay Ready",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    text  = "Mirror your iPhone or Mac screen\nto '${receiver.displayName}'",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                PinBox(pin = receiver.pin)
                Spacer(Modifier.height(4.dp))
                AirPlayBadge()
            }

            AirPlayReceiver.Status.Stopping,
            AirPlayReceiver.Status.Idle -> {
                Text("AirPlay receiver stopped", color = Color.Gray)
            }

            else -> Unit
        }
    }
}

@Composable
private fun PinBox(pin: String) {
    Box(
        modifier = Modifier
            .background(Color(0xFF2C2C2E), shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "AirPlay PIN",
                color = Color.Gray,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text  = pin,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun AirPlayBadge() {
    Box(
        modifier = Modifier
            .background(Color(0xFF1C1C1E), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text  = "⬡  AirPlay",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
