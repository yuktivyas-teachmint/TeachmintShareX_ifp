package com.teachmint.sharex.share.shared

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.shepeliev.webrtckmp.WebRtc
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.math.abs

@Composable
actual fun VideoRenderer(
    track: PlatformVideoTrack?,
    modifier: Modifier,
    debugLabel: String?,
    onFrameAspectRatioChanged: ((Float) -> Unit)?,
) {
    val context = LocalContext.current
    val rendererLabel = debugLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "unlabeled"
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var frameAspectRatio by remember { mutableStateOf<Float?>(null) }
    // Key the renderer on orientation so the SurfaceView (and its EGL surface) is
    // recreated after config-handled orientation changes.  Without this, the EGL
    // surface can become stale and frames render to a disconnected buffer, causing the
    // display to freeze on the first frame even though WebRTC keeps decoding at full fps.
    val orientation = LocalConfiguration.current.orientation
    val renderer = remember(context, orientation) {
        SurfaceViewRenderer(context).apply {
            // Place the Surface above the main window layer so that SurfaceFlinger
            // composites fresh frames instead of caching a stale first-frame buffer.
            // Without this, some devices (OnePlus/Oppo) freeze the display even
            // though EglRenderer keeps rendering at full fps.
            setZOrderMediaOverlay(true)
            init(WebRtc.rootEglBase.eglBaseContext, null)
            // Keep fixed-size surface scaling disabled so we don't force a surface aspect
            // ratio that can crop tall desktop frames on very wide host layouts.
            setEnableHardwareScaler(false)
            setMirror(false)
            // Force FIT mode for both matching and mismatching orientation cases.
            // This ensures full-frame rendering (no top/bottom crop) for desktop streams.
            setScalingType(
                RendererCommon.ScalingType.SCALE_ASPECT_FIT,
                RendererCommon.ScalingType.SCALE_ASPECT_FIT,
            )
            println(
                "VIDEO_RENDERER: label=$rendererLabel initialized with SCALE_ASPECT_FIT " +
                    "(full frame, no cropping) orientation=$orientation"
            )
        }
    }
    val rendererId = remember(renderer) { System.identityHashCode(renderer) }

    DisposableEffect(renderer) {
        val scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT
        renderer.setScalingType(scalingType, scalingType)
        onDispose { }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val boxWidth = maxWidth
        val boxHeight = maxHeight
        val containerAspect = if (boxHeight.value > 0f) {
            boxWidth.value / boxHeight.value
        } else {
            1f
        }

        val videoModifier = frameAspectRatio?.let { videoAspect ->
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
            factory = { renderer },
            modifier = videoModifier,
        )
    }

    // Register this effect first so on disposal it runs after sink-detach effect.
    DisposableEffect(renderer) {
        onDispose {
            println("VIDEO_RENDERER: label=$rendererLabel releasing renderer id=$rendererId")
            renderer.release()
        }
    }

    DisposableEffect(track, renderer) {
        val trackTag = when {
            track?.nativeTrack != null -> "native:${System.identityHashCode(track.nativeTrack)}"
            track?.value != null -> "kmp:${System.identityHashCode(track.value)}"
            else -> "none"
        }

        val aspectSink = object : org.webrtc.VideoSink {
            private var lastAspect: Float = -1f
            private var framesInWindow: Int = 0
            private var totalFrames: Long = 0
            private var windowStartMs: Long = SystemClock.elapsedRealtime()

            override fun onFrame(frame: org.webrtc.VideoFrame) {
                framesInWindow += 1
                totalFrames += 1
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - windowStartMs >= 4_000L) {
                    val elapsedSec = (nowMs - windowStartMs).coerceAtLeast(1L) / 1000.0
                    val fps = framesInWindow / elapsedSec
                    println(
                        "VIDEO_RENDERER: label=$rendererLabel renderer id=$rendererId track=$trackTag " +
                            "render-input fps=${"%.2f".format(fps)} " +
                            "windowFrames=$framesInWindow totalFrames=$totalFrames"
                    )
                    framesInWindow = 0
                    windowStartMs = nowMs
                }

                val height = frame.rotatedHeight
                if (height <= 0) return
                val aspect = frame.rotatedWidth.toFloat() / height.toFloat()
                if (aspect <= 0f) return
                if (lastAspect > 0f && abs(lastAspect - aspect) < 0.01f) return
                lastAspect = aspect
                mainHandler.post {
                    val current = frameAspectRatio
                    if (current == null || abs(current - aspect) >= 0.01f) {
                        frameAspectRatio = aspect
                        onFrameAspectRatioChanged?.invoke(aspect)
                        println("VIDEO_RENDERER: label=$rendererLabel updated dynamic aspect ratio=$aspect")
                    }
                }
            }
        }
        var nativeTrackAttached = false
        var kmpTrackAttached = false

        if (track != null) {
            println(
                "VIDEO_RENDERER: label=$rendererLabel adding track to renderer " +
                    "id=$rendererId track=$trackTag"
            )
            when {
                track.nativeTrack != null -> {
                    nativeTrackAttached = runCatching {
                        track.nativeTrack.addSink(renderer)
                        track.nativeTrack.addSink(aspectSink)
                    }.onSuccess {
                        println(
                            "VIDEO_RENDERER: label=$rendererLabel attached native track sink " +
                                "to renderer id=$rendererId"
                        )
                    }.onFailure { error ->
                        runCatching {
                            track.nativeTrack.removeSink(renderer)
                            track.nativeTrack.removeSink(aspectSink)
                        }
                        println(
                            "VIDEO_RENDERER: label=$rendererLabel skipping disposed native track " +
                                "for renderer id=$rendererId: ${error.message}"
                        )
                    }.isSuccess
                }
                track.value != null -> {
                    kmpTrackAttached = runCatching {
                        track.value.addSink(renderer)
                        track.value.addSink(aspectSink)
                    }.onSuccess {
                        println(
                            "VIDEO_RENDERER: label=$rendererLabel attached KMP track sink " +
                                "to renderer id=$rendererId"
                        )
                    }.onFailure { error ->
                        runCatching {
                            track.value.removeSink(renderer)
                            track.value.removeSink(aspectSink)
                        }
                        println(
                            "VIDEO_RENDERER: label=$rendererLabel skipping disposed KMP track " +
                                "for renderer id=$rendererId: ${error.message}"
                        )
                    }.isSuccess
                }
                else -> {
                    println(
                        "VIDEO_RENDERER: label=$rendererLabel could not get video track " +
                            "for renderer id=$rendererId"
                    )
                }
            }
        } else {
            println(
                "VIDEO_RENDERER: label=$rendererLabel track is null, cannot render " +
                    "(renderer id=$rendererId)"
            )
        }
        onDispose {
            if (track != null) {
                println("VIDEO_RENDERER: label=$rendererLabel removing track from renderer id=$rendererId")
                when {
                    track.nativeTrack != null && nativeTrackAttached -> {
                        runCatching {
                            track.nativeTrack.removeSink(renderer)
                            track.nativeTrack.removeSink(aspectSink)
                        }.onFailure { error ->
                            println(
                                "VIDEO_RENDERER: label=$rendererLabel native track already detached " +
                                    "for renderer id=$rendererId: ${error.message}"
                            )
                        }
                    }
                    track.value != null && kmpTrackAttached -> {
                        runCatching {
                            track.value.removeSink(renderer)
                            track.value.removeSink(aspectSink)
                        }.onFailure { error ->
                            println(
                                "VIDEO_RENDERER: label=$rendererLabel KMP track already detached " +
                                    "for renderer id=$rendererId: ${error.message}"
                            )
                        }
                    }
                }
            }
        }
    }
}

actual fun setVideoOverlayViewportInsetTop(px: Int) = Unit
actual fun setVideoOverlayViewportInsetBottom(px: Int) = Unit
