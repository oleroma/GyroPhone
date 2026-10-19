package com.oleroma.gyrophone.core.sensor

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the shared-memory ring buffer filled by Android's SensorDirectChannel.
 *
 * Each event is 104 bytes, little endian:
 *  0 int32 size, 4 int32 report token, 8 int32 sensor type, 12 uint32 atomic counter,
 *  16 int64 timestamp (ns), 24 float[16] values, 88 int32[4] reserved.
 *
 * The HAL writes events round-robin and bumps a per-event counter; we track the last counter
 * seen and hand back newer events in order. This skips the SensorEventListener/Looper path and
 * reaches the highest rate the HAL offers (RATE_VERY_FAST, several hundred Hz).
 */
class DirectReportParser(private val eventCount: Int) {

    data class Event(val token: Int, val type: Int, val counter: Long, val timestampNs: Long,
                     val x: Float, val y: Float, val z: Float)

    private var lastCounter = 0L

    /** Returns events newer than the previous call, oldest first. */
    fun parse(buffer: ByteArray, token: Int): List<Event> {
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val fresh = ArrayList<Event>()
        for (i in 0 until eventCount) {
            val base = i * EVENT_SIZE
            if (base + EVENT_SIZE > buffer.size) break
            if (bb.getInt(base) != EVENT_SIZE || bb.getInt(base + 4) != token) continue
            val counter = bb.getInt(base + 12).toLong() and 0xFFFFFFFFL
            if (counter <= lastCounter) continue
            fresh += Event(
                token = token,
                type = bb.getInt(base + 8),
                counter = counter,
                timestampNs = bb.getLong(base + 16),
                x = bb.getFloat(base + 24),
                y = bb.getFloat(base + 28),
                z = bb.getFloat(base + 32),
            )
        }
        fresh.sortBy { it.counter }
        if (fresh.isNotEmpty()) lastCounter = fresh.last().counter
        return fresh
    }

    fun reset() { lastCounter = 0L }

    companion object {
        const val EVENT_SIZE = 104
    }
}
