package com.teachmint.sharex.share.shared

/**
 * Applies sender-side bitrate hints while preserving adaptive behavior.
 *
 * Useful for both initial share and reverse mirroring negotiation paths so
 * sender quality does not regress due to missing SDP bitrate constraints.
 */
fun applyHighQualityVideoSdpHints(
    sdp: SessionDescriptionData,
    platformName: String,
    logPrefix: String,
): SessionDescriptionData {
    val isIosClient = platformName.contains("iOS", ignoreCase = true)
    val isAndroidClient = platformName.contains("Android", ignoreCase = true)
    val isDesktopClient = platformName.contains("Desktop", ignoreCase = true)
    val disableCpuOveruseDetection = isIosClient
    val targetMaxKbps = when {
        isIosClient -> 1800
        isAndroidClient -> 2800
        isDesktopClient -> 6500
        else -> 6500
    }
    val targetMinKbps = when {
        isIosClient -> 300
        isAndroidClient -> 700
        isDesktopClient -> 1200
        else -> 1500
    }
    val targetStartKbps = when {
        isIosClient -> 800
        isAndroidClient -> 1200
        isDesktopClient -> 3000
        else -> 3200
    }
    val lines = sdp.sdp.lines()
    val modifiedLines = mutableListOf<String>()
    var inVideoSection = false
    var inAudioSection = false
    var bitrateAdded = false
    // Discover the Opus payload type from a=rtpmap lines in the audio section.
    var opusPayloadType: String? = null

    // First pass: find the Opus payload type in the audio section.
    var scanAudio = false
    for (line in lines) {
        if (line.startsWith("m=audio")) scanAudio = true
        else if (line.startsWith("m=") && !line.startsWith("m=audio")) scanAudio = false
        if (scanAudio && line.startsWith("a=rtpmap:") && line.contains("opus/48000", ignoreCase = true)) {
            opusPayloadType = line.removePrefix("a=rtpmap:").substringBefore(' ')
        }
    }

    for (line in lines) {
        when {
            line.startsWith("m=video") -> {
                inVideoSection = true
                inAudioSection = false
                bitrateAdded = false
                modifiedLines.add(line)
            }
            line.startsWith("m=audio") -> {
                inVideoSection = false
                inAudioSection = true
                modifiedLines.add(line)
            }
            line.startsWith("m=") && !line.startsWith("m=video") && !line.startsWith("m=audio") -> {
                inVideoSection = false
                inAudioSection = false
                modifiedLines.add(line)
            }
            inVideoSection && (line.startsWith("b=AS:") || line.startsWith("b=TIAS:")) -> {
                // Replace existing bitrate caps with tuned values.
            }
            inVideoSection && line.startsWith("c=") && !bitrateAdded -> {
                modifiedLines.add(line)
                modifiedLines.add("b=AS:$targetMaxKbps")
                modifiedLines.add("b=TIAS:${targetMaxKbps * 1000}")
                bitrateAdded = true
                println(
                    "$logPrefix: Added bitrate constraints " +
                        "(min=${targetMinKbps}kbps, start=${targetStartKbps}kbps, max=${targetMaxKbps}kbps)"
                )
            }
            inVideoSection && line.startsWith("a=fmtp:") -> {
                val hasMax = line.contains("x-google-max-bitrate")
                val hasMin = line.contains("x-google-min-bitrate")
                val hasStart = line.contains("x-google-start-bitrate")
                val hasCpuOveruse = line.contains("x-google-cpu-overuse-detection")
                val hasSuspendBelowMin = line.contains("x-google-suspend-below-min-bitrate")
                val modifiedFmtp = buildString {
                    append(line)
                    if (!hasMin) append(";x-google-min-bitrate=$targetMinKbps")
                    if (!hasStart) append(";x-google-start-bitrate=$targetStartKbps")
                    if (!hasMax) append(";x-google-max-bitrate=$targetMaxKbps")
                    if (disableCpuOveruseDetection && !hasCpuOveruse) {
                        append(";x-google-cpu-overuse-detection=false")
                    }
                    if (!hasSuspendBelowMin) append(";x-google-suspend-below-min-bitrate=false")
                }
                modifiedLines.add(modifiedFmtp)
                println("$logPrefix: Added high-motion bitrate parameters")
            }
            // Boost Opus audio bitrate from default ~32kbps to 128kbps for
            // clear system audio (music, video playback, etc.).
            // Also disable DTX (comfort noise) which adds artificial hiss
            // during quiet passages — undesirable for system audio sharing.
            inAudioSection && opusPayloadType != null &&
                line.startsWith("a=fmtp:$opusPayloadType ") -> {
                var modified = line
                // Set or replace maxaveragebitrate.
                if (modified.contains("maxaveragebitrate")) {
                    modified = modified.replace(
                        Regex("maxaveragebitrate=\\d+"),
                        "maxaveragebitrate=$OPUS_MAX_AVERAGE_BITRATE"
                    )
                } else {
                    modified = "$modified;maxaveragebitrate=$OPUS_MAX_AVERAGE_BITRATE"
                }
                // Disable DTX to prevent comfort noise during silence.
                if (modified.contains("usedtx")) {
                    modified = modified.replace(Regex("usedtx=\\d+"), "usedtx=0")
                } else {
                    modified = "$modified;usedtx=0"
                }
                modifiedLines.add(modified)
                println("$logPrefix: Set Opus maxaveragebitrate=${OPUS_MAX_AVERAGE_BITRATE / 1000}kbps, usedtx=0")
            }
            else -> {
                modifiedLines.add(line)
            }
        }
    }

    val modifiedSdpString = modifiedLines.joinToString("\r\n")
    println("$logPrefix: Modified SDP for high-quality streaming - length: ${modifiedSdpString.length}")
    return sdp.copy(sdp = modifiedSdpString)
}

/** Opus max average bitrate in bits per second (128 kbps). */
private const val OPUS_MAX_AVERAGE_BITRATE = 128_000
