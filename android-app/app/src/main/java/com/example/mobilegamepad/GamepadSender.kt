package com.example.mobilegamepad

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Sends the full controller state over UDP: immediately whenever the state
 * changes, plus a periodic resend so a receiver that missed a packet
 * re-synchronizes within one heartbeat interval.
 *
 * All socket work happens on a single scheduled executor thread; callers may
 * update the state from any thread.
 */
class GamepadSender {
    private var executor: ScheduledExecutorService? = null
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var port: Int = 0

    @Volatile
    private var state = ControllerState()
    private val sequence = AtomicInteger(0)
    private val controllerId = Random.nextInt(1, 0xFFFF)

    private val sentCount = AtomicLong(0)
    val packetsSent: Long get() = sentCount.get()

    @Volatile
    var lastError: String? = null
        private set

    fun start(config: NetworkConfig) {
        stop()
        state = ControllerState()
        lastError = null
        val exec = Executors.newSingleThreadScheduledExecutor()
        executor = exec
        exec.execute {
            try {
                socket = DatagramSocket()
                address = InetAddress.getByName(config.host)
                port = config.port
            } catch (exception: Exception) {
                lastError = exception.message
                Log.w(TAG, "Failed to open socket for ${config.host}:${config.port}", exception)
            }
        }
        exec.scheduleAtFixedRate(
            { send(GamepadProtocol.TYPE_STATE) },
            HEARTBEAT_MS,
            HEARTBEAT_MS,
            TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        val exec = executor ?: return
        executor = null
        exec.execute {
            // Tell the receiver to release every input right away instead of
            // waiting for its idle timeout.
            send(GamepadProtocol.TYPE_GOODBYE)
            socket?.close()
            socket = null
            address = null
        }
        exec.shutdown()
    }

    /** Publishes a new state and sends it without waiting for the heartbeat. */
    fun update(newState: ControllerState) {
        state = newState
        executor?.let { exec ->
            try {
                exec.execute { send(GamepadProtocol.TYPE_STATE) }
            } catch (exception: java.util.concurrent.RejectedExecutionException) {
                // Streaming stopped between the null check and submit; ignore.
            }
        }
    }

    private fun send(type: Int) {
        val activeSocket = socket ?: return
        val target = address ?: return
        try {
            val payload = GamepadProtocol.encode(
                type = type,
                state = if (type == GamepadProtocol.TYPE_GOODBYE) ControllerState() else state,
                controllerId = controllerId,
                sequence = sequence.incrementAndGet(),
                timestampMs = System.currentTimeMillis()
            )
            activeSocket.send(DatagramPacket(payload, payload.size, target, port))
            sentCount.incrementAndGet()
        } catch (exception: Exception) {
            lastError = exception.message
            Log.w(TAG, "Send failed", exception)
        }
    }

    companion object {
        private const val TAG = "GamepadSender"
        private const val HEARTBEAT_MS = 50L
    }
}
