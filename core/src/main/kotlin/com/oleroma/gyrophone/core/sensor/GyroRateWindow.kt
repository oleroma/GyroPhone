package com.oleroma.gyrophone.core.sensor

import kotlin.math.roundToInt

/**
 * Measures the gyro sample rate over one-second windows of sensor time, and tells when it has
 * stayed below [CAPPED_BELOW_HZ] for [CAPPED_WINDOWS] windows in a row. Android caps a direct
 * channel to its normal rate (~50 Hz) while the microphone privacy toggle is on, where a
 * listener still gets ~200 Hz; the runtime then falls back to the listener. Two windows, so a
 * single HAL hiccup does not downgrade the source. Used from the one thread that receives samples.
 */
class GyroRateWindow {
    /** Samples per second over the last closed window; 0 until one has closed. */
    var rateHz = 0
        private set

    /** Closed windows in a row that measured below [CAPPED_BELOW_HZ]. */
    var slowWindows = 0
        private set

    val capped: Boolean get() = slowWindows >= CAPPED_WINDOWS

    private var start = 0L
    private var count = 0
    private var open = false

    /** Counts a sample with sensor timestamp [ts]; true when it closes a window ([rateHz] is then new). */
    fun add(ts: Long): Boolean {
        if (!open) { open = true; start = ts; count = 0; return false }
        count++
        val elapsed = ts - start
        if (elapsed < WINDOW_NS) return false
        rateHz = (count * 1e9 / elapsed).roundToInt()
        slowWindows = if (rateHz < CAPPED_BELOW_HZ) slowWindows + 1 else 0
        start = ts; count = 0
        return true
    }

    /** Starts over, e.g. when a source (re)starts: the next sample opens a new window. */
    fun reset() {
        open = false; rateHz = 0; slowWindows = 0
    }

    companion object {
        private const val WINDOW_NS = 1_000_000_000L
        const val CAPPED_BELOW_HZ = 150
        const val CAPPED_WINDOWS = 2
    }
}
