package com.teachmint.sharex.share.shared

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.telephony.TelephonyManager

enum class AndroidHostAccessState {
    HOST_ALLOWED,
    HOST_REQUIRES_SUBSCRIPTION,
    CLIENT,
}

private const val TEACHMINT_PREF_FILE = "Teachmint"
private const val TEACHMINT_IFP_KEY = "teachmint_ifp"
private const val TEACHMINT_IFP_HW_KEY = "teachmint_ifp_hw"
private const val TEACHMINT_IS_ALLOWED_KEY = "teachmint_host_allowed"
private const val TEACHMINT_DEVICE_KEY = "teachmint_device"

/**
 * Android implementation of IFP detection
 * Uses sensor and telephony checks to identify Interactive Flat Panels
 */
actual fun isInteractiveFlatPanel(): Boolean {
    return getAndroidHostAccessState() == AndroidHostAccessState.HOST_ALLOWED
}

fun getAndroidHostAccessState(): AndroidHostAccessState {
    val context = AndroidContextHolder.applicationContext ?: return AndroidHostAccessState.CLIENT

    // Detect IFP hardware profile (no motion sensors + no telephony).
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val hasNoMotionSensors = (accelerometer == null && gyroscope == null)
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val hasTelephony = telephonyManager?.phoneType != TelephonyManager.PHONE_TYPE_NONE
    val isIfpHardware = hasNoMotionSensors && !hasTelephony

    // Restrict HOST mode to Teachmint devices only.
    val isTeachmintDevice = isTeachmintBrandedDevice()
    val isHostAllowed = isIfpHardware && isTeachmintDevice

    val preferences: SharedPreferences = context.getSharedPreferences(TEACHMINT_PREF_FILE, Context.MODE_PRIVATE)
    preferences.edit().apply {
        putString(TEACHMINT_IFP_KEY, isHostAllowed.toString())
        putString(TEACHMINT_IFP_HW_KEY, isIfpHardware.toString())
        putString(TEACHMINT_IS_ALLOWED_KEY, isHostAllowed.toString())
        putString(TEACHMINT_DEVICE_KEY, isTeachmintDevice.toString())
    }.apply()

    return when {
        isHostAllowed -> AndroidHostAccessState.HOST_ALLOWED
        isIfpHardware -> AndroidHostAccessState.HOST_REQUIRES_SUBSCRIPTION
        else -> AndroidHostAccessState.CLIENT
    }
}

private fun isTeachmintBrandedDevice(): Boolean {
    // Fast path: check device manufacturer/brand (works without package visibility)
    val manufacturer = Build.MANUFACTURER.orEmpty()
    val brand = Build.BRAND.orEmpty()
    if (manufacturer.contains("teachmint", ignoreCase = true) ||
        brand.contains("teachmint", ignoreCase = true)) {
        return true
    }

    val context = AndroidContextHolder.applicationContext ?: return false
    val packages = listOf("com.teachmint.teachmint", "com.teachmint.whiteboard")
    val pm = context.packageManager
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager

    for (pkg in packages) {
        val systemApp = try {
            val flags = pm.getApplicationInfo(pkg, 0).flags
            (flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        if (systemApp) return true

        if (dpm != null) {
            if (dpm.isDeviceOwnerApp(pkg)) return true
            if (dpm.isProfileOwnerApp(pkg)) return true
            val isActiveAdmin = dpm.activeAdmins?.any { it.packageName == pkg } == true
            if (isActiveAdmin) return true
        }
    }
    return false
}
