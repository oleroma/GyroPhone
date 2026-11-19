package com.oleroma.gyrophone.core.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The world's up in the phone's frame, for the World and Player gyro spaces
 * ([com.oleroma.gyrophone.core.model.GyroSpace]): the gyro's turn, pulled toward the
 * accelerometer's gravity over about [GRAVITY_TAU_S], so it is quick and never drifts. Each good
 * accelerometer reading pulls once, by the time since the reading before it; a jolt off 1 g (a hard
 * flick, a tap) pulls nothing, so during one the gyro alone carries the pose.
 *
 * Called from the gyro's thread; the accelerometer's ([setUp]) may come from another, so the
 * vector is guarded.
 */
class GravityPose {
    // The world's up in the phone's frame (right, up, toward the player), unit length.
    private var ux = 0f
    private var uy = 1f
    private var uz = 0f
    private var hasUp = false

    // The time of the last accelerometer reading, good or not (ns; 0 = none yet).
    private var lastAccelNs = 0L

    /** The world's up in the phone's frame (right, up, toward the player), unit length, into [out]. */
    fun up(out: FloatArray) = synchronized(this) {
        out[0] = ux; out[1] = uy; out[2] = uz
    }

    /**
     * An accelerometer reading in the phone's frame (right, up, toward the player), gravity [g] in
     * its units, taken at [timestampNs]: a good one pulls the pose toward it by the time since the
     * last reading; one off 1 g (a jolt) is skipped, and its time is not made up later.
     */
    fun setUp(x: Float, y: Float, z: Float, g: Float, timestampNs: Long) = synchronized(this) {
        val dt = if (lastAccelNs == 0L) 0f else ((timestampNs - lastAccelNs) / 1e9f).coerceIn(0f, MAX_ACCEL_DT_S)
        lastAccelNs = timestampNs
        val n = sqrt(x * x + y * y + z * z)
        if (n == 0f || abs(n - g) > g * G_SLACK) return@synchronized
        val ax = x / n; val ay = y / n; val az = z / n
        if (!hasUp) {
            ux = ax; uy = ay; uz = az; hasUp = true
            return@synchronized
        }
        val k = 1f - exp(-dt / GRAVITY_TAU_S)
        ux += k * (ax - ux); uy += k * (ay - uy); uz += k * (az - uz)
        normalize()
    }

    /**
     * Forgets the pose (the screen turned, so the phone's frame changed, or the gyro space did):
     * the next accelerometer reading sets it at once. Until then up is the screen's up.
     */
    fun reset() = synchronized(this) {
        ux = 0f; uy = 1f; uz = 0f
        hasUp = false; lastAccelNs = 0L
    }

    /**
     * Turns the pose by the phone's rates [yaw], [pitch], [roll] (deg/s, as [GyroMouse.motion]) over
     * [dtSeconds]; gravity pulls it in [setUp].
     */
    fun rotate(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float) {
        if (dtSeconds <= 0f) return
        synchronized(this) {
            // The phone turns by ω (right, up, toward = pitch, -yaw, -roll); a world-fixed vector turns by -ω.
            val wx = pitch * DEG_TO_RAD * dtSeconds
            val wy = -yaw * DEG_TO_RAD * dtSeconds
            val wz = -roll * DEG_TO_RAD * dtSeconds
            // u -= ω × u
            val cx = wy * uz - wz * uy
            val cy = wz * ux - wx * uz
            val cz = wx * uy - wy * ux
            ux -= cx; uy -= cy; uz -= cz
            normalize()
        }
    }

    private fun normalize() {
        val n = sqrt(ux * ux + uy * uy + uz * uz)
        if (n > 0f) {
            ux /= n; uy /= n; uz /= n
        }
    }

    companion object {
        /** The pose follows gravity with this time constant (s). */
        const val GRAVITY_TAU_S = 0.5f

        /** Accelerometer readings further than this share off 1 g (the phone shaken) are skipped. */
        private const val G_SLACK = 0.15f

        /** A gap between accelerometer readings longer than this (s) pulls no more than this much. */
        private const val MAX_ACCEL_DT_S = 0.1f
        private const val DEG_TO_RAD = (Math.PI / 180).toFloat()
    }
}
