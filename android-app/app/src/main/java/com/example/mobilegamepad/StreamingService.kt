package com.example.mobilegamepad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class StreamingService : Service() {
    private val binder = LocalBinder()
    private val sender = GamepadSender()

    var isStreaming: Boolean = false
        private set

    val packetsSent: Long get() = sender.packetsSent
    val lastError: String? get() = sender.lastError

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        return START_STICKY
    }

    override fun onDestroy() {
        sender.stop()
        isStreaming = false
        super.onDestroy()
    }

    fun startStreaming(config: NetworkConfig) {
        sender.start(config)
        isStreaming = true
        startForeground(NOTIFICATION_ID, buildNotification(config))
    }

    fun stopStreaming() {
        sender.stop()
        isStreaming = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun updateState(state: ControllerState) {
        if (!isStreaming) return
        sender.update(state)
    }

    private fun buildNotification(config: NetworkConfig): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body, config.host, config.port))
            .setSmallIcon(R.drawable.ic_stat_gamepad)
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "mobile_gamepad_stream"
        private const val NOTIFICATION_ID = 1001
    }
}
