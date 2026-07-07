package com.teachmint.sharex.share.shared

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL

actual class DiscoveryService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    actual val hosts: StateFlow<List<DiscoveredHost>> = _hosts.asStateFlow()

    private var discoveryJob: Job? = null
    private var broadcastJob: Job? = null
    private var discoverySocket: DatagramSocket? = null
    private val discoveryMutex = Mutex()

    actual suspend fun startDiscovery() {
        discoveryMutex.withLock {
            if (discoveryJob?.isActive == true) return

            discoveryJob = scope.launch {
                val multicastLock = acquireMulticastLock()
                val socket = runCatching {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        soTimeout = SOCKET_TIMEOUT_MS
                        bind(InetSocketAddress(DISCOVERY_PORT))
                    }
                }.getOrElse { error ->
                    val message = error.message ?: "unknown"
                    if (error is BindException) {
                        println("DISCOVERY_ANDROID: Discovery socket already bound; using probe fallback: $message")
                    } else {
                        println("DISCOVERY_ANDROID: Failed to start discovery socket; using probe fallback: $message")
                    }
                    null
                }

                if (socket != null) {
                    discoverySocket = socket
                }

                val buffer = ByteArray(1024)
                val hostMap = mutableMapOf<String, DiscoveredHost>()
                var fallbackSubnetIndex = 0
                var subnetRefreshAtMs = 0L
                var subnetCursor = 0
                var lastAnnouncementSeenAtMs = 0L
                var nextProbeAtMs = 0L
                var probeSubnets = emptyList<SubnetInfo>()
                val nextProbeOctetByPrefix = mutableMapOf<String, Int>()
                // F-008: Rate limiter for UDP packets to prevent flood-based DoS
                var packetCountInWindow = 0
                var packetWindowStartMs = currentTimeMillis()

                try {
                    while (isActive) {
                        val now = currentTimeMillis()
                        try {
                            if (socket != null) {
                                val packet = DatagramPacket(buffer, buffer.size)
                                socket.receive(packet)
                                // F-008: Rate limit incoming UDP packets (max 50/sec)
                                val packetNow = currentTimeMillis()
                                if (packetNow - packetWindowStartMs >= 1000L) {
                                    packetCountInWindow = 0
                                    packetWindowStartMs = packetNow
                                }
                                packetCountInWindow++
                                if (packetCountInWindow > MAX_DISCOVERY_PACKETS_PER_SECOND) {
                                    delay(100)
                                    continue
                                }
                                val payload = packet.data.decodeToString(0, packet.length)
                                val announcement = ShareXJson.decodeFromString<DiscoveryAnnouncement>(payload)
                                val address = announcement.hostAddress
                                    ?.takeIf { it.isNotBlank() }
                                    ?: packet.address.hostAddress
                                    ?: continue
                                hostMap.remove(probeHostKey(address))
                                val host = DiscoveredHost(
                                    hostId = announcement.hostId,
                                    name = announcement.name,
                                    address = address,
                                    port = announcement.port,
                                    lastSeenEpochMs = now,
                                )
                                hostMap[announcement.hostId] = host
                                lastAnnouncementSeenAtMs = now
                                println(
                                    "DISCOVERY_ANDROID: announcement hostId=${announcement.hostId} " +
                                        "name=${announcement.name} address=$address:${announcement.port}"
                                )
                            } else {
                                delay(SOCKET_RETRY_DELAY_MS)
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Expected; used to keep loop responsive.
                        } catch (e: Exception) {
                            if (isActive) {
                                println("DISCOVERY_ANDROID: Discovery receive loop error: ${e.message}")
                            }
                        }

                        val refreshedNow = currentTimeMillis()
                        if (probeSubnets.isEmpty() || refreshedNow >= subnetRefreshAtMs) {
                            val resolution = resolveProbeSubnets(fallbackSubnetIndex)
                            probeSubnets = resolution.subnets
                            fallbackSubnetIndex = resolution.nextFallbackIndex
                            subnetRefreshAtMs = refreshedNow + SUBNET_REFRESH_INTERVAL_MS
                            val validPrefixes = probeSubnets.map { it.prefix }.toSet()
                            nextProbeOctetByPrefix.keys.retainAll(validPrefixes)
                            if (subnetCursor >= probeSubnets.size) {
                                subnetCursor = 0
                            }
                        }

                        val announcementRecentlySeen =
                            lastAnnouncementSeenAtMs > 0L &&
                                (refreshedNow - lastAnnouncementSeenAtMs) <= ANNOUNCEMENT_GRACE_MS
                        // Keep probing even when announcements are present so we don't miss
                        // hosts that are on-LAN but temporarily silent on UDP broadcast.
                        if (probeSubnets.isNotEmpty() && refreshedNow >= nextProbeAtMs) {
                            val subnet = probeSubnets[subnetCursor % probeSubnets.size]
                            subnetCursor = (subnetCursor + 1) % probeSubnets.size
                            val startOctet = nextProbeOctetByPrefix[subnet.prefix]
                                ?: ((subnet.selfOctet % 254) + 1).coerceAtLeast(1)
                            val probeResult = probeNextBatch(
                                subnet = subnet,
                                startOctet = startOctet,
                                timestampMs = refreshedNow,
                            )
                            nextProbeOctetByPrefix[subnet.prefix] = probeResult.nextOctet
                            probeResult.hosts.forEach { probedHost ->
                                val alreadyKnownByAnnouncement = hostMap.values.any { existing ->
                                    existing.address == probedHost.address &&
                                        existing.port == probedHost.port &&
                                        !isProbedHost(existing)
                                }
                                if (!alreadyKnownByAnnouncement) {
                                    hostMap[probedHost.hostId] = probedHost
                                }
                            }
                            val nextIntervalMs =
                                if (announcementRecentlySeen) {
                                    PROBE_INTERVAL_WHEN_ANNOUNCEMENTS_PRESENT_MS
                                } else {
                                    PROBE_INTERVAL_MS
                                }
                            nextProbeAtMs = refreshedNow + nextIntervalMs
                        }

                        val activeHosts = hostMap.values.filter { host ->
                            val ttl = if (isProbedHost(host)) PROBED_HOST_TTL_MS else HOST_TTL_MS
                            refreshedNow - host.lastSeenEpochMs <= ttl
                        }
                        _hosts.value = deduplicateHosts(activeHosts)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        println("DISCOVERY_ANDROID: Discovery stopped due to error: ${e.message}")
                    }
                } finally {
                    runCatching { socket?.close() }
                    if (discoverySocket === socket) {
                        discoverySocket = null
                    }
                    multicastLock?.release()
                }
            }
        }
    }

    actual suspend fun stopDiscovery() {
        val jobToCancel = discoveryMutex.withLock {
            runCatching { discoverySocket?.close() }
            discoverySocket = null
            val job = discoveryJob
            discoveryJob = null
            job
        }
        jobToCancel?.cancelAndJoin()
        _hosts.value = emptyList()
    }

    actual suspend fun startBroadcast(hostInfo: HostInfo) {
        if (broadcastJob != null) return
        broadcastJob = scope.launch {
            val socket = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrElse { error ->
                println("DISCOVERY_ANDROID: Failed to open broadcast socket: ${error.message}")
                return@launch
            }
            val message = runCatching {
                ShareXJson.encodeToString(
                    DiscoveryAnnouncement(
                        hostId = hostInfo.hostId,
                        name = hostInfo.name,
                        port = hostInfo.port,
                        hostAddress = hostInfo.address,
                    )
                )
            }.getOrElse { error ->
                println("DISCOVERY_ANDROID: Failed to serialize discovery announcement: ${error.message}")
                socket.close()
                return@launch
            }
            val data = message.encodeToByteArray()
            val broadcastAddresses = mutableSetOf<InetAddress>()
            broadcastAddresses.add(InetAddress.getByName("255.255.255.255"))
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
            }.getOrDefault(emptyList()).forEach { iface ->
                runCatching {
                    iface.interfaceAddresses.forEach { addr ->
                        addr.broadcast?.let { broadcastAddresses.add(it) }
                    }
                }.onFailure {
                    println("DISCOVERY_ANDROID: Skipping interface ${iface.name}: ${it.message}")
                }
            }
            try {
                while (isActive) {
                    val blockedAddresses = mutableListOf<InetAddress>()
                    broadcastAddresses.forEach { address ->
                        runCatching {
                            val packet = DatagramPacket(data, data.size, address, DISCOVERY_PORT)
                            socket.send(packet)
                        }.onFailure {
                            val messageText = it.message.orEmpty()
                            if ("EPERM" in messageText || "Operation not permitted" in messageText) {
                                blockedAddresses += address
                                println(
                                    "DISCOVERY_ANDROID: Broadcast blocked for ${address.hostAddress}, " +
                                        "removing from retry list",
                                )
                            } else {
                                // No-network situations can fail here; keep app running and retry later.
                                println(
                                    "DISCOVERY_ANDROID: Broadcast send failed for ${address.hostAddress}: ${it.message}",
                                )
                            }
                        }
                    }
                    if (blockedAddresses.isNotEmpty()) {
                        broadcastAddresses.removeAll(blockedAddresses.toSet())
                        if (broadcastAddresses.isEmpty()) {
                            println(
                                "DISCOVERY_ANDROID: No permitted broadcast addresses remain, " +
                                    "stopping UDP broadcast loop",
                            )
                            break
                        }
                    }
                    delay(BROADCAST_INTERVAL_MS)
                }
            } finally {
                socket.close()
            }
        }
    }

    actual suspend fun stopBroadcast() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    private fun currentTimeMillis(): Long = System.currentTimeMillis()

    private suspend fun probeNextBatch(
        subnet: SubnetInfo,
        startOctet: Int,
        timestampMs: Long,
    ): ProbeBatchResult = coroutineScope {
        val octets = buildProbeOctets(
            selfOctet = subnet.selfOctet,
            startOctet = startOctet,
        )
        if (octets.isEmpty()) {
            return@coroutineScope ProbeBatchResult(nextOctet = startOctet, hosts = emptyList())
        }

        val hosts = octets.map { octet ->
            async {
                val address = "${subnet.prefix}.$octet"
                val resolvedHostName = probeHostName(address)
                val isReachable = resolvedHostName != null || probeHost(address)
                if (isReachable) {
                    println("DISCOVERY_ANDROID: found host via probe at $address:$SIGNALING_PORT")
                    DiscoveredHost(
                        hostId = probeHostKey(address),
                        name = resolvedHostName ?: "$PROBED_HOST_NAME_PREFIX $address",
                        address = address,
                        port = SIGNALING_PORT,
                        lastSeenEpochMs = timestampMs,
                    )
                } else {
                    null
                }
            }
        }.awaitAll().filterNotNull()

        val nextOctet = (octets.last() % 254) + 1
        ProbeBatchResult(nextOctet = nextOctet, hosts = hosts)
    }

    private fun buildProbeOctets(selfOctet: Int, startOctet: Int): List<Int> {
        val safeStart = when {
            startOctet < 1 -> 1
            startOctet > 254 -> 1
            else -> startOctet
        }

        val octets = mutableListOf<Int>()
        var current = safeStart
        while (octets.size < PROBE_BATCH_SIZE && octets.size < 254) {
            if (current != selfOctet) {
                octets += current
            }
            current = (current % 254) + 1
        }
        return octets
    }

    private fun probeHost(address: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(
                    InetSocketAddress(address, SIGNALING_PORT),
                    PROBE_CONNECT_TIMEOUT_MS,
                )
                true
            }
        }.getOrDefault(false)
    }

    private fun probeHostName(address: String): String? {
        return runCatching {
            val connection = (URL("http://$address:$SIGNALING_PORT$HOST_NAME_ENDPOINT_PATH")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = PROBE_CONNECT_TIMEOUT_MS
                readTimeout = PROBE_CONNECT_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            try {
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    return@runCatching null
                }
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }
                    .trim()
                    .takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun resolveProbeSubnets(fallbackIndex: Int): SubnetResolution {
        val subnetsByPrefix = linkedMapOf<String, SubnetInfo>()

        val preferredSubnet = getLocalIpAddress()?.let { subnetInfoFromAddress(it) }
        if (preferredSubnet != null) {
            subnetsByPrefix[preferredSubnet.prefix] = preferredSubnet
        }

        runCatching { NetworkInterface.getNetworkInterfaces().toList() }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { iface -> isEligibleNetworkInterface(iface) }
            .forEach { iface ->
                iface.inetAddresses.toList().forEach { address ->
                    val ipv4 = address as? Inet4Address ?: return@forEach
                    val hostAddress = ipv4.hostAddress ?: return@forEach
                    if (!isUsableIpv4(hostAddress, ipv4)) return@forEach
                    val subnet = subnetInfoFromAddress(hostAddress) ?: return@forEach
                    subnetsByPrefix.putIfAbsent(subnet.prefix, subnet)
                }
            }

        if (subnetsByPrefix.isNotEmpty()) {
            return SubnetResolution(
                subnets = subnetsByPrefix.values.toList(),
                nextFallbackIndex = fallbackIndex,
            )
        }

        if (FALLBACK_SUBNET_PREFIXES.isEmpty()) {
            return SubnetResolution(
                subnets = emptyList(),
                nextFallbackIndex = fallbackIndex,
            )
        }

        val normalizedIndex =
            ((fallbackIndex % FALLBACK_SUBNET_PREFIXES.size) + FALLBACK_SUBNET_PREFIXES.size) %
                FALLBACK_SUBNET_PREFIXES.size
        val fallbackSubnet = SubnetInfo(
            prefix = FALLBACK_SUBNET_PREFIXES[normalizedIndex],
            selfOctet = 0,
        )
        val nextIndex = (normalizedIndex + 1) % FALLBACK_SUBNET_PREFIXES.size
        return SubnetResolution(
            subnets = listOf(fallbackSubnet),
            nextFallbackIndex = nextIndex,
        )
    }

    private fun isEligibleNetworkInterface(iface: NetworkInterface): Boolean {
        val eligible = runCatching {
            iface.isUp && !iface.isLoopback && !iface.isVirtual && !iface.isPointToPoint
        }.getOrDefault(false)
        if (!eligible) return false

        val label = "${iface.name.orEmpty()} ${iface.displayName.orEmpty()}".lowercase()
        return EXCLUDED_INTERFACE_KEYWORDS.none { keyword -> keyword in label }
    }

    private fun isUsableIpv4(hostAddress: String, address: Inet4Address): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress) {
            return false
        }
        if (address.isMulticastAddress || hostAddress == "0.0.0.0") {
            return false
        }
        val subnet = subnetInfoFromAddress(hostAddress) ?: return false
        return !subnet.prefix.startsWith("169.254.")
    }

    private fun subnetInfoFromAddress(address: String): SubnetInfo? {
        val octets = address.split(".")
        if (octets.size != 4) return null
        val parsed = octets.map { part -> part.toIntOrNull() ?: return null }
        if (parsed.any { it !in 0..255 }) return null
        return SubnetInfo(
            prefix = "${parsed[0]}.${parsed[1]}.${parsed[2]}",
            selfOctet = parsed[3],
        )
    }

    private fun deduplicateHosts(hosts: List<DiscoveredHost>): List<DiscoveredHost> {
        return hosts
            .groupBy { "${it.address}:${it.port}" }
            .values
            .map { sameEndpoint ->
                sameEndpoint
                    .sortedWith(
                        compareByDescending<DiscoveredHost> { !isProbedHost(it) }
                            .thenByDescending { it.lastSeenEpochMs },
                    )
                    .first()
            }
            .sortedBy { it.name }
    }

    private fun isProbedHost(host: DiscoveredHost): Boolean = host.hostId.startsWith(PROBE_HOST_ID_PREFIX)

    private fun probeHostKey(address: String): String = "$PROBE_HOST_ID_PREFIX$address"

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val context = AndroidContextHolder.applicationContext ?: return null
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return try {
            wifi.createMulticastLock("sharex_discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class SubnetInfo(
        val prefix: String,
        val selfOctet: Int,
    )

    private data class ProbeBatchResult(
        val nextOctet: Int,
        val hosts: List<DiscoveredHost>,
    )

    private data class SubnetResolution(
        val subnets: List<SubnetInfo>,
        val nextFallbackIndex: Int,
    )
}

