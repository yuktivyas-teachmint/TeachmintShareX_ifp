package com.teachmint.sharex.share.shared

import com.teachmint.sharex.share.utli.IpCodeEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IpCodeEncoderTest {

    @Test
    fun encode_thenDecode_returnsOriginalIp() {
        val ips = listOf(
            "0.0.0.0",
            "10.0.0.12",
            "192.168.1.1",
            "255.255.255.255",
        )

        ips.forEach { ip ->
            val code = IpCodeEncoder.encode(ip)
            assertNotNull(code)
            assertEquals(6, code.length)
            assertEquals(ip, IpCodeEncoder.decode(code))
        }
    }

    @Test
    fun encode_returnsNull_forInvalidIpAddress() {
        assertNull(IpCodeEncoder.encode(""))
        assertNull(IpCodeEncoder.encode("192.168.1"))
        assertNull(IpCodeEncoder.encode("192.168.1.999"))
        assertNull(IpCodeEncoder.encode("host.local"))
    }

    @Test
    fun decode_returnsNull_forInvalidCode() {
        assertNull(IpCodeEncoder.decode(""))
        assertNull(IpCodeEncoder.decode("12345"))
        assertNull(IpCodeEncoder.decode("abc1234"))
        assertNull(IpCodeEncoder.decode("ab$123"))
    }

    @Test
    fun decode_returnsNull_whenCodeOverflowsIpv4Range() {
        assertNull(IpCodeEncoder.decode("zzzzzz"))
    }
}
