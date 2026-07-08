package com.teachmint.sharex.remoteconfig

data class ShareXRemoteConfig(
    val enableDifferentWifiConnection: Boolean = true,
    val enableFileShareOnDifferentNetwork: Boolean = true,
    val enableOtaUpdates: Boolean = true,
    /** JSON payload: {"version_number": "1.0.0", "apk_url": "https://..."} */
    val otaUpdateJson: String = "",
) {
    companion object {
        const val KEY_ENABLE_DIFFERENT_WIFI_CONNECTION = "enable_different_wifi_connection"
        const val KEY_ENABLE_FILE_SHARE_ON_DIFFERENT_NETWORK = "enable_file_share_on_different_network"
        const val KEY_ENABLE_OTA_UPDATES = "enable_ota_updates"
        const val KEY_OTA_UPDATE = "ota_update"

        val DEFAULT = ShareXRemoteConfig()
    }
}
