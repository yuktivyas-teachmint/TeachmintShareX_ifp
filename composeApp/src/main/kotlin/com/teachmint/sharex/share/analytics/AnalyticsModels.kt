package com.teachmint.sharex.share.analytics

import kotlinx.serialization.Serializable

object AnalyticsEventId {
    const val SHAREX_RECEIVER_LOADED: String = "SHAREX_RECEIVER_LOADED"
    const val SHAREX_CONNECTION_REQUESTED: String = "SHAREX_CONNECTION_REQUESTED"
    const val SHAREX_DIRECT_CASTING_ON: String = "SHAREX_DIRECT_CASTING_ON"
    const val SHAREX_CONTROL_ON: String = "SHARE_X_CONTROL_ON"
    const val SHAREX_RECIEVED_FILES_TAB: String = "SHAREX_RECIEVED_FILES_TAB"
    const val SHAREX_DEVICE_RENAMED: String = "SHAREX_DEVICE_RENAMED"
    const val SHAREX_SCREEN_CASTING_STARTED: String = "SHAREX_SCREEN_CASTING_STARTED"
    const val SHAREX_CONNECTION_LIMIT_REACHED: String = "SHAREX_CONNECTION_LIMIT_REACHED"
    const val SHAREX_DEVICE_PINNED: String = "SHAREX_DEVICE_PINNED"
    const val SHAREX_SCREEN_CAST_ON: String = "SHAREX_SCREEN_CAST_ON"
    const val SHAREX_CONNECT_DEVICE: String = "SHAREX_CONNECT_DEVICE"
    const val SHAREX_SENDER_DEVICE_CONNECTED: String = "SHAREX_SENDER_DEVICE_CONNECTED"
    const val SHAREX_DISCONNECT_DEVICE: String = "SHAREX_DISCONNECT_DEVICE"
    const val SHAREX_START_MIRRORING_CLICKED: String = "SHAREX_START_MIRRORING_CLICKED"
    const val SHAREX_BROADCASTING_STARTED: String = "SHAREX_BROADCASTING_STARTED"
    const val SHAREX_BROADCASTING_STOPPED: String = "SHAREX_BROADCASTING_STOPPED"
    const val SHAREX_REVERSE_SCREEN_CASTING: String = "SHAREX_REVERSE_SCREEN_CASTING"
    const val SHAREX_REVERSE_CASTING_STOPPED: String = "SHAREX_REVERSE_CASTING_STOPPED"
    const val SHAREX_NO_RECIEVER_FOUND: String = "SHAREX_NO_RECIEVER_FOUND"
}

object AnalyticsEventTag {
    const val INFO: String = "info"
}

object AnalyticsConfig {
    const val APP_ID: String = "baa41e77-c6eb-4726-bf01-749daff2aca1"
}

@Serializable
data class AnalyticsEventPayload(
    val device_id: String,
    val serial_number: String,
    val app_id: String,
    val app_version: String,
    val brand: String,
    val model: String,
    val t: String,
    // Event-specific fields. Add new optional fields here as new event contracts require them.
    val device_type: String? = null,
    val share_code: String? = null,
    val number_of_devices_connected: String? = null,
    val device_name: String? = null,
    val status: String? = null,
    val number_of_screens: String? = null,
    val sender_device_id: String? = null,
    val sender_device_name: String? = null,
    val host_device_name: String? = null,
    val mode: String? = null,
    val type: String? = null,
)
