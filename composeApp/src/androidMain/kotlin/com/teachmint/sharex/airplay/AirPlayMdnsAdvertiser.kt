package com.teachmint.sharex.airplay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.teachmint.sharex.share.shared.AndroidContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Advertises the AirPlay receiver on the local network via mDNS/Bonjour (Android).
 *
 * Uses JmDNS directly (not NsdManager) to create its own multicast socket,
 * bound to the device's WiFi IP. This works reliably with Apple devices and
 * avoids issues with custom OEM mDNS daemons on IFP devices.
 *
 * NsdManager was tried first but the IFP runs a custom mDNS daemon (fangr_mdns)
 * that does not propagate TXT records to Apple devices correctly.
 */
class AirPlayMdnsAdvertiser(
    private val deviceInfo: AirPlayDeviceInfo,
    private val httpPort: Int,
) {
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        val ctx = AndroidContextHolder.applicationContext
            ?: run { Log.e("AirPlay", "mDNS: AndroidContextHolder not initialized"); return@withContext }

        Log.d("AirPlay", "mDNS: start() entered")
        try {
            // Multicast lock — without this Android drops mDNS packets in the WiFi driver
            val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("AirPlayMdns").apply {
                setReferenceCounted(true)
                acquire()
            }

            // Bind JmDNS to the actual WiFi IP — avoids binding to 127.0.0.1 (loopback)
            // which would make the service invisible on the LAN.
            val localAddr = getWifiAddress(wifiManager)
            Log.d("AirPlay", "mDNS: binding JmDNS to ${localAddr.hostAddress}")

            jmdns = JmDNS.create(localAddr, deviceInfo.name)

            val serviceInfo = ServiceInfo.create(
                "_airplay._tcp.local.",
                deviceInfo.name,
                httpPort,
                0, 0,
                buildTxtRecord(),
            )

            jmdns?.registerService(serviceInfo)
            Log.d("AirPlay", "mDNS: advertised '${deviceInfo.name}' on port $httpPort at ${localAddr.hostAddress}")
        } catch (e: Exception) {
            Log.e("AirPlay", "mDNS: failed to start: ${e.message}")
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (e: Exception) {
            Log.e("AirPlay", "mDNS: error stopping: ${e.message}")
        } finally {
            jmdns = null
            runCatching { multicastLock?.release() }
            multicastLock = null
            Log.d("AirPlay", "mDNS: stopped")
        }
    }

    /**
     * Gets the device's current WiFi IP address via WifiManager.
     * Android returns the IP as a 32-bit int in little-endian byte order.
     * Falls back to getLocalHost() if WiFi is not connected.
     */
    private fun getWifiAddress(wifiManager: WifiManager): InetAddress {
        @Suppress("DEPRECATION")
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt != 0) {
            val ipBytes = ByteArray(4) { i -> ((ipInt shr (i * 8)) and 0xFF).toByte() }
            return InetAddress.getByAddress(ipBytes)
        }
        return InetAddress.getLocalHost()
    }

    private fun buildTxtRecord(): Map<String, String> = mapOf(
        "deviceid"     to deviceInfo.deviceId,
        "features"     to AirPlayProtocol.FEATURES_STRING,
        "flags"        to "0x4",   // required for macOS Screen Mirroring to list this device
        "acl"          to "0",     // access control level 0 = open; macOS hides devices without this
        "model"        to AirPlayProtocol.MODEL,
        "manufacturer" to AirPlayProtocol.MANUFACTURER,
        "srcvers"      to AirPlayProtocol.SRC_VERS,
        "vv"           to AirPlayProtocol.VV,
        "pi"           to deviceInfo.pi,
        "pk"           to deviceInfo.publicKeyHex,
        "protovers"    to AirPlayProtocol.PROTO_VERS,
    )
}
