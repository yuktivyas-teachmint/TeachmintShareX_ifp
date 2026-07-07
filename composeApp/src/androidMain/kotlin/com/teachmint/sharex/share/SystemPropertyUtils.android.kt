package com.teachmint.sharex.share.shared

import android.util.Log

/**
 * Reflection-based wrapper for Android system properties.
 * Uses hidden APIs safely with graceful fallback.
 */
object SystemPropertyUtils {
    private const val TAG = "SystemPropertyUtils"

    fun getSystemProperty(key: String, defaultValue: String): String {
        val value = runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java)
            getMethod.invoke(null, key) as? String
        }.getOrNull()

        return value?.takeIf { it.isNotBlank() } ?: defaultValue
    }

    fun setSystemProperty(key: String, value: String) {
        runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, key, value)
            Log.i(TAG, "setSystemProperty: $key=${getSystemProperty(key, "")}")
        }.onFailure {
            Log.d(TAG, "Unable to write system property: $key")
        }
    }

    fun getSystemPropertyForEvents(key: String, defaultValue: String): String {
        return runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod =
                systemPropertiesClass.getDeclaredMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, defaultValue) as? String
        }.getOrNull() ?: defaultValue
    }
}
