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

/** The accelerometer at game rate on a thread of its own, for tilt and shake. */
class AccelSource(private val sensorManager: SensorManager) {
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    /** Starts reading into [sink]; false when the phone has no accelerometer. */
    fun start(sink: AccelSink): Boolean {
        if (listener != null) return true
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val t = HandlerThread("accel-listener", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = sink.onAccel(e.values[0], e.values[1], e.values[2], e.timestamp)
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        if (!sensorManager.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(t.looper))) {
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
}
