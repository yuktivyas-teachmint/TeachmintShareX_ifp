package com.teachmint.sharex.remoteconfig

data class ShareXRemoteConfig(
    val enableDifferentWifiConnection: Boolean = true,
    val enableFileShareOnDifferentNetwork: Boolean = true,
) {
    companion object {
        const val KEY_ENABLE_DIFFERENT_WIFI_CONNECTION = "enable_different_wifi_connection"
        const val KEY_ENABLE_FILE_SHARE_ON_DIFFERENT_NETWORK = "enable_file_share_on_different_network"

        val DEFAULT = ShareXRemoteConfig()
    }
}
