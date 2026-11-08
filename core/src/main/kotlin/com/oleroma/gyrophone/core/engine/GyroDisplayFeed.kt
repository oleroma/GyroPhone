package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The data one gyro display ([com.oleroma.gyrophone.core.model.TouchKind.DISPLAY], control [id])
 * draws, gathered from gyro samples ([feed], the gyro thread), accelerometer readings ([up]) and
 * magnetometer readings ([north]); the UI reads it once per frame. Only what its [display] kind
 * needs is kept up to date.
 */
class GyroDisplayFeed(val id: String, display: GyroDisplay) {
    /** Replaced from the UI when its settings change; read once per sample. */
    @Volatile var display: GyroDisplay = display

    /** The pose the pose kinds show ([GyroDisplayKind.pose]): turned by the gyro, or against the world with gravity. */
    val pose = GyroPose()

    /** The graph's speeds (GRAPH only). */
    val trace: GyroTrace? = if (display.kind == GyroDisplayKind.GRAPH) GyroTrace() else null

    /** Seconds since the phone stopped turning, or since the ratchet press while easing on it. */
    private var calm = 0f

    /** Easing back after a ratchet press ([GyroDisplay.easeOnRatchet]), until straight or let go. */
    private var easing = false

    /** [GyroDisplay.restNow] of [restOf], kept so a sample does not make it again. */
    private var restOf: GyroDisplay? = null

    /** Its mode at the last sample (gravity, trackball): switching starts it straight. */
    private var wasGravity = display.needsGravity
    private var wasTrackball = display.isTrackball
    private var wasCompass = display.needsCompass

    /**
     * One sample: rates in deg/s ([GyroMouse.motion]) over [dtSeconds]; [axes] the gyro axes that
     * move the mouse ([MappingEngine.gyroAxes]); a ratchet was just [pressed]; one is [held];
     * [gyro] the gyro settings in use (a trackball may turn by their real world sensitivity).
     */
    fun feed(
        yaw: Float, pitch: Float, roll: Float, dtSeconds: Float, axes: Int, pressed: Boolean, held: Boolean,
        gyro: GyroSettings? = null,
    ) {
        val d = display
        if (d.kind == GyroDisplayKind.GRAPH) {
            // Shown names: the turn through the screen (stored as roll) is yaw.
            if (dtSeconds > 0f) trace?.add(roll, pitch, yaw, dtSeconds, held)
            return
        }
        if (!d.kind.pose) return
        if (d.needsGravity != wasGravity || d.isTrackball != wasTrackball) {
            wasGravity = d.needsGravity
            wasTrackball = d.isTrackball
            pose.reset()
        }
        if (d.isTrackball) {
            if (dtSeconds == 0f || axes == 0) return
            fun k(a: MotionAxis) = if (axes and MappingEngine.gyroBit(a) == 0) 0f else trackballGain(d, gyro, a)
            pose.counterRotate(yaw * k(MotionAxis.YAW), pitch * k(MotionAxis.PITCH), roll * k(MotionAxis.ROLL), dtSeconds)
            return
        }
        val compass = d.needsCompass
        if (restOf !== d) {
            restOf = d
            pose.rest = d.restNow
        }
        // Turning the compass on takes the way the phone faces as heading 0.
        if (compass && !wasCompass) pose.aimHere()
        wasCompass = compass
        // With the compass the heading is the compass's: a ratchet press aims it again.
        if (pressed && compass) pose.aimHere()
        val onRatchet = d.ease && d.easeOnRatchet && !compass
        if (!onRatchet) easing = false
        else if (pressed) {
            easing = true
            calm = 0f
        }
        // Letting the ratchet go stops it at once: the phone's turn takes over, never fought.
        else if (!held) easing = false
        // While a ratchet is held it eases back until straight (the gyro is paused then).
        if (easing) easeAfterPress(d, dtSeconds)
        if (dtSeconds == 0f) return
        if (d.gravity) {
            pose.fuse(yaw, pitch, roll, dtSeconds, 0f, compass)
        } else if (axes != 0) {
            pose.rotate(
                if (axes and MappingEngine.gyroBit(MotionAxis.YAW) != 0) yaw else 0f,
                if (axes and MappingEngine.gyroBit(MotionAxis.PITCH) != 0) pitch else 0f,
                if (axes and MappingEngine.gyroBit(MotionAxis.ROLL) != 0) roll else 0f,
                dtSeconds,
            )
        }
        if (!d.ease || compass || d.easeOnRatchet) return
        val busy = sqrt(yaw * yaw + pitch * pitch + roll * roll) > STILL_DPS
        calm = if (busy) 0f else calm + dtSeconds
        if (busy || calm < d.easeDelayMs / 1000f) return
        easeBack(d, dtSeconds)
    }

