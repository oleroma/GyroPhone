package com.oleroma.gyrophone.core.engine

/**
 * Steady horizontal mouse counts for measuring a game's full turn ([com.oleroma.gyrophone.core.model.GameSens]):
 * runs at a fixed rate, optionally up to a target, and counts every count it sent, nudges
 * included, so the total is exact whatever the timing.
 */
class CountStream {
    /** Counts sent since [reset], right +. */
    var sent = 0L
        private set

    private var rate = 0f
    private var rem = 0f
    private var target: Long? = null

    /** Streaming now. */
    val running: Boolean get() = rate > 0f

    /** Streams at [countsPerSecond] until [stop]. */
    fun run(countsPerSecond: Float) {
        rate = countsPerSecond; target = null; rem = 0f
    }

    /** Streams at [countsPerSecond] until [sent] reaches [total], then stops by itself. */
    fun runTo(total: Long, countsPerSecond: Float) {
        if (total <= sent) return
        rate = countsPerSecond; target = total; rem = 0f
    }

    fun stop() {
        rate = 0f; rem = 0f; target = null
    }

    /** Stops and forgets what was sent. */
    fun reset() {
        stop(); sent = 0
    }

    /** [n] counts sent at once (negative: left); returns them. */
    fun nudge(n: Int): Int {
        sent += n
        return n
    }

    /** The counts due after [dtSeconds] of streaming. */
    fun tick(dtSeconds: Float): Int {
        if (rate <= 0f) return 0
        val f = rate * dtSeconds + rem
        var n = f.toInt()
        rem = f - n
        target?.let { t ->
            if (sent + n >= t) {
                n = (t - sent).toInt()
                stop()
            }
        }
        sent += n
        return n
    }
}
