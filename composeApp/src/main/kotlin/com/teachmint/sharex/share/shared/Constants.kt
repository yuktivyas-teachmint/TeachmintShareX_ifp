package com.teachmint.sharex.share.shared

import android.content.pm.PackageInfo

const val SIGNALING_PORT: Int = 9090
const val DISCOVERY_PORT: Int = 37020
const val HOST_NAME_ENDPOINT_PATH: String = "/host-name"

// PackageManager is the source of truth for the installed version; the generated
// build-time constants can lag behind it (e.g. after an OTA update built with a
// different version configuration).
//
// Deliberately NOT cached: a self-updating app can replace its own APK while the
// process is still alive (silent install on panels that hold INSTALL_PACKAGES),
// so caching the first read would pin the version to the pre-update value until
// the next manual restart. This is queried only on the settings screen, so the
// per-call binder cost is negligible.
private fun installedPackageInfo(): PackageInfo? {
    val context = AndroidContextHolder.applicationContext ?: return null
    return runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
}

val APP_VERSION: String
    get() = installedPackageInfo()?.versionName ?: GeneratedBuildSecrets.APP_VERSION_NAME

val APP_VERSION_CODE: Int
    get() = installedPackageInfo()?.longVersionCode?.toInt() ?: GeneratedBuildSecrets.APP_VERSION_CODE

const val FILE_UPLOAD_ENDPOINT_PATH: String = "/upload"
