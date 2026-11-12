package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroSettings
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The phone's tilt and shakes from its accelerometer (m/s², Android sensor frame). Gravity is the
 * reading low-passed by time ([TILT_TAU_NS], so the feel is the same at any sensor rate); turned to
 * the screen as it is shown ([displayRotation]). Sideways tilt is
 * the turn in the screen's plane from upright (+ clockwise: right, like a wheel); forward and back
 * is the lean from where it was held when tilt started (+ back: the top edge toward the player).
 * Both are -1..1: 0 within the axis's deadzone, full at its range ([GyroSettings.tiltRangeOf]). A
 * shake is the reading jumping [GyroSettings.shakeStrength] from a slower gravity ([SHAKE_TAU_NS], as
 * it was at 50 Hz), at most once per [SHAKE_GAP_NS].
 */
class TiltShake {
    /** Screen rotation in degrees (0/90/180/270). */
    @Volatile var displayRotation: Int = 90

    /** The tilt ranges and deadzones and the shake strength; replaced whole from another thread. */
    @Volatile var settings: GyroSettings = GyroSettings()

    var x = 0f
        private set
    var y = 0f
        private set

    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    // Gravity as shakes measure it: slower, so a shake's first samples don't move it much.
    private var hx = 0f
    private var hy = 0f
    private var hz = 0f
    private var lastNs = 0L
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
        // The share of this sample that reaches gravity grows with the time since the last one.
        val dt = if (started) (timestampNs - lastNs).coerceAtLeast(0L).toFloat() else 0f
        lastNs = timestampNs
        if (!started) {
            gx = ax; gy = ay; gz = az
            hx = ax; hy = ay; hz = az
        } else {
            val a = 1f - exp(-dt / TILT_TAU_NS)
            gx += (ax - gx) * a; gy += (ay - gy) * a; gz += (az - gz) * a
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
        val s = settings
        x = level(side, s.tiltRangeOf(true), s.tiltDeadzoneOf(true))
        // Leaning back toward flat (more gravity on z) is the top edge going away: forward, -.
        y = level(-(lean - neutral), s.tiltRangeOf(false), s.tiltDeadzoneOf(false))

        // The jump from the slow gravity before this sample, scaled as the old 50 Hz filter saw it
        // (it moved 20% toward the sample first), so a shake strength means what it did.
        val lx = ax - hx; val ly = ay - hy; val lz = az - hz
        val jolt = sqrt(lx * lx + ly * ly + lz * lz) * (1f - SHAKE_STEP)
        if (dt > 0f) {
            val b = 1f - exp(-dt / SHAKE_TAU_NS)
            hx += lx * b; hy += ly * b; hz += lz * b
        }
        if (jolt > s.shakeStrength && timestampNs - lastShakeNs > SHAKE_GAP_NS) {
            lastShakeNs = timestampNs
            return true
        }
        return false
    }

    /** [deg] of tilt as -1..1: 0 within [deadzone], full at [range]. */
    private fun level(deg: Float, range: Float, deadzone: Float): Float {
        val dz = deadzone.coerceIn(0f, range - 1f)
        val a = kotlin.math.abs(deg)
        if (a <= dz) return 0f
        return (kotlin.math.sign(deg) * (a - dz) / (range.coerceAtLeast(1f) - dz)).coerceIn(-1f, 1f)
    }

    companion object {
        const val SHAKE_GAP_NS = 500_000_000L

        /**
         * Tilt's smoothing time: about 63% of a change within it. Short enough to follow the phone
         * closely at the accelerometer's 200 Hz, long enough to keep hand jitter out of the stick.
         */
        const val TILT_TAU_NS = 35_000_000f

        /** The old filter's step: 20% of each 50 Hz sample reached gravity. */
        private const val SHAKE_STEP = 0.2f

        /** Shake's gravity: the old filter's time constant (20 ms / -ln 0.8, about 90 ms). */
        const val SHAKE_TAU_NS = 89_600_000f
    }
}
