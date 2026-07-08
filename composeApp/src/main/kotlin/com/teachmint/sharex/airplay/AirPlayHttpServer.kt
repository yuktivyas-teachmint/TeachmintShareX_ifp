package com.teachmint.sharex.airplay

import android.util.Log
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AirPlay HTTP server (port 7000).
 *
 *  GET  /info        → XML plist with device capabilities
 *  POST /pair-setup  → SRP6a pairing handshake (phases 1-3)
 *  POST /pair-verify → Curve25519 session key establishment (steps 1-2)
 */
class AirPlayHttpServer(
    private val deviceInfo: AirPlayDeviceInfo,
    private val pairingHandler: AirPlayPairingHandler,
    private val port: Int = AirPlayProtocol.HTTP_PORT,
) {
    // Dedicated scope so Ktor's server coroutines are NOT children of the calling coroutine.
    // In Ktor 3.x, start(wait=false) still creates child coroutines that keep withContext alive forever.
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        server = embeddedServer(CIO, port = port) {
            routing {
                get("/info")         { handleInfo(call) }
                get("/server-info")  { handleInfo(call) }
                post("/pair-setup")  { handlePairSetup(call) }
                post("/pair-verify") { handlePairVerify(call) }
                post("/fp-setup")    {
                    Log.d("AirPlay", "HTTP: POST /fp-setup → 200 stub")
                    call.respond(HttpStatusCode.OK)
                }
                post("/feedback")    {
                    Log.d("AirPlay", "HTTP: POST /feedback → 200 stub")
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
        serverScope.launch { server?.start(wait = true) }
        Log.d("AirPlay", "HTTP: server started on port $port")
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        serverScope.cancel()
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
        Log.d("AirPlay", "HTTP: server stopped")
    }

    private suspend fun handleInfo(call: ApplicationCall) {
        Log.d("AirPlay", "HTTP: GET /info → 200 name=${deviceInfo.name}")
        val plist = buildXmlPlist(
            mapOf(
                "deviceid"    to deviceInfo.deviceId,
                "features"    to AirPlayProtocol.FEATURES_LOW,
                "name"        to deviceInfo.name,
                "model"       to AirPlayProtocol.MODEL,
                "pi"          to deviceInfo.pi,
                "pk"          to deviceInfo.publicKeyHex,
                "protovers"   to AirPlayProtocol.PROTO_VERS,
                "srcvers"     to AirPlayProtocol.SRC_VERS,
                "manufacturer" to AirPlayProtocol.MANUFACTURER,
                "sourceVersion" to AirPlayProtocol.SRC_VERS,
                "keepAliveLowPower" to true,
                "keepAliveSendStatsAsBody" to true,
                "nameIsFactoryDefault" to false,
                "vv"          to AirPlayProtocol.VV.toInt(),
                "statusFlags" to 4,
                "audioLatencies" to listOf(
                    mapOf(
                        "type" to 100,
                        "audioType" to "default",
                        "inputLatencyMicros" to 0L,
                        "outputLatencyMicros" to 400_000L,
                    ),
                    mapOf(
                        "type" to 100,
                        "audioType" to "media",
                        "inputLatencyMicros" to 0L,
                        "outputLatencyMicros" to 400_000L,
                    ),
                ),
                "audioFormats" to listOf(
                    mapOf(
                        "type" to 100,
                        "audioInputFormats" to 67_108_860L,
                        "audioOutputFormats" to 67_108_860L,
                    ),
                ),
            )
        )
        call.respondBytes(
            plist.toByteArray(Charsets.UTF_8),
            ContentType.parse("text/x-apple-plist+xml"),
        )
    }

    private suspend fun handlePairSetup(call: ApplicationCall) {
        val body = call.receiveStream().readBytes()
        Log.d("AirPlay", "HTTP: POST /pair-setup ${body.size}B state=${pairingHandler.state}")
        val response = pairingHandler.handlePairSetup(body)
        if (response.isEmpty()) {
            call.respond(HttpStatusCode.OK)
        } else {
            Log.d("AirPlay", "HTTP: /pair-setup → 200 (${response.size}B)")
            call.respondBytes(response, ContentType.parse("application/octet-stream"))
        }
    }

    private suspend fun handlePairVerify(call: ApplicationCall) {
        val body = call.receiveStream().readBytes()
        Log.d("AirPlay", "HTTP: POST /pair-verify ${body.size}B state=${pairingHandler.state}")
        val response = pairingHandler.handlePairVerify(body)
        if (response.isEmpty()) {
            call.respond(HttpStatusCode.OK)
        } else {
            Log.d("AirPlay", "HTTP: /pair-verify → 200 (${response.size}B)")
            call.respondBytes(response, ContentType.parse("application/octet-stream"))
        }
    }

    private fun buildXmlPlist(values: Map<String, Any>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""")
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        for ((key, value) in values) {
            appendLine("    <key>$key</key>")
            when (value) {
                is String  -> appendLine("    <string>$value</string>")
                is Int     -> appendLine("    <integer>$value</integer>")
                is Long    -> appendLine("    <integer>$value</integer>")
                is Boolean -> appendLine("    <${if (value) "true" else "false"}/>")
                else       -> appendLine("    <string>$value</string>")
            }
        }
        appendLine("</dict>")
        append("</plist>")
    }
}
