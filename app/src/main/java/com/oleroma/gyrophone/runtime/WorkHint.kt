package com.oleroma.gyrophone.runtime

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log

/**
 * Tells Android that the calling thread runs a short loop whose every pass must be done within
 * about [TARGET_NS] (a performance hint session, PerformanceHintManager), and how long each pass
 * really took, so the phone keeps it on cores and clocks fast enough rather than on its slowest
 * ones. Saving power is not preferred for it. Used by the gyro reader, the Wi-Fi and Bluetooth
 * senders, and the gyro displays' drawing thread (each frame within a few milliseconds). The report is one cheap call after a pass's work is done, never before or during it,
 * so it adds no delay.
 */
class WorkHint private constructor(private val session: PerformanceHintManager.Session) : AutoCloseable {
    private var longestNs = 0L
    private var lastReportNs = 0L

    /** One pass of the loop took [ns], ending at [now] (System.nanoTime): the longest since the last report goes out at most every [REPORT_GAP_NS]. */
    fun done(ns: Long, now: Long) {
        if (ns > longestNs) longestNs = ns
        if (now - lastReportNs < REPORT_GAP_NS) return
        lastReportNs = now
        val d = longestNs.coerceAtLeast(1L)
        longestNs = 0L
        try {
            session.reportActualWorkDuration(d)
        } catch (e: RuntimeException) {
            // A session the system dropped: the loop runs on without hints.
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "WorkHint"

        /** Each pass should be done within about 1 ms. */
        const val TARGET_NS = 1_000_000L

        /** Reports are batched by Android anyway; one every millisecond is plenty. */
        private const val REPORT_GAP_NS = 1_000_000L

        @Volatile private var manager: PerformanceHintManager? = null

        /** Called once with the app's context, before the loops start. */
        fun install(context: Context) {
            manager = context.getSystemService(PerformanceHintManager::class.java)
        }

        /**
         * A session for the calling thread whose passes should each be done within [targetNs], or
         * null when the phone offers none (no hint support).
         */
        fun forThisThread(targetNs: Long = TARGET_NS): WorkHint? {
            val m = manager ?: return null
            return try {
                val s = m.createHintSession(intArrayOf(Process.myTid()), targetNs) ?: return null
                // Android 15+: a hint for latency, not for saving power.
                runCatching { s.setPreferPowerEfficiency(false) }
                WorkHint(s)
            } catch (e: RuntimeException) {
                Log.w(TAG, "no performance hint session", e)
                null
            }
        }
    }
}
