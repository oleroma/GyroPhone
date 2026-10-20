package com.oleroma.gyrophone.sensor

import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.MemoryFile
import android.os.Process
import android.util.Log
import com.oleroma.gyrophone.core.sensor.DirectReportParser
import java.util.concurrent.locks.LockSupport

/** Angular velocity in rad/s (Android sensor frame) with the HAL timestamp in ns. */
fun interface GyroSink {
    fun onGyro(x: Float, y: Float, z: Float, timestampNs: Long)
}

interface GyroSource {
    val name: String
    fun start(sink: GyroSink): Boolean
    fun stop()

    companion object {
        /**
         * Lowest-latency path available without root: the sensor direct channel (shared memory
         * written by the sensor HAL, no Binder or Looper hop) at its highest rate, falling back
         * to a listener at SENSOR_DELAY_FASTEST on a high-priority thread.
         */
        fun best(sensorManager: SensorManager): GyroSource? {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
            val direct = DirectChannelGyroSource(sensorManager, sensor)
            return if (direct.isSupported) direct else ListenerGyroSource(sensorManager, sensor)
        }
    }
}

class ListenerGyroSource(
    private val sensorManager: SensorManager,
    private val sensor: Sensor,
) : GyroSource {
    override val name = "Listener, fastest (${sensor.name})"

    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    override fun start(sink: GyroSink): Boolean {
        val t = HandlerThread("gyro-listener", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = sink.onGyro(e.values[0], e.values[1], e.values[2], e.timestamp)
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        // samplingPeriodUs = 0 requests the sensor's minDelay (fastest); maxReportLatencyUs = 0 disables batching.
        val ok = sensorManager.registerListener(l, sensor, 0, 0, Handler(t.looper))
        if (!ok) { t.quitSafely(); return false }
        thread = t; listener = l
        return true
    }

    override fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        thread?.quitSafely()
        listener = null; thread = null
    }
}

class DirectChannelGyroSource(
    private val sensorManager: SensorManager,
    private val sensor: Sensor,
) : GyroSource {

    val isSupported: Boolean
        get() = sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE) &&
            sensor.highestDirectReportRateLevel >= SensorDirectChannel.RATE_FAST

    private val rateLevel get() = sensor.highestDirectReportRateLevel

    override val name: String
        get() = "Direct channel, " + when (rateLevel) {
            SensorDirectChannel.RATE_VERY_FAST -> "very fast (~800 Hz)"
            SensorDirectChannel.RATE_FAST -> "fast (~200 Hz)"
            else -> "normal"
        } + " (${sensor.name})"

    private var memory: MemoryFile? = null
    private var channel: SensorDirectChannel? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(sink: GyroSink): Boolean {
        val size = EVENTS * DirectReportParser.EVENT_SIZE
        return try {
            val mem = MemoryFile("gyrophone-gyro", size)
            val ch = sensorManager.createDirectChannel(mem)
            val token = ch.configure(sensor, rateLevel)
            if (token <= 0) { ch.close(); mem.close(); return false }
            memory = mem; channel = ch; running = true
            thread = Thread({ poll(mem, token, size, sink) }, "gyro-direct").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "direct channel unavailable", e)
            false
        }
    }

    private fun poll(mem: MemoryFile, token: Int, size: Int, sink: GyroSink) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val parser = DirectReportParser(EVENTS)
        val buf = ByteArray(size)
        while (running) {
            try {
                mem.readBytes(buf, 0, 0, size)
            } catch (e: Exception) {
                Log.w(TAG, "read failed", e); break
            }
            for (ev in parser.parse(buf, token)) sink.onGyro(ev.x, ev.y, ev.z, ev.timestampNs)
            // ~4 kHz polling: well above the HAL rate, so we add <0.25 ms on top of it.
            LockSupport.parkNanos(POLL_NANOS)
        }
    }

    override fun stop() {
        running = false
        thread?.join(50)
        thread = null
        channel?.let { it.configure(sensor, SensorDirectChannel.RATE_STOP); it.close() }
        memory?.close()
        channel = null; memory = null
    }

    companion object {
        private const val TAG = "DirectChannelGyro"
        private const val EVENTS = 64
        private const val POLL_NANOS = 250_000L
    }
}
