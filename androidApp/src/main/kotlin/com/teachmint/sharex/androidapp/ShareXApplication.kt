package com.teachmint.sharex.androidapp

import android.app.Application
import com.teachmint.sharex.crashlytics.CrashlyticsLogger
import com.teachmint.sharex.remoteconfig.RemoteConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShareXApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        CrashlyticsLogger.initialize()
        RemoteConfigManager.initialize()
        applicationScope.launch {
            RemoteConfigManager.fetchAndActivate()
        }
    }
}
