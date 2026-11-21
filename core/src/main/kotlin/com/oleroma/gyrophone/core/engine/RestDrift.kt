package com.oleroma.gyrophone.core.engine

import kotlin.math.abs

/**
 * "Re-measure when the phone rests": watches the raw gyro for a stretch of [durationNs] in which
 * the phone lies perfectly still (every axis stays within [maxSpread] peak to peak, stricter than
 * [GyroCalibration]) and then reports that stretch's average as the new drift. A sample that moves
 * starts the stretch again at that sample. Constant work per sample: a few compares and adds, no
 * buffer, so it can sit on the gyro thread.
 */
class RestDrift(
    private val durationNs: Long = DEFAULT_DURATION_NS,
    private val maxSpread: Float = MAX_SPREAD,
    private val maxBias: Float = GyroCalibration.MAX_RAW_BIAS,
) {
    /** The last measured drift (rad/s), valid after [add] returned true. */
    var x = 0f; private set
    var y = 0f; private set
    var z = 0f; private set

    private var startNs = -1L
    private var lastNs = 0L
    private var n = 0
    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0
    private var minX = 0f; private var maxX = 0f
    private var minY = 0f; private var maxY = 0f
    private var minZ = 0f; private var maxZ = 0f

    /** Feeds one raw sample; true when a still stretch just ended and [x], [y], [z] hold its drift. */
    fun add(x: Float, y: Float, z: Float, timestampNs: Long): Boolean {
        // A gap (the gyro paused) or time going back starts over.
        if (startNs < 0 || timestampNs < lastNs || timestampNs - lastNs > MAX_GAP_NS) begin(x, y, z, timestampNs)
        lastNs = timestampNs
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
        minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        if (maxX - minX > maxSpread || maxY - minY > maxSpread || maxZ - minZ > maxSpread) {
            begin(x, y, z, timestampNs)
        }
        n++
        sx += x; sy += y; sz += z
        if (timestampNs - startNs < durationNs) return false
        val mx = (sx / n).toFloat(); val my = (sy / n).toFloat(); val mz = (sz / n).toFloat()
        startNs = -1L
        if (maxOf(abs(mx), abs(my), abs(mz)) > maxBias) return false
        this.x = mx; this.y = my; this.z = mz
        return true
    }

    fun reset() {
        startNs = -1L
    }

    private fun begin(x: Float, y: Float, z: Float, timestampNs: Long) {
        startNs = timestampNs
        n = 0
        sx = 0.0; sy = 0.0; sz = 0.0
        minX = x; maxX = x; minY = y; maxY = y; minZ = z; maxZ = z
    }

    companion object {
        const val DEFAULT_DURATION_NS = 3_000_000_000L
        /** Peak-to-peak noise allowed: 0.015 rad/s, under 1 deg/s. A phone in a hand never stays this still. */
        const val MAX_SPREAD = 0.015f
        const val MAX_GAP_NS = 100_000_000L
    }
}
