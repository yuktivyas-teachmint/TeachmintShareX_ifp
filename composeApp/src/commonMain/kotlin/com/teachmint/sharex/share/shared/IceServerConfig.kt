package com.teachmint.sharex.share.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

expect fun readRuntimeIceServersOverride(): String?
expect fun readRuntimeIceTurnUrls(): String?
expect fun readRuntimeIceTurnUsername(): String?
expect fun readRuntimeIceTurnCredential(): String?

@Serializable
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

object IceServerConfigDefaults {
    // Kept for callers that explicitly need no ICE servers.
    val empty: List<IceServerConfig> = emptyList()

    private val defaultStunServers: List<IceServerConfig> = listOf(
        IceServerConfig(
            urls = listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
            ),
        )
    )

    /**
     * Default TURN servers for cross-network connectivity.
     *
     * Resolution per field: runtime override (env / sysprop / prefs)
     * → build-time secret injected by :composeApp:generateBuildSecrets
     *   (from local.properties or `SHAREX_TURN_*` env vars)
     * → empty (no TURN server configured).
     *
     * TURN credentials MUST NOT be hardcoded in source code. They are pulled
     * from local.properties (dev) or CI environment variables (prod).
     */
    private const val DEFAULT_TURN_URLS: String = ""
    private const val DEFAULT_TURN_USERNAME: String = ""
    private const val DEFAULT_TURN_CREDENTIAL: String = ""

    private val defaultTurnServers: List<IceServerConfig> = run {
        val turnUrls = readRuntimeIceTurnUrls()
            ?: GeneratedBuildSecrets.TURN_URLS.takeIf { it.isNotBlank() }
        val turnUsername = readRuntimeIceTurnUsername()
            ?: GeneratedBuildSecrets.TURN_USERNAME.takeIf { it.isNotBlank() }
        val turnCredential = readRuntimeIceTurnCredential()
            ?: GeneratedBuildSecrets.TURN_CREDENTIAL.takeIf { it.isNotBlank() }
        if (turnUrls.isNullOrBlank() || turnUsername.isNullOrBlank() || turnCredential.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(
                IceServerConfig(
                    urls = turnUrls.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    username = turnUsername,
                    credential = turnCredential,
                )
            )
        }
    }

    /**
     * Combined default configuration (STUN + TURN).
     * Provides optimal connectivity for both same-network and cross-network scenarios.
     */
    private val defaultServers: List<IceServerConfig> by lazy {
        defaultStunServers + defaultTurnServers
    }

    /**
     * Runtime configurable ICE servers.
     *
     * Supported formats for `SHAREX_ICE_SERVERS` / `sharex.ice.servers` (and platform equivalents):
     * 1. JSON list:
     *    `[{"urls":["turn:turn.example.com:3478"],"username":"u","credential":"p"}]`
     * 2. Delimited string:
     *    `stun:stun.l.google.com:19302;turn:turn.example.com:3478|user|pass`
     *
     * Priority: Runtime override > Default STUN+TURN servers
     */
    val configured: List<IceServerConfig> by lazy {
        val override = parseIceServers(readRuntimeIceServersOverride())
        if (override != null) {
            println("ICE_CONFIG: Using runtime-configured ICE servers (${override.size} servers)")
            logIceServerConfiguration(override)
            override
        } else {
            println("ICE_CONFIG: Using default ICE servers (STUN + TURN)")
            logIceServerConfiguration(defaultServers)
            defaultServers
        }
    }

    /**
     * Log ICE server configuration for debugging.
     * Does NOT log credentials for security.
     */
    private fun logIceServerConfiguration(servers: List<IceServerConfig>) {
        val stunCount = servers.count { server ->
            server.urls.any { it.trim().lowercase().startsWith("stun:") }
        }
        val turnCount = servers.count { server ->
            server.urls.any { url ->
                val normalized = url.trim().lowercase()
                normalized.startsWith("turn:") || normalized.startsWith("turns:")
            }
        }

        println("ICE_CONFIG: Configured with $stunCount STUN servers and $turnCount TURN servers")

        if (turnCount == 0) {
            println("ICE_CONFIG: ⚠️ WARNING: No TURN servers configured! Cross-network connections may fail.")
        } else {
            println("ICE_CONFIG: ✅ TURN servers available for NAT traversal")
        }

        // Log server URLs (but not credentials)
        servers.forEach { server ->
            val hasCredentials = !server.username.isNullOrBlank() && !server.credential.isNullOrBlank()
            val credInfo = if (hasCredentials) " (authenticated)" else " (no auth)"
            server.urls.forEach { url ->
                println("ICE_CONFIG:   - $url$credInfo")
            }
        }
    }

    fun hasTurnServer(iceServers: List<IceServerConfig>): Boolean {
        return iceServers.any { server ->
            server.urls.any { url ->
                val normalized = url.trim().lowercase()
                normalized.startsWith("turn:") || normalized.startsWith("turns:")
            }
        }
    }

    private fun parseIceServers(raw: String?): List<IceServerConfig>? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        if (trimmed.startsWith("[")) {
            return runCatching {
                ShareXJson.decodeFromString<List<IceServerConfig>>(trimmed)
                    .mapNotNull(::normalizeServer)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        if (trimmed.startsWith("{")) {
            return runCatching {
                val server = ShareXJson.decodeFromString<IceServerConfig>(trimmed)
                listOfNotNull(normalizeServer(server))
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

        val parsedServers = trimmed
            .split(';')
            .mapNotNull { entry ->
                val normalizedEntry = entry.trim()
                if (normalizedEntry.isBlank()) return@mapNotNull null

                val parts = normalizedEntry.split('|').map { it.trim() }
                val urls = parts.first()
                    .split(',')
                    .mapNotNull(::normalizeIceUrl)
                    .distinct()
                if (urls.isEmpty()) return@mapNotNull null

                IceServerConfig(
                    urls = urls,
                    username = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                    credential = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                )
            }

        if (parsedServers.isNotEmpty()) return parsedServers

        // Backward-compatible fallback: comma/space/newline separated URLs.
        val fallbackUrls = trimmed
            .split(',', ' ', '\n', '\t')
            .mapNotNull(::normalizeIceUrl)
            .distinct()
        return if (fallbackUrls.isNotEmpty()) {
            listOf(IceServerConfig(urls = fallbackUrls))
        } else {
            null
        }
    }

    private fun normalizeServer(server: IceServerConfig): IceServerConfig? {
        val normalizedUrls = server.urls.mapNotNull(::normalizeIceUrl).distinct()
        if (normalizedUrls.isEmpty()) return null
        return server.copy(
            urls = normalizedUrls,
            username = server.username?.trim()?.takeIf { it.isNotBlank() },
            credential = server.credential?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun normalizeIceUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        val normalized = trimmed.lowercase()
        return if (
            normalized.startsWith("stun:") ||
            normalized.startsWith("turn:") ||
            normalized.startsWith("turns:")
        ) {
            trimmed
        } else {
            null
        }
    }
}
