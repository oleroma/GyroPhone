package com.oleroma.gyrophone.core.net

/**
 * Decides when the Wi-Fi pump sends a STATE. Pure: the caller passes the clock (ns), so tests
 * drive it with a fake one.
 *
 * - Never two packets within `1e9 / rateHz` ns.
 * - A packet is due as soon as the state differs from the last one sent.
 * - After a packet carrying a discrete change, repeats follow at +2 ms and +6 ms (replacing any
 *   pending repeats); a repeat carries the full current state.
 * - A KEEPALIVE goes out when nothing was sent for 20 ms.
 */
class SendScheduler(rateHz: Int = Wire.DEFAULT_RATE_HZ) {
    var rateHz: Int = rateHz
        set(value) {
            require(value > 0)
            field = value
            minIntervalNs = 1_000_000_000L / value
        }

    var minIntervalNs: Long = 1_000_000_000L / rateHz
        private set

    private var hasSent = false
    private var lastSentNs = 0L
    private var dirtyPending = false
    private val repeatAt = LongArray(Wire.REPEAT_OFFSETS_MS.size)
    private var repeatCount = 0 // pending repeats are repeatAt[0 until repeatCount], ascending

    /** Forgets all history, e.g. for a new session. */
    fun reset() {
        hasSent = false; lastSentNs = 0L; dirtyPending = false; repeatCount = 0
    }

    /**
     * What to send now: [NONE], [NORMAL], [REPEAT] or [KEEPALIVE]. [dirty] means the current
     * state differs from the last packet sent; [discrete] that the difference is a discrete
     * change (not just mouse totals); it is accepted for symmetry with [markSent].
     */
    @Suppress("UNUSED_PARAMETER")
    fun poll(nowNs: Long, dirty: Boolean, discrete: Boolean): Int {
        dirtyPending = dirty
        if (!hasSent) return if (dirty) NORMAL else KEEPALIVE
        if (nowNs - lastSentNs < minIntervalNs) return NONE
        if (dirty) return NORMAL
        if (repeatCount > 0 && nowNs >= repeatAt[0]) return REPEAT
        if (nowNs - lastSentNs >= KEEPALIVE_NS) return KEEPALIVE
        return NONE
    }

    /** Records a send of [kind] at [nowNs]; [discrete] if the packet carried a discrete change. */
    fun markSent(nowNs: Long, kind: Int, discrete: Boolean) {
        hasSent = true
        lastSentNs = nowNs
        dirtyPending = false
        if (discrete) {
            repeatCount = 0
            for (ms in Wire.REPEAT_OFFSETS_MS) repeatAt[repeatCount++] = nowNs + ms * 1_000_000L
        } else {
            // Any packet carries the full state, so it also satisfies repeats that are due.
            var drop = 0
            while (drop < repeatCount && repeatAt[drop] <= nowNs) drop++
            if (drop > 0) {
                for (i in drop until repeatCount) repeatAt[i - drop] = repeatAt[i]
                repeatCount -= drop
            }
        }
    }

    /**
     * Before this time (ns) nothing may be sent, so the pump should not take an engine snapshot
     * yet: one taken now would show, and use up, a quick tap that no packet then carries.
     */
    fun sendableAt(): Long = if (hasSent) lastSentNs + minIntervalNs else Long.MIN_VALUE

    /** When [poll] may next return something other than [NONE] without a new input change. */
    fun nextDeadline(): Long {
        if (!hasSent) return Long.MIN_VALUE
        var t = lastSentNs + KEEPALIVE_NS
        if (repeatCount > 0) t = minOf(t, maxOf(repeatAt[0], lastSentNs + minIntervalNs))
        if (dirtyPending) t = minOf(t, lastSentNs + minIntervalNs)
        return t
    }

    companion object {
        const val NONE = 0
        const val NORMAL = 1
        const val REPEAT = 2
        const val KEEPALIVE = 3
        const val KEEPALIVE_NS = Wire.KEEPALIVE_MS * 1_000_000L

        /** STATE flags to add for a send [kind]. */
        fun flagsFor(kind: Int): Int = when (kind) {
            REPEAT -> StateFlags.REPEAT
            KEEPALIVE -> StateFlags.KEEPALIVE
            else -> 0
        }
    }
}
