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
import android.os.SystemClock
import com.oleroma.gyrophone.R
import com.oleroma.gyrophone.link.LinkState
import com.oleroma.gyrophone.runtime
import com.oleroma.gyrophone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the active output link (Bluetooth HID or Wi-Fi) and the gyro alive while the PC is connected.
 * It stops, ending the link, on the notification's Stop (which also closes the app) or after the
 * app has been off screen for [com.oleroma.gyrophone.runtime.GamepadRuntime.idleStopMinutes];
 * MainActivity starts it again.
 */
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
            runtime.linkState.map { s ->
                when (s) {
                    is LinkState.Connected -> getString(R.string.notification_text, s.name)
                    is LinkState.Failed -> s.reason
                    is LinkState.Pairing -> "Pairing with ${s.host}"
                    is LinkState.HostSilent -> "${s.name} is not answering, reconnecting…"
                    else -> getString(R.string.notification_idle)
                }
            }.distinctUntilChanged().collect { text ->
                // Connected's detail (RSSI, link speed) changes every second; post only when the text does.
                nm.notify(NOTIFICATION_ID, notification(text))
            }
        }
        scope.launch {
            combine(runtime.onScreen, runtime.idleStopMinutes) { shown, minutes -> if (shown) 0 else minutes }
                .distinctUntilChanged()
                .collectLatest { minutes ->
                    if (minutes <= 0) return@collectLatest
                    // Counted on the wall clock: the main looper's delays stand still in deep sleep.
                    val stopAt = SystemClock.elapsedRealtime() + minutes * 60_000L
                    while (true) {
                        val left = stopAt - SystemClock.elapsedRealtime()
                        if (left <= 0) break
                        delay(minOf(left, IDLE_CHECK_MS))
                    }
                    stopSelf()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runtime.closeApp?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * `adb shell dumpsys activity service com.oleroma.gyrophone/.service.GamepadService [on|off|record|events N]`:
     * link, gyro and workspace now, the debug log's newest events, and a recording on request
     * (see [com.oleroma.gyrophone.runtime.PhoneDebug.dump]). Only the shell and the system may call it.
     */
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.print(runtime.debug.dump(args ?: emptyArray()))
    }

    override fun onDestroy() {
        scope.cancel()
        runtime.stop()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop),
                PendingIntent.getService(this, 1, Intent(this, GamepadService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE)).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.oleroma.gyrophone.STOP"
        private const val IDLE_CHECK_MS = 15_000L

        fun start(context: Context) = context.startForegroundService(Intent(context, GamepadService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, GamepadService::class.java))
    }
}
