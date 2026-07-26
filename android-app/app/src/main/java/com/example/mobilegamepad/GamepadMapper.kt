package com.example.mobilegamepad

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.max

object GamepadMapper {
    private const val TAG = "GamepadMapper"

    /** Applies every axis carried by a joystick MotionEvent to the state. */
    fun applyMotion(state: ControllerState, event: MotionEvent, deadZone: Float = 0f): ControllerState {
        // Android reports stick Y as negative-up; the wire format uses the
        // Xbox convention of positive-up, so Y axes are negated here.
        val leftX = AxisNormalizer.stick(event.getAxisValue(MotionEvent.AXIS_X), deadZone)
        val leftY = AxisNormalizer.stick(-event.getAxisValue(MotionEvent.AXIS_Y), deadZone)
        val rightX = AxisNormalizer.stick(event.getAxisValue(MotionEvent.AXIS_Z), deadZone)
        val rightY = AxisNormalizer.stick(-event.getAxisValue(MotionEvent.AXIS_RZ), deadZone)

        // Controllers disagree on which axis carries the triggers: most report
        // BRAKE/GAS, some report LTRIGGER/RTRIGGER. Taking the larger value
        // works for both without needing to know the device.
        val leftTrigger = AxisNormalizer.trigger(
            max(event.getAxisValue(MotionEvent.AXIS_BRAKE), event.getAxisValue(MotionEvent.AXIS_LTRIGGER))
        )
        val rightTrigger = AxisNormalizer.trigger(
            max(event.getAxisValue(MotionEvent.AXIS_GAS), event.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        )

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        return state.copy(
            leftStickX = leftX,
            leftStickY = leftY,
            rightStickX = rightX,
            rightStickY = rightY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger
        )
            .withButton(GamepadProtocol.BUTTON_DPAD_LEFT, hatX < -0.5f)
            .withButton(GamepadProtocol.BUTTON_DPAD_RIGHT, hatX > 0.5f)
            .withButton(GamepadProtocol.BUTTON_DPAD_UP, hatY < -0.5f)
            .withButton(GamepadProtocol.BUTTON_DPAD_DOWN, hatY > 0.5f)
    }

    /**
     * Applies a gamepad key event. Returns null when the key is not part of
     * the Xbox 360 layout, so the caller can let the system handle it.
     */
    fun applyKey(state: ControllerState, keyCode: Int, pressed: Boolean): ControllerState? {
        // L2/R2 arrive as key events on controllers with digital triggers.
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> return state.copy(leftTrigger = if (pressed) 255 else 0)
            KeyEvent.KEYCODE_BUTTON_R2 -> return state.copy(rightTrigger = if (pressed) 255 else 0)
        }

        val bit = buttonBit(keyCode)
        if (bit == null) {
            Log.d(TAG, "Unmapped key event: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
            return null
        }
        return state.withButton(bit, pressed)
    }

    fun buttonBit(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> GamepadProtocol.BUTTON_A
        KeyEvent.KEYCODE_BUTTON_B -> GamepadProtocol.BUTTON_B
        KeyEvent.KEYCODE_BUTTON_X -> GamepadProtocol.BUTTON_X
        KeyEvent.KEYCODE_BUTTON_Y -> GamepadProtocol.BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_L1 -> GamepadProtocol.BUTTON_LEFT_SHOULDER
        KeyEvent.KEYCODE_BUTTON_R1 -> GamepadProtocol.BUTTON_RIGHT_SHOULDER
        KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadProtocol.BUTTON_LEFT_THUMB
        KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadProtocol.BUTTON_RIGHT_THUMB
        KeyEvent.KEYCODE_BUTTON_START -> GamepadProtocol.BUTTON_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadProtocol.BUTTON_BACK
        KeyEvent.KEYCODE_BUTTON_MODE -> GamepadProtocol.BUTTON_GUIDE
        KeyEvent.KEYCODE_DPAD_UP -> GamepadProtocol.BUTTON_DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GamepadProtocol.BUTTON_DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GamepadProtocol.BUTTON_DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadProtocol.BUTTON_DPAD_RIGHT
        else -> null
    }
}
