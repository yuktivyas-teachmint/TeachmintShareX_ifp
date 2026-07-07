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
actual object CrashlyticsLogger {

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

    actual fun setAppRole(role: String) {
        crashlytics?.setCustomKey("app_role", role)
    }

    actual fun setCurrentScreen(screen: String) {
        crashlytics?.setCustomKey("current_screen", screen)
        log("Screen: $screen")
    }

    actual fun setConnectedClients(count: Int) {
        crashlytics?.setCustomKey("connected_clients", count)
    }

    actual fun setConnectionState(state: String) {
        crashlytics?.setCustomKey("connection_state", state)
    }

    actual fun setUserId(id: String) {
        crashlytics?.setUserId(id)
    }

    // ── Breadcrumb logs ──────────────────────────────────────────────────

    actual fun log(message: String) {
        crashlytics?.log(message)
    }

    // ── Host-specific breadcrumbs ────────────────────────────────────────

    actual fun logHostStarted(pin: String) {
        log("Host: server started, pin=$pin")
    }

    actual fun logHostClientConnected(clientId: String, clientName: String) {
        log("Host: client connected id=$clientId name=$clientName")
    }

    actual fun logHostAcceptShareRequest(clientId: String) {
        log("Host: accepted share request from $clientId")
    }

    actual fun logHostRejectShareRequest(clientId: String) {
        log("Host: rejected share request from $clientId")
    }

    actual fun logHostStopSharingForClient(clientId: String) {
        log("Host: stopped sharing for $clientId")
    }

    actual fun logHostAudioMuteToggle(clientId: String, muted: Boolean) {
        log("Host: audio ${if (muted) "muted" else "unmuted"} for $clientId")
    }

    actual fun logHostRemoteControlApproved(clientId: String) {
        log("Host: remote control approved for $clientId")
    }

    actual fun logHostRemoteControlDenied(clientId: String) {
        log("Host: remote control denied for $clientId")
    }

    // ── Client-specific breadcrumbs ──────────────────────────────────────

    actual fun logClientConnectToHost(hostName: String, mode: String) {
        log("Client: connecting to $hostName via $mode")
    }

    actual fun logClientConnected(roomName: String) {
        log("Client: connected to room=$roomName")
    }

    actual fun logClientDisconnected() {
        log("Client: disconnected")
    }

    actual fun logClientStartMirroring() {
        log("Client: start mirroring")
    }

    actual fun logClientStopMirroring() {
        log("Client: stop mirroring")
    }

    actual fun logClientStartReverseMirroring() {
        log("Client: start reverse mirroring")
    }

    actual fun logClientRequestRemoteControl() {
        log("Client: requested remote control")
    }

    actual fun logClientFileTransfer(fileName: String, state: String) {
        log("Client: file transfer '$fileName' $state")
    }
}
