package com.oleroma.gyrophone.sensor

import android.hardware.HardwareBuffer
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
         * Fresh sources to try in order, best first. Lowest-latency path available without root:
         * the sensor direct channel (shared memory or a gralloc buffer written by the sensor HAL, no Binder or Looper
         * hop) at its highest rate, when supported; then a listener at SENSOR_DELAY_FASTEST on a
         * high-priority thread, used when the direct channel cannot start (or runs capped, see
         * GamepadRuntime). Empty when the phone has no gyroscope.
         */
        fun candidates(sensorManager: SensorManager, pollHz: Int = DirectChannelGyroSource.DEFAULT_POLL_HZ): List<GyroSource> {
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return emptyList()
            val direct = DirectChannelGyroSource(sensorManager, sensor).apply { this.pollHz = pollHz }
            val listener = ListenerGyroSource(sensorManager, sensor)
            return if (direct.isSupported) listOf(direct, listener) else listOf(listener)
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
        // Let a callback already running finish, so the next source never overlaps this one.
        thread?.let { it.quitSafely(); it.join(50) }
        listener = null; thread = null
    }
}

class DirectChannelGyroSource(
    private val sensorManager: SensorManager,
    private val sensor: Sensor,
) : GyroSource {

    private val memoryFile: Boolean
        get() = sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE)

    /** Some phones (the OnePlus 15) offer the direct channel only as a gralloc HardwareBuffer. */
    private val gralloc: Boolean
        get() = sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_HARDWARE_BUFFER) &&
            HardwareBufferReader.loaded

    val isSupported: Boolean
        get() = (memoryFile || gralloc) && sensor.highestDirectReportRateLevel >= SensorDirectChannel.RATE_FAST

    private val rateLevel get() = sensor.highestDirectReportRateLevel

    override val name: String
        get() = "Direct channel" + (if (memoryFile) "" else " (hardware buffer)") + ", " + when (rateLevel) {
            SensorDirectChannel.RATE_VERY_FAST -> "very fast"
            SensorDirectChannel.RATE_FAST -> "fast"
            else -> "normal"
        } + " (${sensor.name}, up to ${(1_000_000f / sensor.minDelay.coerceAtLeast(1)).toInt()} Hz)"

    /** How often the shared memory is checked for new samples; changes apply on the next check. */
    @Volatile var pollHz: Int = DEFAULT_POLL_HZ
        set(value) { field = value.coerceIn(POLL_RATES_HZ.first(), POLL_RATES_HZ.last()) }

    /** The memory the HAL writes into, read and closed only by the poll thread. */
    private interface Backing : AutoCloseable {
        fun read(dst: ByteArray, size: Int): Boolean
    }

    private class MemoryFileBacking(val file: MemoryFile) : Backing {
        override fun read(dst: ByteArray, size: Int): Boolean { file.readBytes(dst, 0, 0, size); return true }
        override fun close() = file.close()
    }

    private class HardwareBufferBacking(val buffer: HardwareBuffer) : Backing {
        private val reader = HardwareBufferReader(buffer)
        override fun read(dst: ByteArray, size: Int) = reader.read(dst, size)
        override fun close() { reader.close(); buffer.close() }
    }

    private var channel: SensorDirectChannel? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(sink: GyroSink): Boolean {
        val size = EVENTS * DirectReportParser.EVENT_SIZE
        var backing: Backing? = null
        var ch: SensorDirectChannel? = null
        return try {
            val c = if (memoryFile) {
                val m = MemoryFile("gyrophone-gyro", size)
                backing = MemoryFileBacking(m)
                sensorManager.createDirectChannel(m)
            } else {
                // The HAL wants a BLOB buffer the size of the ring, usable for sensor data and CPU reads.
                val hb = HardwareBuffer.create(
                    size, 1, HardwareBuffer.BLOB, 1,
                    HardwareBuffer.USAGE_SENSOR_DIRECT_DATA or HardwareBuffer.USAGE_CPU_READ_OFTEN,
                )
                backing = runCatching { HardwareBufferBacking(hb) }.getOrElse { hb.close(); throw it }
                sensorManager.createDirectChannel(hb)
            }
            ch = c
            val token = c.configure(sensor, rateLevel)
            if (token <= 0) { c.close(); backing?.close(); return false }
            channel = c; running = true
            val b = checkNotNull(backing)
            thread = Thread({ poll(b, token, size, sink) }, "gyro-direct").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "direct channel unavailable", e)
            // Closing the channel also stops it if configure() had started it.
            running = false; channel = null; thread = null
            runCatching { ch?.close() }
            runCatching { backing?.close() }
            false
        }
    }

    private fun poll(backing: Backing, token: Int, size: Int, sink: GyroSink) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val parser = DirectReportParser(EVENTS)
        val buf = ByteArray(size)
        try {
            while (running) {
                val ok = try {
                    backing.read(buf, size)
                } catch (e: Exception) {
                    Log.w(TAG, "read failed", e); break
                }
                if (ok) for (ev in parser.parse(buf, token)) sink.onGyro(ev.x, ev.y, ev.z, ev.timestampNs)
                // Polling at pollHz (1-4 kHz, above the HAL rate) adds at most one interval on top of it.
                LockSupport.parkNanos(1_000_000_000L / pollHz)
            }
        } finally {
            // Freed here, never under a read still running: stop() may give up waiting for this thread.
            runCatching { backing.close() }
        }
    }

    override fun stop() {
        running = false
        thread?.join(50)
        thread = null
        // Stopped and recreated on every pause: a failed stop must not crash, close() still frees it.
        channel?.let { runCatching { it.configure(sensor, SensorDirectChannel.RATE_STOP) }; it.close() }
        channel = null
    }

    companion object {
        private const val TAG = "DirectChannelGyro"
        private const val EVENTS = 64

        /** The poll-rate choices offered in the app, lowest first. */
        val POLL_RATES_HZ = listOf(1000, 2000, 4000)
        const val DEFAULT_POLL_HZ = 4000
    }
}
