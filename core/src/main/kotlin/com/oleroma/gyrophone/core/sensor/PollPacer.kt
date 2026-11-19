package com.oleroma.gyrophone.core.sensor

/**
 * Times the checks of the sensor's shared memory around when the next sample is due, instead of at a fixed rate.
 *
 * The sensor writes a sample every [periodNs] (learned from the samples' own timestamps). After one is seen, nothing
 * new can come for most of that period, so the thread sleeps through it and then checks often (a quarter of the poll
 * interval apart) in the short window where the next one is due. A sample then waits about one short check for its
 * reader instead of up to a whole poll interval, with no more wake-ups than fixed polling.
 *
 * Before the period is known, and whenever the sensor goes quiet or irregular, it checks at the plain poll interval.
 * Not thread safe: used by the poll thread alone.
 */
class PollPacer {
    /** The sensor's smoothed sample period, 0 until two samples in a row gave one. */
    var periodNs = 0L
        private set

    private var lastTimestampNs = 0L
    private var lastSeenNs = 0L

    /** The newest sample of a check that found some: its sensor timestamp, and when the check saw it. */
    fun onSample(nowNs: Long, timestampNs: Long) {
        val step = timestampNs - lastTimestampNs
        if (lastTimestampNs != 0L && step in MIN_PERIOD_NS..MAX_PERIOD_NS) {
            periodNs = if (periodNs == 0L) step else periodNs + (step - periodNs) / 8
        } else if (lastTimestampNs != 0L && step > MAX_PERIOD_NS) {
            periodNs = 0L
        }
        lastTimestampNs = timestampNs
        lastSeenNs = nowNs
    }

    /** How long to sleep before the next check, with [intervalNs] the plain poll interval. */
    fun parkNs(nowNs: Long, intervalNs: Long): Long {
        val period = periodNs
        // Paced only when the poll runs well above the sensor's rate: otherwise there is no quiet stretch to sleep through.
        if (period == 0L || period < intervalNs * 2) return intervalNs
        val since = nowNs - lastSeenNs
        if (since < 0 || since > period * LOST_PERIODS) return intervalNs
        // The last sample was seen up to one short check late, and the sensor's writes wander a little: start early by that much.
        val fine = (intervalNs / FINE_DIVISOR).coerceAtLeast(MIN_FINE_NS)
        val windowStart = period - (fine * 2).coerceAtLeast(MIN_GUARD_NS)
        return if (since < windowStart) windowStart - since else fine
    }

    fun reset() { periodNs = 0L; lastTimestampNs = 0L; lastSeenNs = 0L }

    companion object {
        private const val MIN_PERIOD_NS = 200_000L
        private const val MAX_PERIOD_NS = 20_000_000L
        /** After this many periods without a sample the sensor counts as quiet: plain polling until it is back. */
        private const val LOST_PERIODS = 3
        private const val FINE_DIVISOR = 4
        private const val MIN_FINE_NS = 50_000L
        private const val MIN_GUARD_NS = 150_000L
    }
}
