package com.teachmint.sharex.share.shared

actual fun readRuntimeRemoteServerUrlOverride(): String? {
    val fromSystemProperty = System.getProperty("sharex.remote.signaling.url")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    if (fromSystemProperty != null) return RemoteServerConfig.migrateLegacyRemoteServerUrl(fromSystemProperty)

    return RemoteServerConfig.migrateLegacyRemoteServerUrl(
        System.getenv("SHAREX_REMOTE_SIGNALING_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    )
}

actual fun readRuntimeSignalingAuthToken(): String? {
    return System.getProperty("sharex.signaling.auth.token")
        ?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv("SIGNALING_AUTH_TOKEN")
            ?.trim()?.takeIf { it.isNotBlank() }
}
