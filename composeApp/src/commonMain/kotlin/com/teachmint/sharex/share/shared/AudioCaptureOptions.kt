package com.teachmint.sharex.share.shared

/**
 * Describes which audio sources should be captured when a share session starts.
 *
 * @property microphone  capture the user's microphone (standard VoIP audio).
 * @property systemAudio capture the device's playback ("what-you-hear").
 *                       Currently honored on Web via `getDisplayMedia({audio:true})`.
 *                       Other platforms treat this as a hint and fall back to
 *                       microphone capture.
 */
data class AudioCaptureOptions(
    val microphone: Boolean = true,
    val systemAudio: Boolean = true,
) {
    val isEnabled: Boolean get() = microphone || systemAudio

    companion object {
        /** Capture whatever audio the platform can deliver alongside the screen. */
        val Default: AudioCaptureOptions = AudioCaptureOptions()

        /** Do not capture any audio. */
        val Disabled: AudioCaptureOptions = AudioCaptureOptions(
            microphone = false,
            systemAudio = false,
        )
    }
}
