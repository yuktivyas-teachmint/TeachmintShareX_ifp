package com.teachmint.sharex.airplay

import android.util.Log

/**
 * JNI wrapper for RPiPlay's playfair C library.
 *
 * Extracts the 16-byte AES key from FairPlay handshake data:
 *   playfair_decrypt(message3[164], cipherText[72]) → aesKey[16]
 */
object PlayfairNative {

    private const val TAG = "AirPlay/Playfair"

    init {
        try {
            System.loadLibrary("playfair_jni")
            Log.d(TAG, "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

    /**
     * Decrypts the FairPlay AES key from the M3 handshake data and ekey cipher text.
     *
     * @param message3   164-byte M3 body from fp-setup phase 2
     * @param cipherText 72-byte ekey from SETUP binary plist
     * @return 16-byte AES key, or null on failure
     */
    fun decrypt(message3: ByteArray, cipherText: ByteArray): ByteArray? {
        if (message3.size != 164) {
            Log.e(TAG, "Invalid M3 size: ${message3.size} (expected 164)")
            return null
        }
        if (cipherText.size != 72) {
            Log.e(TAG, "Invalid cipherText size: ${cipherText.size} (expected 72)")
            return null
        }
        return try {
            val key = nativeDecrypt(message3, cipherText)
            Log.d(TAG, "Decrypted AES key (${key.size}B): ${key.joinToString("") { "%02x".format(it) }}")
            key
        } catch (e: Exception) {
            Log.e(TAG, "playfair_decrypt failed: ${e.message}")
            null
        }
    }

    private external fun nativeDecrypt(message3: ByteArray, cipherText: ByteArray): ByteArray
}
