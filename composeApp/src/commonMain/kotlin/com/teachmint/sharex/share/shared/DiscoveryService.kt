package com.teachmint.sharex.share.shared

import kotlinx.coroutines.flow.StateFlow

expect class DiscoveryService() {
    val hosts: StateFlow<List<DiscoveredHost>>

    suspend fun startDiscovery()
    suspend fun stopDiscovery()

    suspend fun startBroadcast(hostInfo: HostInfo)
    suspend fun stopBroadcast()
}