    /** One step of easing back over [dt] seconds: the heading (always with gravity) or the whole pose. */
    private fun easeBack(d: GyroDisplay, dt: Float) {
        // By time: an exponential, 95% of the way back after easeSeconds.
        val amount = if (d.easeBySpeed) d.easeSpeed * DEG_TO_RAD * dt else 3f * dt / d.easeSeconds.coerceAtLeast(0.05f)
        if (d.gravity || d.easeHeadingOnly) pose.easeHeading(d.easeBySpeed, amount) else pose.easeAll(d.easeBySpeed, amount)
    }

    /** Easing after a ratchet press: after the delay, until it is (nearly) straight, then exactly straight. */
    private fun easeAfterPress(d: GyroDisplay, dt: Float) {
        calm += dt
        if (calm < d.easeDelayMs / 1000f) return
        easeBack(d, dt)
        val heading = d.gravity || d.easeHeadingOnly
        val h = if (heading) pose.headingFrom(pose.rest) else 0f
        val left = if (heading) abs(h) else pose.angle()
        if (left > STRAIGHT_RAD) return
        easing = false
        if (heading) pose.turnHeading(-h) else pose.straighten()
    }

    private val q = FloatArray(4)

    /**
     * The pose shown, as a row-major 3×3 matrix into [out] (UI thread): seen from the resting grip
     * ([GyroDisplay.rest], with gravity), so held that way it shows face-on and the phone's own yaw,
     * pitch and roll show as the same turns on screen; its turn times [GyroDisplay.motionScale]. As
     * the PC overlays show it.
     */
    fun shown(out: FloatArray) {
        pose.quaternion(q)
        val d = display
        // A trackball turns by Spin (or as far as the game's camera): no turn amount on top.
        shownMatrix(q, d.restNow, if (d.isTrackball) 1f else d.motionScale, out)
    }

    /**
     * Level without gravity: the pose starts again from straight (with gravity, Level saves the
     * tilt now as [GyroDisplay.rest], from [GyroPose.tiltFromUp]).
     */
    fun restart() = pose.straighten()

    /**
     * Mouse counts [dx], [dy] sent by anything but the live gyro ([MappingEngine.cameraSink]), on
     * the pump's thread. A trackball turns as the gyro turn that would send them would turn it:
     * about the gyro axis [routes] map to that mouse axis (roll for X, pitch for Y when none does),
     * at [gyro]'s sensitivity of that axis. The pose takes turns from both threads.
     */
    fun camera(dx: Int, dy: Int, routes: List<Mapping>, gyro: GyroSettings) {
        val d = display
        if (!d.isTrackball) return
        val deg = cameraDegrees(dx, dy, routes, gyro)
        pose.counterRotate(
            deg[0] * trackballGain(d, gyro, MotionAxis.YAW), deg[1] * trackballGain(d, gyro, MotionAxis.PITCH),
            deg[2] * trackballGain(d, gyro, MotionAxis.ROLL), 1f,
        )
    }

    /**
     * How far a trackball turns per degree the phone turns on [a]: with RWS as far as the game's
     * camera ([GyroSettings.rwsOf]; as the phone while the game's turn is unknown), else Spin times;
     * negative with [GyroDisplay.trackballInvert] (it turns as the camera does).
     */
    private fun trackballGain(d: GyroDisplay, gyro: GyroSettings?, a: MotionAxis): Float =
        (if (!d.trackballRws) d.trackballSpin else gyro?.let { it.rwsOf(it.sensitivityOf(a)) } ?: 1f) *
            (if (d.trackballInvert) -1f else 1f)

    /**
     * The compass ([GyroDisplay.needsCompass]) takes the way the phone faces now as heading 0 (the
     * app opened, edit mode closed, the workspace changed).
     */
    fun aimHere() = pose.aimHere()

