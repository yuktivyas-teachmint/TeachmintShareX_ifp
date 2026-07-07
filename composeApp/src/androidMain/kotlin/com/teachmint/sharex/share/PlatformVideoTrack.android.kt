package com.teachmint.sharex.share.shared

import com.shepeliev.webrtckmp.VideoTrack as KmpVideoTrack

actual class PlatformVideoTrack internal constructor(
    internal val value: KmpVideoTrack?,
    internal val nativeTrack: org.webrtc.VideoTrack? = null
) {
    // Primary constructor for KMP VideoTrack
    internal constructor(kmpTrack: KmpVideoTrack) : this(kmpTrack, null)

    // Alternative constructor for native org.webrtc.VideoTrack (for custom screen capture)
    internal constructor(nativeTrack: org.webrtc.VideoTrack) : this(null, nativeTrack)
}

