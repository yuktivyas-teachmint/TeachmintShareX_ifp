package com.teachmint.sharex.share.shared

/**
 * Platform-specific wrapper around a WebRTC audio track.
 *
 * Mirrors [PlatformVideoTrack] so the same peer-connection code path can
 * accept both media kinds in a platform-neutral way. Each platform provides
 * its own `actual` that wraps the native audio-track type (org.webrtc.AudioTrack
 * on Android, RTCAudioTrack on iOS, dev.onvoid.webrtc.media.audio.AudioTrack on
 * JVM, and a trackId string on Web).
 */
expect class PlatformAudioTrack
