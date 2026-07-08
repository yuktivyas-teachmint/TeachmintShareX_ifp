package com.teachmint.sharex.share.shared

/**
 * Android audio track wrapper. Holds the native `org.webrtc.AudioTrack` created
 * by the same `PeerConnectionFactory` that owns the screen video track so the
 * two can share a media-stream id and stay lip-synced on the receiver.
 */
class PlatformAudioTrack internal constructor(
    internal val nativeTrack: org.webrtc.AudioTrack,
)
