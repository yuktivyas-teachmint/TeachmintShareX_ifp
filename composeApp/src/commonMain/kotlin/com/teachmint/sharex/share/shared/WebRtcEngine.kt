package com.teachmint.sharex.share.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class ScreenCapturePermissionRequired(message: String = "Screen capture permission required") : Exception(message)
class ScreenCaptureNotSupported(message: String = "Screen capture not supported") : Exception(message)

/**
 * Bidirectional data channel over an existing WebRTC peer connection.
 * Used for low-latency transport of remote-control input events.
 */
interface WebRtcDataChannel {
    val label: String
    val incoming: Flow<ByteArray>
    fun send(data: ByteArray)
    fun close()
}

interface WebRtcPeerConnection {
    val iceCandidates: Flow<IceCandidateData>
    val remoteVideoTracks: Flow<PlatformVideoTrack>

    /**
     * Flow of encoder statistics, updated periodically during active streaming.
     * Default implementation provides empty stats for platforms that don't support monitoring.
     */
    val encoderStats: StateFlow<EncoderStats> get() = MutableStateFlow(EncoderStats.Empty)

    suspend fun createOffer(): SessionDescriptionData
    suspend fun createAnswer(): SessionDescriptionData
    suspend fun setLocalDescription(description: SessionDescriptionData)
    suspend fun setRemoteDescription(description: SessionDescriptionData)
    suspend fun addIceCandidate(candidate: IceCandidateData)
    fun addLocalVideoTrack(track: PlatformVideoTrack)

    /**
     * Attach a local audio track to this peer connection.
     *
     * Implementations should attach the audio track to the SAME media stream id
     * that is used for the video track so the remote peer can keep the two
     * tracks lip-synced. Default is a no-op so platforms that do not yet
     * implement audio capture keep working unchanged.
     */
    fun addLocalAudioTrack(track: PlatformAudioTrack) {}

    /**
     * Update encoder configuration dynamically.
     * Default implementation is no-op for platforms that don't support dynamic configuration.
     */
    suspend fun setEncoderConfiguration(config: EncoderConfiguration) {}

    /**
     * Locally enable or disable playback of any remote audio tracks received
     * on this peer connection. Setting `false` mutes the remote audio on the
     * receiver only — the sender is unaffected and no signaling round-trip is
     * needed. Default is a no-op for platforms that have not wired up remote
     * audio yet.
     */
    fun setRemoteAudioEnabled(enabled: Boolean) {}

    /**
     * Route the incoming remote audio from this peer connection to the BYOM virtual
     * microphone device (e.g. "BYOM-Microphone" CoreAudio HAL on macOS) so that
     * meeting apps like Zoom or Meet can pick it up. No-op on platforms where this
     * routing is not implemented.
     */
    fun routeRemoteAudioToBYOM() {}

    /**
     * Create an outbound data channel on this peer connection.
     * The remote peer will see it via [incomingDataChannels].
     * Default returns a no-op channel for platforms that haven't implemented it yet.
     */
    fun createDataChannel(label: String): WebRtcDataChannel = NoOpDataChannel(label)

    /**
     * Flow of data channels opened by the remote peer.
     * Default emits nothing for platforms that haven't implemented it yet.
     */
    val incomingDataChannels: Flow<WebRtcDataChannel> get() = MutableSharedFlow()

    fun close()
}

/** Stub data channel for platforms that don't implement WebRTC data channels yet. */
private class NoOpDataChannel(override val label: String) : WebRtcDataChannel {
    override val incoming: Flow<ByteArray> = MutableSharedFlow()
    override fun send(data: ByteArray) {}
    override fun close() {}
}

interface WebRtcEngine {
    suspend fun createPeerConnection(iceServers: List<IceServerConfig>): WebRtcPeerConnection
    suspend fun startScreenCapture(): PlatformVideoTrack
    fun stopScreenCapture(stopBroadcast: Boolean = true) {}
    fun setScreenCapturePermission(permission: ScreenCapturePermissionData)

    /**
     * Start capturing audio that will be streamed alongside the shared screen.
     *
     * Returns the captured [PlatformAudioTrack] ready to be added to a peer
     * connection via [WebRtcPeerConnection.addLocalAudioTrack], or `null` if
     * the platform cannot satisfy the request (e.g. permission denied or
     * capture unsupported). Callers should treat a `null` return as "video-only"
     * and continue normally.
     *
     * The default implementation returns `null` so platforms that have not yet
     * implemented audio capture keep their existing video-only behavior.
     */
    suspend fun startAudioCapture(options: AudioCaptureOptions = AudioCaptureOptions.Default): PlatformAudioTrack? = null

    /** Stop and release any audio capture started via [startAudioCapture]. */
    fun stopAudioCapture() {}

    /**
     * Flow that emits the current physical display rotation (0/90/180/270) of
     * the device performing screen capture. Only meaningful on Android where
     * the VirtualDisplay can letterbox landscape content into a portrait buffer.
     */
    val captureDisplayRotation: StateFlow<Int> get() = MutableStateFlow(0)

    /**
     * Emits when the platform detects that the screen-capture pipeline has
     * entered a state requiring a full restart (e.g. the VirtualDisplay
     * mirror has been left in a stuck state by the system after an overlay
     * window appeared/disappeared, like a screenshot animation, on Android).
     *
     * The host controller observes this and triggers a fresh-permission
     * prompt to rebuild the capture from scratch — the only reliable
     * recovery on Android 14+ where MediaProjection cannot be silently
     * re-acquired.
     */
    val captureRestartRequested: SharedFlow<Unit> get() = MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * Register a callback that receives diagnostic log lines from the audio
     * capture subsystem. The host can use this to display client-side audio
     * diagnostics in its own logs. Default no-op for platforms that don't
     * need it.
     */
    fun setAudioDiagnosticLogCallback(callback: ((String) -> Unit)?) {}

    /**
     * Capture from the device's front-facing (or first available) camera for BYOM.
     * Returns the camera [PlatformVideoTrack] to be added to a BYOM peer connection,
     * or null if camera capture is unavailable on this platform/device.
     * Default implementation returns null (platform actuals override for supported devices).
     */
    suspend fun startCameraCapture(): PlatformVideoTrack? = null

    /** Stop and release any camera capture started via [startCameraCapture]. */
    fun stopCameraCapture() {}

    fun release()
}

expect fun createWebRtcEngine(): WebRtcEngine
