package com.teachmint.sharex.airplay

/**
 * AirPlay protocol constants and shared data models.
 */
object AirPlayProtocol {
    /** HTTP port for device info and pairing */
    const val HTTP_PORT = 7000

    /** RTSP port for session management */
    const val RTSP_PORT = 49153

    // Feature flags advertised to source devices (from UxPlay reference)
    const val FEATURES_LOW = 0x5A7FFFF7L
    const val FEATURES_HIGH = 0x1EL
    const val FEATURES_STRING = "0x5A7FFFF7,0x1E"

    const val MODEL = "AppleTV5,3"
    const val MANUFACTURER = "Apple Inc."
    const val PROTO_VERS = "1.1"
    const val SRC_VERS = "220.68"
    const val VV = "2"
    const val ATV = "0x5"

    // mDNS service types
    const val MDNS_AIRPLAY_TYPE = "_airplay._tcp.local."

    // RTSP method names
    const val RTSP_OPTIONS = "OPTIONS"
    const val RTSP_ANNOUNCE = "ANNOUNCE"
    const val RTSP_SETUP = "SETUP"
    const val RTSP_RECORD = "RECORD"
    const val RTSP_TEARDOWN = "TEARDOWN"
    const val RTSP_FLUSH = "FLUSH"
    const val RTSP_GET_PARAMETER = "GET_PARAMETER"
    const val RTSP_SET_PARAMETER = "SET_PARAMETER"

    // RTP port range for server-side receivers
    const val RTP_PORT_RANGE_START = 50000
    const val RTP_PORT_RANGE_END = 51000

    // Fixed UUID for the virtual display advertised in /info and SETUP responses.
    // Must be the same in both so macOS can match the display description to the stream.
    const val DISPLAY_UUID = "77257AA5-A871-4B6D-B700-000000000001"
}

data class AirPlayDeviceInfo(
    /** Colon-separated hex MAC address: "AA:BB:CC:DD:EE:FF" */
    val deviceId: String,
    /** Human-readable display name shown in AirPlay picker */
    val name: String,
    /** Pairing identity UUID */
    val pi: String,
    /** Ed25519 public key, 32 bytes — advertised in mDNS TXT record */
    val publicKey: ByteArray,
) {
    val publicKeyHex: String get() = publicKey.joinToString("") { "%02x".format(it) }
}

data class RtspSession(
    val sessionId: String,
    val videoRtpPort: Int,
    val videoRtcpPort: Int,
    /** 0 = no audio stream allocated yet */
    val audioRtpPort: Int = 0,
    val audioRtcpPort: Int = 0,
    val clientAddress: String = "",
    val clientId: String = "",
    val clientName: String = "",
    val videoSdp: AirPlaySdp? = null,
    val audioSdp: AirPlayAudioSdp? = null,
) {
    val sdp: AirPlaySdp? get() = videoSdp
}

/**
 * Parsed SDP from RTSP ANNOUNCE — H.264 video codec parameters.
 */
data class AirPlaySdp(
    val videoPayloadType: Int = 96,
    val videoClockRate: Int = 90000,
    val sps: ByteArray? = null,
    val pps: ByteArray? = null,
)

/**
 * Parsed audio section from RTSP SDP.
 */
data class AirPlayAudioSdp(
    val payloadType: Int = 96,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    val codec: String = "MPEG4-GENERIC",
    val codecConfig: ByteArray? = null,
    val aesKey: ByteArray? = null,
    val aesKeyCandidates: List<ByteArray> = emptyList(),
    val aesIv: ByteArray? = null,
    val aesIvCandidates: List<ByteArray> = emptyList(),
    val encryptionType: Int = 0,
    val samplesPerFrame: Int = 480,
)

data class AirPlayConnectionRequest(
    val clientId: String,
    val clientName: String,
    val clientAddress: String,
    val userAgent: String? = null,
)
