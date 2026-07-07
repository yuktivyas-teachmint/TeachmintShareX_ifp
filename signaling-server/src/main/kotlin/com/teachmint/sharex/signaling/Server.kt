package com.teachmint.sharex.signaling

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8090

    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        configureServer()
    }.start(wait = true)
}

fun Application.configureServer() {
    // V-008: Fail-closed — require auth token to start.
    val authToken: String = System.getenv("SIGNALING_AUTH_TOKEN")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: error("FATAL: SIGNALING_AUTH_TOKEN is required. Server will not start without authentication. Set the SIGNALING_AUTH_TOKEN environment variable.")

    install(CallLogging) {
        level = Level.INFO
    }

    // Populate call.request.origin with real client IP from X-Forwarded-For / X-Real-IP
    // when running behind a reverse proxy (nginx, ALB, Fly.io, etc.)
    install(XForwardedHeaders)

    install(CORS) {
        // V-009: Fail-closed CORS — require explicit origins in production.
        val allowedOrigins = System.getenv("CORS_ALLOWED_ORIGINS")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (allowedOrigins.isEmpty()) {
            println("WARNING: CORS_ALLOWED_ORIGINS not set — no cross-origin requests will be allowed. Set CORS_ALLOWED_ORIGINS for web client support.")
            // Fail-closed: no anyHost() fallback. Only same-origin requests will work.
        } else {
            allowedOrigins.forEach { rawOrigin ->
                // Ktor's allowHost() rejects values containing "://" — it wants a bare
                // host[:port] and schemes passed separately. Accept either form in the
                // env var so "https://foo.com" and "foo.com" both work.
                val host = rawOrigin
                    .substringAfter("://")
                    .substringBefore("/")
                    .trim()
                if (host.isNotEmpty()) {
                    allowHost(host, schemes = listOf("https", "http"))
                }
            }
        }
        allowHeader("Content-Type")
        allowHeader("Authorization")
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = 50 * 1024 * 1024 // 50 MB - increased for file transfer relay
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    val signalingService = SignalingService()

    routing {
        webSocket("/ws") {
            // V-008: Auth token is always required
            val providedToken = call.request.queryParameters["token"]
                ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            if (providedToken != authToken) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                return@webSocket
            }
            val clientIp = call.request.origin.remoteHost
            signalingService.handleConnection(this, clientIp)
        }

        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
    }
}
