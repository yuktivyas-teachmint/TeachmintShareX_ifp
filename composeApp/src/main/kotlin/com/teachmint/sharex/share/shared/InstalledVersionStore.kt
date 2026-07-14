package com.teachmint.sharex.share.shared

import android.content.Context
import android.content.SharedPreferences

/**
 * Fallback source for the installed app version when the APK's own versionName
 * can't be trusted: an OTA APK built without `-PappVersionName` is stamped with
 * the default versionName (e.g. "1.0.0"), so after a successful update
 * PackageManager still reports the old-looking version. That both shows a stale
 * version on the settings screen and makes OtaUpdateManager re-download the
 * same update on every poll.
 *
 * The remote-config version is recorded when a download is triggered, together
 * with the package's current lastUpdateTime. Once the package is re-installed
 * (lastUpdateTime advanced), the pending version is promoted to the "installed
 * override" and wins over the package versionName wherever it is newer.
 */
object InstalledVersionStore {
    private const val PREFS_NAME = "installed_version_store"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_PENDING_PACKAGE_UPDATE_TIME = "pending_package_update_time"
    private const val KEY_INSTALLED_OVERRIDE = "installed_version_override"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records that an OTA download for [version] was triggered. */
    fun onUpdateDownloadStarted(context: Context, version: String) {
        prefs(context).edit()
            .putString(KEY_PENDING_VERSION, version)
            .putLong(KEY_PENDING_PACKAGE_UPDATE_TIME, packageLastUpdateTime(context))
            .apply()
    }

    /**
     * Promotes the pending version to the installed override once the package
     * has been re-installed since the download was triggered. Safe to call
     * often; no-ops when nothing is pending or the package hasn't changed.
     */
    fun promotePendingIfInstalled(context: Context) {
        val p = prefs(context)
        val pending = p.getString(KEY_PENDING_VERSION, null) ?: return
        val recordedAt = p.getLong(KEY_PENDING_PACKAGE_UPDATE_TIME, Long.MAX_VALUE)
        if (packageLastUpdateTime(context) > recordedAt) {
            p.edit()
                .putString(KEY_INSTALLED_OVERRIDE, pending)
                .remove(KEY_PENDING_VERSION)
                .remove(KEY_PENDING_PACKAGE_UPDATE_TIME)
                .apply()
        }
    }

    /**
     * The effective installed version: the higher of the package versionName
     * and the last OTA-delivered version. Falls back to [packageVersion] when
     * no OTA update has been applied.
     */
    fun effectiveVersionName(context: Context, packageVersion: String): String {
        promotePendingIfInstalled(context)
        val override = prefs(context).getString(KEY_INSTALLED_OVERRIDE, null)
            ?.takeIf { it.isNotBlank() }
            ?: return packageVersion
        return if (compareVersionNames(override, packageVersion) > 0) override else packageVersion
    }

    private fun packageLastUpdateTime(context: Context): Long =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
}

/** Numeric segment-wise comparison: "1.0.10" > "1.0.9". Missing segments count as 0. */
fun compareVersionNames(a: String, b: String): Int {
    val aParts = a.split(".").map { it.trim().toIntOrNull() ?: 0 }
    val bParts = b.split(".").map { it.trim().toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(aParts.size, bParts.size)) {
        val diff = (aParts.getOrElse(i) { 0 }).compareTo(bParts.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}
