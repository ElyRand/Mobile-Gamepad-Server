package com.example.mobilegamepad

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent

/**
 * One physical axis, with the range the device actually reports.
 *
 * Controllers do not agree on ranges: sticks are usually -1..1 but some
 * report 0..255, and triggers may be 0..1 or -1..1. Reading the range from
 * the device instead of assuming one is what makes an unknown controller
 * work.
 */
data class AxisSpec(
    val axis: Int,
    val min: Float,
    val max: Float,
    val flat: Float
) {
    private val mid = (min + max) / 2f
    private val half = ((max - min) / 2f).takeIf { it > 0f } ?: 1f

    /** Normalizes to -1..1. */
    fun signed(raw: Float): Float = ((raw - mid) / half).coerceIn(-1f, 1f)

    /** Normalizes to 0..1. */
    fun unsigned(raw: Float): Float =
        if (max - min <= 0f) 0f else ((raw - min) / (max - min)).coerceIn(0f, 1f)

    companion object {
        fun of(device: InputDevice, axis: Int, source: Int = InputDevice.SOURCE_JOYSTICK): AxisSpec? {
            val range = device.getMotionRange(axis, source) ?: return null
            if (range.max <= range.min) return null
            return AxisSpec(axis, range.min, range.max, range.flat)
        }
    }
}

/**
 * How to read one controller. Built by inspecting the device's own axis list
 * rather than from a hard-coded vendor table, so controllers nobody has
 * tested still work; vendor quirks are applied on top where auto-detection
 * is known to guess wrong.
 */
data class DeviceProfile(
    val deviceName: String,
    val layout: String,
    val leftStickX: AxisSpec?,
    val leftStickY: AxisSpec?,
    val rightStickX: AxisSpec?,
    val rightStickY: AxisSpec?,
    val leftTrigger: AxisSpec?,
    val rightTrigger: AxisSpec?,
    val hatX: AxisSpec?,
    val hatY: AxisSpec?,
    val stickDeadZone: Float = DEFAULT_DEAD_ZONE,
    /** Every axis the device reports, for diagnosing an unmapped control. */
    val availableAxes: List<String> = emptyList()
) {
    fun withDeadZone(deadZone: Float): DeviceProfile =
        copy(stickDeadZone = deadZone.coerceIn(0f, 0.5f))

    /** Human-readable summary shown on screen when a control does not work. */
    fun describeTriggers(): String {
        val left = leftTrigger?.let { axisName(it.axis) } ?: "none"
        val right = rightTrigger?.let { axisName(it.axis) } ?: "none"
        return "$left / $right"
    }

    companion object {
        const val DEFAULT_DEAD_ZONE = 0.08f

        fun axisName(axis: Int): String =
            MotionEvent.axisToString(axis).removePrefix("AXIS_")
    }
}

object DeviceProfiles {
    private const val TAG = "DeviceProfiles"

    private const val VENDOR_SONY = 0x054C
    private const val VENDOR_MICROSOFT = 0x045E
    private const val VENDOR_NINTENDO = 0x057E

    fun detect(device: InputDevice, deadZone: Float = DeviceProfile.DEFAULT_DEAD_ZONE): DeviceProfile {
        logAxes(device)

        val leftX = AxisSpec.of(device, MotionEvent.AXIS_X)
        val leftY = AxisSpec.of(device, MotionEvent.AXIS_Y)
        val hatX = AxisSpec.of(device, MotionEvent.AXIS_HAT_X)
        val hatY = AxisSpec.of(device, MotionEvent.AXIS_HAT_Y)

        val z = AxisSpec.of(device, MotionEvent.AXIS_Z)
        val rz = AxisSpec.of(device, MotionEvent.AXIS_RZ)
        val rx = AxisSpec.of(device, MotionEvent.AXIS_RX)
        val ry = AxisSpec.of(device, MotionEvent.AXIS_RY)
        val ltrigger = AxisSpec.of(device, MotionEvent.AXIS_LTRIGGER)
        val rtrigger = AxisSpec.of(device, MotionEvent.AXIS_RTRIGGER)
        val brake = AxisSpec.of(device, MotionEvent.AXIS_BRAKE)
        val gas = AxisSpec.of(device, MotionEvent.AXIS_GAS)

        // Right stick: Z/RZ on Xbox-style pads (the common case), RX/RY when
        // the device does not expose Z/RZ.
        var rightX = z
        var rightY = rz
        if (rightX == null || rightY == null) {
            rightX = rx
            rightY = ry
        }

        // Triggers, in order of how explicit the reporting is.
        var left = ltrigger ?: brake
        var right = rtrigger ?: gas
        var layout = "generic"

        if (device.vendorId == VENDOR_SONY) {
            // PlayStation pads report the triggers on RX/RY and keep Z/RZ for
            // the right stick, which the generic order above would mistake.
            layout = "playstation"
            if (z != null && rz != null) {
                rightX = z
                rightY = rz
                left = ltrigger ?: rx ?: brake
                right = rtrigger ?: ry ?: gas
            }
        } else if (device.vendorId == VENDOR_MICROSOFT) {
            layout = "xbox"
        } else if (device.vendorId == VENDOR_NINTENDO) {
            layout = "nintendo"
        }

        // If the triggers ended up on the same axes as the right stick, the
        // device has no analog triggers; the L2/R2 keycodes carry them.
        if (left != null && (left.axis == rightX?.axis || left.axis == rightY?.axis)) {
            left = null
        }
        if (right != null && (right.axis == rightX?.axis || right.axis == rightY?.axis)) {
            right = null
        }

        // Last resort: plenty of controllers put the triggers on RX/RY while
        // the right stick sits on Z/RZ. Only reachable when RX/RY were not
        // already claimed as the right stick above.
        if (left == null && rx != null && rx.axis != rightX?.axis && rx.axis != rightY?.axis) {
            left = rx
        }
        if (right == null && ry != null && ry.axis != rightX?.axis && ry.axis != rightY?.axis) {
            right = ry
        }

        val profile = DeviceProfile(
            deviceName = device.name ?: "Unknown controller",
            layout = layout,
            leftStickX = leftX,
            leftStickY = leftY,
            rightStickX = rightX,
            rightStickY = rightY,
            leftTrigger = left,
            rightTrigger = right,
            hatX = hatX,
            hatY = hatY,
            stickDeadZone = deadZone.coerceIn(0f, 0.5f),
            availableAxes = device.motionRanges.map { DeviceProfile.axisName(it.axis) }
        )
        Log.i(TAG, "Detected profile: $profile")
        return profile
    }

    /** Logs every axis a device exposes so unmapped ones can be identified. */
    private fun logAxes(device: InputDevice) {
        val axes = device.motionRanges.joinToString { range ->
            "${MotionEvent.axisToString(range.axis)}[${range.min}..${range.max} flat=${range.flat}]"
        }
        Log.i(
            TAG,
            "Controller '${device.name}' vendor=0x%04X product=0x%04X axes: %s".format(
                device.vendorId, device.productId, axes
            )
        )
    }
}
