package com.teachmint.sharex.share.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QRCodeDataTest {

    @Test
    fun fromJson_parsesStandardJsonPayload() {
        val payload = """{"hostIp":"192.168.1.11","port":9090,"hostName":"Teacher Device","protocol":"ws"}"""

        val parsed = QRConnectionData.fromJson(payload)

        assertNotNull(parsed)
        assertEquals("192.168.1.11", parsed.hostIp)
        assertEquals(9090, parsed.port)
        assertEquals("Teacher Device", parsed.hostName)
        assertEquals("ws", parsed.protocol)
    }

    @Test
    fun fromJson_trimsAndIgnoresUnknownKeys() {
        val payload = """
            
            {"hostIp":"10.0.0.2","port":9090,"hostName":"Room A","protocol":"wss","extra":"ignored"}
            
        """.trimIndent()

        val parsed = QRConnectionData.fromJson(payload)

        assertNotNull(parsed)
        assertEquals("10.0.0.2", parsed.hostIp)
        assertEquals(9090, parsed.port)
        assertEquals("Room A", parsed.hostName)
        assertEquals("wss", parsed.protocol)
    }

    @Test
    fun fromJson_parsesWsUrlPayload() {
        val payload = "ws://172.16.0.5:9191/ws"

        val parsed = QRConnectionData.fromJson(payload)

        assertNotNull(parsed)
        assertEquals("172.16.0.5", parsed.hostIp)
        assertEquals(9191, parsed.port)
        assertEquals("172.16.0.5", parsed.hostName)
        assertEquals("ws", parsed.protocol)
    }

    @Test
    fun fromJson_returnsNullForInvalidPayload() {
        val parsed = QRConnectionData.fromJson("not_a_qr_payload")

        assertNull(parsed)
    }
}
