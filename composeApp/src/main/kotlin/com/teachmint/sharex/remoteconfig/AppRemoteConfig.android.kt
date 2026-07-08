package com.teachmint.sharex.remoteconfig

fun isDifferentWifiConnectionEnabled(): Boolean =
    RemoteConfigManager.config.enableDifferentWifiConnection

fun isFileShareOnDifferentNetworkEnabled(): Boolean =
    RemoteConfigManager.config.enableFileShareOnDifferentNetwork
