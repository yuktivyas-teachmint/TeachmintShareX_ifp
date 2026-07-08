package com.teachmint.sharex.share.shared

const val REMOTE_PIN_LENGTH: Int = 6

private val RemotePinRegex = Regex("^\\d{$REMOTE_PIN_LENGTH}$")

fun sanitizeRemotePin(input: String): String {
    return input.filter { it.isDigit() }.take(REMOTE_PIN_LENGTH)
}

fun isValidRemotePin(input: String): Boolean {
    return RemotePinRegex.matches(input)
}
