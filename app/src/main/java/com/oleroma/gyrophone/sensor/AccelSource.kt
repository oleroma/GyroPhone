package com.oleroma.gyrophone.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/** Accelerometer readings in m/s² (Android sensor frame) with the HAL timestamp in ns. */
fun interface AccelSink {
    fun onAccel(x: Float, y: Float, z: Float, timestampNs: Long)
}

/**
 * The accelerometer at about 200 Hz ([ACCEL_PERIOD_US]) on a thread of its own, for tilt, shake and
 * gravity; or another 3-axis sensor of [type] at game rate (the magnetometer, for a gyro display's
 * compass).
 */
class AccelSource(private val sensorManager: SensorManager, private val type: Int = Sensor.TYPE_ACCELEROMETER) {
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null
    private val periodUs = if (type == Sensor.TYPE_ACCELEROMETER) ACCEL_PERIOD_US else SensorManager.SENSOR_DELAY_GAME

    /** Starts reading into [sink]; false when the phone has no accelerometer. */
    fun start(sink: AccelSink): Boolean {
        if (listener != null) return true
        val sensor = sensorManager.getDefaultSensor(type) ?: return false
        val t = HandlerThread(if (type == Sensor.TYPE_ACCELEROMETER) "accel-listener" else "sensor-$type-listener", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = sink.onAccel(e.values[0], e.values[1], e.values[2], e.timestamp)
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        if (!sensorManager.registerListener(l, sensor, periodUs, Handler(t.looper))) {
            t.quitSafely()
            return false
        }
        thread = t; listener = l
        return true
    }

    fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        thread?.let { it.quitSafely(); it.join(50) }
        listener = null; thread = null
    }

    companion object {
        /** One reading every 5 ms: tilt waits less for its next sample than at game rate (20 ms). */
        const val ACCEL_PERIOD_US = 5000
    }
}
