package com.example.mobilegamepad

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadMapperTest {

    @Test
    fun mapsFaceButtonsToDistinctBits() {
        val codes = listOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE
        )

        val bits = codes.map { GamepadMapper.buttonBit(it) }
        bits.forEach { assertNotNull(it) }
        assertEquals(codes.size, bits.distinct().size)
    }

    @Test
    fun mapsStartSelectAndStickClicks() {
        assertEquals(GamepadProtocol.BUTTON_START, GamepadMapper.buttonBit(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(GamepadProtocol.BUTTON_BACK, GamepadMapper.buttonBit(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(GamepadProtocol.BUTTON_LEFT_THUMB, GamepadMapper.buttonBit(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(GamepadProtocol.BUTTON_RIGHT_THUMB, GamepadMapper.buttonBit(KeyEvent.KEYCODE_BUTTON_THUMBR))
    }

    @Test
    fun unknownKeyIsNotMapped() {
        assertNull(GamepadMapper.buttonBit(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(GamepadMapper.applyKey(ControllerState(), KeyEvent.KEYCODE_VOLUME_UP, pressed = true))
    }

    @Test
    fun pressAndReleaseAreSymmetric() {
        val pressed = GamepadMapper.applyKey(ControllerState(), KeyEvent.KEYCODE_BUTTON_A, pressed = true)!!
        assertTrue(pressed.isPressed(GamepadProtocol.BUTTON_A))

        val released = GamepadMapper.applyKey(pressed, KeyEvent.KEYCODE_BUTTON_A, pressed = false)!!
        assertEquals(ControllerState(), released)
    }

    /** Upstream dropped these entirely: they were sent under names the PC ignored. */
    @Test
    fun digitalTriggerKeysDriveTheAnalogTriggers() {
        val leftDown = GamepadMapper.applyKey(ControllerState(), KeyEvent.KEYCODE_BUTTON_L2, pressed = true)!!
        assertEquals(255, leftDown.leftTrigger)
        assertEquals(0, leftDown.rightTrigger)

        val rightDown = GamepadMapper.applyKey(leftDown, KeyEvent.KEYCODE_BUTTON_R2, pressed = true)!!
        assertEquals(255, rightDown.rightTrigger)

        val leftUp = GamepadMapper.applyKey(rightDown, KeyEvent.KEYCODE_BUTTON_L2, pressed = false)!!
        assertEquals(0, leftUp.leftTrigger)
        assertEquals(255, leftUp.rightTrigger)
    }

    @Test
    fun dpadKeysMapToTheirOwnBits() {
        assertEquals(GamepadProtocol.BUTTON_DPAD_UP, GamepadMapper.buttonBit(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(GamepadProtocol.BUTTON_DPAD_DOWN, GamepadMapper.buttonBit(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(GamepadProtocol.BUTTON_DPAD_LEFT, GamepadMapper.buttonBit(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(GamepadProtocol.BUTTON_DPAD_RIGHT, GamepadMapper.buttonBit(KeyEvent.KEYCODE_DPAD_RIGHT))
    }
}