// F-008: Max discovery packets processed per second to prevent UDP flood DoS
private const val MAX_DISCOVERY_PACKETS_PER_SECOND: Int = 50
private const val HOST_TTL_MS: Long = 10_000
private const val PROBED_HOST_TTL_MS: Long = 30_000
private const val BROADCAST_INTERVAL_MS: Long = 1_000
private const val SUBNET_REFRESH_INTERVAL_MS: Long = 15_000
private const val ANNOUNCEMENT_GRACE_MS: Long = 2_500
private const val PROBE_INTERVAL_MS: Long = 700
private const val PROBE_INTERVAL_WHEN_ANNOUNCEMENTS_PRESENT_MS: Long = 900
private const val SOCKET_TIMEOUT_MS: Int = 250
private const val SOCKET_RETRY_DELAY_MS: Long = 300
private const val PROBE_BATCH_SIZE: Int = 20
private const val PROBE_CONNECT_TIMEOUT_MS: Int = 300
private const val PROBE_HOST_ID_PREFIX: String = "probe:"
private const val PROBED_HOST_NAME_PREFIX: String = "ShareX Host"
private val EXCLUDED_INTERFACE_KEYWORDS: List<String> = listOf(
    "vmware",
    "virtualbox",
    "vbox",
    "hyper-v",
    "docker",
    "bridge",
    "veth",
    "loopback",
    "tun",
    "tap",
    "vpn",
    "utun",
    "awdl",
    "zerotier",
    "tailscale",
    "hamachi",
)
private val FALLBACK_SUBNET_PREFIXES: List<String> = listOf(
    "192.168.100",
    "192.168.80",
    "192.168.1",
    "192.168.0",
    "10.0.0",
    "10.0.1",
    "172.16.0",
)
