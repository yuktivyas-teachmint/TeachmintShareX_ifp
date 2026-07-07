package com.teachmint.sharex.share.shared

import kotlinx.coroutines.*
import kotlin.math.pow
import kotlin.math.round

/**
 * Adaptive quality controller that monitors encoder performance and
 * dynamically adjusts encoding parameters to maintain smooth frame delivery.
 *
 * Prevents frame drops during complex content (e.g., YouTube videos) by
 * downgrading quality when encoder is overloaded and upgrading when stable.
 */
class AdaptiveQualityController(
    private val peerConnection: WebRtcPeerConnection,
    private val scope: CoroutineScope,
    private val maxCaptureFramerate: Int = 30,
    private val onFrameRateChange: ((Int) -> Unit)? = null,  // Callback for capture frame rate changes
) {
    private enum class QualityLevel(
        val config: EncoderConfiguration,
        val captureFramerate: Int
    ) {
        ULTRA(EncoderConfiguration.HighQuality, 30),
        MEDIUM(EncoderConfiguration.Medium, 30),
        LOW(EncoderConfiguration.LowBandwidth, 27),
        MINIMAL(EncoderConfiguration.Minimal, 24);

        fun downgrade(): QualityLevel? = when (this) {
            ULTRA -> MEDIUM
            MEDIUM -> LOW
            LOW -> MINIMAL
            MINIMAL -> null
        }

        fun upgrade(): QualityLevel? = when (this) {
            MINIMAL -> LOW
            LOW -> MEDIUM
            MEDIUM -> ULTRA
            ULTRA -> null
        }
    }

    private var currentQuality = QualityLevel.ULTRA
    private var consecutiveBadSamples = 0
    private var stableGoodSamples = 0
    private var monitoringJob: Job? = null
    private var lastStats: EncoderStats? = null
    private var lastEvaluationTimestamp = 0L
    private var lastQualityChangeTimestamp = 0L

    /**
     * Start monitoring encoder stats and applying adaptive quality adjustments
     */
    fun start() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            peerConnection.encoderStats.collect { stats ->
                if (stats.framesEncoded <= 0) return@collect

                val now = currentTimeMillis()
                if (now - lastEvaluationTimestamp < EVALUATION_INTERVAL_MS) {
                    return@collect
                }
                lastEvaluationTimestamp = now
                evaluateAndAdjust(stats)
            }
        }
        println("QUALITY_CONTROLLER: 🎯 Started adaptive quality monitoring")
    }

    /**
     * Stop monitoring and cleanup
     */
    fun stop() {
        monitoringJob?.cancel()
        monitoringJob = null
        println("QUALITY_CONTROLLER: Stopped adaptive quality monitoring")
    }

    private suspend fun evaluateAndAdjust(stats: EncoderStats) {
        val prev = lastStats
        lastStats = stats

        // Skip if no previous stats or no new frames encoded
        if (prev == null || stats.framesEncoded == prev.framesEncoded) return
        if (stats.framesEncoded < prev.framesEncoded) return

        // Calculate metrics
        val expectedFps = currentQuality.captureFramerate
            .coerceAtMost(maxCaptureFramerate)
            .toDouble()
        val actualFps = stats.frameRate
        val frameDropRate = if (expectedFps > 0) {
            (expectedFps - actualFps).coerceAtLeast(0.0) / expectedFps
        } else {
            0.0
        }

        // Check for quality issues
        val highFrameDrop = frameDropRate > 0.15
        val highEncodeTime = stats.avgEncodeTimeMs > 28.0
        val cpuLimited = stats.qualityLimitationReason == "cpu"
        val highPacketLoss = stats.packetLossRate > 3.0
        val belowTargetBitrate = stats.targetBitrate > 0 &&
            stats.currentBitrate in 1 until (stats.targetBitrate * 7 / 10)
        val qualityChangeOnCooldown = currentTimeMillis() - lastQualityChangeTimestamp < QUALITY_CHANGE_COOLDOWN_MS

        val shouldDowngrade = !qualityChangeOnCooldown &&
            (highFrameDrop || highEncodeTime || cpuLimited || (highPacketLoss && belowTargetBitrate))
        val shouldUpgrade = !qualityChangeOnCooldown &&
            frameDropRate < 0.08 &&
            stats.avgEncodeTimeMs < 20.0 &&
            !cpuLimited &&
            stats.packetLossRate < 1.0

        when {
            shouldDowngrade -> {
                consecutiveBadSamples++
                stableGoodSamples = 0

                if (consecutiveBadSamples >= 3) {
                    downgradeQuality(stats)
                    consecutiveBadSamples = 0
                }
            }
            shouldUpgrade -> {
                consecutiveBadSamples = 0
                stableGoodSamples++

                if (stableGoodSamples >= 6 && currentQuality != QualityLevel.ULTRA) {
                    upgradeQuality()
                    stableGoodSamples = 0
                }
            }
            else -> {
                consecutiveBadSamples = 0
                // Keep stable counter if not at max quality
                if (currentQuality == QualityLevel.ULTRA) {
                    stableGoodSamples = 0
                }
            }
        }
    }

    private suspend fun downgradeQuality(stats: EncoderStats) {
        val newQuality = currentQuality.downgrade()
        if (newQuality == null) {
            println("QUALITY_CONTROLLER: ⚠️ Already at minimum quality, cannot downgrade further")
            return
        }

        println("QUALITY_CONTROLLER: 📉 Downgrading $currentQuality -> $newQuality " +
                "(fps: ${stats.frameRate.formatForLog(1)}, encode: ${stats.avgEncodeTimeMs.formatForLog(1)}ms, " +
                "limitation: ${stats.qualityLimitationReason})")

        currentQuality = newQuality
        lastQualityChangeTimestamp = currentTimeMillis()
        applyQuality(newQuality)
    }

    private suspend fun upgradeQuality() {
        val newQuality = currentQuality.upgrade()
        if (newQuality == null) {
            println("QUALITY_CONTROLLER: ✅ Already at maximum quality")
            return
        }

        println("QUALITY_CONTROLLER: 📈 Upgrading $currentQuality -> $newQuality")
        currentQuality = newQuality
        lastQualityChangeTimestamp = currentTimeMillis()
        applyQuality(newQuality)
    }

    private suspend fun applyQuality(quality: QualityLevel) {
        // Apply encoder configuration via RTP sender
        peerConnection.setEncoderConfiguration(quality.config)

        // Notify about frame rate change (for capture source adjustment)
        onFrameRateChange?.invoke(quality.captureFramerate.coerceAtMost(maxCaptureFramerate))
    }

    private companion object {
        private const val EVALUATION_INTERVAL_MS = 2_000L
        private const val QUALITY_CHANGE_COOLDOWN_MS = 8_000L
    }
}

private fun Double.formatForLog(decimals: Int): String {
    if (decimals <= 0) return round(this).toInt().toString()
    val multiplier = 10.0.pow(decimals)
    val rounded = round(this * multiplier) / multiplier
    return rounded.toString()
}
