package com.example.mobilegamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerStateTest {

    @Test
    fun setsAndClearsIndividualButtons() {
        var state = ControllerState()
        assertFalse(state.isPressed(GamepadProtocol.BUTTON_A))

        state = state.withButton(GamepadProtocol.BUTTON_A, true)
        state = state.withButton(GamepadProtocol.BUTTON_START, true)
        assertTrue(state.isPressed(GamepadProtocol.BUTTON_A))
        assertTrue(state.isPressed(GamepadProtocol.BUTTON_START))

        state = state.withButton(GamepadProtocol.BUTTON_A, false)
        assertFalse(state.isPressed(GamepadProtocol.BUTTON_A))
        // Clearing one button must not disturb the others.
        assertTrue(state.isPressed(GamepadProtocol.BUTTON_START))
    }

    @Test
    fun releasingAnUnpressedButtonIsANoOp() {
        val state = ControllerState().withButton(GamepadProtocol.BUTTON_B, false)
        assertEquals(0, state.buttons)
    }

    @Test
    fun defaultStateIsFullyNeutral() {
        val state = ControllerState()
        assertEquals(0, state.buttons)
        assertEquals(0, state.leftStickX.toInt())
        assertEquals(0, state.leftStickY.toInt())
        assertEquals(0, state.rightStickX.toInt())
        assertEquals(0, state.rightStickY.toInt())
        assertEquals(0, state.leftTrigger)
        assertEquals(0, state.rightTrigger)
    }
}

class AxisNormalizerTest {

    @Test
    fun mapsStickExtremes() {
        assertEquals(32767, AxisNormalizer.stick(1f).toInt())
        assertEquals(-32767, AxisNormalizer.stick(-1f).toInt())
        assertEquals(0, AxisNormalizer.stick(0f).toInt())
    }

    @Test
    fun clampsOutOfRangeStickValues() {
        assertEquals(32767, AxisNormalizer.stick(2.5f).toInt())
        assertEquals(-32767, AxisNormalizer.stick(-9f).toInt())
    }

    @Test
    fun appliesStickDeadZone() {
        assertEquals(0, AxisNormalizer.stick(0.05f, deadZone = 0.1f).toInt())
        assertEquals(0, AxisNormalizer.stick(-0.05f, deadZone = 0.1f).toInt())
        // Just outside the dead zone still passes through.
        assertEquals(
            (0.2f * 32767f).toInt(),
            AxisNormalizer.stick(0.2f, deadZone = 0.1f).toInt()
        )
    }

    @Test
    fun mapsTriggerRange() {
        assertEquals(0, AxisNormalizer.trigger(0f))
        assertEquals(255, AxisNormalizer.trigger(1f))
        assertEquals(128, AxisNormalizer.trigger(0.5f))
    }

    @Test
    fun clampsOutOfRangeTriggerValues() {
        assertEquals(0, AxisNormalizer.trigger(-1f))
        assertEquals(255, AxisNormalizer.trigger(3f))
    }
}

class AxisSpecTest {

    @Test
    fun normalizesSymmetricStickRange() {
        val spec = AxisSpec(axis = 0, min = -1f, max = 1f, flat = 0f)
        assertEquals(1f, spec.signed(1f), 0.0001f)
        assertEquals(-1f, spec.signed(-1f), 0.0001f)
        assertEquals(0f, spec.signed(0f), 0.0001f)
    }

    @Test
    fun normalizesUnusualStickRange() {
        // Some controllers report 0..255 instead of -1..1.
        val spec = AxisSpec(axis = 0, min = 0f, max = 255f, flat = 0f)
        assertEquals(-1f, spec.signed(0f), 0.0001f)
        assertEquals(1f, spec.signed(255f), 0.0001f)
        assertEquals(0f, spec.signed(127.5f), 0.0001f)
    }

    @Test
    fun normalizesTriggerReportedAsSignedRange() {
        // A trigger that idles at -1 and bottoms out at 1 must still give 0..1.
        val spec = AxisSpec(axis = 0, min = -1f, max = 1f, flat = 0f)
        assertEquals(0f, spec.unsigned(-1f), 0.0001f)
        assertEquals(1f, spec.unsigned(1f), 0.0001f)
        assertEquals(0.5f, spec.unsigned(0f), 0.0001f)
    }

    @Test
    fun clampsValuesOutsideTheReportedRange() {
        val spec = AxisSpec(axis = 0, min = 0f, max = 1f, flat = 0f)
        assertEquals(1f, spec.unsigned(5f), 0.0001f)
        assertEquals(0f, spec.unsigned(-5f), 0.0001f)
        assertEquals(1f, spec.signed(99f), 0.0001f)
    }
}
