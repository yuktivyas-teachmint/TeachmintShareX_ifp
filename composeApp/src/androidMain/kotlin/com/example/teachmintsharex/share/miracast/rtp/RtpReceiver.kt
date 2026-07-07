package com.example.teachmintsharex.share.miracast.rtp

/**
 * Interface for RTP receiver implementations
 * Allows switching between native C++ and pure Kotlin implementations
 */
interface RtpReceiver {
    /**
     * Start receiving RTP packets
     * @return Result indicating success or failure
     */
    suspend fun start(): Result<Unit>

    /**
     * Stop receiving RTP packets
     */
    fun stop()

    /**
     * Check if receiver is currently running
     */
    fun isRunning(): Boolean
}

/**
 * Factory for creating RTP receiver instances
 * Automatically chooses best available implementation
 */
object RtpReceiverFactory {
    private const val TAG = "RtpReceiverFactory"

    /**
     * Create an RTP receiver
     * @param port UDP port to listen on
     * @param onH264Data Callback for received H.264 data
     * @return RTP receiver instance (native if available, Kotlin fallback)
     */
    fun create(
        port: Int,
        onH264Data: (data: ByteArray, ptsUs: Long, size: Int, isKeyFrame: Boolean) -> Unit,
        onAudioData: ((data: ByteArray, ptsUs: Long) -> Unit)? = null
    ): RtpReceiver {
        // Try native implementation first (better performance)
        return try {
            if (NativeRtpReceiver.isAvailable()) {
                android.util.Log.d(TAG, "✨ Using native C++ RTP receiver (optimized)")
                NativeRtpReceiver(port, onH264Data, onAudioData)
            } else {
                android.util.Log.w(TAG, "⚠️ Native RTP not available, falling back to Kotlin implementation")
                KotlinRtpReceiver(port, onH264Data, onAudioData)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to create native receiver, using Kotlin fallback", e)
            KotlinRtpReceiver(port, onH264Data, onAudioData)
        }
    }

    /**
     * Force use of native RTP receiver
     * @throws UnsatisfiedLinkError if native library not available
     */
    fun createNative(
        port: Int,
        onH264Data: (data: ByteArray, ptsUs: Long, size: Int, isKeyFrame: Boolean) -> Unit,
        onAudioData: ((data: ByteArray, ptsUs: Long) -> Unit)? = null
    ): NativeRtpReceiver {
        return NativeRtpReceiver(port, onH264Data, onAudioData)
    }

    /**
     * Force use of Kotlin RTP receiver
     */
    fun createKotlin(
        port: Int,
        onH264Data: (data: ByteArray, ptsUs: Long, size: Int, isKeyFrame: Boolean) -> Unit,
        onAudioData: ((data: ByteArray, ptsUs: Long) -> Unit)? = null
    ): KotlinRtpReceiver {
        return KotlinRtpReceiver(port, onH264Data, onAudioData)
    }
}
