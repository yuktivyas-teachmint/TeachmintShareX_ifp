package com.teachmint.sharex.share.analytics

import android.os.Build
import com.teachmint.sharex.share.shared.APP_VERSION

actual fun resolveAnalyticsIdentity(): AnalyticsIdentity {
    // M-2: Do not use ANDROID_ID — it is a persistent device identifier that may
    // violate COPPA/GDPR in education contexts. Use a random UUID generated on first
    // launch instead, persisted locally via resolveOrCreatePersistedDeviceId.
    val uniqueDeviceId = resolveOrCreatePersistedDeviceId(candidate = null)
    val serialNumber = resolveOrCreatePersistedSerialNumber(
        candidate = resolveAndroidSerial(),
        fallbackDeviceId = uniqueDeviceId,
    )

    val brand = sanitizeAnalyticsValue(Build.BRAND) ?: "Teachmint X"
    val model = sanitizeAnalyticsValue(Build.MODEL) ?: "Teachmint X"

    return AnalyticsIdentity(
        serialNumber = serialNumber,
        uniqueDeviceId = uniqueDeviceId,
        appId = AnalyticsConfig.APP_ID,
        appVersion = APP_VERSION,
        brand = brand,
        model = model,
        deviceType = resolveDeviceTypeLabel(),
    )
}

private fun resolveAndroidSerial(): String? {
    val rawSerial = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Build.getSerial()
        } else {
            @Suppress("DEPRECATION")
            Build.SERIAL
        }
    }.getOrNull()

    val normalized = sanitizeAnalyticsValue(rawSerial)
    if (normalized == null || normalized.equals(Build.UNKNOWN, ignoreCase = true)) {
        return null
    }

    return normalized
}
