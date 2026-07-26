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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pairCodeInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var discoverButton: Button
    private lateinit var statusText: TextView
    private lateinit var debugText: TextView

    private val discoveryClient = DiscoveryClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var streamingService: StreamingService? = null
    private var isBound = false

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
        toggleButton = findViewById(R.id.toggle_button)
        discoverButton = findViewById(R.id.discover_button)
        statusText = findViewById(R.id.status_text)
        debugText = findViewById(R.id.debug_text)

        debugText.text = getString(R.string.debug_idle)
        toggleButton.setOnClickListener { toggleStreaming() }
        discoverButton.setOnClickListener { discoverHost() }

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
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isStreaming()) {
            return super.onGenericMotionEvent(event)
        }
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            val axes = GamepadMapper.mapAxes(event)
            sendPayload(axes, emptyMap(), event.device)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isStreaming() && event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            val mapped = GamepadMapper.mapButton(keyCode)
            if (mapped != null) {
                sendPayload(emptyMap(), mapOf(mapped to true), event.device)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isStreaming() && event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
            val mapped = GamepadMapper.mapButton(keyCode)
            if (mapped != null) {
                sendPayload(emptyMap(), mapOf(mapped to false), event.device)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
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
        service.startStreaming(NetworkConfig(host, port))
        toggleButton.text = getString(R.string.stop_streaming)
        statusText.text = getString(R.string.status_connected, host, port)
    }

    private fun stopStreaming() {
        streamingService?.stopStreaming()
        toggleButton.text = getString(R.string.start_streaming)
        statusText.text = getString(R.string.status_disconnected)
        debugText.text = getString(R.string.debug_idle)
    }

    private fun sendPayload(
        axes: Map<String, Float>,
        buttons: Map<String, Boolean>,
        device: InputDevice
    ) {
        val payload = GamepadPayload(
            axes = axes,
            buttons = buttons,
            deviceName = device.name ?: "Unknown"
        )
        streamingService?.sendPayload(payload)
        debugText.text = getString(
            R.string.debug_payload,
            axes.keys.joinToString(),
            buttons.keys.joinToString()
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
