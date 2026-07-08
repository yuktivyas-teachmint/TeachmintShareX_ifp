package com.teachmint.sharex.androidapp.ota

/**
 * Backend endpoint (relative to [com.teachmint.sharex.share.shared.NetworkConfig.teachmintBaseUrl])
 * that returns the latest APK metadata:
 * `{status, msg, obj: {version, apk_url, force_update_versions}}`.
 *
 * NOTE: this is the same route chakra (EduAssist) polls. The backend must either expose an
 * IFP-specific route or serve per-app results keyed off the [HEADER_APP_ID] header —
 * cross-team dependency tracked with the platform team.
 */
const val OTA_APK_VERSION_ENDPOINT = "global-edu-ai/app/latest/version"

const val HEADER_SERIAL_NUMBER = "serialnumber"
const val HEADER_UNIQUE_DEVICE_ID = "unique-device-id"
const val HEADER_APP_ID = "app-id"
