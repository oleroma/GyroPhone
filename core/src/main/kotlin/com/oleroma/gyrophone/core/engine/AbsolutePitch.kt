package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroSettings
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Absolute pitch ([com.oleroma.gyrophone.core.model.GyroSettings.absolutePitch]): the phone's pitch
 * against gravity sets the game camera's pitch, [gain] camera degrees per phone degree, level
 * with level. The game is never asked where its camera is: this keeps an estimate (the counts it
 * sent since it knew) and sends the difference to where the phone says the camera should be.
 *
 * Drift (counts the game dropped, the game moving the camera itself) is corrected at the poles:
 * once the phone asks for more than the camera's limit, the camera is sent there plus [PUSH_DEG]
 * once, so the game pins it at its pole whatever the estimate was, and the estimate becomes the
 * limit exactly. Set level ([setLevel]) says the camera is level now: it snaps to the phone.
 *
 * The phone's pitch is the gyro's turn, pulled toward the accelerometer's gravity over about
 * [GRAVITY_TAU_S] (a jolt off 1 g is skipped), so it is quick and never drifts. Pitch 0 is
 * where the phone is level ([levelDeg]), up +.
 *
 * Mouse Y is up + here; [step] returns HID counts (down +). Called from the gyro's thread; the
 * accelerometer's ([setUp]) may come from another, so the gravity vector is guarded.
 */
class AbsolutePitch {
    // The world's up in the phone's frame (right, up, toward the player), unit length.
    private var ux = 0f
    private var uy = 1f
    private var uz = 0f
    private var hasUp = false

    // The last accelerometer reading, unit length, and whether it is near 1 g.
    private var ax = 0f
    private var ay = 1f
    private var az = 0f
    private var accelFresh = false

    /** The camera's pitch as far as we know, in counts (up +); null until the first [step] after a [reset]. */
    private var camera: Float? = null

    /** The phone asked past a pole ([PUSH_DEG] was sent): 1 up, -1 down, 0 within the limits. */
    private var pinned = 0

    /** Set level was pressed: the next [step] takes the camera as level. */
    @Volatile private var levelAsked = false

    /** For a camera display: the camera's estimated pitch, where the phone points it (both camera degrees) and the limit, and whether it runs. */
    @Volatile var cameraDeg: Float = 0f
        private set
    @Volatile var phoneDeg: Float = 0f
        private set
    @Volatile var limitDeg: Float = GyroSettings.DEFAULT_PITCH_LIMIT_DEG
        private set
    @Volatile var running: Boolean = false
        private set

    /**
     * Where the phone is level, degrees: 0 upright, up along the screen's top (Y+); 90 flat with
     * up out of the screen; 180 upside down, up along the screen's bottom (Y-). Set from the UI.
     */
    @Volatile var levelDeg: Float = 0f

    /** The phone's pitch against gravity now, degrees from level ([levelDeg]), up +. */
    val pitchDeg: Float
        get() {
            val a = Math.toRadians(levelDeg.toDouble())
            val c = kotlin.math.cos(a).toFloat()
            val s = kotlin.math.sin(a).toFloat()
            // "Forward" turned from the back (0, 0, -1) toward the screen's top by the level angle.
            return synchronized(this) { asin((-uz * c + uy * s).coerceIn(-1f, 1f)) * RAD_TO_DEG }
        }

    /** The world's up in the phone's frame (right, up, toward the player), unit length, into [out]. */
    fun up(out: FloatArray) = synchronized(this) {
        out[0] = ux; out[1] = uy; out[2] = uz
    }

    /** Forgets the camera: the next [step] takes it as where the phone points it (nothing moves). Keeps the phone's pose. */
    fun reset() {
        camera = null
        pinned = 0
        running = false
    }

    /** The camera is level now: the next [step] sends it to the phone's angle. */
    fun setLevel() {
        levelAsked = true
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

    /**
     * One sample: [gain] camera degrees per phone degree (negative inverts), [countsPerDeg] the
     * game's mouse counts per camera degree, [limit] the camera's pitch limit (degrees each way).
     * Returns the HID mouse Y to send (down +).
     */
    fun step(gain: Float, countsPerDeg: Float, limit: Float): Int {
        if (countsPerDeg <= 0f) return 0
        val lim = limit * countsPerDeg
        val want = pitchDeg * gain * countsPerDeg
        limitDeg = limit
        phoneDeg = want / countsPerDeg
        running = true
        if (levelAsked) {
            levelAsked = false
            camera = 0f
            pinned = 0
        }
        val cam = camera ?: want.coerceIn(-lim, lim).also { camera = it }
        var send: Int
        when {
            want > lim -> {
                send = (lim - cam).roundToInt()
                if (pinned != 1) send += (PUSH_DEG * countsPerDeg).roundToInt()
                pinned = 1
                camera = lim
            }

            want < -lim -> {
                send = (-lim - cam).roundToInt()
                if (pinned != -1) send -= (PUSH_DEG * countsPerDeg).roundToInt()
                pinned = -1
                camera = -lim
            }

            else -> {
                send = (want - cam).roundToInt()
                pinned = 0
                // Whole counts only: the remainder waits in the estimate.
                camera = cam + send
            }
        }
        cameraDeg = camera!! / countsPerDeg
        return -send
    }

    companion object {
        /** Past a pole the camera is sent this much further (camera degrees), once, so the game pins it there. */
        const val PUSH_DEG = 10f

        /** The pose follows gravity with this time constant (s). */
        const val GRAVITY_TAU_S = 0.5f

        /** Accelerometer readings further than this share off 1 g (the phone shaken) are skipped. */
        private const val G_SLACK = 0.15f
        private const val DEG_TO_RAD = (Math.PI / 180).toFloat()
        private const val RAD_TO_DEG = (180 / Math.PI).toFloat()
    }
}