    /** An accelerometer reading in the phone's frame (right, up, toward the player) and gravity [g] in its units. */
    fun up(x: Float, y: Float, z: Float, g: Float) {
        if (display.needsGravity) pose.setUp(x, y, z, g)
    }

    /** A magnetometer reading in the phone's frame, as [up]. */
    fun north(x: Float, y: Float, z: Float) {
        if (display.needsCompass) pose.setNorth(x, y, z)
    }

    companion object {
        /** [q] (w, x, y, z; changed) seen from [rest] and its turn scaled by [scale], as a matrix into [out]. */
        fun shownMatrix(q: FloatArray, rest: FloatArray?, scale: Float, out: FloatArray) {
            var w = q[0]
            var x = q[1]
            var y = q[2]
            var z = q[3]
            if (rest != null) {
                // conj(rest) * q
                val rw = rest[0]
                val rx = -rest[1]
                val ry = -rest[2]
                val rz = -rest[3]
                val nw = rw * w - rx * x - ry * y - rz * z
                val nx = rw * x + rx * w + ry * z - rz * y
                val ny = rw * y - rx * z + ry * w + rz * x
                val nz = rw * z + rx * y - ry * x + rz * w
                w = nw; x = nx; y = ny; z = nz
            }
            val k = scale.coerceIn(0.25f, 4f)
            if (abs(k - 1f) >= 0.001f) {
                if (w < 0f) {
                    w = -w; x = -x; y = -y; z = -z // the short way round
                }
                val cw = w.coerceIn(-1f, 1f)
                val sn = sqrt((1f - cw * cw).coerceAtLeast(0f))
                if (sn < 1e-5f) {
                    w = 1f; x = 0f; y = 0f; z = 0f
                } else {
                    val a = (2f * acos(cw) * k).coerceAtMost(PI_F) / 2f
                    val r = sin(a) / sn
                    w = cos(a); x *= r; y *= r; z *= r
                }
            }
            out[0] = 1 - 2 * (y * y + z * z); out[1] = 2 * (x * y - w * z); out[2] = 2 * (x * z + w * y)
            out[3] = 2 * (x * y + w * z); out[4] = 1 - 2 * (x * x + z * z); out[5] = 2 * (y * z - w * x)
            out[6] = 2 * (x * z - w * y); out[7] = 2 * (y * z + w * x); out[8] = 1 - 2 * (x * x + y * y)
        }

        private const val PI_F = Math.PI.toFloat()

        /**
         * The phone turn (degrees on yaw, pitch, roll) that would send mouse counts [dx], [dy]: about
         * the gyro axis [routes] map to that mouse axis (roll for X, pitch for Y when none does), at
         * [gyro]'s sensitivity of that axis.
         */
        fun cameraDegrees(dx: Int, dy: Int, routes: List<Mapping>, gyro: GyroSettings): FloatArray {
            val deg = FloatArray(3)
            fun add(n: Int, motion: MouseMotion, fallback: MotionAxis) {
                if (n == 0) return
                val m = routes.firstOrNull { it.source is InputSource.Gyro && (it.target as? OutputTarget.MouseAxis)?.motion == motion }
                val a = (m?.source as? InputSource.Gyro)?.axis ?: fallback
                val i = when (a) {
                    MotionAxis.YAW -> 0
                    MotionAxis.PITCH -> 1
                    MotionAxis.ROLL -> 2
                    else -> return
                }
                val perDeg = gyro.sensitivityOf(a) * ((m?.target as? OutputTarget.MouseAxis)?.scale ?: 1f)
                if (perDeg != 0f) deg[i] += n / perDeg
            }
            add(dx, MouseMotion.MOVE_X, MotionAxis.ROLL)
            // The gyro's Y is up +, the counts' down +.
            add(-dy, MouseMotion.MOVE_Y, MotionAxis.PITCH)
            return deg
        }

        /** How fast the world pose's heading eases back to facing the player. */
        const val HEADING_EASE_S = 0.5f

        /** Slower than this (deg/s, all axes) counts as the phone being still. */
        const val STILL_DPS = 5f

        /** Eased this close (radians), it is straight. */
        private const val STRAIGHT_RAD = 0.2f * (Math.PI / 180).toFloat()

        private const val DEG_TO_RAD = (Math.PI / 180).toFloat()
    }
}
