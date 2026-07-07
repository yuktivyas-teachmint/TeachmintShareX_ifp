package com.teachmint.sharex.share.shared

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

/**
 * Some platform services (e.g., Wifi multicast lock) need an application context, but our KMP
 * `expect class DiscoveryService()` can't receive it via constructor. We initialize this once
 * from Android entry points.
 */
object AndroidContextHolder {
    @Volatile
    private var _applicationContext: Context? = null
    @Volatile
    private var _activityRef: WeakReference<Activity>? = null

    val applicationContext: Context?
        get() = _applicationContext

    val currentActivity: Activity?
        get() = _activityRef?.get()

    fun init(context: Context) {
        _applicationContext = context.applicationContext
    }

    fun setCurrentActivity(activity: Activity?) {
        _activityRef = activity?.let { WeakReference(it) }
    }
}
