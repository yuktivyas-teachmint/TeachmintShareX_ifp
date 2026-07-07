package com.teachmint.sharex.share.shared

/**
 * Device role in the screen sharing architecture
 */
enum class DeviceRole {
    HOST,   // IFP/Smart Display - can share screen to clients
    CLIENT  // Mobile/Desktop - can view shared screen
}

/**
 * Platform-specific device type detection
 * Returns true if device is an IFP (Interactive Flat Panel) without sensors
 */
expect fun isInteractiveFlatPanel(): Boolean

/**
 * Determine device role based on hardware capabilities
 * Platform implementations may apply additional product eligibility checks
 * before allowing HOST mode.
 */
fun getDeviceRole(): DeviceRole {
    return if (isInteractiveFlatPanel()) {
        DeviceRole.HOST
    } else {
        DeviceRole.CLIENT
    }
}
