package com.example.teachmintsharex.share.miracast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MiracastDiscoveryService advertises the Android host device via mDNS (Network Service Discovery)
 * so that Windows+P (Project menu) can discover it as a wireless display.
 *
 * Windows+P looks for devices advertising the _display._tcp service type.
 */
class MiracastDiscoveryService(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private val mutex = Mutex()

    // Track registration state
    private var isRegistered = false
    private var currentServiceName: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * Starts advertising this device as a wireless display using mDNS.
     *
     * @param deviceName The name that will appear in Windows+P menu
     * @param controlPort The port where MS-MICE control server listens (default 7250)
     * @param containerId Stable GUID that Windows uses to identify this sink.
     */
    suspend fun startAdvertisement(
        deviceName: String,
        controlPort: Int = MiracastPorts.MICE_CONTROL_PORT,
        containerId: String,
    ) {
        mutex.withLock {
            if (isRegistered || registrationListener != null) {
                println("MIRACAST_NSD: ⚠️ Advertisement already running for: $currentServiceName")
                return
            }

            try {
                nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                if (nsdManager == null) {
                    println("MIRACAST_NSD: ❌ NsdManager not available on this device")
                    return
                }

                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = deviceName
                    // Miracast over infrastructure discovery service.
                    serviceType = "_display._tcp"
                    port = controlPort

                    // Microsoft requires container_id for receiver identification.
                    setAttribute("container_id", containerId)
                    // Source version marker used by Windows infrastructure projection stacks.
                    // Keep both key variants for broad client interoperability.
                    setAttribute("src_vers", "2.0")
                    setAttribute("srcvers", "2.0")
                    // Keep a TXT schema marker for broad DNS-SD compatibility.
                    setAttribute("txtvers", "1")

                    // Add WFD device info to help Windows+K detect us as a Miracast sink
                    // Format: 6-digit hex representing WFD device information
                    // Bit 0-1: Device type (00=source, 01=primary sink, 10=secondary sink, 11=dual role)
                    // Bit 2: Coupled sink support
                    // Bit 3: Session available
                    // Bit 4-7: WFD service discovery
                    // Bit 8-9: Preferred connectivity (00=P2P, 01=TDLS, 10=Infra)
                    // Bit 10: Content protection support
                    // Bit 11: Time sync support
                    // Bit 12-13: Audio support
                    // Bit 14-15: Audio codec
                    // This value (000001) = Primary sink (01) with session available
                    setAttribute("wfd_device_type", "000001")
                    // Indicate Miracast over Infrastructure support
                    setAttribute("mi", "1")
                    // Device friendly name
                    setAttribute("fn", deviceName)
                }

                println(
                    "MIRACAST_NSD: 🔄 Registering service: $deviceName " +
                        "on port $controlPort (container_id=$containerId)",
                )
                currentServiceName = deviceName

                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                        isRegistered = true
                        val actualName = registeredInfo.serviceName
                        currentServiceName = actualName
                        println("MIRACAST_NSD: ✅ Service registered successfully: $actualName")
                        println("MIRACAST_NSD: 📡 Now visible in Windows+K / Windows+P menu")
                    }

                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        isRegistered = false
                        registrationListener = null
                        val errorMessage = when (errorCode) {
                            NsdManager.FAILURE_ALREADY_ACTIVE -> "Service already registered"
                            NsdManager.FAILURE_INTERNAL_ERROR -> "Internal error"
                            NsdManager.FAILURE_MAX_LIMIT -> "Max services limit reached"
                            else -> "Unknown error: $errorCode"
                        }
                        println("MIRACAST_NSD: ❌ Registration failed: $errorMessage")
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        isRegistered = false
                        registrationListener = null
                        println("MIRACAST_NSD: 🔌 Service unregistered: ${serviceInfo.serviceName}")
                    }

                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        registrationListener = null
                        println("MIRACAST_NSD: ⚠️ Unregistration failed with error: $errorCode")
                    }
                }
                registrationListener = listener

                nsdManager?.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            } catch (e: Exception) {
                registrationListener = null
                println("MIRACAST_NSD: ❌ Exception during registration: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Stops advertising this device via mDNS.
     * Windows+P will no longer see this device in the wireless display list.
     */
    suspend fun stopAdvertisement() {
        mutex.withLock {
            try {
                val manager = nsdManager
                val listener = registrationListener
                if (manager != null && listener != null) {
                    println("MIRACAST_NSD: 🔄 Unregistering service: $currentServiceName")
                    manager.unregisterService(listener)
                    isRegistered = false
                }

                registrationListener = null
                nsdManager = null
                currentServiceName = null

            } catch (e: Exception) {
                println("MIRACAST_NSD: ⚠️ Exception during stop: ${e.message}")
            }
        }
    }

    /**
     * Returns whether the service is currently registered and advertising
     */
    fun isAdvertising(): Boolean = isRegistered

    /**
     * Returns the current service name being advertised
     */
    fun getServiceName(): String? = currentServiceName
}
