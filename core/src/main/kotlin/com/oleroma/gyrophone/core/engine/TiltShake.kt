package com.oleroma.gyrophone.core.engine

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The phone's tilt and shakes from its accelerometer (m/s², Android sensor frame). Gravity is the
 * reading low-passed; turned to the screen as it is shown ([displayRotation]). Sideways tilt is
 * the turn in the screen's plane from upright (+ clockwise: right, like a wheel); forward and back
 * is the lean from where it was held when tilt started (+ back: the top edge toward the player).
 * Both are -1..1, full at [rangeDeg]. A shake is the reading jumping [SHAKE_MS2] from gravity, at
 * most once per [SHAKE_GAP_NS].
 */
class TiltShake {
    /** Screen rotation in degrees (0/90/180/270). */
    @Volatile var displayRotation: Int = 90

    /** Tilt for a full deflection, degrees. */
    @Volatile var rangeDeg: Float = DEFAULT_RANGE_DEG

    var x = 0f
        private set
    var y = 0f
        private set

    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var started = false
    private var neutral = 0f
    private var lastShakeNs = Long.MIN_VALUE / 2

    /** Starts over: the next sample sets gravity and the forward / back rest point. */
    fun reset() {
        started = false
        x = 0f; y = 0f
    }

    /** One accelerometer sample at [timestampNs]; true when it is a shake. */
    fun update(ax: Float, ay: Float, az: Float, timestampNs: Long): Boolean {
        if (!started) {
            gx = ax; gy = ay; gz = az
        } else {
            gx += (ax - gx) * SMOOTHING; gy += (ay - gy) * SMOOTHING; gz += (az - gz) * SMOOTHING
        }
        // The screen's x (right) and y (up) as shown.
        val sx: Float
        val sy: Float
        when (displayRotation) {
            90 -> { sx = -gy; sy = gx }
            180 -> { sx = -gx; sy = -gy }
            270 -> { sx = gy; sy = -gx }
            else -> { sx = gx; sy = gy }
        }
        val side = Math.toDegrees(atan2(-sx, sy).toDouble()).toFloat()
        val lean = Math.toDegrees(atan2(gz, sqrt(sx * sx + sy * sy)).toDouble()).toFloat()
        if (!started) {
            neutral = lean
            started = true
        }
        val r = rangeDeg.coerceAtLeast(1f)
        x = (side / r).coerceIn(-1f, 1f)
        // Leaning back toward flat (more gravity on z) is the top edge going away: forward, -.
        y = (-(lean - neutral) / r).coerceIn(-1f, 1f)

        val lx = ax - gx; val ly = ay - gy; val lz = az - gz
        val jolt = sqrt(lx * lx + ly * ly + lz * lz)
        if (jolt > SHAKE_MS2 && timestampNs - lastShakeNs > SHAKE_GAP_NS) {
            lastShakeNs = timestampNs
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_RANGE_DEG = 30f
        const val SHAKE_MS2 = 14f
        const val SHAKE_GAP_NS = 500_000_000L

        /** Share of each sample that reaches gravity (about 0.1 s at 50 Hz). */
        private const val SMOOTHING = 0.2f
    }
}
