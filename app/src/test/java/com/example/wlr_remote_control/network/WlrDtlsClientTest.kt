package com.example.wlr_remote_control.network

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WlrDtlsClientTest {
    @Test
    fun encodeMousePacket_usesNetworkByteOrderAndExpectedLayout() {
        val actual = WlrDtlsClient.encodeMousePacket(
            dx = 0x01020304,
            dy = -2,
            button = 0x1122,
            buttonState = 1
        )

        val expected = byteArrayOf(
            0x01,
            0x02,
            0x03,
            0x04,
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFE.toByte(),
            0x11,
            0x22,
            0x00,
            0x01
        )

        assertArrayEquals(expected, actual)
    }
}

