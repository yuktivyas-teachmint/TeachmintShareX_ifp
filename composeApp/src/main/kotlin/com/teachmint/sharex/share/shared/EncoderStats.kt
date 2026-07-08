package com.teachmint.sharex.share.shared

/**
 * Statistics for video encoder performance monitoring.
 * Collected from WebRTC stats API to enable dynamic quality adjustment.
 */
data class EncoderStats(
    val timestamp: Long = currentTimeMillis(),

    // Bitrate metrics (in bits per second)
    val currentBitrate: Long = 0,
    val targetBitrate: Long = 0,

    // Frame metrics
    val frameRate: Double = 0.0,
    val framesSent: Long = 0,
    val framesEncoded: Long = 0,

    // Resolution
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,

    // Quality metrics
    val packetsSent: Long = 0,
    val packetsLost: Long = 0,
    val bytesSent: Long = 0,

    // Encoder performance
    val totalEncodeTime: Double = 0.0,  // seconds
    val qualityLimitationReason: String? = null,  // "cpu", "bandwidth", "none", or null
) {
    /**
     * Packet loss rate as a percentage
     */
    val packetLossRate: Double
        get() = if (packetsSent > 0) {
            (packetsLost.toDouble() / packetsSent.toDouble()) * 100.0
        } else {
            0.0
        }

    /**
     * Average encoding time per frame in milliseconds
     */
    val avgEncodeTimeMs: Double
        get() = if (framesEncoded > 0) {
            (totalEncodeTime / framesEncoded.toDouble()) * 1000.0
        } else {
            0.0
        }

    companion object {
        val Empty = EncoderStats()
    }
}

/**
 * Configuration for video encoder parameters.
 * Applied to RTCRtpSender to control encoding quality and performance.
 */
data class EncoderConfiguration(
    val maxBitrate: Long,              // Maximum bitrate in bits per second
    val maxFramerate: Int,             // Maximum frames per second
    val scaleResolutionDownBy: Double, // Scale factor (1.0 = full res, 2.0 = half res)
) {
    companion object {
        /**
         * High quality preset for stable connections and high-motion content.
         * 6.5 Mbps, 30 fps, full resolution.
         */
        val HighQuality = EncoderConfiguration(
            maxBitrate = 6_500_000,
            maxFramerate = 30,
            scaleResolutionDownBy = 1.0
        )

        /**
         * Medium quality preset while preserving smooth playback.
         * 5 Mbps, 30 fps, full resolution.
         */
        val Medium = EncoderConfiguration(
            maxBitrate = 5_000_000,
            maxFramerate = 30,
            scaleResolutionDownBy = 1.0
        )

        /**
         * Lower bandwidth preset with a quality floor for 2x playback.
         * 3.8 Mbps, 27 fps, mildly reduced resolution.
         */
        val LowBandwidth = EncoderConfiguration(
            maxBitrate = 3_800_000,
            maxFramerate = 27,
            scaleResolutionDownBy = 1.08
        )

        /**
         * Emergency preset that still avoids severe blur/stutter.
         * 2.8 Mbps, 24 fps, slight resolution reduction.
         */
        val Minimal = EncoderConfiguration(
            maxBitrate = 2_800_000,
            maxFramerate = 24,
            scaleResolutionDownBy = 1.15
        )
    }
}
