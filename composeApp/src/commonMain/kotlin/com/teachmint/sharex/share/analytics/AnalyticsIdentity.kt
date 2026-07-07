package com.teachmint.sharex.share.analytics

import com.teachmint.sharex.share.shared.DeviceRole
import com.teachmint.sharex.share.shared.generateClientId
import com.teachmint.sharex.share.shared.getDeviceRole
import com.teachmint.sharex.utils.sharedpreference.SharedPreferenceUtils

data class AnalyticsIdentity(
    val serialNumber: String,
    val uniqueDeviceId: String,
    val appId: String,
    val appVersion: String,
    val brand: String,
    val model: String,
    val deviceType: String,
)

expect fun resolveAnalyticsIdentity(): AnalyticsIdentity

private const val ANALYTICS_UNIQUE_DEVICE_ID_KEY = "analytics_unique_device_id"
private const val ANALYTICS_SERIAL_NUMBER_KEY = "analytics_serial_number"

internal fun resolveOrCreatePersistedDeviceId(candidate: String?): String {
    val normalizedCandidate = sanitizeAnalyticsValue(candidate)
    if (normalizedCandidate != null) {
        SharedPreferenceUtils.writeString(ANALYTICS_UNIQUE_DEVICE_ID_KEY, normalizedCandidate)
        return normalizedCandidate
    }

    val stored = sanitizeAnalyticsValue(
        SharedPreferenceUtils.readString(ANALYTICS_UNIQUE_DEVICE_ID_KEY, null),
    )
    if (stored != null) {
        return stored
    }

    val generated = generateClientId().replace("-", "")
    SharedPreferenceUtils.writeString(ANALYTICS_UNIQUE_DEVICE_ID_KEY, generated)
    return generated
}

internal fun resolveOrCreatePersistedSerialNumber(
    candidate: String?,
    fallbackDeviceId: String,
): String {
    val normalizedCandidate = sanitizeAnalyticsValue(candidate)
    if (normalizedCandidate != null) {
        SharedPreferenceUtils.writeString(ANALYTICS_SERIAL_NUMBER_KEY, normalizedCandidate)
        return normalizedCandidate
    }

    val stored = sanitizeAnalyticsValue(
        SharedPreferenceUtils.readString(ANALYTICS_SERIAL_NUMBER_KEY, null),
    )
    if (stored != null) {
        return stored
    }

    val generated = "SN-${fallbackDeviceId.takeLast(16).uppercase()}"
    SharedPreferenceUtils.writeString(ANALYTICS_SERIAL_NUMBER_KEY, generated)
    return generated
}

internal fun resolveDeviceTypeLabel(): String = when (getDeviceRole()) {
    DeviceRole.HOST -> "receiver"
    DeviceRole.CLIENT -> "client"
}

internal fun sanitizeAnalyticsValue(value: String?): String? {
    val cleaned = value?.trim() ?: return null
    if (cleaned.isBlank()) return null
    if (cleaned.equals("unknown", ignoreCase = true)) return null
    return cleaned
}
