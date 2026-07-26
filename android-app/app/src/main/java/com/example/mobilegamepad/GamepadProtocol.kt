package com.example.mobilegamepad

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixed-size binary wire format. See docs/PROTOCOL.md for the full
 * specification; both sides must be changed together.
 */
object GamepadProtocol {
    val MAGIC = byteArrayOf('M'.code.toByte(), 'G'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte())
    const val VERSION = 1
    const val PACKET_SIZE = 32

    const val TYPE_STATE = 1
    const val TYPE_PING = 2
    const val TYPE_PONG = 3
    const val TYPE_GOODBYE = 4

    const val BUTTON_A = 1 shl 0
    const val BUTTON_B = 1 shl 1
    const val BUTTON_X = 1 shl 2
    const val BUTTON_Y = 1 shl 3
    const val BUTTON_LEFT_SHOULDER = 1 shl 4
    const val BUTTON_RIGHT_SHOULDER = 1 shl 5
    const val BUTTON_BACK = 1 shl 6
    const val BUTTON_START = 1 shl 7
    const val BUTTON_LEFT_THUMB = 1 shl 8
    const val BUTTON_RIGHT_THUMB = 1 shl 9
    const val BUTTON_DPAD_UP = 1 shl 10
    const val BUTTON_DPAD_DOWN = 1 shl 11
    const val BUTTON_DPAD_LEFT = 1 shl 12
    const val BUTTON_DPAD_RIGHT = 1 shl 13
    const val BUTTON_GUIDE = 1 shl 14

    fun encode(
        type: Int,
        state: ControllerState,
        controllerId: Int,
        sequence: Int,
        timestampMs: Long
    ): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(VERSION.toByte())
        buffer.put(type.toByte())
        buffer.putShort(controllerId.toShort())
        buffer.putInt(sequence)
        buffer.putLong(timestampMs)
        buffer.putShort(state.buttons.toShort())
        buffer.putShort(state.leftStickX)
        buffer.putShort(state.leftStickY)
        buffer.putShort(state.rightStickX)
        buffer.putShort(state.rightStickY)
        buffer.put(state.leftTrigger.toByte())
        buffer.put(state.rightTrigger.toByte())
        return buffer.array()
    }
}
