package com.teachmint.sharex.crashlytics

/**
 * Multiplatform Crashlytics logger. Android uses Firebase Crashlytics;
 * other platforms are no-ops.
 */
expect object CrashlyticsLogger {
    fun setAppRole(role: String)
    fun setCurrentScreen(screen: String)
    fun setConnectedClients(count: Int)
    fun setConnectionState(state: String)
    fun setUserId(id: String)
    fun log(message: String)

    // Host breadcrumbs
    fun logHostStarted(pin: String)
    fun logHostClientConnected(clientId: String, clientName: String)
    fun logHostAcceptShareRequest(clientId: String)
    fun logHostRejectShareRequest(clientId: String)
    fun logHostStopSharingForClient(clientId: String)
    fun logHostAudioMuteToggle(clientId: String, muted: Boolean)
    fun logHostRemoteControlApproved(clientId: String)
    fun logHostRemoteControlDenied(clientId: String)

    // Client breadcrumbs
    fun logClientConnectToHost(hostName: String, mode: String)
    fun logClientConnected(roomName: String)
    fun logClientDisconnected()
    fun logClientStartMirroring()
    fun logClientStopMirroring()
    fun logClientStartReverseMirroring()
    fun logClientRequestRemoteControl()
    fun logClientFileTransfer(fileName: String, state: String)
}
