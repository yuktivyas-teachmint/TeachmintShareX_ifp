package com.teachmint.sharex.share.shared

/**
 * Configuration for remote signaling server
 */
object RemoteServerConfig {
    /**
     * Default remote server URL.
     *
     * Resolution order at build time (see :composeApp:generateBuildSecrets):
     *   1. `SHAREX_REMOTE_SIGNALING_URL` environment variable
     *   2. `signaling.server.url` in local.properties
     *   3. Hardcoded QA fallback below (used when neither is set).
     *
     * At runtime the value can still be overridden by env / system property /
     * shared preferences — see the platform `readRuntimeRemoteServerUrlOverride`.
     */
    private const val FALLBACK_REMOTE_SERVER_URL: String = "wss://spectacle.teachmint.qa/ws"
    val DEFAULT_REMOTE_SERVER_URL: String =
        GeneratedBuildSecrets.SIGNALING_SERVER_URL.takeIf { it.isNotBlank() }
            ?: FALLBACK_REMOTE_SERVER_URL
    private const val LEGACY_REMOTE_SERVER_URL: String = "wss://sharex.teachmint.com/ws"
    private val normalizedDefaultRemoteServerUrl: String? = normalizeRemoteServerUrl(DEFAULT_REMOTE_SERVER_URL)

    /**
     * URL of the remote signaling server for web client connections
     * Set to null to disable remote server features
     *
     * Examples:
     * - Local development: "ws://localhost:8090/ws"
     * - Production: "wss://your-signaling-server.com/ws"
     */
    val REMOTE_SERVER_URL: String? = run {
        val runtimeOverride = migrateLegacyRemoteServerUrl(readRuntimeRemoteServerUrlOverride())
        runtimeOverride ?: normalizedDefaultRemoteServerUrl
    }

    /**
     * Auth token for the remote signaling server.
     * V-007: Token is now sent via Authorization header instead of URL query parameter
     * to prevent leakage in server logs, Referer headers, and browser history.
     *
     * Priority: runtime override (env/sysprop/prefs) > compile-time default below.
     * The default is injected at build time by :composeApp:generateBuildSecrets
     * from `signaling.auth.token` in local.properties or the
     * `SIGNALING_AUTH_TOKEN` environment variable.
     */
    private val DEFAULT_SIGNALING_AUTH_TOKEN: String = GeneratedBuildSecrets.SIGNALING_AUTH_TOKEN
    val SIGNALING_AUTH_TOKEN: String? = run {
        val runtimeToken = readRuntimeSignalingAuthToken()
        if (!runtimeToken.isNullOrBlank()) runtimeToken
        else DEFAULT_SIGNALING_AUTH_TOKEN.takeIf { it.isNotBlank() }
    }

    /**
     * Whether to enable remote signaling for web clients
     */
    val isRemoteSignalingEnabled: Boolean
        get() = REMOTE_SERVER_URL != null

    /**
     * V-007: Returns the base URL without the auth token appended.
     * The auth token should now be sent via the Authorization header or as the
     * first WebSocket message, not as a URL query parameter which leaks into logs.
     *
     * @deprecated Use [SIGNALING_AUTH_TOKEN] directly to set the Authorization header.
     */
    fun authenticatedUrl(baseUrl: String): String {
        // V-007: No longer append token to URL — callers should use Authorization header instead
        return baseUrl
    }

    fun normalizeRemoteServerUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        val candidate = when {
            trimmed.startsWith("ws://", ignoreCase = true) ||
                trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) ->
                "ws://${trimmed.substringAfter("://")}"
            trimmed.startsWith("https://", ignoreCase = true) ->
                "wss://${trimmed.substringAfter("://")}"
            else -> "wss://$trimmed"
        }

        val schemeSeparatorIndex = candidate.indexOf("://")
        if (schemeSeparatorIndex < 0) return null

        // Upgrade ws:// to wss:// for non-local hosts
        val authority = candidate.substring(schemeSeparatorIndex + 3).substringBefore('/')
        val host = if (authority.startsWith('[')) {
            // IPv6 bracketed address, e.g. [::1]:8080 -> [::1]
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBefore(':')
        }
        val isLocal = host == "localhost" ||
            host.startsWith("127.") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.startsWith("169.254.") ||
            host == "[::1]" ||
            (host.startsWith("172.") && run {
                val secondOctet = host.removePrefix("172.").substringBefore('.').toIntOrNull()
                secondOctet != null && secondOctet in 16..31
            })
        val upgraded = if (candidate.startsWith("ws://", ignoreCase = true) && !isLocal) {
            "wss://${candidate.substringAfter("://")}"
        } else {
            candidate
        }

        val pathStartIndex = upgraded.indexOf('/', startIndex = upgraded.indexOf("://") + 3)
        if (pathStartIndex < 0) {
            return "$upgraded/ws"
        }

        val pathAndQuery = upgraded.substring(pathStartIndex)
        if (pathAndQuery.isBlank() || pathAndQuery == "/") {
            return "${upgraded.substring(0, pathStartIndex)}/ws"
        }

        return upgraded
    }

    fun migrateLegacyRemoteServerUrl(rawUrl: String?): String? {
        val normalized = normalizeRemoteServerUrl(rawUrl) ?: return null
        val normalizedLegacy = normalizeRemoteServerUrl(LEGACY_REMOTE_SERVER_URL)
        if (!normalizedLegacy.isNullOrBlank() && normalized.equals(normalizedLegacy, ignoreCase = true)) {
            return normalizedDefaultRemoteServerUrl
        }
        return normalized
    }
}
