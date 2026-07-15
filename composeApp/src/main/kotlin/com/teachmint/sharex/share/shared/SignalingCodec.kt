package com.teachmint.sharex.share.shared

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Encodes signaling messages with polymorphic base serializer so `type` is always emitted.
 */
fun encodeSignalingMessage(message: SignalingMessage): String {
    return ShareXJson.encodeToString<SignalingMessage>(message)
}

/**
 * Decodes signaling messages. Accepts canonical polymorphic payloads and legacy payloads
 * that may omit the top-level `type` discriminator.
 */
fun decodeSignalingMessage(payload: String): SignalingMessage {
    return runCatching {
        ShareXJson.decodeFromString<SignalingMessage>(payload)
    }.getOrElse { originalError ->
        val legacy = runCatching {
            val element = ShareXJson.decodeFromString<JsonElement>(payload)
            decodeSignalingElement(element)
        }.getOrNull()

        legacy ?: throw originalError
    }
}

private fun decodeSignalingElement(element: JsonElement): SignalingMessage? {
    val typed = runCatching {
        ShareXJson.decodeFromString<SignalingMessage>(element.toString())
    }.getOrNull()
    if (typed != null) {
        return typed
    }

    val obj = element as? JsonObject ?: return null

    val from = obj.string("from")
    val to = obj.string("to")
    val nestedPayload = obj["payload"]
    if (from != null && to != null && nestedPayload != null) {
        val nested = decodeSignalingElement(nestedPayload) ?: return null
        return SignalingMessage.Relay(from = from, to = to, payload = nested)
    }

    val hostsElement = obj["hosts"]
    if (hostsElement != null) {
        val hosts = runCatching {
            ShareXJson.decodeFromString<List<RemoteHostInfo>>(hostsElement.toString())
        }.getOrNull()
        if (hosts != null) {
            return SignalingMessage.HostsList(hosts = hosts)
        }
    }

    obj.string("message")?.let { message ->
        return SignalingMessage.Error(message = message)
    }

    val hostId = obj.string("hostId")
    val hostName = obj.string("hostName")
    val platform = obj.string("platform")
    if (hostId != null && hostName != null && platform != null) {
        return SignalingMessage.RegisterHost(
            hostId = hostId,
            hostName = hostName,
            platform = platform,
            pin = obj.string("pin"),
            pinExpiresAtEpochMs = obj.long("pinExpiresAtEpochMs"),
        )
    }

    val clientId = obj.string("clientId")
    val clientName = obj.string("clientName")
    if (hostId != null && clientId != null && clientName != null) {
        return SignalingMessage.JoinHost(
            hostId = hostId,
            clientId = clientId,
            clientName = clientName,
            platform = platform,
        )
    }

    val pin = obj.string("pin")
    if (pin != null && clientId != null && clientName != null) {
        return SignalingMessage.JoinHostByPin(
            pin = pin,
            clientId = clientId,
            clientName = clientName,
            platform = platform,
        )
    }

    if (clientId != null && clientName != null) {
        return SignalingMessage.Hello(
            clientId = clientId,
            clientName = clientName,
            platform = platform,
        )
    }

    return null
}

private fun JsonObject.string(name: String): String? {
    return this[name]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.long(name: String): Long? {
    return this[name]?.jsonPrimitive?.longOrNull
}
