package com.teachmint.sharex

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * Returns the local timezone offset in milliseconds for the given epoch time.
 */
expect fun getTimezoneOffsetMs(epochMs: Long): Int