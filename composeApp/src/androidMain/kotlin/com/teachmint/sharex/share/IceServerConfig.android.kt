package com.teachmint.sharex.share.shared

private fun readAndroidRuntimeProperty(sysProp: String, envVar: String): String? {
    val fromSys = System.getProperty(sysProp)?.trim()?.takeIf { it.isNotBlank() }
    if (fromSys != null) return fromSys
    return System.getenv(envVar)?.trim()?.takeIf { it.isNotBlank() }
}

actual fun readRuntimeIceTurnUrls(): String? =
    readAndroidRuntimeProperty("sharex.turn.urls", "SHAREX_TURN_URLS")

actual fun readRuntimeIceTurnUsername(): String? =
    readAndroidRuntimeProperty("sharex.turn.username", "SHAREX_TURN_USERNAME")

actual fun readRuntimeIceTurnCredential(): String? =
    readAndroidRuntimeProperty("sharex.turn.credential", "SHAREX_TURN_CREDENTIAL")

actual fun readRuntimeIceServersOverride(): String? {
    val fromSystemProperty = System.getProperty("sharex.ice.servers")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (fromSystemProperty != null) return fromSystemProperty

    return System.getenv("SHAREX_ICE_SERVERS")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}
