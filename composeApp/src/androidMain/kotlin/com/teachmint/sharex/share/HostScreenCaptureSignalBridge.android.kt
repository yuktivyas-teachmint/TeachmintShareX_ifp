package com.teachmint.sharex.share.shared

/**
 * Public bridge for androidApp module to notify host-side screenshot detection events.
 */
fun notifyHostScreenCaptureDetected(source: String) {
    HostScreenCaptureSignal.notifyCaptureDetected(source)
}
