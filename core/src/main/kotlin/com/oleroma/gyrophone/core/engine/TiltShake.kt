package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroSettings
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The phone's tilt and shakes, from its accelerometer (m/s²) and, while it runs, its gyro (rad/s),
 * both in the Android sensor frame, turned to the screen as it is shown ([displayRotation]).
 *
 * **Sideways tilt** is the phone's turn about the axis through its screen, like a steering wheel
 * (+ clockwise: right). Where the screen is tilted up from flat, gravity says where upright is, so
 * level is always straight. With the gyro running the turn is the gyro's (quick, steady when the
 * screen is tapped), and gravity pulls it to upright the more firmly the more the phone is tilted
 * up ([w2]). Lying flat, gravity can't tell, so the gyro carries on alone from where it was; there
 * [GyroSettings.tiltCompass] lets the compass hold straight where the phone went flat, and
 * [straighten] (Set orientation) makes the way it points now straight. Without the gyro (the
 * workspace's gyro off) it is gravity alone, low-passed by time ([TILT_TAU_NS]), and it holds
 * still while the phone lies flat.
 *
 * **Forward and back** is the lean from where it was held when tilt started (+ back: the top edge
 * toward the player), from the same gravity.
 *
 * Both are -1..1: 0 within the axis's deadzone, full at its range ([GyroSettings.tiltRangeOf]). A
 * shake is the reading jumping [GyroSettings.shakeStrength] from a slower gravity ([SHAKE_TAU_NS],
 * as it was at 50 Hz), at most once per [SHAKE_GAP_NS].
 *
 * The accelerometer, the gyro and the magnetometer may call from their own threads.
 */
class TiltShake {
    /** Screen rotation in degrees (0/90/180/270). */
    @Volatile var displayRotation: Int = 90

    /** The tilt ranges and deadzones, the compass switch and the shake strength; replaced whole from another thread. */
    @Volatile var settings: GyroSettings = GyroSettings()

    @Volatile var x = 0f
        private set
    @Volatile var y = 0f
        private set

    // Gravity from the accelerometer alone, low-passed (screen frame, m/s²).
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    // Gravity turned by the gyro and pulled toward the accelerometer (screen frame, unit length).
    private var fx = 0f
    private var fy = 1f
    private var fz = 0f
    // Gravity as shakes measure it: slower, so a shake's first samples don't move it much.
    private var hx = 0f
    private var hy = 0f
    private var hz = 0f
    private var lastNs = 0L
    private var lastGyroNs = 0L
    private var started = false
    private var neutral = 0f
    private var lastShakeNs = Long.MIN_VALUE / 2

    /** Accelerometer samples since the last gyro sample: the gyro counts as running while few have passed. */
    private var accelSinceGyro = GYRO_STALE

    /** The sideways turn, degrees, -180..180 (+ clockwise). */
    private var wheel = 0f

    /** Lying flat ([w2] under [FLAT_W2]) last time: going flat is when the compass takes its straight. */
    private var flat = false

    /** The compass heading in the screen's plane now and the one that is straight, degrees; NaN = none yet. */
    private var heading = Float.NaN
    private var straightHeading = Float.NaN

    private val fused: Boolean get() = accelSinceGyro < GYRO_STALE

    /** Starts over: the next sample sets gravity, the forward / back rest point and the sideways turn. */
    @Synchronized
    fun reset() {
        started = false
        x = 0f; y = 0f
        accelSinceGyro = GYRO_STALE
        lastGyroNs = 0L
        heading = Float.NaN; straightHeading = Float.NaN
    }

    /**
     * Set orientation: lying flat, the way the phone points now is straight (and the compass's
     * straight). Tilted up, gravity already says where straight is, so nothing changes.
     */
    @Synchronized
    fun straighten() {
        if (!started || w2() >= FLAT_W2) return
        wheel = 0f
        straightHeading = heading
        output()
    }

    /** One accelerometer sample at [timestampNs]; true when it is a shake. */
    @Synchronized
    fun update(ax: Float, ay: Float, az: Float, timestampNs: Long): Boolean {
        // The share of this sample that reaches gravity grows with the time since the last one.
        val dt = if (started) (timestampNs - lastNs).coerceAtLeast(0L).toFloat() else 0f
        lastNs = timestampNs
        if (accelSinceGyro < GYRO_STALE) accelSinceGyro++
        val r = screen(ax, ay, az)
        val sx = r[0]; val sy = r[1]; val sz = r[2]
        if (!started) {
            gx = sx; gy = sy; gz = sz
            hx = ax; hy = ay; hz = az
            setFused(sx, sy, sz)
        } else {
            val a = 1f - exp(-dt / TILT_TAU_NS)
            gx += (sx - gx) * a; gy += (sy - gy) * a; gz += (sz - gz) * a
            if (fused) {
                // Gravity pulls the gyro's pose slowly; a jolt (the phone tapped or shaken) is skipped.
                val n = sqrt(sx * sx + sy * sy + sz * sz)
                if (n > 0f && abs(n - G) <= G * G_SLACK) {
                    val k = 1f - exp(-dt / GRAVITY_TAU_NS)
                    fx += (sx / n - fx) * k; fy += (sy / n - fy) * k; fz += (sz / n - fz) * k
                    normalizeFused()
                }
            } else {
                setFused(gx, gy, gz)
            }
        }
        if (!started) {
            neutral = lean()
            val w = w2()
            flat = w < FLAT_W2
            // Upright, gravity says the turn; lying flat it starts straight.
            wheel = if (flat) 0f else side()
            started = true
        } else if (!fused) {
            // Gravity alone: it follows gravity's turn, and holds still lying flat where that turn is noise.
            if (w2() >= NOISE_W2) wheel = side()
            flat = w2() < FLAT_W2
        }
        output()

        // The jump from the slow gravity before this sample, scaled as the old 50 Hz filter saw it
        // (it moved 20% toward the sample first), so a shake strength means what it did.
        val lx = ax - hx; val ly = ay - hy; val lz = az - hz
        val jolt = sqrt(lx * lx + ly * ly + lz * lz) * (1f - SHAKE_STEP)
        if (dt > 0f) {
            val b = 1f - exp(-dt / SHAKE_TAU_NS)
            hx += lx * b; hy += ly * b; hz += lz * b
        }
        if (jolt > settings.shakeStrength && timestampNs - lastShakeNs > SHAKE_GAP_NS) {
            lastShakeNs = timestampNs
            return true
        }
        return false
    }

    /**
     * One gyro sample (bias removed, rad/s, sensor frame) at [timestampNs]: turns the pose and the
     * sideways turn, then pulls them toward gravity and, lying flat with the compass on, the compass.
     */
    @Synchronized
    fun gyro(wx: Float, wy: Float, wz: Float, timestampNs: Long) {
        val dt = if (lastGyroNs == 0L) 0f else ((timestampNs - lastGyroNs) / 1e9f).coerceIn(0f, MAX_GYRO_DT_S)
        lastGyroNs = timestampNs
        accelSinceGyro = 0
        if (!started || dt <= 0f) return
        val r = screen(wx, wy, wz)
        // The phone turns by ω; a world-fixed vector (gravity) turns by -ω: f -= ω × f.
        val ox = r[0] * dt; val oy = r[1] * dt; val oz = r[2] * dt
        val cx = oy * fz - oz * fy
        val cy = oz * fx - ox * fz
        val cz = ox * fy - oy * fx
        fx -= cx; fy -= cy; fz -= cz
        normalizeFused()
        // Turning clockwise (seen from the front) is a negative turn about the axis toward the player.
        wheel = wrap(wheel - Math.toDegrees(oz.toDouble()).toFloat())
        val w = w2()
        // Gravity holds the turn to upright, firmly when the screen faces sideways, not at all lying flat.
        val k = 1f - exp(-dt * w / ANCHOR_TAU_S)
        wheel = wrap(wheel + wrap(side() - wheel) * k)
        val nowFlat = w < FLAT_W2
        if (nowFlat && !flat) straightHeading = if (heading.isNaN()) Float.NaN else wrap(heading - wheel)
        flat = nowFlat
        if (flat && settings.tiltCompass && !heading.isNaN() && !straightHeading.isNaN()) {
            // The compass holds straight where the phone went flat; the flatter, the firmer.
            val c = 1f - exp(-dt * (1f - w / FLAT_W2) / COMPASS_TAU_S)
            wheel = wrap(wheel + wrap(wrap(heading - straightHeading) - wheel) * c)
        }
        output()
    }

    /** One magnetometer sample (µT, sensor frame): the compass heading in the screen's plane, level. */
    @Synchronized
    fun north(mx: Float, my: Float, mz: Float) {
        if (!started) return
        val r = screen(mx, my, mz)
        // The field's level part (gravity's share taken out), so leaning a little doesn't turn it.
        val d = r[0] * fx + r[1] * fy + r[2] * fz
        val lx = r[0] - d * fx; val ly = r[1] - d * fy
        if (lx * lx + ly * ly < 1e-6f) return
        heading = Math.toDegrees(atan2(-lx, ly).toDouble()).toFloat()
        // Already flat when the compass came on: straight is where the phone points now.
        if (straightHeading.isNaN() && flat) straightHeading = wrap(heading - wheel)
    }

    /** The screen's x (right), y (up) and z (toward the player) as shown, from the sensor frame. */
    private val turned = FloatArray(3)
    private fun screen(x: Float, y: Float, z: Float): FloatArray {
        when (displayRotation) {
            90 -> { turned[0] = -y; turned[1] = x }
            180 -> { turned[0] = -x; turned[1] = -y }
            270 -> { turned[0] = y; turned[1] = -x }
            else -> { turned[0] = x; turned[1] = y }
        }
        turned[2] = z
        return turned
    }

    private fun setFused(x: Float, y: Float, z: Float) {
        fx = x; fy = y; fz = z
        normalizeFused()
    }

    private fun normalizeFused() {
        val n = sqrt(fx * fx + fy * fy + fz * fz)
        if (n > 0f) { fx /= n; fy /= n; fz /= n } else { fx = 0f; fy = 1f; fz = 0f }
    }

    /** How much of gravity lies in the screen's plane, squared: 1 with the screen upright, 0 lying flat. */
    private fun w2(): Float = fx * fx + fy * fy

    /** Gravity's turn in the screen's plane from upright, degrees (+ clockwise). */
    private fun side(): Float = Math.toDegrees(atan2(-fx, fy).toDouble()).toFloat()

    /** How far the screen leans back from upright, degrees. */
    private fun lean(): Float = Math.toDegrees(atan2(fz, sqrt(fx * fx + fy * fy)).toDouble()).toFloat()

    private fun output() {
        val s = settings
        x = level(wheel, s.tiltRangeOf(true), s.tiltDeadzoneOf(true))
        // Leaning back toward flat (more gravity on z) is the top edge going away: forward, -.
        y = level(-(lean() - neutral), s.tiltRangeOf(false), s.tiltDeadzoneOf(false))
    }

    /** [deg] of tilt as -1..1: 0 within [deadzone], full at [range]. */
    private fun level(deg: Float, range: Float, deadzone: Float): Float {
        val dz = deadzone.coerceIn(0f, range - 1f)
        val a = abs(deg)
        if (a <= dz) return 0f
        return (kotlin.math.sign(deg) * (a - dz) / (range.coerceAtLeast(1f) - dz)).coerceIn(-1f, 1f)
    }

    private fun wrap(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f else if (d <= -180f) d += 360f
        return d
    }

    companion object {
        const val SHAKE_GAP_NS = 500_000_000L

        /**
         * Tilt's smoothing time without the gyro: about 63% of a change within it. Short enough to
         * follow the phone closely at the accelerometer's 200 Hz, long enough to keep hand jitter out of the stick.
         */
        const val TILT_TAU_NS = 35_000_000f

        /** With the gyro, gravity pulls its pose over this time, so taps and hand jolts don't reach the tilt. */
        const val GRAVITY_TAU_NS = 500_000_000f

        /** Upright, gravity pulls the sideways turn over this time (s); tilted back, slower ([w2] times), and lying flat not at all. */
        const val ANCHOR_TAU_S = 0.05f

        /** Lying flat, the compass pulls the sideways turn over this time (s). */
        const val COMPASS_TAU_S = 1f

        /** Gravity's share in the screen's plane, squared, under which the phone counts as lying flat: within 20° of flat. */
        const val FLAT_W2 = 0.117f

        /** Without the gyro, under this (within 10° of flat) gravity's turn is noise and the tilt holds still. */
        const val NOISE_W2 = 0.03f

        /** The gyro counts as stopped after this many accelerometer samples with no gyro sample. */
        private const val GYRO_STALE = 4

        /** A gap in gyro samples longer than this (s) is not turned through. */
        private const val MAX_GYRO_DT_S = 0.05f

        /** Accelerometer readings further than this share off 1 g (the phone tapped or shaken) don't pull gravity. */
        private const val G_SLACK = 0.15f
        private const val G = 9.80665f

        /** The old filter's step: 20% of each 50 Hz sample reached gravity. */
        private const val SHAKE_STEP = 0.2f

        /** Shake's gravity: the old filter's time constant (20 ms / -ln 0.8, about 90 ms). */
        const val SHAKE_TAU_NS = 89_600_000f
    }
}
