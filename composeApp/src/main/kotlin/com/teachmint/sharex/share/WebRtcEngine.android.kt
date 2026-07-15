package com.teachmint.sharex.share.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceConnectionStateChange
import com.shepeliev.webrtckmp.onSignalingStateChange
import com.shepeliev.webrtckmp.onTrack
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.VideoTrack
import com.shepeliev.webrtckmp.WebRtc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.media.AudioAttributes
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import android.media.AudioManager
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CapturerObserver
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import androidx.core.content.ContextCompat
import java.lang.reflect.Field
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class AndroidDisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
)

private fun resolveDefaultDisplayInfo(context: Context): AndroidDisplayInfo {
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)

    // Primary path: use Display.getRealSize() which automatically accounts for the
    // current display rotation.  This is more reliable than physicalWidth/Height +
    // manual rotation swap, especially when the foreground Activity has a locked
    // orientation (e.g. portrait) but the user navigated to a landscape app/launcher.
    if (display != null) {
        val realSize = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(realSize)

        val densityMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(densityMetrics)
        val density = densityMetrics.densityDpi
            .takeIf { it > 0 }
            ?: context.resources.displayMetrics.densityDpi.coerceAtLeast(ANDROID_MIN_CAPTURE_DENSITY_DPI)

        if (realSize.x > 0 && realSize.y > 0) {
            return AndroidDisplayInfo(
                widthPx = realSize.x,
                heightPx = realSize.y,
                densityDpi = density,
            )
        }
    }

    // Fallback: use WindowManager metrics.
    val fallbackMetrics = DisplayMetrics()
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    @Suppress("DEPRECATION")
    windowManager?.defaultDisplay?.getRealMetrics(fallbackMetrics)

    val fallbackDensity = fallbackMetrics.densityDpi
        .takeIf { it > 0 }
        ?: context.resources.displayMetrics.densityDpi.coerceAtLeast(ANDROID_MIN_CAPTURE_DENSITY_DPI)

    return AndroidDisplayInfo(
        widthPx = fallbackMetrics.widthPixels.coerceAtLeast(1),
        heightPx = fallbackMetrics.heightPixels.coerceAtLeast(1),
        densityDpi = fallbackDensity,
    )
}

