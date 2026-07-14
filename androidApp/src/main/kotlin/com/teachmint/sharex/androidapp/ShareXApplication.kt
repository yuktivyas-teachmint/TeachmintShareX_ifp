package com.teachmint.sharex.androidapp

import android.app.Application
import android.content.Context
import android.util.Log
import com.teachmint.ota.Ota
import com.teachmint.sharex.androidapp.ota.AppUpdateBridgeWiring
import com.teachmint.sharex.androidapp.ota.HEADER_APP_ID
import com.teachmint.sharex.androidapp.ota.HEADER_SERIAL_NUMBER
import com.teachmint.sharex.androidapp.ota.HEADER_UNIQUE_DEVICE_ID
import com.teachmint.sharex.androidapp.ota.OTA_APK_VERSION_ENDPOINT
import com.teachmint.sharex.crashlytics.CrashlyticsLogger
import com.teachmint.sharex.language.LocaleManager
import com.teachmint.sharex.remoteconfig.RemoteConfigManager
import com.teachmint.sharex.share.analytics.AnalyticsConfig
import com.teachmint.sharex.share.analytics.resolveAnalyticsIdentity
import com.teachmint.sharex.share.shared.AndroidContextHolder
import com.teachmint.sharex.share.shared.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShareXApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.init(this)
        CrashlyticsLogger.initialize()
        RemoteConfigManager.initialize()
        applicationScope.launch {
            RemoteConfigManager.fetchAndActivate()
        }
        initOta()
        AppUpdateBridgeWiring.start(this, applicationScope)
    }

    private fun initOta() {
        // Serial number / device id are persisted by the analytics identity layer;
        // fall back to blanks if resolution fails this early in startup.
        val identity = runCatching { resolveAnalyticsIdentity() }
            .onFailure { Log.e(TAG, "Failed to resolve device identity for OTA", it) }
            .getOrNull()

        // Disable the library's own check scheduler entirely: even with a huge
        // checkIntervalMinutes, WorkManager runs the FIRST iteration of the
        // periodic worker immediately after install, which hits the (chakra)
        // backend route and surfaces "Update Failed: Server error" on first
        // launch. The enabled flag only gates the scheduler — downloadFromUrl/
        // install used by OtaUpdateManager are unaffected. Must be set before
        // init, which schedules (or, with the flag off, cancels) the worker.
        // OtaPrefs is internal to the library, so write its pref directly.
        getSharedPreferences("ota_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("ota_auto_update_enabled", false)
            .apply()

        Ota.init(this) {
            updateUrl = NetworkConfig.teachmintBaseUrl + OTA_APK_VERSION_ENDPOINT
            headers =
                mapOf(
                    HEADER_SERIAL_NUMBER to (identity?.serialNumber ?: ""),
                    HEADER_UNIQUE_DEVICE_ID to (identity?.uniqueDeviceId ?: ""),
                    HEADER_APP_ID to AnalyticsConfig.APP_ID,
                )
            appName = "ShareX IFP"
            network {
                // Updates are driven by the Firebase remote-config "ota_update" key
                // (see OtaUpdateManager), not the library's backend polling worker.
                // The worker is disabled via OtaPrefs above; the parked interval
                // is just a backstop in case the pref is ever flipped back on.
                checkIntervalMinutes = 60 * 24 * 365
            }
            verification {
                skipSignature = false
                // Backend sends version names, not version codes.
                skipVersionCode = true
            }
        }
    }

    private companion object {
        const val TAG = "ShareXApplication"
    }
}
