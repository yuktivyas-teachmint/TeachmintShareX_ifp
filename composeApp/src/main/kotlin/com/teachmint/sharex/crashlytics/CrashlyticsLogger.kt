package com.teachmint.sharex.crashlytics

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Android actual — delegates to Firebase Crashlytics.
 *
 * Sets custom keys (filterable in the dashboard) and breadcrumb logs (visible
 * in the crash timeline) so every crash report shows:
 *  - app_role: "host" or "client"
 *  - current_screen: last known screen name
 *  - last ~64 breadcrumb log entries leading up to the crash
 */
object CrashlyticsLogger {

    private val crashlytics: FirebaseCrashlytics?
        get() = try {
            FirebaseCrashlytics.getInstance()
        } catch (_: Exception) {
            // Firebase not initialized (no google-services.json in local builds)
            null
        }

    /**
     * Call once from Application.onCreate() to enable crash collection.
     * Gracefully no-ops when Firebase is absent.
     */
    fun initialize() {
        try {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
        } catch (_: Exception) {
            // Firebase not available in this build.
        }
    }

    // ── Custom keys ──────────────────────────────────────────────────────

    fun setAppRole(role: String) {
        crashlytics?.setCustomKey("app_role", role)
    }

    fun setCurrentScreen(screen: String) {
        crashlytics?.setCustomKey("current_screen", screen)
        log("Screen: $screen")
    }

    fun setConnectedClients(count: Int) {
        crashlytics?.setCustomKey("connected_clients", count)
    }

    fun setConnectionState(state: String) {
        crashlytics?.setCustomKey("connection_state", state)
    }

    fun setUserId(id: String) {
        crashlytics?.setUserId(id)
    }

    // ── Breadcrumb logs ──────────────────────────────────────────────────

    fun log(message: String) {
        crashlytics?.log(message)
    }

    // ── Host-specific breadcrumbs ────────────────────────────────────────

    fun logHostStarted(pin: String) {
        log("Host: server started, pin=$pin")
    }

    fun logHostClientConnected(clientId: String, clientName: String) {
        log("Host: client connected id=$clientId name=$clientName")
    }

    fun logHostAcceptShareRequest(clientId: String) {
        log("Host: accepted share request from $clientId")
    }

    fun logHostRejectShareRequest(clientId: String) {
        log("Host: rejected share request from $clientId")
    }

    fun logHostStopSharingForClient(clientId: String) {
        log("Host: stopped sharing for $clientId")
    }

    fun logHostAudioMuteToggle(clientId: String, muted: Boolean) {
        log("Host: audio ${if (muted) "muted" else "unmuted"} for $clientId")
    }

    fun logHostRemoteControlApproved(clientId: String) {
        log("Host: remote control approved for $clientId")
    }

    fun logHostRemoteControlDenied(clientId: String) {
        log("Host: remote control denied for $clientId")
    }

    // ── Client-specific breadcrumbs ──────────────────────────────────────

    fun logClientConnectToHost(hostName: String, mode: String) {
        log("Client: connecting to $hostName via $mode")
    }

    fun logClientConnected(roomName: String) {
        log("Client: connected to room=$roomName")
    }

    fun logClientDisconnected() {
        log("Client: disconnected")
    }

    fun logClientStartMirroring() {
        log("Client: start mirroring")
    }

    fun logClientStopMirroring() {
        log("Client: stop mirroring")
    }

    fun logClientStartReverseMirroring() {
        log("Client: start reverse mirroring")
    }

    fun logClientRequestRemoteControl() {
        log("Client: requested remote control")
    }

    fun logClientFileTransfer(fileName: String, state: String) {
        log("Client: file transfer '$fileName' $state")
    }
}
