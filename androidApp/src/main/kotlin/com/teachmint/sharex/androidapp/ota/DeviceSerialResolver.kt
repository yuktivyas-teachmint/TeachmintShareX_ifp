package com.teachmint.sharex.androidapp.ota

import android.content.Context
import android.os.Build
import android.text.TextUtils
import android.util.Log
import com.ifpdos.udi.sdk.UdiSdk
import com.xbh.sdk4.XbhApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * Reads the panel serial number using each vendor's own mechanism, in the same
 * order the onboarding app (prarambh) uses:
 * 1. KTC/SKG (star & nonstar X2 panels): `persist.sys.skg.serialnumber`
 * 2. Lango (XBH boards): `XbhApi.burningHelper.sn`
 * 3. CVTE: the UDI local HTTP service (`/v1/device/sn/machine`)
 * with `ro.serialno` / `Build.getSerial()` as last-resort fallbacks.
 *
 * Vendor SDK calls simply throw on the other vendors' firmware, so every step
 * is fail-soft and the next one runs. Returns null when nothing yields a
 * serial.
 */
object DeviceSerialResolver {
    private const val TAG = "DeviceSerialResolver"
    private const val INVALID = "-1"
    private const val CVTE_SN_TIMEOUT_MS = 3000L
    private const val UDI_TOKEN = "xZLhdFeUjexAwBPj6H2yMiAgjcy43ze8rT4PvIVXaiD-BRrWj0zNgXRE20A="

    @Volatile
    private var cached: String? = null

    suspend fun resolve(context: Context): String? {
        cached?.let { return it }
        val serial = skgSerial()
            ?: xbhSerial()
            ?: cvteSerial(context)
            ?: systemProperty("ro.serialno")
            ?: frameworkSerial()
        if (serial != null) {
            Log.d(TAG, "Resolved device serial: $serial")
            cached = serial
        } else {
            Log.w(TAG, "No vendor mechanism yielded a serial number")
        }
        return serial
    }

    /** KTC/SKG panels expose the serial as a system property. */
    private fun skgSerial(): String? = systemProperty("persist.sys.skg.serialnumber")

    /** Lango panels are XBH boards; the SDK reads the factory-burned serial. */
    private fun xbhSerial(): String? = runCatching {
        XbhApi.getInstance().burningHelper.sn
    }.getOrNull()?.takeUnless { it.isBlank() || it == INVALID }

    /** CVTE panels expose the serial via the UDI on-device HTTP service. */
    private suspend fun cvteSerial(context: Context): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(CVTE_SN_TIMEOUT_MS) {
            try {
                UdiSdk.init(context.applicationContext, UDI_TOKEN)
                val url = UdiSdk.getFullUrl("/v1/device/sn/machine")
                val client = UdiSdk.newOkHttpClientBuilder().build()
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.code != 200 || body.isEmpty()) return@use null
                    val json = JSONObject(body)
                    json.optString("value", json.optString("sn", json.optString("machine", "")))
                        .takeUnless { it.isBlank() || it == INVALID }
                }
            } catch (e: Throwable) {
                Log.d(TAG, "UDI serial lookup failed (non-CVTE device?): ${e.message}")
                null
            }
        }
    }

    private fun frameworkSerial(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Build.getSerial()
        } else {
            @Suppress("DEPRECATION")
            Build.SERIAL
        }
    }.getOrNull()?.takeUnless { it.isBlank() || it.equals(Build.UNKNOWN, ignoreCase = true) }

    private fun systemProperty(key: String): String? {
        val value = try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
        return if (TextUtils.isEmpty(value) || value == INVALID) null else value
    }
}
