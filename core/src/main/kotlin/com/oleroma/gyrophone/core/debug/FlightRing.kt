package com.oleroma.gyrophone.core.debug

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Diagnostics' per-packet record on the phone (docs/spec/phone-settings.md, "Diagnostics", "Flight record"): one fixed
 * entry per STATE sent, in a ring made when Diagnostics comes on (sized for the minutes it keeps). A Save writes it as
 * `phone-<time>-flight.bin` beside the text save; tools/flight-read.py reads it, and pairs it with the PC's by seq.
 *
 * Only the send thread calls [add] (plain array stores, no allocation, no lock); [write] copies from any thread and
 * leaves out the entries the send thread overwrote meanwhile.
 */
class FlightRing(minutes: Int) {
    val capacity = minutes.coerceIn(1, 30) * PER_MINUTE
    val minutes get() = capacity / PER_MINUTE

    private val at = LongArray(capacity)
    private val seq = IntArray(capacity)
    private val phone = IntArray(capacity)
    private val age = IntArray(capacity)
    private val send = IntArray(capacity)
    private val wake = IntArray(capacity)
    private val flags = IntArray(capacity)

    @Volatile private var head = 0L

    val count: Long get() = head

    /**
     * One STATE sent: when (phone µs), its seq and the phone time it carries, how old the newest gyro sample in it was
     * (-1 none), how long the send took and how late the send thread woke for it (µs), [flags].
     */
    fun add(atUs: Long, seq: Int, phoneUs: Int, ageUs: Int, sendUs: Int, wakeLateUs: Int, flags: Int) {
        val h = head
        val i = (h % capacity).toInt()
        at[i] = atUs
        this.seq[i] = seq
        phone[i] = phoneUs
        age[i] = ageUs
        send[i] = sendUs
        wake[i] = wakeLateUs
        this.flags[i] = flags
        head = h + 1
    }

    /**
     * Writes the entries sent after [fromUs], oldest first, after the header ("GPFLPHON", version, entry size, count, the
     * phone clock and the wall clock in Unix ms at [nowUs]). Returns the count.
     */
    fun write(out: OutputStream, fromUs: Long, nowUs: Long, unixMs: Long): Int {
        val h = head
        val tail = maxOf(0L, h - capacity)
        val end = h - 1 // the newest may be half written
        val all = ByteBuffer.allocate(maxOf(0L, end - tail).toInt() * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var n = 0
        var k = tail
        while (k < end) {
            val i = (k % capacity).toInt()
            k++
            if (at[i] <= fromUs) continue
            all.putLong(at[i]).putInt(seq[i]).putInt(phone[i]).putInt(age[i]).putInt(send[i]).putInt(wake[i]).putInt(flags[i])
            n++
        }
        // Entries the send thread reached while this copied (once the ring is full), and one more: left out from the oldest.
        val skip = (head + 1 - capacity - tail).coerceIn(0L, n.toLong()).toInt()
        val count = n - skip
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("GPFLPHON".toByteArray(Charsets.US_ASCII)).putInt(VERSION).putInt(RECORD_BYTES)
            .putLong(count.toLong()).putLong(nowUs).putLong(unixMs)
        out.write(header.array())
        out.write(all.array(), skip * RECORD_BYTES, count * RECORD_BYTES)
        return count
    }

    companion object {
        const val VERSION = 1
        const val RECORD_BYTES = 32
        const val HEADER_BYTES = 40
        /** Entries per minute kept: up to a thousand STATEs a second, with room. */
        const val PER_MINUTE = 66_000
        /** The STATE carried a change of keys or buttons. */
        const val DISCRETE = 1
        /** It was a keepalive (nothing changed for 20 ms). */
        const val KEEPALIVE = 2
        /** The send failed. */
        const val FAILED = 4
        /** It carried a gyro sample not sent before. */
        const val SAMPLE = 8
    }
}
