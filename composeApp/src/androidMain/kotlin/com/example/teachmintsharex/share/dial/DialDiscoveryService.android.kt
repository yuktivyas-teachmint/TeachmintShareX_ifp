package com.example.teachmintsharex.share.dial

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * DialDiscoveryService implements SSDP (Simple Service Discovery Protocol) for DIAL
 * (Discovery and Launch) protocol. This allows Windows Connect app (Win+K) to discover
 * the device as a wireless display.
 *
 * DIAL is used by Chromecast, Roku, and smart TVs for device discovery over infrastructure Wi-Fi.
 */
class DialDiscoveryService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var ssdpSocket: MulticastSocket? = null
    private var discoveryJob: Job? = null
    private var isRunning = false

    // SSDP multicast address and port (standard UPnP)
    private val ssdpMulticastAddress = "239.255.255.250"
    private val ssdpPort = 1900

    // DIAL service type
    private val dialServiceType = "urn:dial-multiscreen-org:service:dial:1"

    private var deviceDescriptionUrl: String? = null
    private var deviceUuid: String? = null

    /**
     * Starts DIAL/SSDP discovery broadcasting
     *
     * @param deviceName Friendly name of the device
     * @param deviceUuid Unique identifier for this device
     * @param localIp Local IP address of the device
     * @param httpPort Port where DIAL HTTP server is listening (for device description XML)
     */
    suspend fun startDiscovery(
        deviceName: String,
        deviceUuid: String,
        localIp: String,
        httpPort: Int = 8080
    ) {
        mutex.withLock {
            if (discoveryJob?.isActive == true) {
                println("DIAL_SSDP: ⚠️ Discovery already running")
                return
            }

            this.deviceUuid = deviceUuid
            this.deviceDescriptionUrl = "http://$localIp:$httpPort/dd.xml"

            discoveryJob = scope.launch {
                try {
                    println("DIAL_SSDP: 🔄 Starting SSDP discovery")
                    println("DIAL_SSDP: Device: $deviceName")
                    println("DIAL_SSDP: UUID: $deviceUuid")
                    println("DIAL_SSDP: Description URL: $deviceDescriptionUrl")

                    // Create multicast socket for SSDP
                    val socket = MulticastSocket(ssdpPort).apply {
                        reuseAddress = true
                        timeToLive = 4  // Limit to local network
                    }
                    ssdpSocket = socket

                    // Join SSDP multicast group
                    val multicastGroup = InetAddress.getByName(ssdpMulticastAddress)
                    val networkInterface = getActiveNetworkInterface()

                    if (networkInterface != null) {
                        val socketAddress = InetSocketAddress(multicastGroup, ssdpPort)
                        socket.joinGroup(socketAddress, networkInterface)
                        println("DIAL_SSDP: ✅ Joined multicast group on ${networkInterface.displayName}")
                    } else {
                        println("DIAL_SSDP: ⚠️ No active network interface found, using default")
                    }

                    isRunning = true

                    // Listen for M-SEARCH requests and respond
                    val buffer = ByteArray(2048)
                    while (isActive && isRunning) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)

                            val message = String(packet.data, 0, packet.length)

                            // Check if this is an M-SEARCH request for DIAL
                            if (message.startsWith("M-SEARCH") &&
                                (message.contains("ssdp:all") || message.contains(dialServiceType))) {

                                println("DIAL_SSDP: 📡 Received M-SEARCH from ${packet.address.hostAddress}")

                                // Send SSDP response
                                sendSsdpResponse(packet.address, packet.port, localIp, httpPort)
                            }
                        } catch (e: Exception) {
                            if (isActive && isRunning) {
                                println("DIAL_SSDP: ⚠️ Error receiving packet: ${e.message}")
                            }
                        }
                    }

                } catch (e: Exception) {
                    println("DIAL_SSDP: ❌ Error starting discovery: ${e.message}")
                    e.printStackTrace()
                } finally {
                    cleanup()
                }
            }

            // Also send periodic NOTIFY messages to announce presence
            startPeriodicNotify(deviceName, localIp, httpPort)

            println("DIAL_SSDP: 📡 DIAL/SSDP discovery started")
            println("DIAL_SSDP: Device should now be visible in Windows Connect (Win+K)")
        }
    }

    /**
     * Sends SSDP response to M-SEARCH request
     */
    private fun sendSsdpResponse(
        targetAddress: InetAddress,
        targetPort: Int,
        localIp: String,
        httpPort: Int
    ) {
        try {
            val response = buildSsdpResponse(localIp, httpPort)
            val responseBytes = response.toByteArray()

            val socket = DatagramSocket()
            socket.send(
                DatagramPacket(
                    responseBytes,
                    responseBytes.size,
                    targetAddress,
                    targetPort
                )
            )
            socket.close()

            println("DIAL_SSDP: ✅ Sent SSDP response to ${targetAddress.hostAddress}:$targetPort")
        } catch (e: Exception) {
            println("DIAL_SSDP: ⚠️ Error sending SSDP response: ${e.message}")
        }
    }

    /**
     * Builds SSDP response message
     */
    private fun buildSsdpResponse(localIp: String, httpPort: Int): String {
        val descriptionUrl = "http://$localIp:$httpPort/dd.xml"

        return """HTTP/1.1 200 OK
CACHE-CONTROL: max-age=1800
EXT:
LOCATION: $descriptionUrl
SERVER: Android UPnP/1.1 TeachmintShareX/1.0
ST: $dialServiceType
USN: uuid:$deviceUuid::$dialServiceType
BOOTID.UPNP.ORG: 1
CONFIGID.UPNP.ORG: 1


""".replace("\n", "\r\n")  // SSDP requires CRLF line endings
    }

    /**
     * Sends periodic NOTIFY messages to announce device presence
     */
    private fun startPeriodicNotify(deviceName: String, localIp: String, httpPort: Int) {
        scope.launch {
            while (isActive && isRunning) {
                try {
                    sendNotifyAlive(localIp, httpPort)
                    kotlinx.coroutines.delay(30000)  // Send every 30 seconds
                } catch (e: Exception) {
                    if (isActive && isRunning) {
                        println("DIAL_SSDP: ⚠️ Error sending NOTIFY: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Sends NOTIFY ssdp:alive message
     */
    private fun sendNotifyAlive(localIp: String, httpPort: Int) {
        try {
            val notify = buildNotifyMessage(localIp, httpPort)
            val notifyBytes = notify.toByteArray()

            val socket = DatagramSocket()
            socket.send(
                DatagramPacket(
                    notifyBytes,
                    notifyBytes.size,
                    InetAddress.getByName(ssdpMulticastAddress),
                    ssdpPort
                )
            )
            socket.close()

            println("DIAL_SSDP: 📢 Sent NOTIFY alive")
        } catch (e: Exception) {
            println("DIAL_SSDP: ⚠️ Error sending NOTIFY: ${e.message}")
        }
    }

    /**
     * Builds NOTIFY message
     */
    private fun buildNotifyMessage(localIp: String, httpPort: Int): String {
        val descriptionUrl = "http://$localIp:$httpPort/dd.xml"

        return """NOTIFY * HTTP/1.1
HOST: $ssdpMulticastAddress:$ssdpPort
CACHE-CONTROL: max-age=1800
LOCATION: $descriptionUrl
NT: $dialServiceType
NTS: ssdp:alive
SERVER: Android UPnP/1.1 TeachmintShareX/1.0
USN: uuid:$deviceUuid::$dialServiceType
BOOTID.UPNP.ORG: 1
CONFIGID.UPNP.ORG: 1


""".replace("\n", "\r\n")
    }

    /**
     * Gets the active network interface (preferably Wi-Fi)
     */
    private fun getActiveNetworkInterface(): NetworkInterface? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()

            // Prefer Wi-Fi interfaces
            interfaces.firstOrNull {
                it.name.contains("wlan", ignoreCase = true) && it.isUp && !it.isLoopback
            } ?: interfaces.firstOrNull {
                it.isUp && !it.isLoopback && !it.isVirtual
            }
        } catch (e: Exception) {
            println("DIAL_SSDP: ⚠️ Error getting network interface: ${e.message}")
            null
        }
    }

    /**
     * Stops DIAL/SSDP discovery
     */
    suspend fun stopDiscovery() {
        mutex.withLock {
            println("DIAL_SSDP: 🔄 Stopping DIAL discovery")
            isRunning = false

            // Send NOTIFY byebye before shutting down
            deviceDescriptionUrl?.let { url ->
                val localIp = url.substringAfter("http://").substringBefore(":")
                val httpPort = url.substringAfter(":").substringBefore("/").toIntOrNull() ?: 8080
                sendNotifyByebye(localIp, httpPort)
            }

            discoveryJob?.cancel()
            discoveryJob = null
            cleanup()
            println("DIAL_SSDP: ✅ DIAL discovery stopped")
        }
    }

    /**
     * Sends NOTIFY ssdp:byebye message
     */
    private fun sendNotifyByebye(localIp: String, httpPort: Int) {
        try {
            val byebye = """NOTIFY * HTTP/1.1
HOST: $ssdpMulticastAddress:$ssdpPort
NT: $dialServiceType
NTS: ssdp:byebye
USN: uuid:$deviceUuid::$dialServiceType


""".replace("\n", "\r\n")

            val byebyeBytes = byebye.toByteArray()
            val socket = DatagramSocket()
            socket.send(
                DatagramPacket(
                    byebyeBytes,
                    byebyeBytes.size,
                    InetAddress.getByName(ssdpMulticastAddress),
                    ssdpPort
                )
            )
            socket.close()

            println("DIAL_SSDP: 👋 Sent NOTIFY byebye")
        } catch (e: Exception) {
            println("DIAL_SSDP: ⚠️ Error sending byebye: ${e.message}")
        }
    }

    /**
     * Cleanup socket resources
     */
    private fun cleanup() {
        try {
            ssdpSocket?.close()
            ssdpSocket = null
        } catch (e: Exception) {
            println("DIAL_SSDP: ⚠️ Error cleaning up: ${e.message}")
        }
    }

    /**
     * Returns whether discovery is currently running
     */
    fun isDiscovering(): Boolean = isRunning
}
