package com.teachmint.sharex.share.shared

const val SIGNALING_PORT: Int = 9090
const val DISCOVERY_PORT: Int = 37020
const val HOST_NAME_ENDPOINT_PATH: String = "/host-name"
val APP_VERSION: String get() = GeneratedBuildSecrets.APP_VERSION_NAME
val APP_VERSION_CODE: Int get() = GeneratedBuildSecrets.APP_VERSION_CODE
const val FILE_UPLOAD_ENDPOINT_PATH: String = "/upload"
