package com.oleroma.gyrophone.core.debug

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostics' two gyro timings, each as this second's average and longest:
 *
 * - **age**: how old a gyro sample is when the app gets it (now minus the sensor's own time for it):
 *   the sensor's and Android's delay plus the wait until the app looked;
 * - **to send**: from the app getting a sample to the packet that carries it having left.
 *
 * Fed only while Diagnostics is on. The gyro thread and the send thread each add up plain numbers
 * of their own and publish them once a second: no lock, nothing made, one atomic per sample and per send.
 */
class GyroTiming {
    // When the oldest sample no packet has carried yet came (the caller's nanosecond clock); 0: none waits.
    private val pending = AtomicLong()

    // Gyro thread only.
    private var ageSum = 0L
    private var ageN = 0
    private var ageMax = 0L
    private var ageOff = 0
    private var ageFrom = 0L

    // Send thread only.
    private var sendSum = 0L
    private var sendN = 0
    private var sendMax = 0L
    private var sendFrom = 0L

    // The last full second of each, read by [line].
    @Volatile private var ageAvgOut = 0L
    @Volatile private var ageMaxOut = 0L
    @Volatile private var ageNOut = 0
    @Volatile private var ageOffOut = 0
    @Volatile private var ageAtOut = 0L
    @Volatile private var sendAvgOut = 0L
    @Volatile private var sendMaxOut = 0L
    @Volatile private var sendNOut = 0
    @Volatile private var sendAtOut = 0L

    /**
     * Gyro thread: a sample came at [nowNs], [ageNs] after the sensor took it (by the sensor's own
     * time; outside 0..[MAX_AGE_NS] the sensor is on another clock and the sample only counts as such).
     */
    fun sample(ageNs: Long, nowNs: Long) {
        pending.compareAndSet(0L, nowNs)
        if (ageFrom == 0L) ageFrom = nowNs
        if (ageNs in 0..MAX_AGE_NS) {
            ageSum += ageNs
            ageN++
            if (ageNs > ageMax) ageMax = ageNs
        } else ageOff++
        if (nowNs - ageFrom >= SECOND_NS) {
            ageAvgOut = if (ageN > 0) ageSum / ageN else 0L
            ageMaxOut = ageMax
            ageNOut = ageN
            ageOffOut = ageOff
            ageAtOut = nowNs
            ageSum = 0L; ageN = 0; ageMax = 0L; ageOff = 0
            ageFrom = nowNs
        }
    }

    /** Send thread, before it reads the engine: when the oldest waiting sample came, or 0. That sample no longer waits. */
    fun take(): Long = pending.getAndSet(0L)

    /** Send thread: the packet for [taken] couldn't go yet, so its sample waits on (unless an older mark is already back). */
    fun putBack(taken: Long) {
        if (taken != 0L && !pending.compareAndSet(0L, taken)) {
            // A newer sample came meanwhile: the older one is still the one waiting longest.
            pending.set(taken)
        }
    }

    /** Send thread: the packet carrying the sample [taken] (from [take]) has left at [nowNs]. */
    fun sent(taken: Long, nowNs: Long) {
        if (taken == 0L) return
        if (sendFrom == 0L) sendFrom = nowNs
        val d = nowNs - taken
        if (d >= 0) {
            sendSum += d
            sendN++
            if (d > sendMax) sendMax = d
        }
        if (nowNs - sendFrom >= SECOND_NS) {
            sendAvgOut = if (sendN > 0) sendSum / sendN else 0L
            sendMaxOut = sendMax
            sendNOut = sendN
            sendAtOut = nowNs
            sendSum = 0L; sendN = 0; sendMax = 0L
            sendFrom = nowNs
        }
    }

    /**
     * The last second in words, for the link line; null when no sample came lately:
     * `gyro age 0.42 ms avg, 1.31 max · sample to send 0.18 ms avg, 0.74 max`.
     */
    fun line(nowNs: Long): String? {
        val parts = ArrayList<String>(2)
        if (ageAtOut != 0L && nowNs - ageAtOut < STALE_NS) {
            if (ageNOut > 0) parts.add("gyro age ${ms(ageAvgOut)} ms avg, ${ms(ageMaxOut)} max")
            else if (ageOffOut > 0) parts.add("gyro age unknown (the sensor's clock differs)")
        }
        if (sendAtOut != 0L && nowNs - sendAtOut < STALE_NS && sendNOut > 0) {
            parts.add("sample to send ${ms(sendAvgOut)} ms avg, ${ms(sendMaxOut)} max")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    private fun ms(ns: Long) = String.format(Locale.US, "%.2f", ns / 1e6)

    companion object {
        /** A sample older than this (or newer than now) isn't on the app's clock. */
        const val MAX_AGE_NS = 1_000_000_000L
        private const val SECOND_NS = 1_000_000_000L
        private const val STALE_NS = 2_500_000_000L
    }
}
