package com.teachmint.sharex.utils.sharedpreference

expect object SharedPreferenceUtils {
    fun writeString(key: String, value: String?)
    fun readString(key: String, defaultValue: String? = null): String?

    fun writeInt(key: String, value: Int)
    fun readInt(key: String, defaultValue: Int = 0): Int

    fun writeLong(key: String, value: Long)
    fun readLong(key: String, defaultValue: Long = 0L): Long

    fun writeFloat(key: String, value: Float)
    fun readFloat(key: String, defaultValue: Float = 0f): Float

    fun writeBoolean(key: String, value: Boolean)
    fun readBoolean(key: String, defaultValue: Boolean = false): Boolean

    fun writeStringSet(key: String, value: Set<String>?)
    fun readStringSet(key: String, defaultValue: Set<String>? = null): Set<String>?

    fun contains(key: String): Boolean
    fun remove(key: String)
    fun clear()
}
