package com.teachmint.sharex.share.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SignalingMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val clientId: String,
        val clientName: String,
        /** Client OS, e.g. "android", "ios", "macos", "windows". Null for older clients. */
        val platform: String? = null,
    ) : SignalingMessage()

    @Serializable
    @SerialName("connection_approved")
    data object ConnectionApproved : SignalingMessage()

    @Serializable
    @SerialName("connection_rejected")
    data class ConnectionRejected(
        val message: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("start_share")
    data class StartShare(
        val targetClientId: String? = null,
    ) : SignalingMessage()

    @Serializable
    @SerialName("offer")
    data class Offer(
        val sdp: SessionDescriptionData,
    ) : SignalingMessage()

    @Serializable
    @SerialName("answer")
    data class Answer(
        val sdp: SessionDescriptionData,
    ) : SignalingMessage()

    @Serializable
    @SerialName("ice")
    data class Ice(
        val candidate: IceCandidateData,
    ) : SignalingMessage()

    @Serializable
    @SerialName("stop_share")
    data object StopShare : SignalingMessage()

    /**
     * Sent by the host to active reverse-mirroring clients when its
     * MediaProjection (screen capture) is revoked by the platform — e.g.
     * another app took screen-capture permission, the system reclaimed it
     * for a screenshot/screen recorder, or the user dismissed the projection
     * notification. Distinct from [StopShare] so the client can distinguish
     * an involuntary interruption from an explicit user-stop and apply
     * auto-retry logic before surfacing an error.
     */
    @Serializable
    @SerialName("host_capture_interrupted")
    data object HostCaptureInterrupted : SignalingMessage()

    /**
     * Sent by the sharing client whenever its capture-source display rotation
     * changes. The receiver (host) uses this to rotate the remote video tile so
     * landscape content rendered inside a portrait capture buffer is shown the
     * right way up and filling the tile.
     *
     * [rotation] follows Android's Surface.ROTATION_* constants expressed in
     * degrees: 0, 90, 180, 270.
     */
    @Serializable
    @SerialName("display_rotation_changed")
    data class DisplayRotationChanged(
        val rotation: Int,
    ) : SignalingMessage()

    @Serializable
    @SerialName("request_reverse_share")
    data object RequestReverseShare : SignalingMessage()

    @Serializable
    @SerialName("reverse_share_approved")
    data object ReverseShareApproved : SignalingMessage()

    @Serializable
    @SerialName("cancel_reverse_share")
    data object CancelReverseShare : SignalingMessage()

    @Serializable
    @SerialName("client_disconnected")
    data object ClientDisconnected : SignalingMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val message: String,
    ) : SignalingMessage()

    /** Lightweight diagnostic log forwarded from client to host for remote debugging. */
    @Serializable
    @SerialName("diagnostic_log")
    data class DiagnosticLog(
        val message: String,
    ) : SignalingMessage()

    /** Client requests permission to remotely control the host screen. */
    @Serializable
    @SerialName("request_remote_control")
    data object RequestRemoteControl : SignalingMessage()

    /** Host grants the client remote-control permission. */
    @Serializable
    @SerialName("remote_control_approved")
    data object RemoteControlApproved : SignalingMessage()

    /** Host denies the client remote-control permission. */
    @Serializable
    @SerialName("remote_control_denied")
    data class RemoteControlDenied(
        val message: String = "Remote control not available",
    ) : SignalingMessage()

    /** Either side stops the remote-control session. */
    @Serializable
    @SerialName("stop_remote_control")
    data object StopRemoteControl : SignalingMessage()

    // ── BYOM (Bring Your Own Meeting) ──────────────────────────────────────

    /** Client requests BYOM — use the IFP's camera and microphone in their meeting app. */
    @Serializable
    @SerialName("request_byom")
    data object RequestBYOM : SignalingMessage()

    /** Host approves the BYOM request; will follow with a [BYOMOffer]. */
    @Serializable
    @SerialName("byom_approved")
    data object BYOMApproved : SignalingMessage()

    /** Host denies the BYOM request. */
    @Serializable
    @SerialName("byom_denied")
    data class BYOMDenied(
        val reason: String = "BYOM not available",
    ) : SignalingMessage()

    /** Either side terminates the active BYOM session. */
    @Serializable
    @SerialName("stop_byom")
    data object StopBYOM : SignalingMessage()

    /** WebRTC offer for the BYOM camera/mic stream (host → client). */
    @Serializable
    @SerialName("byom_offer")
    data class BYOMOffer(val sdp: SessionDescriptionData) : SignalingMessage()

    /** WebRTC answer for the BYOM stream (client → host). */
    @Serializable
    @SerialName("byom_answer")
    data class BYOMAnswer(val sdp: SessionDescriptionData) : SignalingMessage()

    /** ICE candidate for the dedicated BYOM peer connection. */
    @Serializable
    @SerialName("byom_ice")
    data class BYOMIce(val candidate: IceCandidateData) : SignalingMessage()

    // Remote server specific messages
    @Serializable
    @SerialName("register_host")
    data class RegisterHost(
        val hostId: String,
        val hostName: String,
        val platform: String,
        val pin: String? = null,
        val pinExpiresAtEpochMs: Long? = null,
    ) : SignalingMessage()

    @Serializable
    @SerialName("unregister_host")
    data class UnregisterHost(
        val hostId: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("list_hosts")
    data object ListHosts : SignalingMessage()

    @Serializable
    @SerialName("hosts_list")
    data class HostsList(
        val hosts: List<RemoteHostInfo>,
    ) : SignalingMessage()

    @Serializable
    @SerialName("join_host")
    data class JoinHost(
        val hostId: String,
        val clientId: String,
        val clientName: String,
        val platform: String? = null,
    ) : SignalingMessage()

    @Serializable
    @SerialName("join_host_by_pin")
    data class JoinHostByPin(
        val pin: String,
        val clientId: String,
        val clientName: String,
        val platform: String? = null,
    ) : SignalingMessage()

    /** File upload relayed through the signaling server (web client → host). */
    @Serializable
    @SerialName("file_upload_data")
    data class FileUploadData(
        val fileName: String,
        val fileSize: Long,
        val fileDataBase64: String,
    ) : SignalingMessage()

    /** Chunked file upload: sent first to announce an incoming file. */
    @Serializable
    @SerialName("file_upload_start")
    data class FileUploadStart(
        val uploadId: String,
        val fileName: String,
        val fileSize: Long,
        val totalChunks: Int,
    ) : SignalingMessage()

    /** Chunked file upload: one chunk of Base64 data. */
    @Serializable
    @SerialName("file_upload_chunk")
    data class FileUploadChunk(
        val uploadId: String,
        val chunkIndex: Int,
        val chunkDataBase64: String,
    ) : SignalingMessage()

    /** Chunked file upload: all chunks have been sent. */
    @Serializable
    @SerialName("file_upload_end")
    data class FileUploadEnd(
        val uploadId: String,
    ) : SignalingMessage()

    @Serializable
    @SerialName("relay")
    data class Relay(
        val from: String,
        val to: String,
        val payload: SignalingMessage,
    ) : SignalingMessage()
}

@Serializable
data class RemoteHostInfo(
    val hostId: String,
    val hostName: String,
    val platform: String,
    val connectedClients: Int = 0,
)

@Serializable
data class SessionDescriptionData(
    val type: SdpType,
    val sdp: String,
)

@Serializable
enum class SdpType {
    Offer,
    Answer,
}

@Serializable
data class IceCandidateData(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String,
)
