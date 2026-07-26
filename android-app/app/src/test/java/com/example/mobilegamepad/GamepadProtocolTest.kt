package com.example.mobilegamepad

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadProtocolTest {

    private val sample = ControllerState(
        buttons = GamepadProtocol.BUTTON_A or GamepadProtocol.BUTTON_DPAD_LEFT,
        leftStickX = -32767,
        leftStickY = 32767,
        rightStickX = 1234,
        rightStickY = -1234,
        leftTrigger = 255,
        rightTrigger = 7
    )

    @Test
    fun packetIsExactly32Bytes() {
        val bytes = GamepadProtocol.encode(GamepadProtocol.TYPE_STATE, sample, 1, 1, 0)
        assertEquals(32, bytes.size)
        assertEquals(GamepadProtocol.PACKET_SIZE, bytes.size)
    }

    /**
     * Byte-for-byte expectation shared with the C# test of the same name, so
     * the two implementations cannot drift apart silently.
     */
    @Test
    fun matchesGoldenBytes() {
        val state = ControllerState(
            buttons = 0x1234,
            leftStickX = -32767,
            leftStickY = 32767,
            rightStickX = 256,
            rightStickY = -256,
            leftTrigger = 200,
            rightTrigger = 10
        )

        val actual = GamepadProtocol.encode(
            type = GamepadProtocol.TYPE_STATE,
            state = state,
            controllerId = 0xBEEF,
            sequence = 0x01020304,
            timestampMs = 0x1122334455667788L
        )

        val expected = byteArrayOf(
            0x4D, 0x47, 0x50, 0x44,                                     // "MGPD"
            0x01,                                                       // version
            0x01,                                                       // type = STATE
            0xEF.toByte(), 0xBE.toByte(),                               // controller id
            0x04, 0x03, 0x02, 0x01,                                     // sequence
            0x88.toByte(), 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11,    // timestamp
            0x34, 0x12,                                                 // buttons
            0x01, 0x80.toByte(),                                        // left stick X  (-32767)
            0xFF.toByte(), 0x7F,                                        // left stick Y  ( 32767)
            0x00, 0x01,                                                 // right stick X ( 256)
            0x00, 0xFF.toByte(),                                        // right stick Y (-256)
            0xC8.toByte(),                                              // left trigger
            0x0A                                                        // right trigger
        )

        assertArrayEquals(expected, actual)
    }

    @Test
    fun decodesItsOwnHeader() {
        val bytes = GamepadProtocol.encode(GamepadProtocol.TYPE_PONG, sample, 4242, 99, 1_700_000_000_123L)
        val header = GamepadProtocol.decodeHeader(bytes, bytes.size)!!

        assertEquals(GamepadProtocol.TYPE_PONG, header.type)
        assertEquals(4242, header.controllerId)
        assertEquals(99, header.sequence)
        assertEquals(1_700_000_000_123L, header.timestampMs)
    }

    @Test
    fun rejectsWrongMagic() {
        val bytes = GamepadProtocol.encode(GamepadProtocol.TYPE_STATE, sample, 1, 1, 0)
        bytes[0] = 'X'.code.toByte()
        assertNull(GamepadProtocol.decodeHeader(bytes, bytes.size))
    }

    @Test
    fun rejectsUnknownVersion() {
        val bytes = GamepadProtocol.encode(GamepadProtocol.TYPE_STATE, sample, 1, 1, 0)
        bytes[4] = 99
        assertNull(GamepadProtocol.decodeHeader(bytes, bytes.size))
    }

    @Test
    fun rejectsShortPacket() {
        val bytes = GamepadProtocol.encode(GamepadProtocol.TYPE_STATE, sample, 1, 1, 0)
        assertNull(GamepadProtocol.decodeHeader(bytes, 31))
    }

    @Test
    fun buttonBitsAreDistinctAndSingle() {
        val bits = listOf(
            GamepadProtocol.BUTTON_A, GamepadProtocol.BUTTON_B, GamepadProtocol.BUTTON_X, GamepadProtocol.BUTTON_Y,
            GamepadProtocol.BUTTON_LEFT_SHOULDER, GamepadProtocol.BUTTON_RIGHT_SHOULDER,
            GamepadProtocol.BUTTON_BACK, GamepadProtocol.BUTTON_START,
            GamepadProtocol.BUTTON_LEFT_THUMB, GamepadProtocol.BUTTON_RIGHT_THUMB,
            GamepadProtocol.BUTTON_DPAD_UP, GamepadProtocol.BUTTON_DPAD_DOWN,
            GamepadProtocol.BUTTON_DPAD_LEFT, GamepadProtocol.BUTTON_DPAD_RIGHT,
            GamepadProtocol.BUTTON_GUIDE
        )

        assertEquals(bits.size, bits.distinct().size)
        bits.forEach { assertEquals(1, Integer.bitCount(it)) }
        // Everything must fit the u16 field on the wire.
        bits.forEach { assertEquals(0, it and 0xFFFF.inv()) }
    }
}
