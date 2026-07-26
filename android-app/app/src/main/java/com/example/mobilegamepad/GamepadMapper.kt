package com.example.mobilegamepad

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent

object GamepadMapper {
    private const val TAG = "GamepadMapper"

    /**
     * Applies a joystick MotionEvent using the axes the device actually
     * reports. Axes the profile could not find are left untouched, so a
     * controller without, say, analog triggers keeps whatever the L2/R2
     * keycodes set.
     */
    fun applyMotion(state: ControllerState, event: MotionEvent, profile: DeviceProfile): ControllerState {
        var next = state

        // Android reports stick Y as negative-up; the wire format uses the
        // Xbox convention of positive-up, so Y axes are negated here.
        profile.leftStickX?.let {
            next = next.copy(leftStickX = AxisNormalizer.stick(it.signed(event.getAxisValue(it.axis)), profile.stickDeadZone))
        }
        profile.leftStickY?.let {
            next = next.copy(leftStickY = AxisNormalizer.stick(-it.signed(event.getAxisValue(it.axis)), profile.stickDeadZone))
        }
        profile.rightStickX?.let {
            next = next.copy(rightStickX = AxisNormalizer.stick(it.signed(event.getAxisValue(it.axis)), profile.stickDeadZone))
        }
        profile.rightStickY?.let {
            next = next.copy(rightStickY = AxisNormalizer.stick(-it.signed(event.getAxisValue(it.axis)), profile.stickDeadZone))
        }
        profile.leftTrigger?.let {
            next = next.copy(leftTrigger = AxisNormalizer.trigger(it.unsigned(event.getAxisValue(it.axis))))
        }
        profile.rightTrigger?.let {
            next = next.copy(rightTrigger = AxisNormalizer.trigger(it.unsigned(event.getAxisValue(it.axis))))
        }

        profile.hatX?.let {
            val hatX = it.signed(event.getAxisValue(it.axis))
            next = next
                .withButton(GamepadProtocol.BUTTON_DPAD_LEFT, hatX < -0.5f)
                .withButton(GamepadProtocol.BUTTON_DPAD_RIGHT, hatX > 0.5f)
        }
        profile.hatY?.let {
            val hatY = it.signed(event.getAxisValue(it.axis))
            next = next
                .withButton(GamepadProtocol.BUTTON_DPAD_UP, hatY < -0.5f)
                .withButton(GamepadProtocol.BUTTON_DPAD_DOWN, hatY > 0.5f)
        }

        return next
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
            Log.i(TAG, "Unmapped key: keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
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
