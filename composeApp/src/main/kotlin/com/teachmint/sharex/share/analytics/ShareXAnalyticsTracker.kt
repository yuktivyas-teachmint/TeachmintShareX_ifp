package com.teachmint.sharex.share.analytics

import com.teachmint.sharex.share.shared.APP_VERSION
import com.teachmint.sharex.share.shared.createHttpClient
import com.teachmint.sharex.share.shared.currentTimeMillis

class ShareXAnalyticsTracker(
    private val api: AnalyticsApi = KtorAnalyticsApi(createHttpClient()),
    private val identityResolver: () -> AnalyticsIdentity = ::resolveAnalyticsIdentity,
) {
    suspend fun trackEvent(
        eventId: String,
        shareCode: String? = null,
        connectedDeviceCount: Int? = null,
        numberOfScreens: Int? = null,
        deviceType: String? = null,
        deviceName: String? = null,
        hostDeviceName: String? = null,
        mode: String? = null,
        senderDeviceId: String? = null,
        senderDeviceName: String? = null,
        status: String? = null,
        type: String? = null,
        eventTag: String = AnalyticsEventTag.INFO,
    ) {
        val identity = identityResolver()
        val normalizedShareCode = shareCode?.trim()?.takeIf { it.isNotBlank() }
        val normalizedConnectedDeviceCount = connectedDeviceCount
            ?.coerceAtLeast(0)
            ?.toString()
        val normalizedNumberOfScreens = numberOfScreens
            ?.coerceAtLeast(0)
            ?.toString()
        val normalizedDeviceName = deviceName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedHostDeviceName = hostDeviceName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedMode = mode?.trim()?.takeIf { it.isNotBlank() }
        val normalizedSenderDeviceId = senderDeviceId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedSenderDeviceName = senderDeviceName?.trim()?.takeIf { it.isNotBlank() }
        val normalizedStatus = status?.trim()?.takeIf { it.isNotBlank() }
        val normalizedType = type?.trim()?.takeIf { it.isNotBlank() }
        val resolvedDeviceType = when {
            !deviceType.isNullOrBlank() -> deviceType
            normalizedShareCode != null || normalizedConnectedDeviceCount != null -> identity.deviceType
            else -> null
        }

        val payload = AnalyticsEventPayload(
            device_id = identity.uniqueDeviceId,
            serial_number = identity.serialNumber,
            app_id = identity.appId,
            app_version = identity.appVersion.ifBlank { APP_VERSION },
            brand = identity.brand,
            model = identity.model,
            t = (currentTimeMillis() / 1000L).toString(),
            device_type = resolvedDeviceType,
            share_code = normalizedShareCode,
            number_of_devices_connected = normalizedConnectedDeviceCount,
            device_name = normalizedDeviceName,
            status = normalizedStatus,
            number_of_screens = normalizedNumberOfScreens,
            sender_device_id = normalizedSenderDeviceId,
            sender_device_name = normalizedSenderDeviceName,
            host_device_name = normalizedHostDeviceName,
            mode = normalizedMode,
            type = normalizedType,
        )

        api.postEvent(
            eventId = eventId,
            eventTag = eventTag,
            payload = payload,
            identity = identity,
        ).onFailure { error ->
            println("ANALYTICS: Failed to send $eventId: ${error.message}")
        }
    }
}

object ShareXAnalytics {
    private val tracker: ShareXAnalyticsTracker by lazy {
        ShareXAnalyticsTracker()
    }

    fun tracker(): ShareXAnalyticsTracker = tracker
}
