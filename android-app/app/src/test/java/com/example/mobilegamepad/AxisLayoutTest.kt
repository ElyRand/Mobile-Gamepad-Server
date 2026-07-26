package com.example.mobilegamepad

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dead-zone heuristic that decides which axis pair is the right stick and
 * which carries the triggers, checked against the two real layouts that are
 * exact opposites of one another.
 *
 * [DeviceProfiles.detect] needs a live InputDevice, so these exercise the
 * decision itself using ranges captured from real hardware.
 */
class AxisLayoutTest {

    private fun stickIsRxRy(zFlat: Float, rzFlat: Float, rxFlat: Float, ryFlat: Float): Boolean =
        maxOf(rxFlat, ryFlat) > maxOf(zFlat, rzFlat)

    /**
     * "Generic X-Box pad" (vendor 0x2dc8) over USB on a Pixel 9 Pro XL, read
     * from dumpsys: raw evdev layout, sticks on X/Y and RX/RY with a dead
     * zone, triggers on Z/RZ with none. Getting this backwards made the
     * triggers drive the right stick.
     */
    @Test
    fun evdevPadPutsTheStickOnRxRy() {
        val stick = stickIsRxRy(zFlat = 0.0f, rzFlat = 0.0f, rxFlat = 0.00391f, ryFlat = 0.00391f)
        assertEquals(true, stick)
    }

    /** The opposite convention: right stick on Z/RZ, triggers on RX/RY. */
    @Test
    fun androidStylePadPutsTheStickOnZRz() {
        val stick = stickIsRxRy(zFlat = 0.00391f, rzFlat = 0.00391f, rxFlat = 0.0f, ryFlat = 0.0f)
        assertEquals(false, stick)
    }

    /** With no dead zone reported either way, fall back to the Z/RZ convention. */
    @Test
    fun fallsBackToZRzWhenNeitherPairReportsADeadZone() {
        val stick = stickIsRxRy(zFlat = 0.0f, rzFlat = 0.0f, rxFlat = 0.0f, ryFlat = 0.0f)
        assertEquals(false, stick)
    }

    /**
     * A trigger resting against its stop reads as the axis minimum, which must
     * normalize to zero rather than to the midpoint.
     */
    @Test
    fun triggerRestingAtMinimumNormalizesToZero() {
        // Z on the pad above: raw 0..1023 presented to apps as -1..1.
        val trigger = AxisSpec(axis = MotionEventAxisZ, min = -1f, max = 1f, flat = 0f)
        assertEquals(0f, trigger.unsigned(-1f), 0.0001f)
        assertEquals(0.5f, trigger.unsigned(0f), 0.0001f)
        assertEquals(1f, trigger.unsigned(1f), 0.0001f)
        assertEquals(0, AxisNormalizer.trigger(trigger.unsigned(-1f)))
        assertEquals(255, AxisNormalizer.trigger(trigger.unsigned(1f)))
    }

    private companion object {
        const val MotionEventAxisZ = 11
    }
}
