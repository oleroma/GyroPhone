package com.oleroma.gyrophone.core.net

/**
 * Phone-to-host clock offset (host µs − phone µs) from PING/PONG.
 *
 * Each ping gives a round trip and an offset whose error is at most half the round trip's asymmetry, so the
 * fastest pings are the most trustworthy. The phone's and the PC's clocks also drift apart slowly (tens of
 * millionths), so a single best ping goes stale. This keeps the fastest ping of every [BUCKET_US] stretch for the
 * last [BUCKETS] stretches (about two minutes) and follows the drift with a straight line through them; the
 * offset is read off that line at the newest ping's time, so it moves smoothly instead of jumping when an old best
 * ping leaves the window. Until the line has [MIN_FIT_POINTS] points over [MIN_FIT_SPAN_US], it is the fastest
 * of the two newest stretches.
 *
 * t1/t4 are phone microseconds (send / receive), t2/t3 host microseconds (receive / send).
 * Only the receive thread calls [add]; the readers take the published values.
 */
class ClockSync {
    // The fastest ping of each stretch: phone time, round trip, offset. Ring, oldest at [head].
    private val bT = LongArray(BUCKETS)
    private val bRtt = LongArray(BUCKETS)
    private val bOff = LongArray(BUCKETS)
    private var bCount = 0
    private var head = 0
    private var curBucket = Long.MIN_VALUE

    // The newest round trips, for the recent best and the typical one.
    private val recent = LongArray(RECENT)
    private var rCount = 0
    private var rNext = 0
    private val sortBuf = LongArray(RECENT)

    /** hostUs - phoneUs at the newest ping; 0 before any sample. */
    var offsetUs = 0L
        private set

    /** How fast the PC's clock gains on the phone's, in parts per billion (0 until the line is fitted). */
    var driftPpb = 0L
        private set

    /** Lowest round trip of the last [RECENT_MIN] pings (host processing excluded); -1 before any sample. */
    var rttMinUs = -1L
        private set

    /** Middle (median) round trip of the last [RECENT] pings; -1 before any sample. */
    var rttTypUs = -1L
        private set

    /** Pings answered since [takePings]. */
    private var pings = 0

    /** Phone time of the newest ping. */
    private var lastT1 = 0L

    val hasSample: Boolean get() = rCount > 0

    /**
     * Adds a sample and returns true if what the PC is told changed: the offset moved by [REPORT_US] or more,
     * or the recent best round trip changed (or it was the first).
     */
    fun add(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        val r = (t4 - t1) - (t3 - t2)
        if (r < 0) return false // clock went backwards or a bogus PONG
        val off = ((t2 - t1) + (t3 - t4)) / 2
        pings++
        lastT1 = t1

        recent[rNext] = r
        rNext = (rNext + 1) % RECENT
        if (rCount < RECENT) rCount++

        val bucket = Math.floorDiv(t1, BUCKET_US)
        if (bCount == 0 || bucket != curBucket) {
            // A new stretch: its first ping is its best so far.
            val slot = if (bCount < BUCKETS) (head + bCount++) % BUCKETS else head.also { head = (head + 1) % BUCKETS }
            bT[slot] = t1; bRtt[slot] = r; bOff[slot] = off
            curBucket = bucket
        } else {
            val slot = (head + bCount - 1) % BUCKETS
            if (r < bRtt[slot]) { bT[slot] = t1; bRtt[slot] = r; bOff[slot] = off }
        }

        val beforeOff = offsetUs
        val beforeMin = rttMinUs
        val first = rCount == 1
        estimate()
        return first || rttMinUs != beforeMin || kotlin.math.abs(offsetUs - beforeOff) >= REPORT_US
    }

    /** Pings answered since the last call (for PHONE_STATS). */
    fun takePings(): Int = pings.also { pings = 0 }

    private fun estimate() {
        // Recent best and typical round trip.
        val n = rCount
        var min = Long.MAX_VALUE
        for (k in 0 until minOf(n, RECENT_MIN)) {
            val v = recent[(rNext - 1 - k + RECENT) % RECENT]
            if (v < min) min = v
        }
        rttMinUs = min
        for (k in 0 until n) sortBuf[k] = recent[k]
        java.util.Arrays.sort(sortBuf, 0, n)
        rttTypUs = sortBuf[n / 2]

        // Offset: a line through the stretches' best pings, weighted toward the faster ones.
        val oldest = bT[head]
        val newest = bT[(head + bCount - 1) % BUCKETS]
        if (bCount >= MIN_FIT_POINTS && newest - oldest >= MIN_FIT_SPAN_US) {
            var sw = 0.0; var st = 0.0; var so = 0.0
            for (k in 0 until bCount) {
                val i = (head + k) % BUCKETS
                val w = weight(bRtt[i])
                sw += w; st += w * (bT[i] - oldest); so += w * bOff[i]
            }
            val tMean = st / sw
            val oMean = so / sw
            var stt = 0.0; var sto = 0.0
            for (k in 0 until bCount) {
                val i = (head + k) % BUCKETS
                val w = weight(bRtt[i])
                val dt = (bT[i] - oldest) - tMean
                stt += w * dt * dt; sto += w * dt * (bOff[i] - oMean)
            }
            var slope = if (stt > 0) sto / stt else 0.0
            slope = slope.coerceIn(-MAX_DRIFT, MAX_DRIFT)
            driftPpb = (slope * 1e9).toLong()
            offsetUs = (oMean + slope * ((lastT1 - oldest) - tMean)).let { kotlin.math.round(it).toLong() }
        } else {
            // Too little history for a line: the faster of the two newest stretches' best pings.
            var best = (head + bCount - 1) % BUCKETS
            if (bCount >= 2) {
                val prev = (head + bCount - 2) % BUCKETS
                if (bRtt[prev] < bRtt[best]) best = prev
            }
            offsetUs = bOff[best]
            driftPpb = 0
        }
    }

    /** A faster ping's offset is more trustworthy: its error is at most half its round trip. */
    private fun weight(rtt: Long): Double {
        val r = maxOf(rtt, 50L).toDouble()
        return 1.0 / (r * r)
    }

    fun reset() {
        bCount = 0; head = 0; curBucket = Long.MIN_VALUE
        rCount = 0; rNext = 0
        offsetUs = 0L; driftPpb = 0L; rttMinUs = -1L; rttTypUs = -1L
        pings = 0; lastT1 = 0L
    }

    companion object {
        /** Each stretch keeps its fastest ping. */
        const val BUCKET_US = 4_000_000L
        /** Stretches kept: about two minutes. */
        const val BUCKETS = 30
        /** A line needs this many stretches... */
        const val MIN_FIT_POINTS = 4
        /** ...spread over at least this long. */
        const val MIN_FIT_SPAN_US = 12_000_000L
        /** Clocks never drift more than this (300 parts per million); a steeper line is noise. */
        const val MAX_DRIFT = 300e-6
        /** Round trips kept for the typical one. */
        const val RECENT = 16
        /** The recent best is over this many newest pings. */
        const val RECENT_MIN = 8
        /** An offset change this big (µs) is worth telling the PC at once. */
        const val REPORT_US = 20L
    }
}
