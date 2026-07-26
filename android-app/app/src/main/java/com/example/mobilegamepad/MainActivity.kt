package com.example.mobilegamepad

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pairCodeInput: EditText
    private lateinit var deadZoneInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var discoverButton: Button
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView

    private val discoveryClient = DiscoveryClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var streamingService: StreamingService? = null
    private var isBound = false

    /** Current full controller state; only touched on the UI thread. */
    private var state = ControllerState()
    private var controllerName: String? = null

    private var lastRateSampleCount = 0L
    private var lastRateSampleAt = 0L
    private var packetsPerSecond = 0f

    private var activeProfile: DeviceProfile? = null
    private var activeDeviceId: Int = -1
    private var deadZone: Float = DeviceProfile.DEFAULT_DEAD_ZONE

    private val diagnosticsTick = object : Runnable {
        override fun run() {
            updateDebugText()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StreamingService.LocalBinder ?: return
            streamingService = binder.getService()
            isBound = true
            updateUiForServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            isBound = false
            updateUiForServiceState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.host_input)
        portInput = findViewById(R.id.port_input)
        pairCodeInput = findViewById(R.id.pair_code_input)
        deadZoneInput = findViewById(R.id.dead_zone_input)
        toggleButton = findViewById(R.id.toggle_button)
        discoverButton = findViewById(R.id.discover_button)
        statusText = findViewById(R.id.status_text)
        debugText = findViewById(R.id.debug_text)

        debugText.text = getString(R.string.debug_idle)
        toggleButton.setOnClickListener { toggleStreaming() }
        discoverButton.setOnClickListener { discoverHost() }

        deadZoneInput.setText((DeviceProfile.DEFAULT_DEAD_ZONE * 100).toInt().toString())
        deadZoneInput.doAfterTextChanged { deadZone = readDeadZone() }

        window.decorView.isFocusableInTouchMode = true
        window.decorView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, StreamingService::class.java)
        // startService, not startForegroundService: the service only promotes
        // itself to foreground once streaming actually begins, and a service
        // started with startForegroundService must call startForeground within
        // seconds or the OS kills the app.
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        uiHandler.post(diagnosticsTick)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(diagnosticsTick)
        releaseAllInputs()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    /**
     * Android delivers controller input only to the focused window, so once
     * this screen loses focus no press or release can reach us. Streaming
     * continues (the connection stays alive and the service keeps its
     * notification), but the state must be neutralized first: otherwise the
     * heartbeat would keep re-sending whatever was held at that moment, and
     * the PC would hold that button down forever. The receiver's watchdog
     * cannot save us here, because packets are still arriving on time.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            releaseAllInputs()
        }
    }

    private fun releaseAllInputs() {
        if (state == ControllerState()) {
            return
        }
        state = ControllerState()
        streamingService?.updateState(state)
    }

    // Input is intercepted at dispatch level, before the focused view sees
    // it. Going through onKeyDown/onGenericMotionEvent means a focused
    // EditText eats D-pad and button presses to move its cursor, and the
    // controller appears dead whenever the keyboard has been used.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isFromGamepad(event.source) &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) &&
            handleGamepadKey(event.keyCode, event, pressed = event.action == KeyEvent.ACTION_DOWN)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isStreaming() && event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            val profile = profileFor(event.device)
            if (profile != null) {
                publish(GamepadMapper.applyMotion(state, event, profile))
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isFromGamepad(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private fun handleGamepadKey(keyCode: Int, event: KeyEvent, pressed: Boolean): Boolean {
        if (!isStreaming()) {
            return false
        }
        // Auto-repeat events carry no new information for a full-state protocol.
        if (pressed && event.repeatCount > 0) {
            return true
        }
        val updated = GamepadMapper.applyKey(state, keyCode, pressed) ?: return false
        event.device?.let { profileFor(it) }
        publish(updated)
        return true
    }

    /**
     * Returns the profile for a device, re-detecting when the controller or
     * the dead-zone setting changes.
     */
    private fun profileFor(device: InputDevice?): DeviceProfile? {
        if (device == null) {
            return activeProfile
        }
        val current = activeProfile
        if (current != null && activeDeviceId == device.id && current.stickDeadZone == deadZone) {
            return current
        }
        val profile = DeviceProfiles.detect(device, deadZone)
        activeProfile = profile
        activeDeviceId = device.id
        controllerName = "${profile.deviceName} (${profile.layout})"
        return profile
    }

    private fun readDeadZone(): Float {
        val percent = deadZoneInput.text?.toString()?.trim()?.toFloatOrNull()
            ?: return DeviceProfile.DEFAULT_DEAD_ZONE
        return (percent / 100f).coerceIn(0f, 0.5f)
    }

    private fun publish(newState: ControllerState) {
        if (newState == state) {
            return
        }
        state = newState
        streamingService?.updateState(newState)
    }

    private fun toggleStreaming() {
        if (isStreaming()) {
            stopStreaming()
        } else {
            startStreamingIfValid()
        }
    }

    private fun startStreamingIfValid() {
        val host = hostInput.text?.toString()?.trim().orEmpty()
        val port = portInput.text?.toString()?.trim()?.toIntOrNull()
        if (host.isBlank() || port == null) {
            statusText.text = getString(R.string.status_invalid)
            return
        }
        val service = streamingService
        if (service == null) {
            statusText.text = getString(R.string.status_service_unavailable)
            return
        }
        state = ControllerState()
        service.startStreaming(NetworkConfig(host, port))
        toggleButton.text = getString(R.string.stop_streaming)
        statusText.text = getString(R.string.status_connected, host, port)
    }

    private fun stopStreaming() {
        streamingService?.stopStreaming()
        state = ControllerState()
        toggleButton.text = getString(R.string.start_streaming)
        statusText.text = getString(R.string.status_disconnected)
        debugText.text = getString(R.string.debug_idle)
    }

    private fun updateDebugText() {
        val service = streamingService
        if (service == null || !service.isStreaming) {
            return
        }

        val sent = service.packetsSent
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastRateSampleAt
        if (elapsed >= 1000) {
            packetsPerSecond = (sent - lastRateSampleCount) * 1000f / elapsed
            lastRateSampleCount = sent
            lastRateSampleAt = now
        }

        val peer = when {
            service.isPeerResponding ->
                getString(R.string.peer_connected, service.roundTripMs ?: 0L)
            else -> getString(R.string.peer_no_reply)
        }

        val error = service.lastError
        val errorLine = if (error == null) "" else getString(R.string.debug_error, error)

        val profile = activeProfile
        val axisReport = if (profile == null) {
            ""
        } else {
            getString(
                R.string.debug_axes,
                profile.describeTriggers(),
                profile.availableAxes.joinToString(", ")
            )
        }

        debugText.text = getString(
            R.string.debug_state,
            peer,
            controllerName ?: getString(R.string.controller_unknown),
            sent,
            packetsPerSecond,
            state.buttons,
            state.leftStickX,
            state.leftStickY,
            state.rightStickX,
            state.rightStickY,
            state.leftTrigger,
            state.rightTrigger,
            axisReport + errorLine
        )
    }

    private fun discoverHost() {
        debugText.text = getString(R.string.debug_discovering)
        statusText.text = getString(R.string.status_discovering)
        val pairCode = pairCodeInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        executor.execute {
            val result = runCatching { discoveryClient.discover(pairCode) }.getOrNull()
            uiHandler.post {
                if (result == null) {
                    statusText.text = getString(R.string.status_discovery_failed)
                    debugText.text = getString(R.string.debug_idle)
                } else {
                    hostInput.setText(result.host)
                    portInput.setText(result.port.toString())
                    result.pairCode?.let { pairCodeInput.setText(it) }
                    statusText.text = getString(R.string.status_discovery_success, result.host, result.port)
                    debugText.text = getString(R.string.debug_idle)
                }
            }
        }
    }

    private fun isStreaming(): Boolean = streamingService?.isStreaming == true

    private fun updateUiForServiceState() {
        if (isStreaming()) {
            toggleButton.text = getString(R.string.stop_streaming)
        } else {
            toggleButton.text = getString(R.string.start_streaming)
        }
    }
}
