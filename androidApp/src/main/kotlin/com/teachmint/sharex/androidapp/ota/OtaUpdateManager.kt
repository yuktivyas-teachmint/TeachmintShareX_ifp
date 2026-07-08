package com.teachmint.sharex.androidapp.ota

import android.content.Context
import android.util.Log
import com.teachmint.ota.Ota
import com.teachmint.ota.model.UpdateState
import com.teachmint.sharex.remoteconfig.RemoteConfigManager
import org.json.JSONObject

/**
 * Drives OTA updates from the Firebase Remote Config key
 * [com.teachmint.sharex.remoteconfig.ShareXRemoteConfig.KEY_OTA_UPDATE]
 * (`{"version_number": "1.0.0", "apk_url": "https://..."}`) instead of the
 * OTA library's own backend polling (disabled in ShareXApplication).
 *
 * [checkForUpdate] refreshes remote config, and when the published version is
 * newer than the installed one hands the APK URL to [Ota.downloadFromUrl] —
 * from there the library's existing state machine (download → verify →
 * Available → dialog / boot install) takes over.
 */
object OtaUpdateManager {
    private const val TAG = "OtaUpdateManager"

    /** How long the app may stay in foreground before re-checking. */
    const val FOREGROUND_POLL_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * Firebase-side fetch cache. Shorter than the poll interval so every poll
     * hits the network, but still throttles rapid app open/close cycles.
     */
    private const val FETCH_MIN_INTERVAL_SECONDS = 600L

    suspend fun checkForUpdate(context: Context) {
        if (!RemoteConfigManager.config.enableOtaUpdates) {
            Log.d(TAG, "OTA updates disabled via remote config")
            return
        }

        RemoteConfigManager.fetchAndActivate(minimumFetchIntervalSeconds = FETCH_MIN_INTERVAL_SECONDS)

        val payload = parsePayload(RemoteConfigManager.config.otaUpdateJson) ?: return
        if (payload.apkUrl.isBlank()) {
            Log.d(TAG, "ota_update has no apk_url, skipping")
            return
        }

        val currentVersion = installedVersionName(context)
        if (compareVersions(payload.versionNumber, currentVersion) <= 0) {
            Log.d(TAG, "Up to date (installed=$currentVersion, remote=${payload.versionNumber})")
            return
        }

        // Don't restart the pipeline if a download/install/prompt is already in flight.
        val state = Ota.state.value
        if (state !is UpdateState.Idle && state !is UpdateState.UpToDate) {
            Log.d(TAG, "OTA busy (state=${state::class.simpleName}), skipping trigger")
            return
        }

        Log.i(
            TAG,
            "Update ${payload.versionNumber} available (installed $currentVersion), downloading ${payload.apkUrl}",
        )
        Ota.downloadFromUrl(payload.apkUrl)
    }

    internal data class OtaUpdatePayload(
        val versionNumber: String,
        val apkUrl: String,
    )

    internal fun parsePayload(json: String): OtaUpdatePayload? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            OtaUpdatePayload(
                versionNumber = obj.optString("version_number").trim(),
                apkUrl = obj.optString("apk_url").trim(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Invalid ota_update payload: $json", e)
            null
        }
    }

    private fun installedVersionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"

    /** Numeric segment-wise comparison: "1.0.10" > "1.0.9". Missing segments count as 0. */
    internal fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val diff = (aParts.getOrElse(i) { 0 }).compareTo(bParts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
