package com.teachmint.sharex.share.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Data embedded in QR code for easy client connection
 */
@Serializable
data class QRConnectionData(
    val hostIp: String,
    val port: Int = 9090,
    val hostName: String,
    val protocol: String = "ws"
) {
    init {
        require(protocol == "ws" || protocol == "wss") { "Protocol must be ws or wss" }
    }

    fun toUrl(): String = "$protocol://$hostIp:$port/ws"

    fun toJson(): String = Json.encodeToString(this)

    companion object {
        private val parser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fromJson(json: String): QRConnectionData? {
            val payload = json.trim()
            if (payload.isEmpty()) return null

            return runCatching {
                parser.decodeFromString<QRConnectionData>(payload)
            }.getOrNull() ?: parseUrlPayload(payload)
        }

        private fun parseUrlPayload(payload: String): QRConnectionData? {
            val regex = Regex("""^(wss?)://([^:/\s]+)(?::(\d+))?/?.*$""", RegexOption.IGNORE_CASE)
            val match = regex.matchEntire(payload) ?: return null
            val protocol = match.groupValues.getOrNull(1)?.lowercase() ?: "ws"
            val host = match.groupValues.getOrNull(2).orEmpty()
            if (host.isBlank()) return null
            val port = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 9090

            return QRConnectionData(
                hostIp = host,
                port = port,
                hostName = host,
                protocol = protocol
            )
        }
    }
}
