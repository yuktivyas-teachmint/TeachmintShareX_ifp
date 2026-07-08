package com.teachmint.sharex.remoteconfig

import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.tasks.await

object RemoteConfigManager {
    private const val TAG = "REMOTE_CONFIG"
    private const val MINIMUM_FETCH_INTERVAL_SECONDS = 3600L

    @Volatile
    private var _config: ShareXRemoteConfig = ShareXRemoteConfig.DEFAULT

    val config: ShareXRemoteConfig
        get() = _config

    private var firebaseAvailable: Boolean = false

    fun initialize() {
        try {
            val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = MINIMUM_FETCH_INTERVAL_SECONDS
            }
            remoteConfig.setConfigSettingsAsync(configSettings)

            val defaults = mapOf<String, Any>(
                ShareXRemoteConfig.KEY_ENABLE_DIFFERENT_WIFI_CONNECTION to true,
                ShareXRemoteConfig.KEY_ENABLE_FILE_SHARE_ON_DIFFERENT_NETWORK to true,
                ShareXRemoteConfig.KEY_ENABLE_OTA_UPDATES to true,
                ShareXRemoteConfig.KEY_OTA_UPDATE to "",
            )
            remoteConfig.setDefaultsAsync(defaults)

            _config = readFromFirebase(remoteConfig)
            firebaseAvailable = true

            println("$TAG: Initialized with config: $_config")
        } catch (e: Exception) {
            firebaseAvailable = false
            println("$TAG: Firebase not available, using defaults: ${e.message}")
        }
    }

    /**
     * @param minimumFetchIntervalSeconds override for callers that need fresher values than
     * the default 1h cache (e.g. the 15-min OTA update poll). Null uses the default interval.
     */
    suspend fun fetchAndActivate(minimumFetchIntervalSeconds: Long? = null): Boolean {
        if (!firebaseAvailable) return false
        return try {
            val remoteConfig = Firebase.remoteConfig
            val activated = if (minimumFetchIntervalSeconds != null) {
                remoteConfig.fetch(minimumFetchIntervalSeconds).await()
                remoteConfig.activate().await()
            } else {
                remoteConfig.fetchAndActivate().await()
            }
            _config = readFromFirebase(remoteConfig)
            println("$TAG: Fetch and activate complete. activated=$activated, config=$_config")
            activated
        } catch (e: Exception) {
            println("$TAG: Fetch failed, using cached/default values: ${e.message}")
            false
        }
    }

    private fun readFromFirebase(remoteConfig: FirebaseRemoteConfig): ShareXRemoteConfig {
        return ShareXRemoteConfig(
            enableDifferentWifiConnection = remoteConfig.getBoolean(
                ShareXRemoteConfig.KEY_ENABLE_DIFFERENT_WIFI_CONNECTION
            ),
            enableFileShareOnDifferentNetwork = remoteConfig.getBoolean(
                ShareXRemoteConfig.KEY_ENABLE_FILE_SHARE_ON_DIFFERENT_NETWORK
            ),
            enableOtaUpdates = remoteConfig.getBoolean(
                ShareXRemoteConfig.KEY_ENABLE_OTA_UPDATES
            ),
            otaUpdateJson = remoteConfig.getString(
                ShareXRemoteConfig.KEY_OTA_UPDATE
            ),
        )
    }
}
