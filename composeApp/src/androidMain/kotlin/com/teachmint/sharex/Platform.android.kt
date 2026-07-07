package com.teachmint.sharex

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun getTimezoneOffsetMs(epochMs: Long): Int =
    java.util.TimeZone.getDefault().getOffset(epochMs)