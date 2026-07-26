package com.example.mobilegamepad

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A complete controller snapshot in Xbox 360 conventions: sticks are
 * -32767..32767 with up and right positive, triggers are 0..255.
 *
 * The Android app normalizes into these conventions before sending so the
 * Windows side can apply a packet without knowing anything about the
 * physical device.
 */
data class ControllerState(
    val buttons: Int = 0,
    val leftStickX: Short = 0,
    val leftStickY: Short = 0,
    val rightStickX: Short = 0,
    val rightStickY: Short = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0
) {
    fun withButton(bit: Int, pressed: Boolean): ControllerState =
        copy(buttons = if (pressed) buttons or bit else buttons and bit.inv())

    fun isPressed(bit: Int): Boolean = buttons and bit != 0
}

/** Converts raw Android axis floats into the wire encoding. */
object AxisNormalizer {
    /** Maps -1..1 to -32767..32767, applying a radial-free per-axis dead zone. */
    fun stick(value: Float, deadZone: Float = 0f): Short {
        val clamped = value.coerceIn(-1f, 1f)
        if (abs(clamped) <= deadZone) {
            return 0
        }
        return (clamped * 32767f).roundToInt().toShort()
    }

    /** Maps 0..1 to 0..255. */
    fun trigger(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