class AndroidWebRtcEngine : WebRtcEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var screenCapturer: ScreenCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: org.webrtc.VideoTrack? = null

    // Audio capture resources. We create them lazily in [startAudioCapture]
    // so they are only allocated when audio sharing is actually requested.
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // System-playback (non-microphone) audio capturer. Active only during a
    // share session that has both MediaProjection consent and RECORD_AUDIO.
    private var systemAudioCapturer: SystemAudioCapturer? = null

    private var released = false

    private val _captureDisplayRotation = kotlinx.coroutines.flow.MutableStateFlow(0)
    override val captureDisplayRotation: kotlinx.coroutines.flow.StateFlow<Int> =
        _captureDisplayRotation

    private val _captureRestartRequested = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val captureRestartRequested: kotlinx.coroutines.flow.SharedFlow<Unit> =
        _captureRestartRequested

    init {
        // Eagerly configure webrtc-kmp with a PeerConnectionFactory whose
        // AudioDeviceModule uses MEDIA / MOVIE audio attributes. This makes
        // remote audio play through Android's STREAM_MUSIC path so devices
        // that don't have an audible voice-call speaker (e.g. the Rockchip
        // classroom box, TV sticks, HDMI dongles) route it to the connected
        // display instead of the tiny onboard codec the communication-mode
        // ADM would otherwise target. Must run before any PeerConnection is
        // created by webrtc-kmp.
        configureWebRtcKmpAudioRouting()
    }

    // Our own PeerConnectionFactory for native track creation on the sender
    // side (screen video + microphone audio). Also uses the media-attribute
    // ADM so the local audio source and any locally rendered tracks behave
    // consistently with what the host receiver is doing.
    private val nativeFactory: PeerConnectionFactory by lazy {
        val context = AndroidContextHolder.applicationContext
            ?: error("AndroidContextHolder not initialized; cannot create PeerConnectionFactory")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(buildMediaAudioDeviceModule(context))
            .createPeerConnectionFactory()
    }

    // Dedicated factory for BYOM sessions. Every other factory in this engine
    // (webrtc-kmp's and [nativeFactory]) uses [buildMediaAudioDeviceModule],
    // whose AudioBufferCallback discards mic samples and substitutes system
    // playback — correct for screen sharing, but it makes a BYOM "microphone"
    // stream silent whenever no MediaProjection is active. Audio capture is
    // driven by the ADM of the factory that owns the peer connection, so BYOM
    // needs its own factory with a plain ADM AND its own peer connections
    // ([createByomPeerConnection]); tracks alone are not enough.
    private var byomFactoryInitialized = false
    private var byomAudioDeviceModule: JavaAudioDeviceModule? = null

    private val byomFactory: PeerConnectionFactory by lazy {
        val context = AndroidContextHolder.applicationContext
            ?: error("AndroidContextHolder not initialized; cannot create BYOM PeerConnectionFactory")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        // Log the level of every captured mic buffer (peak/avg since last log,
        // ~5s cadence) so "host sends silence" vs "host sends audio but client
        // hears nothing" is decidable from logcat alone.
        var micCallbackCount = 0L
        var micWindowPeak = 0
        var micWindowAbsSum = 0L
        var micWindowSamples = 0L
        val adm = JavaAudioDeviceModule.builder(context)
            .setSamplesReadyCallback { samples ->
                micCallbackCount++
                val data = samples.data
                var i = 0
                while (i + 1 < data.size) {
                    val s = (((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()).toInt()
                    val a = if (s < 0) -s else s
                    if (a > micWindowPeak) micWindowPeak = a
                    micWindowAbsSum += a
                    micWindowSamples++
                    i += 2
                }
                if (micCallbackCount == 1L || micCallbackCount % 500L == 0L) {
                    val avg = if (micWindowSamples > 0) micWindowAbsSum / micWindowSamples else 0
                    val silence = if (micWindowPeak < 50) " ⚠️ MIC CAPTURING SILENCE" else ""
                    println(
                        "BYOM_MIC HOST_SEND#$micCallbackCount: ${samples.sampleRate}Hz " +
                            "${samples.channelCount}ch peak=$micWindowPeak avg=$avg (max 32767)$silence"
                    )
                    micWindowPeak = 0; micWindowAbsSum = 0; micWindowSamples = 0
                }
            }
            .createAudioDeviceModule()
        byomAudioDeviceModule = adm
        val eglContext = WebRtc.rootEglBase.eglBaseContext
        byomFactoryInitialized = true
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
    }

    private fun configureWebRtcKmpAudioRouting() {
        val context = AndroidContextHolder.applicationContext
        if (context == null) {
            println("WEBRTC_ENGINE (Android): ⚠️ No application context; skipping ADM configure")
            return
        }
        // webrtc-kmp needs PeerConnectionFactory.initialize() called before we
        // build a factory for it.
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val builder = WebRtc.createPeerConnectionFactoryBuilder()
            .setAudioDeviceModule(buildMediaAudioDeviceModule(context))
        runCatching { WebRtc.configure(peerConnectionFactoryBuilder = builder) }
            .onFailure {
                println(
                    "WEBRTC_ENGINE (Android): ⚠️ WebRtc factory already configured " +
                        "(${it.message}); remote audio may fall back to voice-call routing"
                )
            }
            .onSuccess {
                println("WEBRTC_ENGINE (Android): ✅ Configured webrtc-kmp ADM for media playback (STREAM_MUSIC)")
            }
    }

    private fun buildMediaAudioDeviceModule(context: Context): JavaAudioDeviceModule {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        // We override the outbound audio buffer with system-playback PCM
        // (whatever is playing on the device) via SystemAudioBufferProvider.
        // WebRTC still opens a mic AudioRecord to drive the capture loop, but
        // its samples are completely replaced before transmission, so the
        // remote peer only hears the device's media playback, not the mic.
        val audioBufferCallback = JavaAudioDeviceModule.AudioBufferCallback {
                buffer, _ /*audioFormat*/, _ /*channelCount*/, _ /*sampleRate*/,
                _ /*bytesRead*/, captureTimestampNs ->
            SystemAudioBufferProvider.fillBuffer(buffer)
            captureTimestampNs
        }

        return JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(audioAttributes)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioBufferCallback(audioBufferCallback)
            .createAudioDeviceModule()
    }

    override suspend fun createPeerConnection(iceServers: List<IceServerConfig>): WebRtcPeerConnection {
        println("WEBRTC_ENGINE (Android): Creating peer connection with ${iceServers.size} ICE servers")
        val hasTurn = IceServerConfigDefaults.hasTurnServer(iceServers)
        println("WEBRTC_ENGINE (Android): TURN servers available: $hasTurn")
        if (!hasTurn) {
            println("WEBRTC_ENGINE (Android): ⚠️ WARNING: No TURN servers configured. Cross-network connections may fail.")
        }

        // Log each ICE server URL for debugging TURN allocation issues
        iceServers.forEachIndexed { idx, server ->
            val hasAuth = !server.username.isNullOrBlank() && !server.credential.isNullOrBlank()
            println("WEBRTC_ENGINE (Android): ICE server[$idx] urls=${server.urls} auth=$hasAuth")
        }

        val nativeIceServers = iceServers.map {
            org.webrtc.PeerConnection.IceServer.builder(it.urls)
                .setUsername(it.username.orEmpty())
                .setPassword(it.credential.orEmpty())
                .createIceServer()
        }
        val rtcConfig = RtcConfiguration(
            iceServers = iceServers.map {
                IceServer(
                    urls = it.urls,
                    username = it.username.orEmpty(),
                    password = it.credential.orEmpty(),
                )
            }
        )
        val pc = PeerConnection(rtcConfig)
        return KmpPeerConnection(scope, pc, nativeIceServers)
    }

    override suspend fun createByomPeerConnection(iceServers: List<IceServerConfig>): WebRtcPeerConnection {
        println("WEBRTC_ENGINE (Android): Creating BYOM peer connection with ${iceServers.size} ICE servers")
        val nativeIceServers = iceServers.map {
            org.webrtc.PeerConnection.IceServer.builder(it.urls)
                .setUsername(it.username.orEmpty())
                .setPassword(it.credential.orEmpty())
                .createIceServer()
        }
        val rtcConfig = org.webrtc.PeerConnection.RTCConfiguration(nativeIceServers).apply {
            sdpSemantics = org.webrtc.PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return NativeByomPeerConnection(byomFactory, rtcConfig)
    }

    override suspend fun startScreenCapture(): PlatformVideoTrack {
        val context = AndroidContextHolder.applicationContext
            ?: throw IllegalStateException("AndroidContextHolder not initialized")

        // Release previous capture resources but keep the foreground service alive so that
        // ensureStartedAndReady() can reuse it.  This avoids the mAllowStartForeground
        // restriction that blocks startForegroundService() when the app is in background
        // (e.g. during signaling transport recovery).
        stopScreenCapture(stopBroadcast = false)

        // Android 14+ validates media projection consent when the foreground service starts.
        // Make sure we already have a fresh permission intent before starting the service.
        val mediaProjectionIntent =
            cachedScreenCapturePermission?.let { android.content.Intent(it) }
                ?: throw ScreenCapturePermissionRequired()

        // Start foreground service before screen capture (required for Android 14+)
        val isServiceReady =
            ScreenCaptureService.ensureStartedAndReady(context) ||
                retryEnsureScreenCaptureServiceReady(context)
        if (!isServiceReady) {
            if (requiresFreshPermissionPerCapture()) {
                cachedScreenCapturePermission = null
                throw ScreenCapturePermissionRequired(
                    "Screen capture permission is required before starting the projection service.",
                )
            }
            throw ScreenCaptureNotSupported(
                "Foreground mediaProjection service is not ready. Please retry screen sharing."
            )
        }

        // Get device screen dimensions for full-resolution capture.
        val displayInfo = resolveDefaultDisplayInfo(context)
        val displayWidth = displayInfo.widthPx
        val displayHeight = displayInfo.heightPx
        val captureProfile = computeAndroidCaptureProfile(
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            displayDensityDpi = displayInfo.densityDpi,
        )
        val width = captureProfile.width
        val height = captureProfile.height
        val density = captureProfile.densityDpi

        println(
            "📱 ANDROID CAPTURE: Display=${displayWidth}x${displayHeight}, " +
                "capture=${width}x${height}@${TARGET_CAPTURE_FPS}fps, density=$density dpi"
        )

        val capturer = ScreenCapturer(
            context = context,
            mediaProjectionIntent = mediaProjectionIntent,
            width = width,
            height = height,
            initialDensityDpi = density,
            onDisplayRotationChanged = { rotation ->
                _captureDisplayRotation.value = rotation
            },
            onCaptureRestartRequested = {
                cachedScreenCapturePermission = null
                _captureRestartRequested.tryEmit(Unit)
            },
            onKeyFrameRequested = {
                // Briefly disable and re-enable the local VideoTrack. WebRTC's
                // encoder resets its keyframe counter on the enabled→disabled→
                // enabled transition and emits an IDR on the next delivered frame.
                // This ensures all connected clients can resync after a
                // VirtualDisplay kick (e.g. post-screenshot recovery).
                val keyFrameHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val track = localVideoTrack ?: return@ScreenCapturer
                track.setEnabled(false)
                keyFrameHandler.postDelayed({
                    localVideoTrack?.setEnabled(true)
                    println("SCREEN_CAPTURER: 🔑 IDR forced via track re-enable ✅")
                }, 80L)
            },
        )
        screenCapturer = capturer

        // Create video source from our native factory
        // IMPORTANT: Pass true for isScreencast to prevent WebRTC from downscaling
        val source = nativeFactory.createVideoSource(true)
        videoSource = source
        println("SCREEN_CAPTURE: Created VideoSource with isScreencast=true")

        // Create SurfaceTextureHelper for the capturer
        val surfaceTextureHelper = SurfaceTextureHelper.create(
            "ScreenCaptureThread",
            WebRtc.rootEglBase.eglBaseContext
        )

        // Initialize the capturer with the video source
        capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)

        println(
            "📱 ANDROID CAPTURE: Starting capture at " +
                "${width}x${height} @ ${TARGET_CAPTURE_FPS}fps"
        )
        try {
            capturer.startCapture(width, height, TARGET_CAPTURE_FPS)
            if (requiresFreshPermissionPerCapture()) {
                cachedScreenCapturePermission = null
            }
        } catch (error: Exception) {
            if (isExpiredProjectionPermissionError(error)) {
                println("SCREEN_CAPTURE: Cached screen-capture permission expired: ${error.message}")
                cachedScreenCapturePermission = null
                stopScreenCapture()
                throw ScreenCapturePermissionRequired(
                    "Screen capture permission expired. Please grant screen capture again.",
                )
            }
            stopScreenCapture()
            throw error
        }

        // Create video track from native factory
        val videoTrack = nativeFactory.createVideoTrack("screen", source)
        localVideoTrack = videoTrack
        println(
            "📱 ANDROID CAPTURE: ✅ Created video track - sending " +
                "${width}x${height} @ ${TARGET_CAPTURE_FPS}fps"
        )

        return PlatformVideoTrack(videoTrack)
    }

    override fun setScreenCapturePermission(permission: ScreenCapturePermissionData) {
        cachedScreenCapturePermission = android.content.Intent(permission)
    }

    /**
     * Start capturing audio for the share session.
     *
     * On Android we now stream **system playback audio** — whatever is playing
     * on the device (e.g. a video in a browser or media app) — instead of the
     * microphone. This is done via [AudioPlaybackCaptureConfiguration] (API 29+)
     * attached to the same [MediaProjection] token that drives screen capture.
     *
     * The captured PCM is injected into WebRTC's outbound audio stream through
     * an `AudioBufferCallback` registered on the [JavaAudioDeviceModule]
     * ([buildMediaAudioDeviceModule]). WebRTC still opens a mic AudioRecord to
     * drive the capture loop, but its samples are fully overwritten before
     * transmission, so the remote peer only hears the device's media playback.
     *
     * If [options.microphone] is false (and systemAudio is also false) we
     * skip all audio. RECORD_AUDIO is required by both paths.
     */
    override suspend fun startAudioCapture(options: AudioCaptureOptions): PlatformAudioTrack? {
        if (!options.isEnabled) {
            println("AUDIO_CAPTURE (Android): audio capture disabled; skipping")
            return null
        }

        val context = AndroidContextHolder.applicationContext
        if (context != null &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            println(
                "AUDIO_CAPTURE (Android): RECORD_AUDIO not granted; skipping audio capture " +
                    "(screen sharing will continue without audio)"
            )
            return null
        }

        // Always reset any previous audio capture before starting a new one.
        stopAudioCapture()

        // Start system-audio (playback) capture tied to the current MediaProjection.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = ScreenMediaProjectionHolder.current()
            if (projection != null) {
                val capturer = SystemAudioCapturer(projection)
                capturer.start()
                systemAudioCapturer = capturer
            } else {
                println(
                    "AUDIO_CAPTURE (Android): ⚠️ No MediaProjection available yet; " +
                        "system audio will stream silence until screen capture starts."
                )
            }
        } else {
            println(
                "AUDIO_CAPTURE (Android): ⚠️ System audio capture requires Android 10+ " +
                    "(current=${Build.VERSION.SDK_INT}); streaming silence."
            )
        }

        return try {
            val source = nativeFactory.createAudioSource(MediaConstraints())
            val track = nativeFactory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, source)
            track.setEnabled(true)
            localAudioSource = source
            localAudioTrack = track
            println("AUDIO_CAPTURE (Android): ✅ System-audio track created (id=$LOCAL_AUDIO_TRACK_ID)")
            PlatformAudioTrack(track)
        } catch (error: Throwable) {
            println("AUDIO_CAPTURE (Android): ❌ Failed to create audio track: ${error.message}")
            stopAudioCapture()
            null
        }
    }

    override fun stopAudioCapture() {
        systemAudioCapturer?.let { runCatching { it.stop() } }
        systemAudioCapturer = null

        localAudioTrack?.let { track ->
            runCatching { track.setEnabled(false) }
            runCatching { track.dispose() }
        }
        localAudioTrack = null

        localAudioSource?.let { source ->
            runCatching { source.dispose() }
        }
        localAudioSource = null
    }

    // ── BYOM Camera Capture ───────────────────────────────────────────────

    private var byomCameraCapturer: org.webrtc.VideoCapturer? = null
    private var byomCameraSource: VideoSource? = null
    private var byomCameraTrackNative: org.webrtc.VideoTrack? = null
    private var byomCameraSurfaceHelper: SurfaceTextureHelper? = null

    private val _cameraCaptureFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val cameraCaptureFailed: kotlinx.coroutines.flow.SharedFlow<Unit> =
        _cameraCaptureFailed.asSharedFlow()

    private val byomCameraEventsHandler = object : org.webrtc.CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(errorDescription: String?) {
            println("BYOM_CAMERA: ❌ Camera error: $errorDescription")
            _cameraCaptureFailed.tryEmit(Unit)
        }

        override fun onCameraDisconnected() {
            println("BYOM_CAMERA: ❌ Camera disconnected")
            _cameraCaptureFailed.tryEmit(Unit)
        }

        override fun onCameraFreezed(errorDescription: String?) {
            println("BYOM_CAMERA: ⚠️ Camera freezed: $errorDescription")
        }

        override fun onCameraOpening(cameraName: String?) {
            println("BYOM_CAMERA: Opening camera: $cameraName")
        }

        override fun onFirstFrameAvailable() {
            println("BYOM_CAMERA: First frame available")
        }

        override fun onCameraClosed() {
            println("BYOM_CAMERA: Camera closed")
        }
    }

    override suspend fun startCameraCapture(): PlatformVideoTrack? {
        val context = AndroidContextHolder.applicationContext ?: run {
            println("BYOM_CAMERA: No application context — skipping")
            return null
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            println("BYOM_CAMERA: CAMERA permission not granted")
            return null
        }
        stopCameraCapture()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val allCameraIds = runCatching { cameraManager.cameraIdList.toList() }.getOrDefault(emptyList())
        println("BYOM_CAMERA: Available cameras from CameraManager: $allCameraIds")

        fun facingOf(id: String) = runCatching {
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        }.getOrNull()

        val cameraId = allCameraIds.firstOrNull { facingOf(it) == CameraCharacteristics.LENS_FACING_EXTERNAL }
            ?: allCameraIds.firstOrNull { facingOf(it) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: allCameraIds.firstOrNull()
            ?: run {
                println("BYOM_CAMERA: No camera found on device")
                return null
            }
        println("BYOM_CAMERA: Selected cameraId=$cameraId facing=${facingOf(cameraId)}")

        // Created from byomFactory: BYOM tracks must belong to the same factory
        // as the BYOM peer connection they are added to.
        val source = byomFactory.createVideoSource(false)
        byomCameraSource = source

        val surfaceHelper = SurfaceTextureHelper.create("BYOMCaptureThread", WebRtc.rootEglBase.eglBaseContext)
        byomCameraSurfaceHelper = surfaceHelper

        val capturer = org.webrtc.Camera2Capturer(context, cameraId, byomCameraEventsHandler)
        capturer.initialize(surfaceHelper, context, source.capturerObserver)
        capturer.startCapture(BYOM_CAMERA_WIDTH, BYOM_CAMERA_HEIGHT, BYOM_CAMERA_FPS)
        byomCameraCapturer = capturer

        val track = byomFactory.createVideoTrack("byom_camera", source)
        byomCameraTrackNative = track
        println("BYOM_CAMERA: Camera capture started — ${BYOM_CAMERA_WIDTH}x${BYOM_CAMERA_HEIGHT}@${BYOM_CAMERA_FPS}fps, cameraId=$cameraId")
        return PlatformVideoTrack(track)
    }

    override fun stopCameraCapture() {
        byomCameraCapturer?.let { runCatching { it.stopCapture() }; runCatching { it.dispose() } }
        byomCameraCapturer = null
        byomCameraTrackNative?.let { runCatching { it.dispose() } }
        byomCameraTrackNative = null
        byomCameraSource?.let { runCatching { it.dispose() } }
        byomCameraSource = null
        byomCameraSurfaceHelper?.let { runCatching { it.dispose() } }
        byomCameraSurfaceHelper = null
    }

    // ── BYOM Microphone Capture ───────────────────────────────────────────

    private var byomMicSource: AudioSource? = null
    private var byomMicTrack: AudioTrack? = null

    override suspend fun startMicrophoneCapture(): PlatformAudioTrack? {
        val context = AndroidContextHolder.applicationContext
        if (context != null &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            println("BYOM_MIC: RECORD_AUDIO not granted; BYOM will stream video-only")
            return null
        }
        stopMicrophoneCapture()
        return try {
            val source = byomFactory.createAudioSource(MediaConstraints())
            val track = byomFactory.createAudioTrack(BYOM_MIC_TRACK_ID, source)
            track.setEnabled(true)
            byomMicSource = source
            byomMicTrack = track
            // Don't trust Android's default VOICE_COMMUNICATION routing: it
            // follows the most-recently-attached USB input, so a webcam
            // (Brio) re-enumerating behind the hub silently steals the mic
            // route from the real mic array. Pin the capture explicitly and
            // log every candidate so route theft shows up in logcat instead
            // of requiring dumpsys archaeology.
            context?.let { applyPreferredMicDevice(it) }
            println("BYOM_MIC: ✅ Microphone track created (id=$BYOM_MIC_TRACK_ID)")
            PlatformAudioTrack(track)
        } catch (error: Throwable) {
            println("BYOM_MIC: ❌ Failed to create microphone track: ${error.message}")
            stopMicrophoneCapture()
            null
        }
    }

    private fun applyPreferredMicDevice(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val inputs = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
        inputs.forEach { device ->
            println(
                "BYOM_MIC: input candidate id=${device.id} type=${device.type} " +
                    "product='${device.productName}'"
            )
        }

        fun nameOf(device: android.media.AudioDeviceInfo) =
            device.productName?.toString()?.lowercase().orEmpty()

        val webcamNames = listOf("brio", "webcam", "c920", "c925", "c930")
        val usbType = android.media.AudioDeviceInfo.TYPE_USB_DEVICE

        // Priority: the IFP's mic array (mvsilicon B1) → built-in mic →
        // any USB input that isn't a known webcam. Null keeps OS routing.
        val preferred = inputs.firstOrNull { device ->
            device.type == usbType &&
                (nameOf(device).contains("mvsilicon") || nameOf(device).contains("b1"))
        }
            ?: inputs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: inputs.firstOrNull { device ->
                device.type == usbType && webcamNames.none { nameOf(device).contains(it) }
            }

        if (preferred == null) {
            println("BYOM_MIC: ⚠️ no preferred input matched — using OS default routing")
            return
        }
        runCatching {
            byomAudioDeviceModule?.setPreferredInputDevice(preferred)
        }.onSuccess {
            println(
                "BYOM_MIC: ✅ capture pinned to '${preferred.productName}' " +
                    "(id=${preferred.id}, type=${preferred.type})"
            )
        }.onFailure { error ->
            println("BYOM_MIC: ⚠️ setPreferredInputDevice failed: ${error.message}")
        }
    }

    override fun stopMicrophoneCapture() {
        byomMicTrack?.let { track ->
            runCatching { track.setEnabled(false) }
            runCatching { track.dispose() }
        }
        byomMicTrack = null
        byomMicSource?.let { source ->
            runCatching { source.dispose() }
        }
        byomMicSource = null
    }

    override fun stopScreenCapture(stopBroadcast: Boolean) {
        localVideoTrack?.dispose()
        localVideoTrack = null

        screenCapturer?.dispose()
        screenCapturer = null

        videoSource?.dispose()
        videoSource = null

        if (stopBroadcast) {
            val context = AndroidContextHolder.applicationContext
            context?.let { ScreenCaptureService.stop(it) }
        } else {
            println("SCREEN_CAPTURER: Keeping foreground service alive for transport recovery")
        }
    }

    override fun release() {
        if (released) return
        released = true
        stopScreenCapture()
        stopAudioCapture()
        stopCameraCapture()
        stopMicrophoneCapture()
        scope.cancel()
        runCatching { nativeFactory.dispose() }
        if (byomFactoryInitialized) {
            runCatching { byomFactory.dispose() }
            byomAudioDeviceModule?.let { runCatching { it.release() } }
            byomAudioDeviceModule = null
        }
    }

    private fun isPermissionAvailable(): Boolean {
        return cachedScreenCapturePermission != null
    }

    private fun computeAndroidCaptureProfile(
        displayWidth: Int,
        displayHeight: Int,
        displayDensityDpi: Int,
    ): AndroidCaptureProfile {
        val sourceWidth = alignToEven(displayWidth)
        val sourceHeight = alignToEven(displayHeight)
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return AndroidCaptureProfile(width = 1280, height = 720, densityDpi = displayDensityDpi)
        }

        val edgeScale =
            ANDROID_MAX_CAPTURE_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight).toDouble()
        val pixelsScale = sqrt(
            ANDROID_MAX_CAPTURE_PIXELS.toDouble() / (sourceWidth.toDouble() * sourceHeight.toDouble())
        )
        val scale = min(1.0, min(edgeScale, pixelsScale))
        if (scale >= 0.999) {
            return AndroidCaptureProfile(
                width = sourceWidth,
                height = sourceHeight,
                densityDpi = displayDensityDpi,
            )
        }

        val targetWidth = alignToEven((sourceWidth * scale).toInt())
        val targetHeight = alignToEven((sourceHeight * scale).toInt())
        val targetDensity = (displayDensityDpi * scale).toInt()
            .coerceAtLeast(ANDROID_MIN_CAPTURE_DENSITY_DPI)
        return AndroidCaptureProfile(
            width = targetWidth,
            height = targetHeight,
            densityDpi = targetDensity,
        )
    }

    private fun alignToEven(value: Int): Int {
        val normalized = value.coerceAtLeast(ANDROID_DIM_ALIGNMENT)
        return (normalized / ANDROID_DIM_ALIGNMENT) * ANDROID_DIM_ALIGNMENT
    }

    private fun requiresFreshPermissionPerCapture(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    private suspend fun retryEnsureScreenCaptureServiceReady(context: Context): Boolean {
        println("SCREEN_CAPTURE: Foreground service not ready yet, retrying once")
        delay(SCREEN_CAPTURE_SERVICE_RETRY_DELAY_MS)
        return ScreenCaptureService.ensureStartedAndReady(context)
    }

    private fun isExpiredProjectionPermissionError(error: Exception): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("don't re-use the resultdata") ||
            message.contains("token that has timed out") ||
            message.contains("multiple captures") ||
            message.contains("createvirtualdisplay")
    }

    companion object {
        private const val TARGET_CAPTURE_FPS = 20
        private const val SCREEN_CAPTURE_SERVICE_RETRY_DELAY_MS = 400L
        private const val LOCAL_AUDIO_TRACK_ID = "mic"
        private const val BYOM_MIC_TRACK_ID = "byom_mic"
        private const val BYOM_CAMERA_WIDTH = 1280
        private const val BYOM_CAMERA_HEIGHT = 720
        private const val BYOM_CAMERA_FPS = 30
    }
}

