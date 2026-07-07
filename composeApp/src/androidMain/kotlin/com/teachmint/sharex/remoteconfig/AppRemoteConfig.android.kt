package com.teachmint.sharex.remoteconfig

actual fun isDifferentWifiConnectionEnabled(): Boolean =
    RemoteConfigManager.config.enableDifferentWifiConnection

actual fun isFileShareOnDifferentNetworkEnabled(): Boolean =
    RemoteConfigManager.config.enableFileShareOnDifferentNetwork
