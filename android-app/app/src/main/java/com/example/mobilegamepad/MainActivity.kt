package com.example.mobilegamepad

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var pairCodeInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var discoverButton: Button
    private lateinit var scanQrButton: Button
    private lateinit var statusText: TextView
    private lateinit var pairingStatusText: TextView
    private lateinit var debugText: TextView

    private val discoveryClient = DiscoveryClient()
    private val pairingClient = PairingClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var streamingService: StreamingService? = null
    private var isBound = false
    private var pairingPayload: QrPairingPayload? = null

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents.isNullOrBlank()) {
            pairingStatusText.text = getString(R.string.pairing_scan_cancelled)
            return@registerForActivityResult
        }
        val payload = QrPairingPayload.parse(result.contents)
        if (payload == null) {
            pairingStatusText.text = getString(R.string.pairing_scan_invalid)
            return@registerForActivityResult
        }
        applyPairingPayload(payload)
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
        secretInput = findViewById(R.id.secret_input)
        toggleButton = findViewById(R.id.toggle_button)
        discoverButton = findViewById(R.id.discover_button)
        scanQrButton = findViewById(R.id.scan_qr_button)
        statusText = findViewById(R.id.status_text)
        pairingStatusText = findViewById(R.id.pairing_status_text)
        debugText = findViewById(R.id.debug_text)

        debugText.text = getString(R.string.debug_idle)
        pairingStatusText.text = getString(R.string.pairing_status_idle)
        toggleButton.setOnClickListener { toggleStreaming() }
        discoverButton.setOnClickListener { discoverHost() }
        scanQrButton.setOnClickListener { scanQrCode() }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                clearPairingPayloadIfManualChange()
                if (isStreaming()) {
                    startStreamingIfValid()
                }
            }
        }
        hostInput.addTextChangedListener(watcher)
        portInput.addTextChangedListener(watcher)
        pairCodeInput.addTextChangedListener(watcher)

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
        val secret = secretInput.text?.toString()?.trim().orEmpty().ifBlank { null }
        if (host.isBlank() || port == null) {
            statusText.text = getString(R.string.status_invalid)
            return
        }
        val service = streamingService
        if (service == null) {
            statusText.text = getString(R.string.status_service_unavailable)
            return
        }
        val pairingData = pairingPayload
        if (pairingData != null) {
            pairingStatusText.text = getString(R.string.pairing_status_exchanging)
            executor.execute {
                val sessionKey = runCatching {
                    val keyPair = KeyExchangeUtils.generateKeyPair()
                    val publicKey = KeyExchangeUtils.publicKeyToBase64(keyPair.public)
                    val ok = pairingClient.exchange(
                        pairingData.host,
                        pairingData.port,
                        pairingData.pairCode,
                        pairingData.keyId,
                        publicKey,
                        Build.MODEL
                    )
                    if (!ok) {
                        null
                    } else {
                        val serverKey = KeyExchangeUtils.parsePublicKey(pairingData.publicKey)
                        KeyExchangeUtils.deriveSessionKey(keyPair.private, serverKey, pairingData.keyId)
                    }
                }.getOrNull()
                uiHandler.post {
                    if (sessionKey == null) {
                        pairingStatusText.text = getString(R.string.pairing_status_failed)
                        statusText.text = getString(R.string.status_discovery_failed)
                    } else {
                        service.startStreaming(NetworkConfig(host, port, secret, sessionKey, pairingData.keyId))
                        toggleButton.text = getString(R.string.stop_streaming)
                        statusText.text = getString(R.string.status_connected, host, port)
                        pairingStatusText.text = getString(R.string.pairing_status_ready)
                    }
                }
            }
        } else {
            service.startStreaming(NetworkConfig(host, port, secret))
            toggleButton.text = getString(R.string.stop_streaming)
            statusText.text = getString(R.string.status_connected, host, port)
        }
    }

    private fun stopStreaming() {
        streamingService?.stopStreaming()
        toggleButton.text = getString(R.string.start_streaming)
        statusText.text = getString(R.string.status_disconnected)
        pairingStatusText.text = getString(R.string.pairing_status_idle)
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
                    if (!result.publicKey.isNullOrBlank() && !result.keyId.isNullOrBlank()) {
                        applyPairingPayload(
                            QrPairingPayload(
                                host = result.host,
                                port = result.port,
                                pairCode = result.pairCode ?: pairCodeInput.text.toString().trim(),
                                publicKey = result.publicKey,
                                keyId = result.keyId
                            )
                        )
                    }
                    statusText.text = getString(R.string.status_discovery_success, result.host, result.port)
                    debugText.text = getString(R.string.debug_idle)
                }
            }
        }
    }

    private fun scanQrCode() {
        val options = ScanOptions()
            .setPrompt(getString(R.string.qr_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(true)
        qrScannerLauncher.launch(options)
    }

    private fun applyPairingPayload(payload: QrPairingPayload) {
        pairingPayload = payload
        hostInput.setText(payload.host)
        portInput.setText(payload.port.toString())
        pairCodeInput.setText(payload.pairCode)
        pairingStatusText.text = getString(R.string.pairing_status_ready)
    }

    private fun clearPairingPayloadIfManualChange() {
        val payload = pairingPayload ?: return
        val host = hostInput.text?.toString()?.trim().orEmpty()
        val port = portInput.text?.toString()?.trim().orEmpty()
        val pairCode = pairCodeInput.text?.toString()?.trim().orEmpty()
        val matches = host == payload.host &&
            port == payload.port.toString() &&
            pairCode == payload.pairCode
        if (!matches) {
            pairingPayload = null
            pairingStatusText.text = getString(R.string.pairing_status_idle)
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
