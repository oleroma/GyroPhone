package com.oleroma.gyrophone.sensor

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * A measurement run only when asked over adb (`dumpsys … gyroprobe`), never by the app itself: how old each gyro
 * sensor's samples are when the direct channel hands them over. Every gyro the phone lists, calibrated and
 * uncalibrated, is read for [RUN_MS] on a channel of its own, one after another, the same way the app reads its gyro.
 * It shows whether another sensor or mode would deliver sooner than the default one.
 */
object GyroAgeProbe {
    private const val RUN_MS = 1000L

    fun run(sensorManager: SensorManager): String = buildString {
        val default = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val sensors = sensorManager.getSensorList(Sensor.TYPE_GYROSCOPE) + sensorManager.getSensorList(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        if (sensors.isEmpty()) { appendLine("gyroprobe: no gyroscope"); return@buildString }
        for (sensor in sensors.take(6)) {
            val source = DirectChannelGyroSource(sensorManager, sensor)
            var n = 0
            var sum = 0L
            var min = Long.MAX_VALUE
            var max = 0L
            val lock = Any()
            val started = source.start { _, _, _, ts ->
                val age = SystemClock.elapsedRealtimeNanos() - ts
                synchronized(lock) { n++; sum += age; if (age < min) min = age; if (age > max) max = age }
            }
            val what = (if (sensor.type == Sensor.TYPE_GYROSCOPE) "gyro" else "uncalibrated gyro") +
                (if (sensor == default) " (the one in use)" else "") + " · ${source.name}"
            if (!started) { appendLine("gyroprobe: $what: no direct channel"); continue }
            Thread.sleep(RUN_MS)
            source.stop()
            synchronized(lock) {
                if (n < 2) appendLine("gyroprobe: $what: no samples")
                else appendLine(
                    "gyroprobe: $what: ${n * 1000 / RUN_MS} samples/s · age %.2f ms avg, %.2f min, %.2f max"
                        .format(java.util.Locale.US, sum / n / 1e6, min / 1e6, max / 1e6),
                )
            }
        }
    }
}
