package com.oleroma.gyrophone.core.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The world's up in the phone's frame, for the World and Player gyro spaces
 * ([com.oleroma.gyrophone.core.model.GyroSpace]): the gyro's turn, pulled toward the
 * accelerometer's gravity over about [GRAVITY_TAU_S] (a jolt off 1 g is skipped), so it is quick
 * and never drifts.
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

    // The last accelerometer reading, unit length, and whether one came in.
    private var ax = 0f
    private var ay = 1f
    private var az = 0f
    private var accelFresh = false

    /** The world's up in the phone's frame (right, up, toward the player), unit length, into [out]. */
    fun up(out: FloatArray) = synchronized(this) {
        out[0] = ux; out[1] = uy; out[2] = uz
    }

    /** An accelerometer reading in the phone's frame (right, up, toward the player), gravity [g] in its units. */
    fun setUp(x: Float, y: Float, z: Float, g: Float) {
        val n = sqrt(x * x + y * y + z * z)
        if (n == 0f || abs(n - g) > g * G_SLACK) return
        synchronized(this) {
            ax = x / n; ay = y / n; az = z / n
            accelFresh = true
            if (!hasUp) {
                ux = ax; uy = ay; uz = az; hasUp = true
            }
        }
    }

    /**
     * Turns the pose by the phone's rates [yaw], [pitch], [roll] (deg/s, as [GyroMouse.motion]) over
     * [dtSeconds] and pulls it toward gravity.
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
            if (accelFresh) {
                val k = 1f - exp(-dtSeconds / GRAVITY_TAU_S)
                ux += k * (ax - ux); uy += k * (ay - uy); uz += k * (az - uz)
            }
            val n = sqrt(ux * ux + uy * uy + uz * uz)
            if (n > 0f) {
                ux /= n; uy /= n; uz /= n
            }
        }
    }

    companion object {
        /** The pose follows gravity with this time constant (s). */
        const val GRAVITY_TAU_S = 0.5f

        /** Accelerometer readings further than this share off 1 g (the phone shaken) are skipped. */
        private const val G_SLACK = 0.15f
        private const val DEG_TO_RAD = (Math.PI / 180).toFloat()
    }
}