private class KmpPeerConnection(
    private val scope: CoroutineScope,
    private val peerConnection: PeerConnection,
    private val nativeIceServers: List<org.webrtc.PeerConnection.IceServer> = emptyList(),
) : WebRtcPeerConnection {
    private val _iceCandidates = MutableSharedFlow<IceCandidateData>(extraBufferCapacity = 64)
    override val iceCandidates: Flow<IceCandidateData> = _iceCandidates.asSharedFlow()

    private val _remoteTracks = MutableSharedFlow<PlatformVideoTrack>(replay = 1, extraBufferCapacity = 8)
    override val remoteVideoTracks: Flow<PlatformVideoTrack> = _remoteTracks.asSharedFlow()
    private val peerTag = "pc@${System.identityHashCode(this)}"
    private var videoSender: RtpSender? = null
    private var nativePeerConnectionField: Field? = null
    private var inboundStatsJob: Job? = null
    private var lastInboundSnapshot: InboundVideoSnapshot? = null
    private var lastInboundAudioSnapshot: InboundAudioSnapshot? = null
    private var lastOutboundVideoSnapshot: OutboundVideoSnapshot? = null
    private var lastOutboundAudioSnapshot: OutboundAudioSnapshot? = null
    private var hasLoggedInboundStats = false
    private var consecutiveVideoStallPolls = 0
    private var remoteAudioPlaybackEnabled = true
    private var preferredMusicVolume: Int? = null
    private var audioPlaybackGuardJob: Job? = null
    private var lastVolumeRecoveryLogAtMs = 0L

    // ── Data Channel support ────────────────────────────────────────────
    private val _incomingDataChannels = MutableSharedFlow<WebRtcDataChannel>(extraBufferCapacity = 8)
    override val incomingDataChannels: Flow<WebRtcDataChannel> = _incomingDataChannels.asSharedFlow()

    init {
        enlargeAudioJitterBuffer()
        startInboundStatsMonitoring()
        registerNativeDataChannelObserver()

        // Monitor ICE connection state changes
        scope.launch {
            peerConnection.onIceConnectionStateChange.collect { state ->
                println("$peerTag 🔌 ICE connection state changed to: $state")
            }
        }

        // Monitor peer connection state changes
        scope.launch {
            peerConnection.onConnectionStateChange.collect { state ->
                println("$peerTag 🔗 Peer connection state changed to: $state")
            }
        }

        // Monitor signaling state changes
        scope.launch {
            peerConnection.onSignalingStateChange.collect { state ->
                println("$peerTag 📡 Signaling state changed to: $state")
            }
        }

        scope.launch {
            peerConnection.onIceCandidate.collect { candidate ->
                val sdp = candidate.toData().candidate
                val candidateType = when {
                    sdp.contains("typ relay") -> "relay"
                    sdp.contains("typ srflx") -> "srflx"
                    sdp.contains("typ prflx") -> "prflx"
                    sdp.contains("typ host") -> "host"
                    else -> "unknown"
                }
                println("$peerTag ICE candidate generated: type=$candidateType mid=${candidate.toData().sdpMid}")
                _iceCandidates.emit(candidate.toData())
            }
        }
        scope.launch {
            peerConnection.onTrack.collect { trackEvent ->
                val track = trackEvent.track
                if (track is VideoTrack) {
                    _remoteTracks.emit(PlatformVideoTrack(track))
                }
            }
        }
    }

    override fun createDataChannel(label: String): WebRtcDataChannel {
        val nativePc = resolveNativePeerConnection()
            ?: error("Native PeerConnection unavailable; cannot create data channel")
        val init = DataChannel.Init().apply {
            ordered = true
            negotiated = true
            id = NEGOTIATED_DATA_CHANNEL_ID
        }
        val dc = nativePc.createDataChannel(label, init)
            ?: error("Failed to create data channel '$label'")
        println("$peerTag DataChannel created: label=$label id=$NEGOTIATED_DATA_CHANNEL_ID (negotiated)")
        return AndroidDataChannel(dc)
    }

    private fun registerNativeDataChannelObserver() {
        // Using negotiated data channels — both peers create the channel with
        // the same {id, negotiated=true} so they auto-connect. No onDataChannel
        // callback registration needed.
        println("$peerTag DataChannel: using negotiated channels (both sides create)")
    }

    override suspend fun createOffer(): SessionDescriptionData {
        val offer = peerConnection.createOffer(
            OfferAnswerOptions(
                offerToReceiveAudio = true,
                offerToReceiveVideo = true,
            )
        )
        return offer.toData()
    }

    override suspend fun createAnswer(): SessionDescriptionData {
        val answer = peerConnection.createAnswer(
            OfferAnswerOptions(
                offerToReceiveAudio = true,
                offerToReceiveVideo = true,
            )
        )
        return answer.toData()
    }

    override suspend fun setLocalDescription(description: SessionDescriptionData) {
        peerConnection.setLocalDescription(description.toNative())
    }

    override suspend fun setRemoteDescription(description: SessionDescriptionData) {
        peerConnection.setRemoteDescription(description.toNative())
    }

    override suspend fun addIceCandidate(candidate: IceCandidateData) {
        peerConnection.addIceCandidate(candidate.toNative())
    }

    override fun addLocalVideoTrack(track: PlatformVideoTrack) {
        when {
            track.value != null -> {
                // KMP track - use normal addTrack
                peerConnection.addTrack(track.value)
            }
            track.nativeTrack != null -> {
                try {
                    val nativePc = resolveNativePeerConnection()
                    if (nativePc == null) {
                        println("WEBRTC_ENGINE: ❌ Native PeerConnection is not available")
                        return
                    }

                    videoSender = nativePc.addTrack(track.nativeTrack, listOf(LOCAL_MEDIA_STREAM_ID))
                    println("WEBRTC_ENGINE: ✅ Native track added to peer connection")
                    scope.launch {
                        setEncoderConfiguration(ANDROID_INITIAL_ENCODER_CONFIG)
                    }
                } catch (e: Exception) {
                    println("WEBRTC_ENGINE: ❌ Could not add native track: ${e.message}")
                    e.printStackTrace()
                }
            }
            else -> println("WEBRTC_ENGINE: ❌ No track to add")
        }
    }

    /**
     * Attach the microphone audio track produced by
     * [AndroidWebRtcEngine.startAudioCapture] to this peer connection.
     *
     * We reach into the underlying native `org.webrtc.PeerConnection` via
     * reflection (the same trick used for native video) so we can attach the
     * track to the SAME media stream id as the video. Using the same stream id
     * is what lets libwebrtc lip-sync the two tracks on the remote peer.
     */
    override fun addLocalAudioTrack(track: PlatformAudioTrack) {
        try {
            val nativePc = resolveNativePeerConnection()
            if (nativePc == null) {
                println("WEBRTC_ENGINE: ❌ Native PeerConnection unavailable; cannot add audio track")
                return
            }
            nativePc.addTrack(track.nativeTrack, listOf(LOCAL_MEDIA_STREAM_ID))
            println("WEBRTC_ENGINE: ✅ Local audio track added to peer connection")
        } catch (e: Exception) {
            println("WEBRTC_ENGINE: ❌ Could not add audio track: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun setEncoderConfiguration(config: EncoderConfiguration) {
        val sender = videoSender
        if (sender == null) {
            println("WEBRTC_ENGINE: ⚠️ No video sender available for encoder configuration")
            return
        }

        try {
            val parameters = sender.parameters
            val primaryEncoding = parameters.encodings.firstOrNull()
            if (primaryEncoding == null) {
                println("WEBRTC_ENGINE: ⚠️ No RTP encodings available")
                return
            }

            primaryEncoding.maxBitrateBps = config.maxBitrate.toInt()
            primaryEncoding.maxFramerate = config.maxFramerate
            primaryEncoding.scaleResolutionDownBy = config.scaleResolutionDownBy
            // MAINTAIN_RESOLUTION: when bandwidth drops, reduce framerate (not resolution).
            // Screen-sharing content needs sharp text; even 2-3 fps is usable.
            // MAINTAIN_FRAMERATE causes a death-spiral: encoder can't fit 20fps into
            // very low bandwidth, drops to 0fps, and BWE never recovers.
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION

            val applied = sender.setParameters(parameters)
            if (applied) {
                println(
                    "WEBRTC_ENGINE: ✅ Applied encoder config " +
                        "(${config.maxBitrate / 1000}kbps, ${config.maxFramerate}fps, scale=${config.scaleResolutionDownBy})"
                )
            } else {
                println("WEBRTC_ENGINE: ⚠️ Sender rejected encoder parameter update")
            }
        } catch (e: Exception) {
            println("WEBRTC_ENGINE: ❌ Failed to set encoder config: ${e.message}")
        }
    }

    private fun resolveNativePeerConnection(): org.webrtc.PeerConnection? {
        val cached = nativePeerConnectionField
        if (cached != null) {
            return cached.get(peerConnection) as? org.webrtc.PeerConnection
        }

        val field = peerConnection::class.java.declaredFields.firstOrNull {
            it.type == org.webrtc.PeerConnection::class.java
        } ?: return null

        field.isAccessible = true
        nativePeerConnectionField = field
        return field.get(peerConnection) as? org.webrtc.PeerConnection
    }

    /**
     * Mute/unmute playback of incoming remote audio. We walk the underlying
     * native peer connection's receivers and flip `enabled` on every audio
     * track we find. This is purely local on the host side — the sending
     * client keeps pushing audio, we just stop playing it.
     */
    override fun setRemoteAudioEnabled(enabled: Boolean) {
        remoteAudioPlaybackEnabled = enabled
        if (!enabled) {
            audioPlaybackGuardJob?.cancel()
            audioPlaybackGuardJob = null
        } else {
            ensurePlaybackGuardRunning()
        }
        try {
            val nativePc = resolveNativePeerConnection() ?: run {
                println("WEBRTC_ENGINE: ⚠️ Native PeerConnection unavailable; cannot toggle remote audio")
                return
            }
            var toggled = 0
            nativePc.receivers.forEach { receiver ->
                val track = receiver.track()
                if (track is AudioTrack) {
                    track.setEnabled(enabled)
                    toggled++
                }
            }
            println("WEBRTC_ENGINE: 🔊 setRemoteAudioEnabled=$enabled (tracks=$toggled)")
        } catch (e: Exception) {
            println("WEBRTC_ENGINE: ❌ Failed to toggle remote audio: ${e.message}")
        }
    }

    override fun close() {
        inboundStatsJob?.cancel()
        inboundStatsJob = null
        audioPlaybackGuardJob?.cancel()
        audioPlaybackGuardJob = null
        videoSender = null
        peerConnection.close()
    }

    /**
     * Increase the WebRTC audio jitter buffer from the default (50 packets)
     * to [AUDIO_JITTER_BUFFER_MAX_PACKETS]. A larger buffer absorbs network
     * jitter spikes and bursty audio delivery from the remote client without
     * audible dropouts. The trade-off is a small increase in end-to-end
     * latency (~20-40 ms extra) which is acceptable for screen-sharing audio.
     */
    private fun enlargeAudioJitterBuffer() {
        try {
            val nativePc = resolveNativePeerConnection() ?: run {
                println("WEBRTC_ENGINE: ⚠️ Cannot enlarge jitter buffer; native PC unavailable")
                return
            }
            // Build a fresh RTCConfiguration with the same ICE servers and
            // the enlarged jitter buffer. The native API has no getConfiguration()
            // so we reconstruct it from the ICE servers passed at creation time.
            val config = org.webrtc.PeerConnection.RTCConfiguration(nativeIceServers).apply {
                sdpSemantics = org.webrtc.PeerConnection.SdpSemantics.UNIFIED_PLAN
                audioJitterBufferMaxPackets = AUDIO_JITTER_BUFFER_MAX_PACKETS
                audioJitterBufferFastAccelerate = true
            }
            val result = nativePc.setConfiguration(config)
            if (result) {
                println(
                    "WEBRTC_ENGINE: ✅ Audio jitter buffer enlarged to " +
                        "$AUDIO_JITTER_BUFFER_MAX_PACKETS packets (fastAccelerate=true)"
                )
            } else {
                println("WEBRTC_ENGINE: ⚠️ setConfiguration returned false for jitter buffer update")
            }
        } catch (e: Exception) {
            println("WEBRTC_ENGINE: ⚠️ Failed to enlarge audio jitter buffer: ${e.message}")
        }
    }

    private fun startInboundStatsMonitoring() {
        inboundStatsJob?.cancel()
        inboundStatsJob = scope.launch {
            delay(INBOUND_STATS_INITIAL_DELAY_MS)
            while (isActive) {
                runCatching { collectStatsOnce() }
                    .onFailure { error ->
                        println("WEBRTC_STATS: $peerTag failed to collect stats: ${error.message}")
                    }
                delay(INBOUND_STATS_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun collectStatsOnce() {
        val nativePc = resolveNativePeerConnection() ?: return
        val report = suspendCancellableCoroutine<RTCStatsReport> { cont ->
            nativePc.getStats { statsReport ->
                if (cont.isActive) {
                    cont.resume(statsReport)
                }
            }
        }

        val nowMs = System.currentTimeMillis()
        collectInboundVideoStats(report, nowMs)
        collectInboundAudioStats(report, nowMs)
        collectOutboundVideoStats(report, nowMs)
        collectOutboundAudioStats(report, nowMs)
    }

    // ── Inbound Video (RX) ──────────────────────────────────────────────

    private fun collectInboundVideoStats(report: RTCStatsReport, nowMs: Long) {
        val stats = parseInboundVideoStats(report)
        if (stats.framesReceived <= 0L && stats.framesDecoded <= 0L) return

        val previous = lastInboundSnapshot
        val receivedFps = previous?.let { s ->
            val elapsed = (nowMs - s.timestampMs).coerceAtLeast(1L) / 1000.0
            (stats.framesReceived - s.framesReceived).coerceAtLeast(0L) / elapsed
        } ?: 0.0
        val decodedFps = previous?.let { s ->
            val elapsed = (nowMs - s.timestampMs).coerceAtLeast(1L) / 1000.0
            (stats.framesDecoded - s.framesDecoded).coerceAtLeast(0L) / elapsed
        } ?: 0.0

        if (!hasLoggedInboundStats || previous != null) {
            println(
                "WEBRTC_RX_VIDEO: $peerTag " +
                    "framesReceived=${stats.framesReceived} " +
                    "framesDecoded=${stats.framesDecoded} " +
                    "framesDropped=${stats.framesDropped} " +
                    "receiveFps=${"%.2f".format(receivedFps)} " +
                    "decodeFps=${"%.2f".format(decodedFps)} " +
                    "framesPerSecond=${"%.2f".format(stats.framesPerSecond)}"
            )
            hasLoggedInboundStats = true
        }

        lastInboundSnapshot = InboundVideoSnapshot(
            timestampMs = nowMs,
            framesReceived = stats.framesReceived,
            framesDecoded = stats.framesDecoded,
        )
    }

    // ── Inbound Audio (RX) ──────────────────────────────────────────────

    private fun collectInboundAudioStats(report: RTCStatsReport, nowMs: Long) {
        val stats = parseInboundAudioStats(report)
        if (stats.packetsReceived <= 0L && stats.bytesReceived <= 0L) return

        val previous = lastInboundAudioSnapshot
        val elapsedSec = previous?.let { (nowMs - it.timestampMs).coerceAtLeast(1L) / 1000.0 } ?: 0.0
        val packetRate = if (previous != null && elapsedSec > 0) {
            (stats.packetsReceived - previous.packetsReceived).coerceAtLeast(0L) / elapsedSec
        } else 0.0
        val bitrateKbps = if (previous != null && elapsedSec > 0) {
            (stats.bytesReceived - previous.bytesReceived).coerceAtLeast(0L) * 8.0 / elapsedSec / 1000.0
        } else 0.0

        println(
            "WEBRTC_RX_AUDIO: $peerTag " +
                "packetsReceived=${stats.packetsReceived} " +
                "bytesReceived=${stats.bytesReceived} " +
                "packetsLost=${stats.packetsLost} " +
                "packetRate=${"%.1f".format(packetRate)}/s " +
                "bitrate=${"%.1f".format(bitrateKbps)}kbps " +
                "audioLevel=${"%.4f".format(stats.audioLevel)} " +
                "jitter=${"%.4f".format(stats.jitter)} " +
                "playbackEnabled=$remoteAudioPlaybackEnabled"
        )

        // W71B firmware intermittently forces STREAM_MUSIC to 0/mute while
        // playback is active. Keep restoring a sane level so remote audio
        // remains audible on host.
        ensurePlaybackGuardRunning()
        ensureRemotePlaybackAudible(nowMs, reason = "stats")

        lastInboundAudioSnapshot = InboundAudioSnapshot(
            timestampMs = nowMs,
            packetsReceived = stats.packetsReceived,
            bytesReceived = stats.bytesReceived,
        )
    }

    // ── Outbound Video (TX) ─────────────────────────────────────────────

    private fun collectOutboundVideoStats(report: RTCStatsReport, nowMs: Long) {
        val stats = parseOutboundVideoStats(report)
        if (stats.packetsSent <= 0L && stats.framesSent <= 0L) return

        val previous = lastOutboundVideoSnapshot
        val elapsedSec = previous?.let { (nowMs - it.timestampMs).coerceAtLeast(1L) / 1000.0 } ?: 0.0
        val sendFps = if (previous != null && elapsedSec > 0) {
            (stats.framesSent - previous.framesSent).coerceAtLeast(0L) / elapsedSec
        } else 0.0
        val bitrateKbps = if (previous != null && elapsedSec > 0) {
            (stats.bytesSent - previous.bytesSent).coerceAtLeast(0L) * 8.0 / elapsedSec / 1000.0
        } else 0.0

        println(
            "WEBRTC_TX_VIDEO: $peerTag " +
                "framesSent=${stats.framesSent} " +
                "packetsSent=${stats.packetsSent} " +
                "bytesSent=${stats.bytesSent} " +
                "sendFps=${"%.2f".format(sendFps)} " +
                "bitrate=${"%.1f".format(bitrateKbps)}kbps " +
                "framesPerSecond=${"%.2f".format(stats.framesPerSecond)}"
        )

        // Detect video stall: if zero new frames for consecutive polls, re-apply
        // encoder config to kick-start BWE probing out of the death-spiral.
        if (previous != null && sendFps < 0.5) {
            consecutiveVideoStallPolls++
            if (consecutiveVideoStallPolls >= VIDEO_STALL_RECOVERY_POLLS) {
                consecutiveVideoStallPolls = 0
                println("WEBRTC_TX_VIDEO: $peerTag ⚠️ Video stalled for ${VIDEO_STALL_RECOVERY_POLLS} polls; re-applying encoder config")
                scope.launch { setEncoderConfiguration(ANDROID_INITIAL_ENCODER_CONFIG) }
            }
        } else {
            consecutiveVideoStallPolls = 0
        }

        lastOutboundVideoSnapshot = OutboundVideoSnapshot(
            timestampMs = nowMs,
            framesSent = stats.framesSent,
            bytesSent = stats.bytesSent,
        )
    }

    // ── Outbound Audio (TX) ─────────────────────────────────────────────

    private fun collectOutboundAudioStats(report: RTCStatsReport, nowMs: Long) {
        val stats = parseOutboundAudioStats(report)
        if (stats.packetsSent <= 0L && stats.bytesSent <= 0L) return

        val previous = lastOutboundAudioSnapshot
        val elapsedSec = previous?.let { (nowMs - it.timestampMs).coerceAtLeast(1L) / 1000.0 } ?: 0.0
        val packetRate = if (previous != null && elapsedSec > 0) {
            (stats.packetsSent - previous.packetsSent).coerceAtLeast(0L) / elapsedSec
        } else 0.0
        val bitrateKbps = if (previous != null && elapsedSec > 0) {
            (stats.bytesSent - previous.bytesSent).coerceAtLeast(0L) * 8.0 / elapsedSec / 1000.0
        } else 0.0

        println(
            "WEBRTC_TX_AUDIO: $peerTag " +
                "packetsSent=${stats.packetsSent} " +
                "bytesSent=${stats.bytesSent} " +
                "packetRate=${"%.1f".format(packetRate)}/s " +
                "bitrate=${"%.1f".format(bitrateKbps)}kbps"
        )

        lastOutboundAudioSnapshot = OutboundAudioSnapshot(
            timestampMs = nowMs,
            packetsSent = stats.packetsSent,
            bytesSent = stats.bytesSent,
        )
    }

    // ── Stats parsers ───────────────────────────────────────────────────

    private fun parseInboundVideoStats(report: RTCStatsReport): InboundVideoStats {
        var framesReceived = 0L; var framesDecoded = 0L; var framesDropped = 0L; var framesPerSecond = 0.0
        report.statsMap.values.forEach { stat ->
            if (stat.type != "inbound-rtp" || !isKind(stat, "video")) return@forEach
            framesReceived += stat.memberAsLong("framesReceived")
            framesDecoded += stat.memberAsLong("framesDecoded")
            framesDropped += stat.memberAsLong("framesDropped")
            framesPerSecond += stat.memberAsDouble("framesPerSecond").coerceAtLeast(0.0)
        }
        return InboundVideoStats(framesReceived, framesDecoded, framesDropped, framesPerSecond)
    }

    private fun parseInboundAudioStats(report: RTCStatsReport): InboundAudioStats {
        var packetsReceived = 0L; var bytesReceived = 0L; var packetsLost = 0L
        var audioLevel = 0.0; var jitter = 0.0
        report.statsMap.values.forEach { stat ->
            if (stat.type != "inbound-rtp" || !isKind(stat, "audio")) return@forEach
            packetsReceived += stat.memberAsLong("packetsReceived")
            bytesReceived += stat.memberAsLong("bytesReceived")
            packetsLost += stat.memberAsLong("packetsLost")
            audioLevel = stat.memberAsDouble("audioLevel").coerceAtLeast(audioLevel)
            jitter = stat.memberAsDouble("jitter").coerceAtLeast(jitter)
        }
        return InboundAudioStats(packetsReceived, bytesReceived, packetsLost, audioLevel, jitter)
    }

    private fun parseOutboundVideoStats(report: RTCStatsReport): OutboundVideoStats {
        var framesSent = 0L; var packetsSent = 0L; var bytesSent = 0L; var framesPerSecond = 0.0
        report.statsMap.values.forEach { stat ->
            if (stat.type != "outbound-rtp" || !isKind(stat, "video")) return@forEach
            framesSent += stat.memberAsLong("framesSent")
            packetsSent += stat.memberAsLong("packetsSent")
            bytesSent += stat.memberAsLong("bytesSent")
            framesPerSecond += stat.memberAsDouble("framesPerSecond").coerceAtLeast(0.0)
        }
        return OutboundVideoStats(framesSent, packetsSent, bytesSent, framesPerSecond)
    }

    private fun parseOutboundAudioStats(report: RTCStatsReport): OutboundAudioStats {
        var packetsSent = 0L; var bytesSent = 0L
        report.statsMap.values.forEach { stat ->
            if (stat.type != "outbound-rtp" || !isKind(stat, "audio")) return@forEach
            packetsSent += stat.memberAsLong("packetsSent")
            bytesSent += stat.memberAsLong("bytesSent")
        }
        return OutboundAudioStats(packetsSent, bytesSent)
    }

    private fun isKind(stat: RTCStats, expected: String): Boolean {
        val kind = stat.members["kind"]?.toString()
            ?: stat.members["mediaType"]?.toString()
        return kind == expected
    }

    private fun RTCStats.memberAsLong(key: String): Long {
        return members[key]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
    }

    private fun RTCStats.memberAsDouble(key: String): Double {
        return members[key]?.toString()?.toDoubleOrNull() ?: 0.0
    }

    private fun ensurePlaybackGuardRunning() {
        if (!remoteAudioPlaybackEnabled) return
        if (audioPlaybackGuardJob?.isActive == true) return

        audioPlaybackGuardJob = scope.launch {
            while (isActive && remoteAudioPlaybackEnabled) {
                ensureRemotePlaybackAudible(System.currentTimeMillis(), reason = "guard")
                delay(REMOTE_AUDIO_GUARD_INTERVAL_MS)
            }
        }
    }

    private fun ensureRemotePlaybackAudible(nowMs: Long, reason: String) {
        if (!remoteAudioPlaybackEnabled) return

        val context = AndroidContextHolder.applicationContext ?: return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val stream = AudioManager.STREAM_MUSIC

        val maxVolume = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(stream).coerceAtLeast(0)
        val isMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.isStreamMute(stream)
        } else {
            currentVolume <= 0
        }

        if (!isMuted && currentVolume > 0) {
            preferredMusicVolume = currentVolume
            return
        }

        val restoreTarget = (preferredMusicVolume
            ?: (maxVolume * REMOTE_AUDIO_MIN_VOLUME_RATIO).toInt().coerceAtLeast(1))
            .coerceIn(1, maxVolume)

        runCatching {
            if (isMuted) {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (currentVolume <= 0) {
                audioManager.setStreamVolume(stream, restoreTarget, 0)
            }
        }.onSuccess {
            if (nowMs - lastVolumeRecoveryLogAtMs >= VOLUME_RECOVERY_LOG_INTERVAL_MS) {
                println(
                    "WEBRTC_ENGINE: 🔊 Restored STREAM_MUSIC volume " +
                        "(reason=$reason, muted=$isMuted, from=$currentVolume to=$restoreTarget)"
                )
                lastVolumeRecoveryLogAtMs = nowMs
            }
        }.onFailure { error ->
            if (nowMs - lastVolumeRecoveryLogAtMs >= VOLUME_RECOVERY_LOG_INTERVAL_MS) {
                println("WEBRTC_ENGINE: ⚠️ Failed to restore STREAM_MUSIC volume: ${error.message}")
                lastVolumeRecoveryLogAtMs = nowMs
            }
        }
    }
}

private data class InboundVideoStats(
    val framesReceived: Long = 0L,
    val framesDecoded: Long = 0L,
    val framesDropped: Long = 0L,
    val framesPerSecond: Double = 0.0,
)

private data class InboundVideoSnapshot(
    val timestampMs: Long,
    val framesReceived: Long,
    val framesDecoded: Long,
)

private data class InboundAudioStats(
    val packetsReceived: Long = 0L,
    val bytesReceived: Long = 0L,
    val packetsLost: Long = 0L,
    val audioLevel: Double = 0.0,
    val jitter: Double = 0.0,
)

private data class InboundAudioSnapshot(
    val timestampMs: Long,
    val packetsReceived: Long,
    val bytesReceived: Long,
)

private data class OutboundVideoStats(
    val framesSent: Long = 0L,
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
    val framesPerSecond: Double = 0.0,
)

private data class OutboundVideoSnapshot(
    val timestampMs: Long,
    val framesSent: Long,
    val bytesSent: Long,
)

private data class OutboundAudioStats(
    val packetsSent: Long = 0L,
    val bytesSent: Long = 0L,
)

private data class OutboundAudioSnapshot(
    val timestampMs: Long,
    val packetsSent: Long,
    val bytesSent: Long,
)

private data class AndroidCaptureProfile(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/** Negotiated data-channel id shared between client and host for remote-control input. */
private const val NEGOTIATED_DATA_CHANNEL_ID = 100

/** Shared media-stream id for local video + audio tracks so the remote peer keeps them in sync. */
private const val LOCAL_MEDIA_STREAM_ID = "stream"
private const val ROTATION_POLL_INTERVAL_MS = 1_000L
private const val CROP_POLL_INTERVAL_NS = 500_000_000L
private const val ANDROID_MAX_CAPTURE_EDGE = 1280
private const val ANDROID_MAX_CAPTURE_PIXELS = 921_600 // 1280x720 equivalent budget
private const val ANDROID_DIM_ALIGNMENT = 2
private const val ANDROID_MIN_CAPTURE_DENSITY_DPI = 120
private const val ORIENTATION_RESIZE_MIN_AREA_RATIO = 0.8
private const val CAPTURE_STALL_WATCHDOG_POLL_MS = 1_500L
private const val CAPTURE_STALL_THRESHOLD_MS = 2_500L
private const val CAPTURE_STALL_MAX_NUDGE_ATTEMPTS = 2
private const val CAPTURE_STALL_RESTART_COOLDOWN_MS = 20_000L
// Content-staleness watchdog: detects frozen SurfaceTexture buffer timestamps
// even when frame callbacks keep firing (the OEM-screenshot freeze on
// Samsung-derived Android 14+ where MediaProjection.Callback.onStop never
// fires). Two-tier: cheap kick first, full restart if kick doesn't unfreeze.
// 1500ms kick threshold is just above the watchdog poll, so we react on the
// first poll tick after content freezes; 4000ms restart threshold gives the
// kick two cycles to take effect before we tear down. Must be larger than
// the longest expected genuine idle-display interval to avoid false positives
// on a static slide — 4s is empirically safe on typical lecture content.
private const val CONTENT_STALENESS_KICK_THRESHOLD_MS = 1_500L
private const val CONTENT_STALENESS_KICK_COOLDOWN_MS = 2_500L
private const val CONTENT_STALENESS_RESTART_THRESHOLD_MS = 4_000L
private const val CONTENT_STALENESS_RESTART_COOLDOWN_MS = 20_000L
// Debounce repeated MediaStore notifications — the system can fire several
// onChange events for a single screenshot/recording (file-creation,
// indexing, thumbnail generation). 2s is wide enough to coalesce those
// without missing back-to-back genuine captures.
private const val MEDIASTORE_NOTIFY_DEBOUNCE_MS = 2_000L
private const val CAPTURE_REFRESH_VALIDATION_DELAY_MS = 2_500L
private const val PROJECTION_EVENT_KICK_DEBOUNCE_MS = 220L
private const val PROJECTION_EVENT_KICK_COOLDOWN_MS = 3_000L
private const val PROJECTION_CALLBACK_SUPPRESSION_MS = 1_500L
private const val SCREEN_CAPTURE_EVENT_KICK_COOLDOWN_MS = 2_500L
private const val INBOUND_STATS_INITIAL_DELAY_MS = 1_500L
private const val INBOUND_STATS_POLL_INTERVAL_MS = 4_000L
/** Re-apply encoder config after this many consecutive 0-fps polls (~3 polls × 4s = 12s stall). */
private const val VIDEO_STALL_RECOVERY_POLLS = 3
private const val REMOTE_AUDIO_GUARD_INTERVAL_MS = 1_000L
private const val REMOTE_AUDIO_MIN_VOLUME_RATIO = 0.2
/** Larger jitter buffer absorbs network jitter + bursty client audio delivery.
 *  Default is 50 packets; 200 gives ~200 ms headroom at the cost of slight latency. */
private const val AUDIO_JITTER_BUFFER_MAX_PACKETS = 200
private const val VOLUME_RECOVERY_LOG_INTERVAL_MS = 3_000L
private val STARTUP_FRAME_NUDGE_DELAYS_MS = longArrayOf(80L, 180L, 320L, 500L, 750L)
private val ANDROID_INITIAL_ENCODER_CONFIG = EncoderConfiguration(
    maxBitrate = 2_500_000,
    maxFramerate = 20,
    scaleResolutionDownBy = 1.0,
)
@Volatile
private var cachedScreenCapturePermission: android.content.Intent? = null

private fun SessionDescription.toData(): SessionDescriptionData =
    SessionDescriptionData(
        type = when (type) {
            SessionDescriptionType.Offer -> SdpType.Offer
            SessionDescriptionType.Answer -> SdpType.Answer
            else -> SdpType.Offer
        },
        sdp = sdp,
    )

private fun SessionDescriptionData.toNative(): SessionDescription =
    SessionDescription(
        type = when (type) {
            SdpType.Offer -> SessionDescriptionType.Offer
            SdpType.Answer -> SessionDescriptionType.Answer
        },
        sdp = sdp,
    )

private fun com.shepeliev.webrtckmp.IceCandidate.toData(): IceCandidateData =
    IceCandidateData(sdpMid = sdpMid, sdpMLineIndex = sdpMLineIndex, candidate = candidate)

private fun IceCandidateData.toNative(): com.shepeliev.webrtckmp.IceCandidate =
    com.shepeliev.webrtckmp.IceCandidate(sdpMid = sdpMid, sdpMLineIndex = sdpMLineIndex, candidate = candidate)

private fun org.webrtc.SessionDescription.toData(): SessionDescriptionData =
    SessionDescriptionData(
        type = when (type) {
            org.webrtc.SessionDescription.Type.ANSWER -> SdpType.Answer
            else -> SdpType.Offer
        },
        sdp = description,
    )

private fun SessionDescriptionData.toNativeSdp(): org.webrtc.SessionDescription =
    org.webrtc.SessionDescription(
        when (type) {
            SdpType.Offer -> org.webrtc.SessionDescription.Type.OFFER
            SdpType.Answer -> org.webrtc.SessionDescription.Type.ANSWER
        },
        sdp,
    )

/**
 * BYOM peer connection built directly on org.webrtc from the dedicated BYOM
 * factory (plain microphone ADM). Audio capture in libwebrtc is driven by the
 * AudioDeviceModule of the factory that OWNS the peer connection, so BYOM
 * sessions cannot reuse the webrtc-kmp factory — its ADM substitutes system
 * playback (or silence) for mic samples. The host only sends camera + mic on
 * BYOM connections; remote tracks and data channels are intentionally unused.
 */
private class NativeByomPeerConnection(
    factory: PeerConnectionFactory,
    rtcConfig: org.webrtc.PeerConnection.RTCConfiguration,
) : WebRtcPeerConnection {

    private val _iceCandidates = MutableSharedFlow<IceCandidateData>(extraBufferCapacity = 64)
    override val iceCandidates: Flow<IceCandidateData> = _iceCandidates.asSharedFlow()
    override val remoteVideoTracks: Flow<PlatformVideoTrack> = MutableSharedFlow()

    private val pc: org.webrtc.PeerConnection = factory.createPeerConnection(
        rtcConfig,
        object : org.webrtc.PeerConnection.Observer {
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
                candidate ?: return
                _iceCandidates.tryEmit(
                    IceCandidateData(
                        sdpMid = candidate.sdpMid.orEmpty(),
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp,
                    )
                )
            }

            override fun onIceConnectionChange(state: org.webrtc.PeerConnection.IceConnectionState?) {
                println("BYOM_PC: 🔌 ICE connection state: $state")
            }

            override fun onConnectionChange(newState: org.webrtc.PeerConnection.PeerConnectionState?) {
                println("BYOM_PC: 🔗 Connection state: $newState")
            }

            override fun onSignalingChange(state: org.webrtc.PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: org.webrtc.PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver?,
                streams: Array<out org.webrtc.MediaStream>?,
            ) {}
        },
    ) ?: error("Failed to create BYOM peer connection")

    override suspend fun createOffer(): SessionDescriptionData =
        awaitCreate { observer -> pc.createOffer(observer, MediaConstraints()) }

    override suspend fun createAnswer(): SessionDescriptionData =
        awaitCreate { observer -> pc.createAnswer(observer, MediaConstraints()) }

    override suspend fun setLocalDescription(description: SessionDescriptionData) =
        awaitSet { observer -> pc.setLocalDescription(observer, description.toNativeSdp()) }

    override suspend fun setRemoteDescription(description: SessionDescriptionData) =
        awaitSet { observer -> pc.setRemoteDescription(observer, description.toNativeSdp()) }

    override suspend fun addIceCandidate(candidate: IceCandidateData) {
        pc.addIceCandidate(
            org.webrtc.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        )
    }

    override fun addLocalVideoTrack(track: PlatformVideoTrack) {
        val nativeTrack = track.nativeTrack
        if (nativeTrack == null) {
            println("BYOM_PC: ❌ Only native tracks can be added to a BYOM peer connection")
            return
        }
        val sender = pc.addTrack(nativeTrack, listOf(BYOM_MEDIA_STREAM_ID))
        println("BYOM_PC: ✅ Camera track added")
        // Without explicit sender params libwebrtc's bandwidth estimator freely
        // downscales the camera (320x180 observed in the field). A webcam feed
        // must hold its resolution — degrade frame rate instead — and gets an
        // explicit bitrate ceiling so it can climb back to 720p quality.
        runCatching {
            val params = sender.parameters
            params.degradationPreference =
                org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = BYOM_VIDEO_MAX_BITRATE_BPS
                encoding.scaleResolutionDownBy = 1.0
            }
            sender.setParameters(params)
            println(
                "BYOM_PC: ✅ Encoder configured — maintain-resolution, " +
                    "max ${BYOM_VIDEO_MAX_BITRATE_BPS / 1000} kbps"
            )
        }.onFailure {
            println("BYOM_PC: ⚠️ Failed to set BYOM encoder params: ${it.message}")
        }
    }

    override fun addLocalAudioTrack(track: PlatformAudioTrack) {
        pc.addTrack(track.nativeTrack, listOf(BYOM_MEDIA_STREAM_ID))
        println("BYOM_PC: ✅ Microphone track added")
    }

    override fun close() {
        runCatching { pc.dispose() }
    }

    private suspend fun awaitCreate(
        block: (org.webrtc.SdpObserver) -> Unit,
    ): SessionDescriptionData = suspendCancellableCoroutine { cont ->
        block(object : ByomSdpObserver() {
            override fun onCreateSuccess(desc: org.webrtc.SessionDescription?) {
                if (desc == null) {
                    cont.resumeWithException(IllegalStateException("BYOM SDP create returned null"))
                } else {
                    cont.resume(desc.toData())
                }
            }

            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(IllegalStateException("BYOM SDP create failed: $error"))
            }
        })
    }

    private suspend fun awaitSet(block: (org.webrtc.SdpObserver) -> Unit) {
        suspendCancellableCoroutine<Unit> { cont ->
            block(object : ByomSdpObserver() {
                override fun onSetSuccess() {
                    cont.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException("BYOM SDP set failed: $error"))
                }
            })
        }
    }

    companion object {
        private const val BYOM_MEDIA_STREAM_ID = "byom_stream"

        // Cap for the BYOM camera stream (1280x720@30 on a LAN). High enough
        // for crisp 720p H.264/VP8, low enough not to starve screen-share.
        private const val BYOM_VIDEO_MAX_BITRATE_BPS = 2_500_000
    }
}

private open class ByomSdpObserver : org.webrtc.SdpObserver {
    override fun onCreateSuccess(desc: org.webrtc.SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

/**
 * WebRTC data channel wrapper for Android using the native org.webrtc.DataChannel.
 */
private class AndroidDataChannel(
    private val nativeChannel: DataChannel,
) : WebRtcDataChannel {
    override val label: String = nativeChannel.label()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    init {
        nativeChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                println("DATA_CHANNEL ($label): state=${nativeChannel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                _incoming.tryEmit(bytes)
            }
        })
    }

    override fun send(data: ByteArray) {
        if (nativeChannel.state() != DataChannel.State.OPEN) return
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
        nativeChannel.send(buffer)
    }

    override fun close() {
        runCatching { nativeChannel.close() }
        runCatching { nativeChannel.dispose() }
    }
}

/**
 * Shares the currently active [MediaProjection] between the screen capturer
 * (video) and the system-audio capturer (playback capture). Both need the
 * same token, and both need to know when the user revokes it.
 */
internal object ScreenMediaProjectionHolder {
    @Volatile private var projection: MediaProjection? = null

    fun set(p: MediaProjection?) {
        projection = p
    }

    fun current(): MediaProjection? = projection

    /** Clear only if the stored projection matches the caller's reference. */
    fun clear(p: MediaProjection?) {
        if (projection === p) projection = null
    }
}

/**
 * Custom screen capturer that uses MediaProjection to capture at full device resolution.
 * This bypasses webrtc-kmp's MediaDevices.getDisplayMedia() which captures at low resolution.
 */
private class ScreenCapturer(
    private val context: Context,
    private val mediaProjectionIntent: android.content.Intent,
    private val width: Int,
    private val height: Int,
    private val initialDensityDpi: Int,
    private val onDisplayRotationChanged: (Int) -> Unit = {},
    private val onCaptureRestartRequested: () -> Unit = {},
    private val onKeyFrameRequested: () -> Unit = {},
) : VideoCapturer {
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var surface: android.view.Surface? = null
    private var captureStopped = false
    private var captureWidthPx: Int = width
    private var captureHeightPx: Int = height
    private var captureDensityDpi: Int = initialDensityDpi
    private var displayListener: DisplayManager.DisplayListener? = null
    private var continuousRotationPollRunnable: Runnable? = null
    @Volatile
    private var hasCapturedFirstFrame: Boolean = false
    @Volatile
    private var lastFrameCapturedAtNs: Long = 0L
    private var frameStallWatchdogRunnable: Runnable? = null
    private var frameStallNudgeAttempts: Int = 0
    private var frameStallRestartCooldownUntilMs: Long = 0L
    private var hasLoggedFrameStallRecovery: Boolean = false
    private var lastObservedFrameTimestampNs: Long = 0L
    private var captureSessionStartedAtMs: Long = 0L
    private var lastSeenScreenCaptureEventVersion: Long = HostScreenCaptureSignal.currentVersion()
    private var projectionEventKickRunnable: Runnable? = null
    private var pendingProjectionEventKickReason: String? = null
    private var projectionEventKickCooldownUntilMs: Long = 0L
    private var suppressProjectionCallbacksUntilMs: Long = 0L
    private var screenCaptureEventKickCooldownUntilMs: Long = 0L
    private var refreshValidationDeadlineMs: Long = 0L
    private var refreshValidationBaselineTimestampNs: Long = 0L
    private var refreshValidationReason: String? = null
    private var refreshValidationRestartCooldownUntilMs: Long = 0L
    // MediaStore observers — fire when another app saves a screenshot or
    // screen-recording on the host. On the Samsung-derived Teachmint X
    // (com.skg.screenshot) the standard Activity.ScreenCaptureCallback and
    // MediaProjection.Callback paths stay silent during a screenshot, so this
    // is the only reliable in-app signal that a screen-capture event
    // happened. ContentObserver registration itself doesn't need media-read
    // permission. Screenshots fire within ~1s of the user pressing the
    // shutter; screen recordings fire when the file is saved at recording
    // end (so live-during-recording recovery is not possible without a
    // notification listener — out of scope for this fix).
    private var screenshotMediaObserver: android.database.ContentObserver? = null
    private var screenRecordingMediaObserver: android.database.ContentObserver? = null
    private var screenshotFileObserver: android.os.FileObserver? = null
    private var skgScreenshotReceiver: android.content.BroadcastReceiver? = null
    private var skgScreenCaptureReceiver: android.content.BroadcastReceiver? = null
    private var lastMediaStoreNotifyMs: Long = 0L
    // Content-staleness watchdog state. When the SurfaceTexture buffer
    // timestamp stops advancing while frame callbacks keep firing, the
    // capture pipeline is producing duplicate frames (e.g. OEM screenshot
    // overlay on Samsung-derived Android 14+ silently freezes the mirror
    // without firing onStop / onCapturedContentVisibilityChanged). The
    // existing wallclock-based stall watchdog can't catch this case.
    private var contentStalenessBaselineFrameTsNs: Long = 0L
    private var contentStalenessBaselineMs: Long = 0L
    private var contentStalenessKickCooldownUntilMs: Long = 0L
    private var contentStalenessRestartCooldownUntilMs: Long = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingResizeChecks = mutableListOf<Runnable>()
    private val pendingStartupFrameNudges = mutableListOf<Runnable>()

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper,
        context: Context,
        capturerObserver: CapturerObserver
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        println("SCREEN_CAPTURER: Initialized")
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        captureStopped = false
        hasCapturedFirstFrame = false
        lastFrameCapturedAtNs = 0L
        frameStallNudgeAttempts = 0
        frameStallRestartCooldownUntilMs = 0L
        hasLoggedFrameStallRecovery = false
        lastObservedFrameTimestampNs = 0L
        contentStalenessBaselineFrameTsNs = 0L
        contentStalenessBaselineMs = 0L
        contentStalenessKickCooldownUntilMs = 0L
        contentStalenessRestartCooldownUntilMs = 0L
        lastMediaStoreNotifyMs = 0L
        captureSessionStartedAtMs = android.os.SystemClock.elapsedRealtime()
        lastSeenScreenCaptureEventVersion = HostScreenCaptureSignal.currentVersion()
        // Watch MediaStore for screenshots / screen-recordings on the host —
        // the most reliable in-app signal for "another app captured the
        // screen" on the Samsung-derived OEM where Activity.ScreenCaptureCallback
        // doesn't fire.
        registerMediaStoreScreenCaptureObservers()
        registerScreenshotFileObserver()
        registerSkgScreenshotReceiver()
        registerSkgScreenCaptureReceiver()
        projectionEventKickCooldownUntilMs = 0L
        suppressProjectionCallbacksUntilMs = 0L
        screenCaptureEventKickCooldownUntilMs = 0L
        refreshValidationDeadlineMs = 0L
        refreshValidationBaselineTimestampNs = 0L
        refreshValidationReason = null
        refreshValidationRestartCooldownUntilMs = 0L
        clearPendingProjectionEventKick()
        captureWidthPx = width
        captureHeightPx = height
        captureDensityDpi = initialDensityDpi
        println(
            "SCREEN_CAPTURER: Starting capture requested=${width}x${height}, " +
                "initial=${captureWidthPx}x${captureHeightPx}@${captureDensityDpi}dpi @ ${framerate}fps"
        )

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(
            android.app.Activity.RESULT_OK,
            mediaProjectionIntent
        )
        // Publish the live MediaProjection so the audio pipeline can attach
        // an AudioPlaybackCaptureConfiguration to the same session.
        ScreenMediaProjectionHolder.set(mediaProjection)

        if (mediaProjection == null) {
            println("SCREEN_CAPTURER: ❌ Failed to create MediaProjection")
            capturerObserver?.onCapturerStarted(false)
            return
        }

        // Register callback for MediaProjection (required for Android 14+).
        // We listen for visibility/resize transitions and schedule a debounced
        // lightweight surface kick (setSurface(null) → resize → setSurface(s)),
        // with cooldown/suppression to prevent callback feedback loops.
        //
        // Known limitation: smaller overlay events (like the screenshot
        // animation) typically don't fire onCapturedContentVisibilityChanged
        // — that callback only triggers when all visible windows on the
        // captured display change state. So a screenshot can leave the
        // mirror in a stuck state with no callback to react to. A separate
        // frame-stall watchdog handles that path and escalates to full restart
        // when lightweight nudges fail.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // captureStopped is set true at the top of stopCaptureInternal(), so
                // when this callback fires as a result of our own projection.stop()
                // the flag is already true. If it is still false here, the platform
                // revoked the token externally (e.g. another app took screen capture
                // permission, the system reclaimed it for a screenshot/recorder, etc.)
                // — in that case we must escalate to a full restart so reverse
                // clients are notified and the pipeline is rebuilt with fresh consent.
                val wasExternallyRevoked = !captureStopped
                println(
                    "SCREEN_CAPTURER: MediaProjection stopped " +
                        "(externally_revoked=$wasExternallyRevoked)"
                )
                stopCaptureInternal(requestProjectionStop = false)
                if (wasExternallyRevoked) {
                    println(
                        "SCREEN_CAPTURER: 📸 onStop() externally triggered; " +
                            "requesting full capture restart"
                    )
                    onCaptureRestartRequested()
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                super.onCapturedContentVisibilityChanged(isVisible)
                println("SCREEN_CAPTURER: onCapturedContentVisibilityChanged isVisible=$isVisible")
                if (isProjectionCallbacksSuppressed()) {
                    println("SCREEN_CAPTURER: skipping visibility callback kick (suppressed)")
                    return
                }
                // Kick on both transitions:
                //  isVisible=true  → screenshot overlay appeared (capturer sees overlay content)
                //  isVisible=false → overlay dismissed, live content resumed
                // The 3-second projection-event cooldown prevents rapid double-kicks.
                scheduleProjectionEventKick("visibility=$isVisible")
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                super.onCapturedContentResize(width, height)
                println("SCREEN_CAPTURER: onCapturedContentResize ${width}x${height}")
                if (isProjectionCallbacksSuppressed()) {
                    println("SCREEN_CAPTURER: skipping resize callback kick (suppressed)")
                    return
                }
                scheduleProjectionEventKick("resize ${width}x${height}")
            }
        }
        mediaProjectionCallback = callback
        mediaProjection?.registerCallback(callback, mainHandler)
        println("SCREEN_CAPTURER: MediaProjection callback registered")

        // Create a surface from the SurfaceTextureHelper
        val surfaceTexture = surfaceTextureHelper?.surfaceTexture
        if (surfaceTexture == null) {
            println("SCREEN_CAPTURER: ❌ SurfaceTexture is null")
            capturerObserver?.onCapturerStarted(false)
            return
        }

        // Set texture size on SurfaceTextureHelper FIRST (required for frame capture)
        surfaceTextureHelper?.setTextureSize(captureWidthPx, captureHeightPx)
        println(
            "SCREEN_CAPTURER: SurfaceTextureHelper texture size set to " +
                "${captureWidthPx}x${captureHeightPx}"
        )

        surfaceTexture.setDefaultBufferSize(captureWidthPx, captureHeightPx)
        surface = android.view.Surface(surfaceTexture)
        println("SCREEN_CAPTURER: Surface created, isValid=${surface?.isValid}")

        // Set up SurfaceTextureHelper listener BEFORE creating VirtualDisplay
        // This ensures the listener is ready to receive frames as soon as VirtualDisplay starts rendering
        println("SCREEN_CAPTURER: Setting up SurfaceTextureHelper listener...")
        var frameCounter = 0
        surfaceTextureHelper?.startListening { frame ->
            frameCounter++
            // Re-evaluate the crop region periodically (throttled). On Samsung
            // tablets where the host activity is portrait-locked, the
            // DisplayManager.DisplayListener.onDisplayChanged callback often
            // does not fire when the underlying physical display rotates, so we
            // poll the display dimensions directly from the capture thread.
            val nowNs = System.nanoTime()
            if (nowNs - lastCropPollNs > CROP_POLL_INTERVAL_NS) {
                lastCropPollNs = nowNs
                runCatching { updateCropRegion() }
            }
            val region = cropRegion
            val outFrame: org.webrtc.VideoFrame
            val ownsOutFrame: Boolean
            if (region != null &&
                (region.width != frame.buffer.width || region.height != frame.buffer.height)
            ) {
                val croppedBuffer = frame.buffer.cropAndScale(
                    region.x,
                    region.y,
                    region.width,
                    region.height,
                    region.width,
                    region.height,
                )
                outFrame = org.webrtc.VideoFrame(croppedBuffer, frame.rotation, frame.timestampNs)
                ownsOutFrame = true
            } else {
                outFrame = frame
                ownsOutFrame = false
            }
            try {
                lastFrameCapturedAtNs = android.os.SystemClock.elapsedRealtimeNanos()
                lastObservedFrameTimestampNs = outFrame.timestampNs
                if (frameStallNudgeAttempts > 0 && !hasLoggedFrameStallRecovery) {
                    println("SCREEN_CAPTURER: ✅ Frame flow recovered after stall nudge(s)")
                    hasLoggedFrameStallRecovery = true
                }
                frameStallNudgeAttempts = 0
                if (frameCounter == 1) {
                    hasCapturedFirstFrame = true
                    clearPendingStartupFrameNudges()
                    println(
                        "📱 ANDROID CAPTURER: First frame captured! " +
                            "raw=${frame.buffer.width}x${frame.buffer.height}, " +
                            "out=${outFrame.buffer.width}x${outFrame.buffer.height}, " +
                            "rotation=${outFrame.rotation}"
                    )
                }
                if (frameCounter % 30 == 0) {
                    println(
                        "📱 ANDROID CAPTURER: Frame #$frameCounter, " +
                            "raw=${frame.buffer.width}x${frame.buffer.height}, " +
                            "out=${outFrame.buffer.width}x${outFrame.buffer.height}, " +
                            "rotation=${outFrame.rotation}"
                    )
                }
                capturerObserver?.onFrameCaptured(outFrame)
            } finally {
                if (ownsOutFrame) outFrame.release()
            }
        }
        println("SCREEN_CAPTURER: SurfaceTextureHelper listener registered")

        // Create VirtualDisplay with full resolution
        println("SCREEN_CAPTURER: Creating VirtualDisplay...")
        virtualDisplay = createVirtualDisplay(captureWidthPx, captureHeightPx)

        if (virtualDisplay == null) {
            println("SCREEN_CAPTURER: ❌ Failed to create VirtualDisplay")
            capturerObserver?.onCapturerStarted(false)
            return
        }

        println("SCREEN_CAPTURER: ✅ VirtualDisplay created: display=${virtualDisplay?.display}, surface=${surface?.isValid}")
        println(
            "SCREEN_CAPTURER: ✅ VirtualDisplay resolution: " +
                "${captureWidthPx}x${captureHeightPx}, density: $captureDensityDpi"
        )
        // Intentionally do NOT resize/recreate the VirtualDisplay on orientation changes.
        // The Android compositor rotates the physical display content into this fixed
        // buffer, matching the behaviour on `main` where landscape content is letterboxed
        // inside the original portrait frame. Resizing causes cropping on Samsung One UI
        // (virtual display buffer updates but the projected source region doesn't follow),
        // and recreating the VirtualDisplay invalidates the MediaProjection token on
        // Samsung Android 14+, which freezes capture altogether.
        //
        // Instead we listen for rotation changes and notify the engine so the receiving
        // peer can rotate its tile to show the letterboxed landscape content upright and
        // full-sized.
        registerDisplayListener()
        scheduleStartupFrameNudges()
        scheduleFrameStallWatchdog()
        capturerObserver?.onCapturerStarted(true)
        println("SCREEN_CAPTURER: Capture started successfully")
    }

    override fun stopCapture() {
        println("SCREEN_CAPTURER: Stopping capture")
        stopCaptureInternal(requestProjectionStop = true)
    }

    override fun dispose() {
        println("SCREEN_CAPTURER: Disposing")
        stopCapture()
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    override fun isScreencast(): Boolean = true

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        println("SCREEN_CAPTURER: changeCaptureFormat called: ${width}x${height} @ ${framerate}fps")
        // Restart capture with new format if needed
        stopCapture()
        startCapture(width, height, framerate)
    }

    @Synchronized
    private fun stopCaptureInternal(requestProjectionStop: Boolean) {
        if (captureStopped) return
        captureStopped = true

        unregisterMediaStoreScreenCaptureObservers()
        unregisterScreenshotFileObserver()
        unregisterSkgScreenshotReceiver()
        unregisterSkgScreenCaptureReceiver()
        unregisterDisplayListener()
        lastReportedRotation = -1
        cropRegion = null
        lastCropRegionLogged = null
        lastCropPhysicalSize = null
        lastCropPollNs = 0L
        clearPendingResizeChecks()
        clearPendingStartupFrameNudges()
        clearFrameStallWatchdog()
        clearPendingProjectionEventKick()
        surfaceTextureHelper?.stopListening()

        virtualDisplay?.release()
        virtualDisplay = null

        val projection = mediaProjection
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        ScreenMediaProjectionHolder.clear(projection)

        if (projection != null) {
            runCatching {
                if (callback != null) {
                    projection.unregisterCallback(callback)
                }
            }
            if (requestProjectionStop) {
                runCatching { projection.stop() }
            }
        }

        surface?.release()
        surface = null

        runCatching { capturerObserver?.onCapturerStopped() }
    }

    private var lastReportedRotation: Int = -1

    @Volatile
    private var cropRegion: CropRegion? = null

    @Volatile
    private var lastCropPollNs: Long = 0L

    private data class CropRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || captureStopped) return
                handleDisplayChanged()
            }
        }
        manager.registerDisplayListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        displayListener = listener
        // Compute the initial crop region so we strip letterbox bars from the
        // very first frame, even if capture starts in landscape.
        handleDisplayChanged()
    }

    private fun handleDisplayChanged() {
        updateCropRegion()
        // Rotation signaling is intentionally not emitted: sender-side cropping
        // already yields properly-oriented content at the buffer's native axes,
        // so the receiver can render the tile without any additional rotation.
    }

    private var lastCropRegionLogged: CropRegion? = null
    private var lastCropPhysicalSize: Pair<Int, Int>? = null

    private fun updateCropRegion() {
        val displayInfo = resolveDefaultDisplayInfo(context)
        val physW = displayInfo.widthPx
        val physH = displayInfo.heightPx
        val bufW = captureWidthPx
        val bufH = captureHeightPx
        if (physW <= 0 || physH <= 0 || bufW <= 0 || bufH <= 0) {
            cropRegion = null
            return
        }

        val physAspect = physW.toDouble() / physH.toDouble()
        val bufAspect = bufW.toDouble() / bufH.toDouble()

        val (rawContentW, rawContentH) = if (physAspect >= bufAspect) {
            // Source wider than buffer → content fills buffer width, letterboxed top/bottom
            bufW to (bufW / physAspect).toInt()
        } else {
            // Source taller than buffer → content fills buffer height, pillarboxed left/right
            (bufH * physAspect).toInt() to bufH
        }

        val alignedW = (rawContentW and 1.inv()).coerceIn(2, bufW)
        val alignedH = (rawContentH and 1.inv()).coerceIn(2, bufH)

        // Skip cropping unless the letterbox bars are substantial (> ~2% of the
        // buffer dimension on either axis). This avoids micro-crops from rounding
        // when source and buffer aspect ratios are nearly identical (portrait
        // capturing portrait), where we'd otherwise chop a few pixels off the
        // edges for no visible benefit.
        val widthMarginPx = bufW - alignedW
        val heightMarginPx = bufH - alignedH
        val widthMarginRatio = widthMarginPx.toDouble() / bufW.toDouble()
        val heightMarginRatio = heightMarginPx.toDouble() / bufH.toDouble()
        if (widthMarginRatio < 0.02 && heightMarginRatio < 0.02) {
            if (cropRegion != null) {
                println(
                    "SCREEN_CAPTURER: Crop region cleared (content ~fills buffer): " +
                        "buffer=${bufW}x${bufH} physical=${physW}x${physH}"
                )
            }
            cropRegion = null
            lastCropRegionLogged = null
            lastCropPhysicalSize = physW to physH
            return
        }

        val x = ((bufW - alignedW) / 2) and 1.inv()
        val y = ((bufH - alignedH) / 2) and 1.inv()
        val newRegion = CropRegion(x, y, alignedW, alignedH)
        cropRegion = newRegion
        if (newRegion != lastCropRegionLogged || lastCropPhysicalSize != physW to physH) {
            println(
                "SCREEN_CAPTURER: Crop region updated: buffer=${bufW}x${bufH} " +
                    "physical=${physW}x${physH} crop=(${x},${y} ${alignedW}x${alignedH})"
            )
            lastCropRegionLogged = newRegion
            lastCropPhysicalSize = physW to physH
        }
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        runCatching {
            manager?.unregisterDisplayListener(listener)
        }
        displayListener = null
    }

    private fun maybeResizeCaptureForOrientationChange() {
        val targetProfile = resolveCaptureProfileForCurrentDisplay()
        val captureIsLandscape = captureWidthPx > captureHeightPx
        val targetIsLandscape = targetProfile.width > targetProfile.height
        val orientationChanged = captureIsLandscape != targetIsLandscape
        val stabilizedTargetProfile = if (orientationChanged) {
            val currentArea = captureWidthPx.toLong() * captureHeightPx.toLong()
            val targetArea = targetProfile.width.toLong() * targetProfile.height.toLong()
            val minAllowedArea = (currentArea * ORIENTATION_RESIZE_MIN_AREA_RATIO).toLong()
            if (currentArea > 0L && targetArea in 1 until minAllowedArea) {
                // Some OEMs can report a transient half-sized display window during rotation.
                // Swapping the current capture dimensions keeps full-frame capture stable.
                val fallbackProfile = AndroidCaptureProfile(
                    width = captureHeightPx,
                    height = captureWidthPx,
                    densityDpi = captureDensityDpi,
                )
                println(
                    "SCREEN_CAPTURER: Ignoring suspicious orientation target " +
                        "${targetProfile.width}x${targetProfile.height}@${targetProfile.densityDpi}dpi " +
                        "(areaRatio=${"%.3f".format(targetArea.toDouble() / currentArea.toDouble())}); " +
                        "using stabilized swap ${fallbackProfile.width}x${fallbackProfile.height}@${fallbackProfile.densityDpi}dpi"
                )
                fallbackProfile
            } else {
                targetProfile
            }
        } else {
            targetProfile
        }
        if (
            stabilizedTargetProfile.width == captureWidthPx &&
            stabilizedTargetProfile.height == captureHeightPx &&
            stabilizedTargetProfile.densityDpi == captureDensityDpi
        ) {
            return
        }
        println(
            "SCREEN_CAPTURER: Orientation changed, resizing virtual display " +
                "${captureWidthPx}x${captureHeightPx}@${captureDensityDpi}dpi -> " +
                "${stabilizedTargetProfile.width}x${stabilizedTargetProfile.height}@${stabilizedTargetProfile.densityDpi}dpi"
        )
        runCatching {
            recreateVirtualDisplay(
                stabilizedTargetProfile.width,
                stabilizedTargetProfile.height,
                stabilizedTargetProfile.densityDpi,
            )
        }.onFailure { error ->
            println("SCREEN_CAPTURER: Failed to resize capture after orientation change: ${error.message}")
        }
    }

    private fun scheduleInitialResizeChecks() {
        clearPendingResizeChecks()
        // Run quick checks in the first 1.5 s to react to any orientation that
        // was already in flight when capture started.
        repeat(6) { index ->
            val runnable = Runnable {
                if (!captureStopped) {
                    maybeResizeCaptureForOrientationChange()
                }
            }
            pendingResizeChecks += runnable
            mainHandler.postDelayed(runnable, (index + 1L) * 250L)
        }
        // Start a continuous periodic poll that catches orientation changes
        // the DisplayListener might miss (e.g. Activity portrait-lock prevents
        // the display rotation callback on some Samsung tablets).
        scheduleContinuousRotationPoll()
    }

    private fun scheduleContinuousRotationPoll() {
        val runnable = object : Runnable {
            override fun run() {
                if (captureStopped) return
                maybeResizeCaptureForOrientationChange()
                mainHandler.postDelayed(this, ROTATION_POLL_INTERVAL_MS)
            }
        }
        continuousRotationPollRunnable = runnable
        mainHandler.postDelayed(runnable, ROTATION_POLL_INTERVAL_MS)
    }

    private fun clearPendingResizeChecks() {
        pendingResizeChecks.forEach { runnable -> mainHandler.removeCallbacks(runnable) }
        pendingResizeChecks.clear()
        continuousRotationPollRunnable?.let { mainHandler.removeCallbacks(it) }
        continuousRotationPollRunnable = null
    }

    private fun scheduleStartupFrameNudges() {
        clearPendingStartupFrameNudges()
        STARTUP_FRAME_NUDGE_DELAYS_MS.forEachIndexed { index, delayMs ->
            val attempt = index + 1
            val runnable = Runnable {
                if (captureStopped || hasCapturedFirstFrame) return@Runnable
                val activeDisplay = virtualDisplay ?: return@Runnable
                val activeSurface = surface
                if (activeSurface == null || !activeSurface.isValid) return@Runnable

                // Some devices wait for a screen damage event before emitting the first frame.
                // Rebinding the existing surface nudges SurfaceFlinger to produce an initial frame.
                runCatching {
                    activeDisplay.setSurface(null)
                    activeDisplay.setSurface(activeSurface)
                }.onSuccess {
                    println("SCREEN_CAPTURER: Startup frame nudge #$attempt applied")
                }.onFailure { error ->
                    println(
                        "SCREEN_CAPTURER: Startup frame nudge #$attempt failed: ${error.message}"
                    )
                }
            }
            pendingStartupFrameNudges += runnable
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun clearPendingStartupFrameNudges() {
        pendingStartupFrameNudges.forEach { runnable -> mainHandler.removeCallbacks(runnable) }
        pendingStartupFrameNudges.clear()
    }

    private fun isProjectionCallbacksSuppressed(): Boolean {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        return nowMs < suppressProjectionCallbacksUntilMs
    }

    private fun clearPendingProjectionEventKick() {
        projectionEventKickRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
        projectionEventKickRunnable = null
        pendingProjectionEventKickReason = null
    }

    private fun scheduleProjectionEventKick(reason: String) {
        if (captureStopped) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs < projectionEventKickCooldownUntilMs) {
            return
        }
        pendingProjectionEventKickReason = reason
        if (projectionEventKickRunnable != null) return
        val runnable = Runnable {
            projectionEventKickRunnable = null
            if (captureStopped) {
                pendingProjectionEventKickReason = null
                return@Runnable
            }
            val executeNowMs = android.os.SystemClock.elapsedRealtime()
            if (executeNowMs < projectionEventKickCooldownUntilMs) {
                pendingProjectionEventKickReason = null
                return@Runnable
            }
            val effectiveReason = pendingProjectionEventKickReason ?: reason
            pendingProjectionEventKickReason = null
            projectionEventKickCooldownUntilMs = executeNowMs + PROJECTION_EVENT_KICK_COOLDOWN_MS
            suppressProjectionCallbacksUntilMs = maxOf(
                suppressProjectionCallbacksUntilMs,
                executeNowMs + PROJECTION_CALLBACK_SUPPRESSION_MS,
            )
            kickVirtualDisplaySurface("projection-event: $effectiveReason")
            armRefreshValidation("projection-event: $effectiveReason")
        }
        projectionEventKickRunnable = runnable
        mainHandler.postDelayed(runnable, PROJECTION_EVENT_KICK_DEBOUNCE_MS)
    }

    private fun armRefreshValidation(reason: String) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        refreshValidationDeadlineMs = nowMs + CAPTURE_REFRESH_VALIDATION_DELAY_MS
        refreshValidationBaselineTimestampNs = lastObservedFrameTimestampNs
        refreshValidationReason = reason
        println(
            "SCREEN_CAPTURER: armed refresh validation " +
                "(reason=$reason, baselineTs=$refreshValidationBaselineTimestampNs)"
        )
    }

    private fun scheduleFrameStallWatchdog() {
        clearFrameStallWatchdog(resetCaptureSessionStartedAtMs = false)
        captureSessionStartedAtMs = android.os.SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                if (captureStopped) return
                if (!hasCapturedFirstFrame) {
                    mainHandler.postDelayed(this, CAPTURE_STALL_WATCHDOG_POLL_MS)
                    return
                }

                val nowMs = android.os.SystemClock.elapsedRealtime()
                val captureEventVersion = HostScreenCaptureSignal.currentVersion()
                if (captureEventVersion != lastSeenScreenCaptureEventVersion) {
                    val delta = (captureEventVersion - lastSeenScreenCaptureEventVersion)
                        .coerceAtLeast(1L)
                    lastSeenScreenCaptureEventVersion = captureEventVersion
                    if (nowMs >= screenCaptureEventKickCooldownUntilMs) {
                        screenCaptureEventKickCooldownUntilMs =
                            nowMs + SCREEN_CAPTURE_EVENT_KICK_COOLDOWN_MS
                        println(
                            "SCREEN_CAPTURER: 📸 Host capture event detected (delta=$delta); " +
                                "requesting full capture restart by policy"
                        )
                        refreshValidationDeadlineMs = 0L
                        refreshValidationBaselineTimestampNs = 0L
                        refreshValidationReason = null
                        onCaptureRestartRequested()
                    } else {
                        println(
                            "SCREEN_CAPTURER: capture-event restart suppressed (cooldown active)"
                        )
                    }
                }

                if (refreshValidationDeadlineMs > 0L && nowMs >= refreshValidationDeadlineMs) {
                    val baselineTimestamp = refreshValidationBaselineTimestampNs
                    val currentTimestamp = lastObservedFrameTimestampNs
                    val validationReason = refreshValidationReason ?: "unknown"
                    refreshValidationDeadlineMs = 0L
                    refreshValidationBaselineTimestampNs = 0L
                    refreshValidationReason = null

                    if (baselineTimestamp != 0L && currentTimestamp == baselineTimestamp) {
                        if (nowMs >= refreshValidationRestartCooldownUntilMs) {
                            refreshValidationRestartCooldownUntilMs =
                                nowMs + CAPTURE_STALL_RESTART_COOLDOWN_MS
                            println(
                                "SCREEN_CAPTURER: ❌ Refresh validation failed after " +
                                    "$validationReason (frame timestamp unchanged: " +
                                    "$currentTimestamp); requesting full capture restart"
                            )
                            onCaptureRestartRequested()
                        } else {
                            println(
                                "SCREEN_CAPTURER: ⏳ Refresh validation failed after " +
                                    "$validationReason but restart is in cooldown"
                            )
                        }
                    } else {
                        println(
                            "SCREEN_CAPTURER: ✅ Refresh validation passed after " +
                                "$validationReason"
                        )
                        // Frame delivery resumed. Force an IDR so all connected
                        // clients can resync from a full keyframe rather than
                        // trying to decode P-frames that reference the pre-kick
                        // (potentially frozen or screenshot-overlay) state.
                        requestEncoderKeyFrame("post-$validationReason")
                    }
                }

                // Content-staleness check: when the SurfaceTexture's buffer
                // timestamp stops advancing while frame callbacks keep firing
                // (i.e. the capturer is delivering duplicate frames), the
                // mirror is silently frozen. This catches the OEM screenshot
                // / one-handed-mode overlay case where neither onStop nor
                // visibility/resize callbacks fire on Samsung-derived Android
                // 14+ — exactly the path the existing wallclock stall check
                // can't see.
                val currentBufferTs = lastObservedFrameTimestampNs
                if (currentBufferTs != 0L) {
                    if (currentBufferTs != contentStalenessBaselineFrameTsNs) {
                        // Buffer ts advanced — content is changing normally.
                        contentStalenessBaselineFrameTsNs = currentBufferTs
                        contentStalenessBaselineMs = nowMs
                    } else if (contentStalenessBaselineMs != 0L) {
                        val staleMs = nowMs - contentStalenessBaselineMs
                        if (staleMs >= CONTENT_STALENESS_RESTART_THRESHOLD_MS &&
                            nowMs >= contentStalenessRestartCooldownUntilMs
                        ) {
                            contentStalenessRestartCooldownUntilMs =
                                nowMs + CONTENT_STALENESS_RESTART_COOLDOWN_MS
                            // Reset baseline so we don't re-fire next tick.
                            contentStalenessBaselineMs = nowMs
                            println(
                                "SCREEN_CAPTURER: ❄️ Content frozen for ${staleMs}ms " +
                                    "(buffer ts=$currentBufferTs unchanged); " +
                                    "requesting full capture restart"
                            )
                            onCaptureRestartRequested()
                        } else if (staleMs >= CONTENT_STALENESS_KICK_THRESHOLD_MS &&
                            nowMs >= contentStalenessKickCooldownUntilMs
                        ) {
                            contentStalenessKickCooldownUntilMs =
                                nowMs + CONTENT_STALENESS_KICK_COOLDOWN_MS
                            println(
                                "SCREEN_CAPTURER: ❄️ Content frozen for ${staleMs}ms " +
                                    "(buffer ts unchanged); kicking VirtualDisplay"
                            )
                            kickVirtualDisplaySurface(
                                "content-staleness ${staleMs}ms"
                            )
                            requestEncoderKeyFrame("content-staleness-kick ${staleMs}ms")
                        }
                    }
                }

                val lastFrameNs = lastFrameCapturedAtNs
                val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
                val stallMs = if (lastFrameNs > 0L) {
                    ((nowNs - lastFrameNs) / 1_000_000L).coerceAtLeast(0L)
                } else {
                    0L
                }

                if (stallMs >= CAPTURE_STALL_THRESHOLD_MS) {
                    hasLoggedFrameStallRecovery = false
                    if (frameStallNudgeAttempts < CAPTURE_STALL_MAX_NUDGE_ATTEMPTS) {
                        frameStallNudgeAttempts += 1
                        println(
                            "SCREEN_CAPTURER: ⚠️ No frame callback for ${stallMs}ms; " +
                                "applying stall nudge #$frameStallNudgeAttempts"
                        )
                        kickVirtualDisplaySurface(
                            "frame-stall-${frameStallNudgeAttempts} (${stallMs}ms)"
                        )
                    } else {
                        if (nowMs >= frameStallRestartCooldownUntilMs) {
                            frameStallRestartCooldownUntilMs =
                                nowMs + CAPTURE_STALL_RESTART_COOLDOWN_MS
                            frameStallNudgeAttempts = 0
                            println(
                                "SCREEN_CAPTURER: ❌ Frame callbacks stalled for ${stallMs}ms " +
                                    "after ${CAPTURE_STALL_MAX_NUDGE_ATTEMPTS} nudges; " +
                                    "requesting full capture restart"
                            )
                            onCaptureRestartRequested()
                        } else {
                            println(
                                "SCREEN_CAPTURER: ⏳ Frame stall persists (${stallMs}ms) but " +
                                    "restart is in cooldown"
                            )
                        }
                    }
                } else {
                    frameStallNudgeAttempts = 0
                }

                if (!captureStopped) {
                    mainHandler.postDelayed(this, CAPTURE_STALL_WATCHDOG_POLL_MS)
                }
            }
        }
        frameStallWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, CAPTURE_STALL_WATCHDOG_POLL_MS)
    }

    private fun clearFrameStallWatchdog(resetCaptureSessionStartedAtMs: Boolean = true) {
        frameStallWatchdogRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
        frameStallWatchdogRunnable = null
        frameStallNudgeAttempts = 0
        hasLoggedFrameStallRecovery = false
        projectionEventKickCooldownUntilMs = 0L
        suppressProjectionCallbacksUntilMs = 0L
        screenCaptureEventKickCooldownUntilMs = 0L
        lastObservedFrameTimestampNs = 0L
        contentStalenessBaselineFrameTsNs = 0L
        contentStalenessBaselineMs = 0L
        contentStalenessKickCooldownUntilMs = 0L
        contentStalenessRestartCooldownUntilMs = 0L
        if (resetCaptureSessionStartedAtMs) {
            captureSessionStartedAtMs = 0L
        }
        refreshValidationDeadlineMs = 0L
        refreshValidationBaselineTimestampNs = 0L
        refreshValidationReason = null
        refreshValidationRestartCooldownUntilMs = 0L
        clearPendingProjectionEventKick()
        lastSeenScreenCaptureEventVersion = HostScreenCaptureSignal.currentVersion()
    }

    private fun resolveCaptureProfileForCurrentDisplay(): AndroidCaptureProfile {
        val displayInfo = resolveDefaultDisplayInfo(context)
        val sourceWidth = alignToEven(displayInfo.widthPx)
        val sourceHeight = alignToEven(displayInfo.heightPx)
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return AndroidCaptureProfile(
                width = captureWidthPx,
                height = captureHeightPx,
                densityDpi = captureDensityDpi,
            )
        }

        val edgeScale =
            ANDROID_MAX_CAPTURE_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight).toDouble()
        val pixelsScale = sqrt(
            ANDROID_MAX_CAPTURE_PIXELS.toDouble() / (sourceWidth.toDouble() * sourceHeight.toDouble())
        )
        val scale = min(1.0, min(edgeScale, pixelsScale))
        if (scale >= 0.999) {
            return AndroidCaptureProfile(
                width = sourceWidth,
                height = sourceHeight,
                densityDpi = displayInfo.densityDpi,
            )
        }

        val targetWidth = alignToEven((sourceWidth * scale).toInt())
        val targetHeight = alignToEven((sourceHeight * scale).toInt())
        val targetDensity = (displayInfo.densityDpi * scale).toInt()
            .coerceAtLeast(ANDROID_MIN_CAPTURE_DENSITY_DPI)
        return AndroidCaptureProfile(
            width = targetWidth,
            height = targetHeight,
            densityDpi = targetDensity,
        )
    }

    private fun alignToEven(value: Int): Int {
        val normalized = value.coerceAtLeast(ANDROID_DIM_ALIGNMENT)
        return (normalized / ANDROID_DIM_ALIGNMENT) * ANDROID_DIM_ALIGNMENT
    }

    /**
     * Register MediaStore ContentObservers that fire when another app on the
     * host saves a screenshot or screen-recording. This is the only reliable
     * in-app signal on the Samsung-derived Teachmint X (com.skg.screenshot)
     * where the standard Activity.ScreenCaptureCallback and MediaProjection
     * callback paths stay silent during a screenshot. The user-set policy
     * is "only fire on actual screen-capture events, never on static
     * content" — MediaStore observation satisfies that because it ties the
     * signal directly to a file-write by another app.
     *
     * Limitation: screen recordings save the file at recording end, so the
     * "during recording" stuck-frame window can't be auto-recovered without
     * a NotificationListenerService — out of scope here.
     */
    private fun registerMediaStoreScreenCaptureObservers() {
        if (screenshotMediaObserver != null || screenRecordingMediaObserver != null) return
        val resolver = runCatching { context.contentResolver }.getOrNull() ?: return

        val imageObserver = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                handleMediaStoreScreenCaptureEvent("image", uri)
            }
        }
        val videoObserver = object : android.database.ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                handleMediaStoreScreenCaptureEvent("video", uri)
            }
        }
        runCatching {
            resolver.registerContentObserver(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants = */ true,
                imageObserver,
            )
            screenshotMediaObserver = imageObserver
            resolver.registerContentObserver(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants = */ true,
                videoObserver,
            )
            screenRecordingMediaObserver = videoObserver
            println(
                "SCREEN_CAPTURER: Registered MediaStore observers for " +
                    "screenshots and screen-recordings"
            )
        }.onFailure { error ->
            println(
                "SCREEN_CAPTURER: Failed to register MediaStore observers: " +
                    "${error.message}"
            )
        }
    }

    private fun unregisterMediaStoreScreenCaptureObservers() {
        val resolver = runCatching { context.contentResolver }.getOrNull() ?: run {
            screenshotMediaObserver = null
            screenRecordingMediaObserver = null
            return
        }
        screenshotMediaObserver?.let { obs ->
            runCatching { resolver.unregisterContentObserver(obs) }
        }
        screenshotMediaObserver = null
        screenRecordingMediaObserver?.let { obs ->
            runCatching { resolver.unregisterContentObserver(obs) }
        }
        screenRecordingMediaObserver = null
    }

    private fun registerScreenshotFileObserver() {
        if (screenshotFileObserver != null) return
        val screenshotDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES
        ).resolve("Screenshots")
        runCatching {
            val observer = object : android.os.FileObserver(
                screenshotDir.absolutePath,
                android.os.FileObserver.CREATE or android.os.FileObserver.MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && (path.endsWith(".png", ignoreCase = true) ||
                            path.endsWith(".jpg", ignoreCase = true) ||
                            path.endsWith(".jpeg", ignoreCase = true))
                    ) {
                        handleMediaStoreScreenCaptureEvent("file_observer", null)
                    }
                }
            }
            observer.startWatching()
            screenshotFileObserver = observer
            println("SCREEN_CAPTURER: Registered FileObserver for screenshots at ${screenshotDir.absolutePath}")
        }.onFailure { error ->
            println("SCREEN_CAPTURER: Failed to register FileObserver: ${error.message}")
        }
    }

    private fun unregisterScreenshotFileObserver() {
        screenshotFileObserver?.stopWatching()
        screenshotFileObserver = null
    }

    private fun registerSkgScreenshotReceiver() {
        if (skgScreenshotReceiver != null) return
        skgScreenshotReceiver = registerScreenCaptureBroadcastReceiver(
            intentAction = "com.skg.full_screenshot",
            eventKind = "skg_broadcast",
            logTag = "SKG screenshot broadcast receiver",
        )
    }

    private fun unregisterSkgScreenshotReceiver() {
        skgScreenshotReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        skgScreenshotReceiver = null
    }

    private fun registerSkgScreenCaptureReceiver() {
        if (skgScreenCaptureReceiver != null) return
        skgScreenCaptureReceiver = registerScreenCaptureBroadcastReceiver(
            intentAction = "com.skg.screenshot.SCREEN_CAPTURE",
            eventKind = "skg_screen_capture",
            logTag = "SKG SCREEN_CAPTURE broadcast receiver",
        )
    }

    private fun unregisterSkgScreenCaptureReceiver() {
        skgScreenCaptureReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        skgScreenCaptureReceiver = null
    }

    private fun registerScreenCaptureBroadcastReceiver(
        intentAction: String,
        eventKind: String,
        logTag: String,
    ): android.content.BroadcastReceiver? {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                handleMediaStoreScreenCaptureEvent(eventKind, null)
            }
        }
        return runCatching {
            context.registerReceiver(
                receiver,
                android.content.IntentFilter(intentAction),
                android.content.Context.RECEIVER_EXPORTED,
            )
            println("SCREEN_CAPTURER: Registered $logTag")
            receiver
        }.getOrElse { error ->
            println("SCREEN_CAPTURER: Failed to register $logTag: ${error.message}")
            null
        }
    }

    private fun handleMediaStoreScreenCaptureEvent(kind: String, uri: android.net.Uri?) {
        // The system can fire several onChange events for a single capture
        // (the file insert, MediaScanner indexing, thumbnail generation,
        // etc.). Debounce so we only forward one signal per real event.
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastMediaStoreNotifyMs < MEDIASTORE_NOTIFY_DEBOUNCE_MS) return
        lastMediaStoreNotifyMs = nowMs
        println(
            "SCREEN_CAPTURER: 📸 MediaStore $kind change detected (uri=$uri); " +
                "treating as host screen-capture event"
        )
        HostScreenCaptureSignal.notifyCaptureDetected("mediastore_$kind")
    }

    /**
     * Requests the WebRTC encoder to emit an IDR (keyframe) as soon as
     * possible. Delegates to [onKeyFrameRequested] which briefly disables
     * then re-enables the local VideoTrack — WebRTC's encoder resets its
     * keyframe counter on track re-enable and sends an IDR on the next frame.
     * Called after a VirtualDisplay kick so all connected clients can resync
     * rather than waiting for the next periodic keyframe (up to 3s away).
     */
    private fun requestEncoderKeyFrame(reason: String) {
        println("SCREEN_CAPTURER: 🔑 Requesting encoder IDR keyframe ($reason)")
        onKeyFrameRequested()
    }

    /**
     * Lightweight nudge to SurfaceFlinger's mirror: setSurface(null) → resize →
     * setSurface(s) on the existing VirtualDisplay. Triggered from
     * MediaProjection visibility/resize callbacks. Helps unstick the mirror in
     * some cases (PiP enter/exit). Doesn't recreate the VirtualDisplay or
     * MediaProjection — those would either invalidate the projection token on
     * Android 14+ or require fresh user consent. Doesn't help for stuck-mirror
     * states caused by smaller overlay events that don't fire the callback at
     * all (e.g., the screenshot animation); those still require a full
     * pipeline rebuild via [WebRtcEngine.captureRestartRequested].
     */
    private fun kickVirtualDisplaySurface(reason: String) {
        if (captureStopped) return
        val activeDisplay = virtualDisplay ?: return
        val activeSurface = surface ?: return
        if (!activeSurface.isValid) return
        val w = captureWidthPx
        val h = captureHeightPx
        val density = captureDensityDpi
        runCatching {
            activeDisplay.setSurface(null)
            activeDisplay.resize(w, h, density)
            activeDisplay.setSurface(activeSurface)
            println("SCREEN_CAPTURER: kicked VirtualDisplay (resize+rebind) ($reason)")
        }.onFailure { error ->
            println("SCREEN_CAPTURER: resize+rebind kick failed (${error.message}); trying bare rebind")
            runCatching {
                activeDisplay.setSurface(null)
                activeDisplay.setSurface(activeSurface)
                println("SCREEN_CAPTURER: kicked VirtualDisplay (bare rebind) ($reason)")
            }.onFailure { rebindError ->
                println("SCREEN_CAPTURER: kickVirtualDisplaySurface gave up: ${rebindError.message}")
            }
        }
    }

    private fun createVirtualDisplay(targetWidth: Int, targetHeight: Int): VirtualDisplay? {
        val projection = mediaProjection ?: return null
        return projection.createVirtualDisplay(
            "ScreenCapture",
            targetWidth,
            targetHeight,
            captureDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
    }

    private fun recreateVirtualDisplay(newWidth: Int, newHeight: Int, newDensityDpi: Int) {
        if (captureStopped) {
            println("SCREEN_CAPTURER: Skipping resize — capture already stopped")
            return
        }
        val helper = surfaceTextureHelper ?: error("SurfaceTextureHelper is unavailable")
        val texture = helper.surfaceTexture ?: error("SurfaceTexture is unavailable")
        val activeDisplay = virtualDisplay ?: error("VirtualDisplay is unavailable")
        val projection = mediaProjection ?: error("MediaProjection is unavailable")
        val previousSurface = surface ?: error("Surface is unavailable")

        // Keep the SurfaceTexture dimensions in sync before we resize/recreate.
        helper.setTextureSize(newWidth, newHeight)
        texture.setDefaultBufferSize(newWidth, newHeight)
        val replacementSurface = android.view.Surface(texture)
        if (!replacementSurface.isValid) {
            replacementSurface.release()
            error("Replacement surface is invalid")
        }

        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        // Samsung One UI on Android 14+ invalidates the MediaProjection token the second
        // time createVirtualDisplay() is called on the same projection instance (observed
        // on SM-T225 / One UI 6). That silently kills the capture session, so we must
        // never recreate the VirtualDisplay on Samsung — use resize() + surface reset
        // instead. Android 14+ in general enforces single-shot consent, so we also skip
        // recreate on API 34+ unless we know the OEM tolerates it.
        val isApi34Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val shouldForceSurfaceReset = manufacturer.contains("samsung") || isApi34Plus
        val shouldSkipPostResizeRebind = manufacturer.contains("oneplus")
        val shouldPreferDisplayRecreate =
            !manufacturer.contains("oneplus") &&
                !manufacturer.contains("samsung") &&
                !isApi34Plus

        if (shouldPreferDisplayRecreate) {
            // Recreating the VirtualDisplay is the most reliable way to clear stale crop
            // windows after an orientation flip on OEMs that tolerate a second
            // createVirtualDisplay() on the same MediaProjection token.
            val recreatedDisplay = runCatching {
                projection.createVirtualDisplay(
                    "ScreenCapture",
                    newWidth,
                    newHeight,
                    newDensityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    replacementSurface,
                    null,
                    null
                )
            }.onFailure { error ->
                println(
                    "SCREEN_CAPTURER: VirtualDisplay recreate failed, falling back to resize: " +
                        "${error.message}"
                )
            }.getOrNull()

            if (recreatedDisplay != null) {
                activeDisplay.release()
                virtualDisplay = recreatedDisplay
                surface = replacementSurface
                previousSurface.release()
                captureWidthPx = newWidth
                captureHeightPx = newHeight
                captureDensityDpi = newDensityDpi
                scheduleStartupFrameNudges()
                println(
                    "SCREEN_CAPTURER: ✅ VirtualDisplay recreated at " +
                        "${captureWidthPx}x${captureHeightPx}@${captureDensityDpi}dpi, " +
                        "surface=${replacementSurface.isValid}"
                )
                return
            }
        }

        try {
            if (shouldForceSurfaceReset) {
                println("SCREEN_CAPTURER: Applying Samsung surface reset during orientation resize")
                activeDisplay.setSurface(null)
                activeDisplay.resize(newWidth, newHeight, newDensityDpi)
                activeDisplay.setSurface(replacementSurface)
            } else {
                runCatching {
                    activeDisplay.resize(newWidth, newHeight, newDensityDpi)
                    activeDisplay.setSurface(replacementSurface)
                    if (!shouldSkipPostResizeRebind) {
                        activeDisplay.setSurface(null)
                        activeDisplay.setSurface(replacementSurface)
                    }
                }.onFailure { resizeError ->
                    println(
                        "SCREEN_CAPTURER: In-place resize failed, retrying with surface reset: " +
                            "${resizeError.message}"
                    )
                    activeDisplay.setSurface(null)
                    activeDisplay.resize(newWidth, newHeight, newDensityDpi)
                    activeDisplay.setSurface(replacementSurface)
                }.getOrThrow()
            }
        } catch (error: Throwable) {
            runCatching { activeDisplay.setSurface(previousSurface) }
            replacementSurface.release()
            throw error
        }

        surface = replacementSurface
        previousSurface.release()
        captureWidthPx = newWidth
        captureHeightPx = newHeight
        captureDensityDpi = newDensityDpi
        scheduleStartupFrameNudges()
        println(
            "SCREEN_CAPTURER: ✅ VirtualDisplay resized at " +
                "${captureWidthPx}x${captureHeightPx}@${captureDensityDpi}dpi, " +
                "surface=${replacementSurface.isValid}"
        )
    }
}
