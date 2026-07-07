package com.teachmint.sharex.share.shared

const val MAX_CONNECTED_CLIENTS = 4

const val SCREEN_CASTING_BLOCKED_MESSAGE =
    "Screen casting is blocked for your device. Ask the host to allow casting."

const val REVERSE_CASTING_BLOCKED_MESSAGE =
    "Reverse casting is blocked by host. Ask the host to allow reverse casting."

const val SCREEN_CAPTURE_PERMISSION_DENIED_MESSAGE =
    "Screen capture permission was denied or cancelled."

const val CLIENT_SCREEN_CAPTURE_PERMISSION_DENIED_MESSAGE =
    "Client cancelled screen capture permission. Screen casting was not started."

const val HOST_SCREEN_CAPTURE_PERMISSION_DENIED_MESSAGE =
    "Host cancelled screen capture permission. Reverse casting was not started."

const val SCREEN_CAST_START_TIMEOUT_MESSAGE =
    "Screen casting could not start in time. Please try again."

const val REVERSE_CAST_START_TIMEOUT_MESSAGE =
    "Reverse casting could not start in time. Please try again."

const val HOST_CAPTURE_INTERRUPTED_RECONNECTING_MESSAGE =
    "Host screen capture was interrupted. Reconnecting…"

const val HOST_CAPTURE_INTERRUPTED_GIVEUP_MESSAGE =
    "Host screen is unavailable right now. Tap start to try again."

const val MULTI_DEVICE_CAST_DISABLED_MESSAGE =
    "Multiple device cast is disabled by host. Ask the host to enable it."

const val CLIENT_REMOVED_BY_HOST_MESSAGE =
    "Host removed this device from connection."

const val CLIENT_CONNECTION_REQUEST_NOT_ACCEPTED_MESSAGE =
    "Your connection request was not accepted at the moment, please try again!"

const val CLIENT_INCORRECT_PIN_MESSAGE =
    "Please enter correct pin."

const val HOST_CONNECTION_LIMIT_TOAST_MESSAGE =
    "New device blocked, 4 already connected.\nDisconnect one to allow."

const val CLIENT_CONNECTION_LIMIT_MESSAGE = "can't connect"

// Backward-compatible disconnect marker sent via SignalingMessage.Error so
// older signaling relays can still notify host UI to remove the client.
const val CLIENT_DISCONNECTED_SIGNAL_MESSAGE = "__sharex_client_disconnected__"

// Server-to-host marker for PIN collisions so host can auto-rotate.
const val SERVER_PIN_COLLISION_SIGNAL_PREFIX = "__sharex_pin_collision__"

fun isServerPinCollisionSignal(message: String?): Boolean {
    val normalized = message?.trim().orEmpty()
    return normalized.startsWith(SERVER_PIN_COLLISION_SIGNAL_PREFIX)
}

fun extractCollidingPinFromSignal(message: String?): String? {
    if (!isServerPinCollisionSignal(message)) return null
    return message
        ?.substringAfter(':', missingDelimiterValue = "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}
