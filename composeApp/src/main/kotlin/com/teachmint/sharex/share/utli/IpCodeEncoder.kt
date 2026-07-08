package com.teachmint.sharex.share.utli

object IpCodeEncoder {
    private const val BASE = 62L
    private const val CODE_LENGTH = 6
    private const val MAX_IPV4 = 0xFFFF_FFFFL
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun encode(ipAddress: String): String? {
        val ipAsLong = ipToLong(ipAddress) ?: return null
        return longToCode(ipAsLong)
    }

    fun decode(code: String): String? {
        if (code.length != CODE_LENGTH) return null

        var value = 0L
        for (char in code) {
            val digit = ALPHABET.indexOf(char)
            if (digit < 0) return null
            value = value * BASE + digit
            if (value > MAX_IPV4) return null
        }

        return longToIp(value)
    }

    private fun ipToLong(ipAddress: String): Long? {
        val octets = ipAddress.trim().split(".")
        if (octets.size != 4) return null

        var value = 0L
        for (octet in octets) {
            val part = octet.toIntOrNull() ?: return null
            if (part !in 0..255) return null
            value = (value shl 8) or part.toLong()
        }

        return value
    }

    private fun longToIp(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    private fun longToCode(value: Long): String {
        if (value == 0L) return "0".repeat(CODE_LENGTH)

        var remaining = value
        val encoded = StringBuilder()
        while (remaining > 0) {
            encoded.append(ALPHABET[(remaining % BASE).toInt()])
            remaining /= BASE
        }

        return encoded.reverse().toString().padStart(CODE_LENGTH, '0')
    }
}
