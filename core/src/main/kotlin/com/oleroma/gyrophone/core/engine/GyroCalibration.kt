package com.oleroma.gyrophone.core.engine

import kotlin.math.abs

/**
 * Rest calibration: averages the gyro while the phone lies still and returns that average as a
 * constant bias to subtract from every later sample. No filtering: the cursor still gets every
 * sample unchanged except for this fixed offset. Fails when the phone moved during the window.
 */
class GyroCalibration(private val durationNs: Long = DEFAULT_DURATION_NS) {

    sealed interface Result {
        /** Bias in rad/s (Android sensor frame). */
        data class Done(val x: Float, val y: Float, val z: Float) : Result
        data object Moved : Result
    }

    private var startNs = -1L
    private var n = 0
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0
    private var minX = 0f; private var maxX = 0f
    private var minY = 0f; private var maxY = 0f
    private var minZ = 0f; private var maxZ = 0f

    /** Feeds one sample; returns a result once [durationNs] has passed, else null. */
    fun add(x: Float, y: Float, z: Float, timestampNs: Long): Result? {
        if (startNs < 0) {
            startNs = timestampNs
            minX = x; maxX = x; minY = y; maxY = y; minZ = z; maxZ = z
        }
        n++
        sx += x; sy += y; sz += z
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
        minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        if (timestampNs - startNs < durationNs) return null
        val spread = maxOf(maxX - minX, maxY - minY, maxZ - minZ)
        val mx = (sx / n).toFloat(); val my = (sy / n).toFloat(); val mz = (sz / n).toFloat()
        return if (spread > MAX_SPREAD || maxOf(abs(mx), abs(my), abs(mz)) > MAX_BIAS) Result.Moved
        else Result.Done(mx, my, mz)
    }

    companion object {
        const val DEFAULT_DURATION_NS = 2_000_000_000L
        /** Peak-to-peak noise allowed while still: 0.05 rad/s, about 3 deg/s. */
        const val MAX_SPREAD = 0.05f
        /** A real bias is tiny; anything larger is slow motion, not drift (0.03 rad/s, ~1.7 deg/s). */
        const val MAX_BIAS = 0.03f
    }
}
