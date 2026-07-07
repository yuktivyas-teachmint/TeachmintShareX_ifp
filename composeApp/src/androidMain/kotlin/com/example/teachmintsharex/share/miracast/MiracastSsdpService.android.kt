package com.example.teachmintsharex.share.miracast

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * MiracastSsdpService implements SSDP discovery for Miracast over infrastructure.
 */
class MiracastSsdpService(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ssdpAddress = "239.255.255.250"
    private val ssdpPort = 1900
    private val miracastUrn = "urn:schemas-upnp-org:device:Wireless-Display:1"
    private val wfaDeviceUrn = "urn:schemas-wifialliance-org:device:WFADevice:1"
    private val mediaRendererUrn = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private val dialServiceUrn = "urn:dial-multiscreen-org:service:dial:1"

    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenerJob: Job? = null
    private var notifyJob: Job? = null

    @Volatile
    private var isRunning = false

    private var deviceUuid: String = ""
    private var localIp: String = ""
    private var descriptionPort: Int = 0

    /**
     * Starts SSDP advertisement + M-SEARCH response handling.
     */
    suspend fun startDiscovery(
        deviceName: String,
        deviceUuid: String,
        localIp: String,
        rtspPort: Int = 8080,
    ) {
        stopDiscovery()

        this.deviceUuid = deviceUuid.trim()
        this.localIp = localIp
        this.descriptionPort = rtspPort

        try {
            println("MIRACAST_SSDP: 🔄 Starting SSDP discovery")
            println("MIRACAST_SSDP: Device: $deviceName")
            println("MIRACAST_SSDP: UUID: ${this.deviceUuid}")
            println("MIRACAST_SSDP: WFD LOCATION: ${wfdDescriptionUrl()}")
            println("MIRACAST_SSDP: DIAL LOCATION: ${dialDescriptionUrl()}")

            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("MiracastSsdpLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            println("MIRACAST_SSDP: 🔓 Multicast lock acquired")

            val socket = MulticastSocket(ssdpPort).apply {
                reuseAddress = true
                timeToLive = 4
            }
            multicastSocket = socket

            val group = InetAddress.getByName(ssdpAddress)
            val networkInterface = getWifiInterface()
            if (networkInterface != null) {
                socket.joinGroup(InetSocketAddress(group, ssdpPort), networkInterface)
                println("MIRACAST_SSDP: ✅ Joined multicast group on ${networkInterface.displayName}")
            } else {
                println("MIRACAST_SSDP: ⚠️ Could not resolve Wi-Fi interface, using default")
            }

            isRunning = true
            listenerJob = scope.launch { listenForSearchRequests() }
            notifyJob = scope.launch {
                delay(800)
                while (isActive && isRunning) {
                    sendNotifyAlive()
                    delay(30_000)
                }
            }
        } catch (e: Exception) {
            println("MIRACAST_SSDP: ❌ Failed to start: ${e.message}")
            stopDiscovery()
        }
    }

    private suspend fun listenForSearchRequests() {
        val socket = multicastSocket ?: return
        val buffer = ByteArray(4096)

        while (coroutineContext.isActive && isRunning) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val message = String(packet.data, 0, packet.length)
                if (!message.startsWith("M-SEARCH", ignoreCase = true)) continue

                val searchTarget = extractSearchTarget(message)
                val man = extractHeaderValue(message, "MAN")
                val mx = extractHeaderValue(message, "MX")
                if (!man.orEmpty().contains("ssdp:discover", ignoreCase = true)) {
                    continue
                }

                println(
                    "MIRACAST_SSDP: 📡 M-SEARCH from ${packet.address.hostAddress} " +
                        "ST=${searchTarget ?: "<missing>"} MAN=${man ?: "<missing>"} MX=${mx ?: "<missing>"}",
                )

                sendSearchResponses(packet.address, packet.port, searchTarget)
            } catch (e: Exception) {
                if (coroutineContext.isActive && isRunning) {
                    println("MIRACAST_SSDP: ⚠️ Listen error: ${e.message}")
                }
            }
        }
    }

    private fun extractSearchTarget(message: String): String? {
        return message.lineSequence()
            .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    }

    private fun extractHeaderValue(message: String, headerName: String): String? {
        return message.lineSequence()
            .firstOrNull { it.startsWith("$headerName:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    }

    private fun sendSearchResponses(
        targetAddress: InetAddress,
        targetPort: Int,
        searchTarget: String?,
    ) {
        if (searchTarget != null &&
            !searchTarget.equals("ssdp:all", ignoreCase = true) &&
            !searchTarget.equals("upnp:rootdevice", ignoreCase = true) &&
            !searchTarget.equals(miracastUrn, ignoreCase = true) &&
            !searchTarget.equals(wfaDeviceUrn, ignoreCase = true) &&
            !searchTarget.equals(mediaRendererUrn, ignoreCase = true) &&
            !searchTarget.equals(dialServiceUrn, ignoreCase = true) &&
            !searchTarget.equals("uuid:$deviceUuid", ignoreCase = true)
        ) {
            println("MIRACAST_SSDP: ℹ️ Unknown ST '$searchTarget' - sending compatible responses")
        }

        val responses = when {
            searchTarget == null || searchTarget.equals("ssdp:all", ignoreCase = true) -> {
                listOf(
                    responseFor("upnp:rootdevice", "uuid:$deviceUuid::upnp:rootdevice", wfdDescriptionUrl()),
                    responseFor("uuid:$deviceUuid", "uuid:$deviceUuid", wfdDescriptionUrl()),
                    responseFor(miracastUrn, "uuid:$deviceUuid::$miracastUrn", wfdDescriptionUrl()),
                    responseFor(wfaDeviceUrn, "uuid:$deviceUuid::$wfaDeviceUrn", wfdDescriptionUrl()),
                    responseFor(mediaRendererUrn, "uuid:$deviceUuid::$mediaRendererUrn", wfdDescriptionUrl()),
                    responseFor(dialServiceUrn, "uuid:$deviceUuid::$dialServiceUrn", dialDescriptionUrl()),
                )
            }

            searchTarget.equals("upnp:rootdevice", ignoreCase = true) -> {
                listOf(responseFor("upnp:rootdevice", "uuid:$deviceUuid::upnp:rootdevice", wfdDescriptionUrl()))
            }

            searchTarget.equals("uuid:$deviceUuid", ignoreCase = true) -> {
                listOf(responseFor("uuid:$deviceUuid", "uuid:$deviceUuid", wfdDescriptionUrl()))
            }

            searchTarget.equals(mediaRendererUrn, ignoreCase = true) -> {
                listOf(responseFor(mediaRendererUrn, "uuid:$deviceUuid::$mediaRendererUrn", wfdDescriptionUrl()))
            }

            searchTarget.equals(wfaDeviceUrn, ignoreCase = true) -> {
                listOf(responseFor(wfaDeviceUrn, "uuid:$deviceUuid::$wfaDeviceUrn", wfdDescriptionUrl()))
            }

            searchTarget.equals(dialServiceUrn, ignoreCase = true) -> {
                listOf(responseFor(dialServiceUrn, "uuid:$deviceUuid::$dialServiceUrn", dialDescriptionUrl()))
            }

            else -> {
                listOf(
                    responseFor("upnp:rootdevice", "uuid:$deviceUuid::upnp:rootdevice", wfdDescriptionUrl()),
                    responseFor(miracastUrn, "uuid:$deviceUuid::$miracastUrn", wfdDescriptionUrl()),
                    responseFor(wfaDeviceUrn, "uuid:$deviceUuid::$wfaDeviceUrn", wfdDescriptionUrl()),
                    responseFor(mediaRendererUrn, "uuid:$deviceUuid::$mediaRendererUrn", wfdDescriptionUrl()),
                    responseFor(dialServiceUrn, "uuid:$deviceUuid::$dialServiceUrn", dialDescriptionUrl()),
                )
            }
        }

        for (response in responses) {
            sendUdpMessage(
                payload = response,
                address = targetAddress,
                port = targetPort,
                logPrefix = "MIRACAST_SSDP: ✅ Sent response to ${targetAddress.hostAddress}",
            )
        }
    }

    private fun responseFor(st: String, usn: String, locationUrl: String): String {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())
        val applicationUrlHeader = if (st.equals(dialServiceUrn, ignoreCase = true)) {
            "Application-URL: ${dialApplicationUrl()}\r\n"
        } else {
            ""
        }
        return (
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "DATE: $date\r\n" +
                "EXT:\r\n" +
                "LOCATION: $locationUrl\r\n" +
                applicationUrlHeader +
                "SERVER: Android/1.0 UPnP/1.1 TeachmintShareX/1.0\r\n" +
                "ST: $st\r\n" +
                "USN: $usn\r\n" +
                "BOOTID.UPNP.ORG: 1\r\n" +
                "CONFIGID.UPNP.ORG: 1\r\n\r\n"
            )
    }

    private fun sendNotifyAlive() {
        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: ${dialDescriptionUrl()}\r\n" +
                    "NT: upnp:rootdevice\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Android/1.0 UPnP/1.1 TeachmintShareX/1.0\r\n" +
                    "USN: uuid:$deviceUuid::upnp:rootdevice\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 📢 Sent NOTIFY alive",
        )

        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: ${wfdDescriptionUrl()}\r\n" +
                    "NT: $wfaDeviceUrn\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Android/1.0 UPnP/1.1 TeachmintShareX/1.0\r\n" +
                    "USN: uuid:$deviceUuid::$wfaDeviceUrn\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 📢 Sent NOTIFY alive",
        )

        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: ${wfdDescriptionUrl()}\r\n" +
                    "NT: $miracastUrn\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Android/1.0 UPnP/1.1 TeachmintShareX/1.0\r\n" +
                    "USN: uuid:$deviceUuid::$miracastUrn\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 📢 Sent NOTIFY alive",
        )

        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: ${wfdDescriptionUrl()}\r\n" +
                    "NT: $mediaRendererUrn\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Android/1.0 UPnP/1.1 TeachmintShareX/1.0\r\n" +
                    "USN: uuid:$deviceUuid::$mediaRendererUrn\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 📢 Sent NOTIFY alive",
        )

        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "LOCATION: ${dialDescriptionUrl()}\r\n" +
                    "NT: $dialServiceUrn\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "SERVER: Android/1.0 UPnP/1.1 TeachmintShareX/1.0\r\n" +
                    "USN: uuid:$deviceUuid::$dialServiceUrn\r\n" +
                    "Application-URL: ${dialApplicationUrl()}\r\n" +
                    "BOOTID.UPNP.ORG: 1\r\n" +
                    "CONFIGID.UPNP.ORG: 1\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 📢 Sent NOTIFY alive",
        )
    }

    private fun sendNotifyByebye() {
        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "NT: upnp:rootdevice\r\n" +
                    "NTS: ssdp:byebye\r\n" +
                    "USN: uuid:$deviceUuid::upnp:rootdevice\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 👋 Sent byebye",
        )

        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "NT: $miracastUrn\r\n" +
                    "NTS: ssdp:byebye\r\n" +
                    "USN: uuid:$deviceUuid::$miracastUrn\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 👋 Sent byebye",
        )

        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "NT: $mediaRendererUrn\r\n" +
                    "NTS: ssdp:byebye\r\n" +
                    "USN: uuid:$deviceUuid::$mediaRendererUrn\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 👋 Sent byebye",
        )

        sendUdpMessage(
            payload =
                "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $ssdpAddress:$ssdpPort\r\n" +
                    "NT: $dialServiceUrn\r\n" +
                    "NTS: ssdp:byebye\r\n" +
                    "USN: uuid:$deviceUuid::$dialServiceUrn\r\n\r\n",
            address = InetAddress.getByName(ssdpAddress),
            port = ssdpPort,
            logPrefix = "MIRACAST_SSDP: 👋 Sent byebye",
        )
    }

    private fun sendUdpMessage(payload: String, address: InetAddress, port: Int, logPrefix: String) {
        runCatching {
            DatagramSocket().use { socket ->
                val data = payload.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(data, data.size, address, port))
            }
            println(logPrefix)
        }.onFailure {
            println("MIRACAST_SSDP: ⚠️ UDP send failed: ${it.message}")
        }
    }

    private fun wfdDescriptionUrl(): String = "http://$localIp:$descriptionPort/wfd/dd.xml"

    private fun dialDescriptionUrl(): String = "http://$localIp:$descriptionPort/dial/dd.xml"

    private fun dialApplicationUrl(): String = "http://$localIp:$descriptionPort/apps/"

    suspend fun stopDiscovery() {
        isRunning = false

        runCatching { listenerJob?.cancelAndJoin() }
        runCatching { notifyJob?.cancelAndJoin() }
        listenerJob = null
        notifyJob = null

        if (deviceUuid.isNotBlank() && localIp.isNotBlank() && descriptionPort > 0) {
            sendNotifyByebye()
        }

        runCatching {
            multicastSocket?.close()
            multicastSocket = null
        }

        runCatching {
            multicastLock?.release()
            multicastLock = null
            println("MIRACAST_SSDP: 🔒 Multicast lock released")
        }

        deviceUuid = ""
        localIp = ""
        descriptionPort = 0

        println("MIRACAST_SSDP: 🔴 SSDP discovery stopped")
    }

    private fun getWifiInterface(): NetworkInterface? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { networkInterface ->
                    val name = networkInterface.name.lowercase(Locale.US)
                    networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        (name.contains("wlan") || name.contains("wifi") || name.contains("eth"))
                }
        }.getOrNull()
    }
}
