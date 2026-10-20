package com.oleroma.gyrophone.core.net

/**
 * Phone-to-host clock offset from PING/PONG. Keeps the last [WINDOW] samples and trusts the one
 * with the lowest round trip, whose offset error is bounded by half its asymmetry.
 * t1/t4 are phone microseconds (send / receive), t2/t3 host microseconds (receive / send).
 */
class ClockSync {
    private val rtt = LongArray(WINDOW)
    private val offset = LongArray(WINDOW)
    private var count = 0
    private var next = 0

    /** hostUs - phoneUs of the best sample; 0 before any sample. */
    var offsetUs = 0L
        private set

    /** Lowest round trip in the window (host processing excluded); -1 before any sample. */
    var rttMinUs = -1L
        private set

    val hasSample: Boolean get() = count > 0

    /** Adds a sample and returns true if it lowered [rttMinUs] (or was the first). */
    fun add(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        val r = (t4 - t1) - (t3 - t2)
        if (r < 0) return false // clock went backwards or a bogus PONG
        rtt[next] = r
        offset[next] = ((t2 - t1) + (t3 - t4)) / 2
        next = (next + 1) % WINDOW
        if (count < WINDOW) count++
        val before = rttMinUs
        var best = -1
        for (i in 0 until count) if (best < 0 || rtt[i] < rtt[best]) best = i
        rttMinUs = rtt[best]
        offsetUs = offset[best]
        return before < 0 || rttMinUs < before
    }

    fun reset() {
        count = 0; next = 0; offsetUs = 0L; rttMinUs = -1L
    }

    companion object {
        const val WINDOW = 8
    }
}
