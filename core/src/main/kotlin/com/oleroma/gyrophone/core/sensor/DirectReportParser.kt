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

    private var lastCounter = 0L

    // The ring the last call read and a view of it, and the samples handed back in primitive
    // arrays: kept, so parsing makes no objects (the gyro thread runs it at the HAL rate).
    private var wrapped: ByteArray? = null
    private var bb: ByteBuffer = ByteBuffer.allocate(0)
    private val counters = LongArray(eventCount)
    private val times = LongArray(eventCount)
    private val xs = FloatArray(eventCount)
    private val ys = FloatArray(eventCount)
    private val zs = FloatArray(eventCount)

    /** The slot after the newest event seen, where the HAL writes next; -1 before any. */
    var nextSlot = -1
        private set

    /** The newest event's counter seen so far (0 before any). */
    val newestCounter: Long get() = lastCounter

    /** Event [i] of the last [parse] (0 = oldest): its counter, timestamp (ns) and values. */
    fun counter(i: Int): Long = counters[i]
    fun timestampNs(i: Int): Long = times[i]
    fun x(i: Int): Float = xs[i]
    fun y(i: Int): Float = ys[i]
    fun z(i: Int): Float = zs[i]

    /**
     * Reads the events newer than the previous call and returns how many, oldest first through
     * [counter], [timestampNs], [x], [y] and [z]. The next call reuses them, so read them before
     * parsing again.
     */
    fun parse(buffer: ByteArray, token: Int): Int {
        val bb = view(buffer)
        var n = 0
        var newestSlot = -1
        var newest = lastCounter
        for (i in 0 until eventCount) {
            val base = i * EVENT_SIZE
            if (base + EVENT_SIZE > buffer.size) break
            if (bb.getInt(base) != EVENT_SIZE || bb.getInt(base + 4) != token) continue
            val counter = bb.getInt(base + 12).toLong() and 0xFFFFFFFFL
            if (counter <= lastCounter) continue
            if (counter > newest) { newest = counter; newestSlot = i }
            // Insertion by counter: the ring is nearly in order (one wrap), so this is short.
            var k = n
            while (k > 0 && counters[k - 1] > counter) {
                counters[k] = counters[k - 1]; times[k] = times[k - 1]
                xs[k] = xs[k - 1]; ys[k] = ys[k - 1]; zs[k] = zs[k - 1]
                k--
            }
            counters[k] = counter
            times[k] = bb.getLong(base + 16)
            xs[k] = bb.getFloat(base + 24)
            ys[k] = bb.getFloat(base + 28)
            zs[k] = bb.getFloat(base + 32)
            n++
        }
        if (n > 0) {
            lastCounter = newest
            nextSlot = (newestSlot + 1) % eventCount
        }
        return n
    }

    /**
     * Whether [slot]'s event in [buffer] (only its first [HEADER_SIZE] bytes need to be there) is
     * newer than the last parsed: the cheap check before copying the whole ring.
     */
    fun isNew(buffer: ByteArray, slot: Int): Boolean {
        val base = slot * EVENT_SIZE
        if (slot < 0 || base + HEADER_SIZE > buffer.size) return false
        val bb = view(buffer)
        if (bb.getInt(base) != EVENT_SIZE) return false
        return (bb.getInt(base + 12).toLong() and 0xFFFFFFFFL) > lastCounter
    }

    private fun view(buffer: ByteArray): ByteBuffer {
        if (buffer !== wrapped) {
            bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            wrapped = buffer
        }
        return bb
    }

    fun reset() { lastCounter = 0L; nextSlot = -1 }

    companion object {
        const val EVENT_SIZE = 104

        /** An event's size, token, type and counter: what [isNew] reads. */
        const val HEADER_SIZE = 16
    }
}
