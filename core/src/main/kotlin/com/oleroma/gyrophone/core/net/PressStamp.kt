package com.oleroma.gyrophone.core.net

import java.util.concurrent.atomic.AtomicLong

/**
 * The newest press or release input time, for PRESS_TIME ("Measure buttons" on the PC). The input
 * threads [note] each touch down or up and controller key edge with its event time; the sender
 * [take]s it when a STATE first carries a new press or release. An input that changed nothing is
 * forgotten after [MAX_AGE_NS], so it can't be blamed on a much later press. Costs one volatile
 * read per input while off.
 */
class PressStamp {
    /** The PC asks for PRESS_TIME (TEST_MODE [TestModeFlags.PRESS_TIMES]). */
    @Volatile var enabled = false
        set(v) {
            field = v
            if (!v) latest.set(0)
        }

    private val latest = AtomicLong(0)

    /** An input edge seen at [eventNanos] (System.nanoTime base). */
    fun note(eventNanos: Long) {
        if (enabled && eventNanos != 0L) latest.set(eventNanos)
    }

    /** The newest noted input time not older than [MAX_AGE_NS] at [nowNanos], or 0; clears it. */
    fun take(nowNanos: Long): Long {
        val t = latest.getAndSet(0)
        return if (t != 0L && nowNanos - t in 0..MAX_AGE_NS) t else 0L
    }

    companion object {
        const val MAX_AGE_NS = 1_000_000_000L
    }
}
