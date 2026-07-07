package com.teachmint.sharex.share.shared

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide signal bus for host-side screenshot/screen-record detection events.
 *
 * MainActivity increments this when Android reports that the app window was captured.
 * The screen capturer polls this version and proactively rebinds VirtualDisplay to
 * avoid stale-mirror freezes observed on some OEM Android 14+ builds.
 */
internal object HostScreenCaptureSignal {
    private val version = AtomicLong(0L)

    fun notifyCaptureDetected(source: String) {
        val next = version.incrementAndGet()
        println("SCREEN_CAPTURE_SIGNAL: detected source=$source version=$next")
    }

    fun currentVersion(): Long = version.get()
}
