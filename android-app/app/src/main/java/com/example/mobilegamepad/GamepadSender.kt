package com.example.mobilegamepad

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Sends the full controller state over UDP: immediately whenever the state
 * changes, plus a periodic resend so a receiver that missed a packet
 * re-synchronizes within one heartbeat interval.
 *
 * Also sends periodic pings; the PC echoes them back, which is the only way
 * the phone can tell whether anything is actually listening (UDP itself gives
 * no delivery feedback).
 *
 * All socket writes happen on a single scheduled executor thread; callers may
 * update the state from any thread.
 */
class GamepadSender {
    private var executor: ScheduledExecutorService? = null
    private var receiver: Thread? = null

    @Volatile
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var port: Int = 0

    @Volatile
    private var state = ControllerState()
    private val sequence = AtomicInteger(0)
    private val controllerId = Random.nextInt(1, 0xFFFF)

    private val sentCount = AtomicLong(0)
    private val lastPongElapsed = AtomicLong(Long.MIN_VALUE)
    private val lastRtt = AtomicLong(-1)

    val packetsSent: Long get() = sentCount.get()

    /** Round-trip time to the PC in ms, or null if it has not answered yet. */
    val roundTripMs: Long? get() = lastRtt.get().takeIf { it >= 0 }

    /** True when the PC answered a ping recently. */
    val isPeerResponding: Boolean
        get() {
            val last = lastPongElapsed.get()
            if (last == Long.MIN_VALUE) return false
            return android.os.SystemClock.elapsedRealtime() - last < PEER_TIMEOUT_MS
        }

    @Volatile
    var lastError: String? = null
        private set

    fun start(config: NetworkConfig) {
        stop()
        state = ControllerState()
        lastError = null
        lastPongElapsed.set(Long.MIN_VALUE)
        lastRtt.set(-1)

        val exec = Executors.newSingleThreadScheduledExecutor()
        executor = exec
        exec.execute {
            try {
                val newSocket = DatagramSocket()
                address = InetAddress.getByName(config.host)
                port = config.port
                socket = newSocket
                receiver = thread(name = "gamepad-pong-reader", isDaemon = true) { receiveLoop(newSocket) }
            } catch (exception: Exception) {
                lastError = exception.message ?: exception.javaClass.simpleName
                Log.w(TAG, "Failed to open socket for ${config.host}:${config.port}", exception)
            }
        }
        exec.scheduleAtFixedRate(
            { send(GamepadProtocol.TYPE_STATE) },
            HEARTBEAT_MS,
            HEARTBEAT_MS,
            TimeUnit.MILLISECONDS
        )
        exec.scheduleAtFixedRate(
            { send(GamepadProtocol.TYPE_PING) },
            PING_MS,
            PING_MS,
            TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        val exec = executor ?: return
        executor = null
        try {
            exec.execute {
                // Tell the receiver to release every input right away instead
                // of waiting for its idle timeout.
                send(GamepadProtocol.TYPE_GOODBYE)
                socket?.close()
                socket = null
                address = null
            }
        } catch (exception: RejectedExecutionException) {
            socket?.close()
            socket = null
        }
        exec.shutdown()
        receiver = null
    }

    /** Publishes a new state and sends it without waiting for the heartbeat. */
    fun update(newState: ControllerState) {
        state = newState
        val exec = executor ?: return
        try {
            exec.execute { send(GamepadProtocol.TYPE_STATE) }
        } catch (exception: RejectedExecutionException) {
            // Streaming stopped between reading the executor and submitting.
        }
    }

    private fun send(type: Int) {
        val activeSocket = socket ?: return
        val target = address ?: return
        try {
            val payload = GamepadProtocol.encode(
                type = type,
                // GOODBYE carries a neutral state so a receiver that applies
                // it regardless still releases everything.
                state = if (type == GamepadProtocol.TYPE_STATE) state else ControllerState(),
                controllerId = controllerId,
                sequence = sequence.incrementAndGet(),
                timestampMs = if (type == GamepadProtocol.TYPE_PING) {
                    android.os.SystemClock.elapsedRealtime()
                } else {
                    System.currentTimeMillis()
                }
            )
            activeSocket.send(DatagramPacket(payload, payload.size, target, port))
            sentCount.incrementAndGet()
        } catch (exception: Exception) {
            lastError = exception.message ?: exception.javaClass.simpleName
            Log.w(TAG, "Send failed", exception)
        }
    }

    private fun receiveLoop(activeSocket: DatagramSocket) {
        val buffer = ByteArray(GamepadProtocol.PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!activeSocket.isClosed) {
            try {
                activeSocket.receive(packet)
                val header = GamepadProtocol.decodeHeader(packet.data, packet.length) ?: continue
                if (header.type != GamepadProtocol.TYPE_PONG) {
                    continue
                }
                // Pings carry elapsedRealtime, so the difference is a true
                // round trip measured against a single monotonic clock.
                val now = android.os.SystemClock.elapsedRealtime()
                lastRtt.set((now - header.timestampMs).coerceAtLeast(0))
                lastPongElapsed.set(now)
            } catch (exception: Exception) {
                if (activeSocket.isClosed) {
                    return
                }
                Log.d(TAG, "Receive failed", exception)
            }
        }
    }

    companion object {
        private const val TAG = "GamepadSender"
        private const val HEARTBEAT_MS = 50L
        private const val PING_MS = 1000L
        private const val PEER_TIMEOUT_MS = 3000L
    }
}
