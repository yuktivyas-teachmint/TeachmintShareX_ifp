package com.example.teachmintsharex.share.wifidirect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Helper to check and handle Wi-Fi Direct permissions
 */
object WifiDirectPermissionHelper {

    /**
     * Checks if all required Wi-Fi Direct permissions are granted
     */
    fun hasPermissions(context: Context): Boolean {
        val requiredPermissions = getRequiredPermissions()

        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Gets list of required permissions based on Android version
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Required for WifiP2pManager initialization and state changes.
        permissions.add(Manifest.permission.CHANGE_WIFI_STATE)

        // Location permissions (required for Wi-Fi P2P discovery)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Android 13+ requires NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        return permissions
    }

    /**
     * Gets list of missing permissions
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Logs permission status for debugging
     */
    fun logPermissionStatus(context: Context) {
        val required = getRequiredPermissions()

        println("WIFI_DIRECT_PERMISSIONS: Required permissions:")
        required.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            val status = if (granted) "✅ GRANTED" else "❌ MISSING"
            println("WIFI_DIRECT_PERMISSIONS:   $status - $permission")
        }
    }
}
