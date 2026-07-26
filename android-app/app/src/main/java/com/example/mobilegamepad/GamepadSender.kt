package com.example.mobilegamepad

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class GamepadSender {
    private val executor = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null
    private var config: NetworkConfig? = null

    fun start(newConfig: NetworkConfig) {
        config = newConfig
        ensureSocket()
    }

    fun stop() {
        socket?.close()
        socket = null
        config = null
    }

    fun sendInput(payload: GamepadPayload) {
        val activeConfig = config ?: return
        executor.execute {
            try {
                val datagramSocket = ensureSocket() ?: return@execute
                val json = JSONObject()
                json.put("type", "gamepad")
                json.put("timestamp", System.currentTimeMillis())
                json.put("axes", payload.axes)
                json.put("buttons", payload.buttons)
                json.put("deviceName", payload.deviceName)

                val bytes = json.toString().toByteArray()
                val packet = DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName(activeConfig.host),
                    activeConfig.port
                )
                datagramSocket.send(packet)
            } catch (exception: Exception) {
                socket?.close()
                socket = null
            }
        }
    }

    private fun ensureSocket(): DatagramSocket? {
        if (socket == null || socket?.isClosed == true) {
            socket = DatagramSocket()
        }
        return socket
    }
}

data class GamepadPayload(
    val axes: Map<String, Float>,
    val buttons: Map<String, Boolean>,
    val deviceName: String
)
