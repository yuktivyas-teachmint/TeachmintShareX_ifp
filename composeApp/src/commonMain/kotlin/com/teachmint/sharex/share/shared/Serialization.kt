package com.teachmint.sharex.share.shared

import kotlinx.serialization.json.Json

val ShareXJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    classDiscriminator = "type"
}
