package com.oleroma.gyrophone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.oleroma.gyrophone.R
import com.oleroma.gyrophone.hid.HidDeviceManager
import com.oleroma.gyrophone.runtime
import com.oleroma.gyrophone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the HID registration, report pump and gyro alive while the PC is connected. */
class GamepadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_idle)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        runtime.start()
        scope.launch {
            runtime.hid.state.collect { s ->
                val text = when (s) {
                    is HidDeviceManager.State.Connected -> getString(R.string.notification_text, s.name)
                    is HidDeviceManager.State.Failed -> s.reason
                    else -> getString(R.string.notification_idle)
                }
                nm.notify(NOTIFICATION_ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        runtime.stop()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = context.startForegroundService(Intent(context, GamepadService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, GamepadService::class.java))
    }
}
