package com.example.teachmintsharex.share.miracast.rtp

import android.util.Log
import java.nio.ByteBuffer

/**
 * Native RTP receiver using C++ implementation via JNI
 * Provides better performance than pure Kotlin implementation
 */
class NativeRtpReceiver(
    private val port: Int,
    private val onH264Sample: (data: ByteArray, ptsUs: Long, size: Int, isKeyFrame: Boolean) -> Unit,
    private val onAudioSample: ((data: ByteArray, ptsUs: Long) -> Unit)? = null
) : RtpReceiver {

    private var nativeHandle: Long = 0
    private var isInitialized = false

    companion object {
        private const val TAG = "NativeRtpReceiver"

        // Load native library
        init {
            try {
                System.loadLibrary("miracast-rtp-receiver")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
        }

        /**
         * Check if native RTP receiver is available
         */
        fun isAvailable(): Boolean {
            return try {
                System.loadLibrary("miracast-rtp-receiver")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }
    }

    init {
        // Create callback object for JNI
        val callback = object {
            @Suppress("unused") // Called from JNI
            fun onH264Data(data: ByteArray, ptsUs: Long, size: Int, isKeyFrame: Boolean) {
                try {
                    onH264Sample(data, ptsUs, size, isKeyFrame)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in H264 callback", e)
                }
            }

            @Suppress("unused") // Called from JNI
            fun onAudioData(data: ByteArray, ptsUs: Long) {
                try {
                    onAudioSample?.invoke(data, ptsUs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio callback", e)
                }
            }
        }

        nativeHandle = nativeInit(port, callback)
        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to initialize native RTP receiver on port $port")
        }
        isInitialized = true
        Log.d(TAG, "✅ Native RTP receiver initialized on port $port (handle: $nativeHandle)")
    }

    override suspend fun start(): Result<Unit> {
        if (!isInitialized) {
            return Result.failure(IllegalStateException("Native receiver not initialized"))
        }

        return try {
            val success = nativeStart(nativeHandle)
            if (success) {
                Log.d(TAG, "📡 Native RTP receiver started on port $port")
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Failed to start native RTP receiver"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting native receiver", e)
            Result.failure(e)
        }
    }

    override fun stop() {
        if (!isInitialized || nativeHandle == 0L) {
            return
        }

        try {
            nativeStop(nativeHandle)
            Log.d(TAG, "🛑 Native RTP receiver stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native receiver", e)
        }
    }

    override fun isRunning(): Boolean {
        if (!isInitialized || nativeHandle == 0L) {
            return false
        }

        return try {
            nativeIsRunning(nativeHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if running", e)
            false
        }
    }

    /**
     * Get statistics
     */
    fun getPacketCount(): Long {
        if (!isInitialized || nativeHandle == 0L) {
            return 0
        }

        return try {
            nativeGetPacketCount(nativeHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting packet count", e)
            0
        }
    }

    fun getBytesReceived(): Long {
        if (!isInitialized || nativeHandle == 0L) {
            return 0
        }

        return try {
            nativeGetBytesReceived(nativeHandle)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting bytes received", e)
            0
        }
    }

    /**
     * Close and cleanup native resources
     */
    fun close() {
        if (!isInitialized || nativeHandle == 0L) {
            return
        }

        try {
            stop()
            nativeDestroy(nativeHandle)
            nativeHandle = 0
            isInitialized = false
            Log.d(TAG, "🗑️ Native RTP receiver destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying native receiver", e)
        }
    }

    protected fun finalize() {
        close()
    }

    // JNI native methods
    private external fun nativeInit(port: Int, callback: Any): Long
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeGetPacketCount(handle: Long): Long
    private external fun nativeGetBytesReceived(handle: Long): Long
    private external fun nativeDestroy(handle: Long)
}
