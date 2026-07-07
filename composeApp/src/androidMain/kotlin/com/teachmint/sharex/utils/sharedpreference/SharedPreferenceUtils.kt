package com.teachmint.sharex.utils.sharedpreference

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.teachmint.sharex.share.shared.AndroidContextHolder

actual object SharedPreferenceUtils {
    private const val PREF_FILE_NAME = "teachmint_sharex_preferences"
    private const val SECURE_PREF_FILE_NAME = "teachmint_sharex_secure_preferences"

    // V-003: Sensitive keys that must be stored in EncryptedSharedPreferences
    private val SENSITIVE_KEYS: Set<String> = emptySet()

    private val preferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val context = requireNotNull(AndroidContextHolder.applicationContext) {
            "AndroidContextHolder is not initialized. Call AndroidContextHolder.init(context) " +
                "before using SharedPreferenceUtils."
        }
        context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
    }

    // V-003: EncryptedSharedPreferences for sensitive credential storage
    private val securePreferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val context = requireNotNull(AndroidContextHolder.applicationContext) {
            "AndroidContextHolder is not initialized. Call AndroidContextHolder.init(context) " +
                "before using SharedPreferenceUtils."
        }
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREF_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Fallback to regular preferences if encryption fails (e.g., on very old devices)
            println("WARNING: Failed to create EncryptedSharedPreferences, falling back to regular: ${e.message}")
            context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun prefsFor(key: String): SharedPreferences =
        if (key in SENSITIVE_KEYS) securePreferences else preferences

    private inline fun edit(key: String, block: SharedPreferences.Editor.() -> Unit) {
        prefsFor(key).edit().apply(block).apply()
    }

    actual fun writeString(key: String, value: String?) {
        edit(key) { putString(key, value) }
    }

    actual fun readString(key: String, defaultValue: String?): String? {
        return prefsFor(key).getString(key, defaultValue) ?: defaultValue
    }

    actual fun writeInt(key: String, value: Int) {
        edit(key) { putInt(key, value) }
    }

    actual fun readInt(key: String, defaultValue: Int): Int {
        return prefsFor(key).getInt(key, defaultValue)
    }

    actual fun writeLong(key: String, value: Long) {
        edit(key) { putLong(key, value) }
    }

    actual fun readLong(key: String, defaultValue: Long): Long {
        return prefsFor(key).getLong(key, defaultValue)
    }

    actual fun writeFloat(key: String, value: Float) {
        edit(key) { putFloat(key, value) }
    }

    actual fun readFloat(key: String, defaultValue: Float): Float {
        return prefsFor(key).getFloat(key, defaultValue)
    }

    actual fun writeBoolean(key: String, value: Boolean) {
        edit(key) { putBoolean(key, value) }
    }

    actual fun readBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefsFor(key).getBoolean(key, defaultValue)
    }

    actual fun writeStringSet(key: String, value: Set<String>?) {
        edit(key) { putStringSet(key, value?.toSet()) }
    }

    actual fun readStringSet(key: String, defaultValue: Set<String>?): Set<String>? {
        return prefsFor(key).getStringSet(key, defaultValue?.toSet())?.toSet() ?: defaultValue
    }

    actual fun contains(key: String): Boolean {
        return prefsFor(key).contains(key)
    }

    actual fun remove(key: String) {
        edit(key) { remove(key) }
    }

    actual fun clear() {
        preferences.edit().clear().apply()
        securePreferences.edit().clear().apply()
    }
}
