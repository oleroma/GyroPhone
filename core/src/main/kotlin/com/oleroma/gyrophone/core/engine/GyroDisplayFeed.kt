package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TurnsWith
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

    /** The laser pointer's trail and paint (LASER only), kept and drawn by the UI. */
    val laser: LaserTrace? = if (display.kind == GyroDisplayKind.LASER) LaserTrace() else null

    /** A ratchet was held at the last sample (the laser pointer dims its beam and stops trailing). */
    @Volatile var ratchetHeld = false
        private set

    /**
     * Which gyro axis moves the mouse sideways and which up and down, and which way ([cameraMapOf]):
     * a display turning with the camera turns as the game's camera, by those. Set by the runtime.
     */
    @Volatile var cameraMap: Int = DEFAULT_CAMERA_MAP

    /** Seconds since the phone stopped turning, or since the ratchet press while easing on it. */
    private var calm = 0f

    /** Easing back after a ratchet press ([GyroDisplay.easeOnRatchet]), until straight or let go. */
    private var easing = false

    /** [GyroDisplay.restNow] of [restOf], kept so a sample does not make it again. */
    private var restOf: GyroDisplay? = null

    /** Its mode at the last sample (gravity, what turns it): switching starts it straight. */
    private var wasGravity = display.needsGravity
    private var wasTurns = display.turnsWith
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
        ratchetHeld = held
        if (d.kind == GyroDisplayKind.GRAPH) {
            // Shown names: the turn through the screen (stored as roll) is yaw.
            if (dtSeconds > 0f) trace?.add(roll, pitch, yaw, dtSeconds, held)
            return
        }
        if (!d.kind.pose) return
        if (d.needsGravity != wasGravity || d.turnsWith != wasTurns) {
            wasGravity = d.needsGravity
            wasTurns = d.turnsWith
            pose.reset()
        }
        // The camera's turn: the live gyro axes' share (other camera moves come through [camera]).
        fun cameraTurn() {
            if (dtSeconds == 0f || axes == 0) return
            if (d.turnsWith == TurnsWith.CAMERA) {
                // As the game's camera: the axis on mouse X turns it right, the one on mouse Y up; no roll.
                val rates = floatArrayOf(yaw, pitch, roll)
                fun deg(code: Int): Float {
                    val a = code and 0x7F
                    if (a == 0) return 0f
                    val axis = CAMERA_AXES[a - 1]
                    if (axes and MappingEngine.gyroBit(axis) == 0) return 0f
                    return rates[a - 1] * dtSeconds * cameraSpeed(d, gyro, axis) * (if (code and 0x80 != 0) -1f else 1f)
                }
                val map = cameraMap
                pose.cameraTurn(deg(map and 0xFF), deg(map shr 8 and 0xFF))
                return
            }
            fun k(a: MotionAxis) = if (axes and MappingEngine.gyroBit(a) == 0) 0f else cameraSpeed(d, gyro, a)
            trackballTurn(d, yaw * k(MotionAxis.YAW), pitch * k(MotionAxis.PITCH), roll * k(MotionAxis.ROLL), dtSeconds)
        }
        // A trackball never eases back: every roll is a camera move.
        if (d.isTrackball) return cameraTurn()
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
        if (d.followsCamera) {
            cameraTurn()
        } else if (d.needsGravity) {
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
        if (d.needsGravity || d.easeHeadingOnly) pose.easeHeading(d.easeBySpeed, amount) else pose.easeAll(d.easeBySpeed, amount)
    }

    /** Easing after a ratchet press: after the delay, until it is (nearly) straight, then exactly straight. */
    private fun easeAfterPress(d: GyroDisplay, dt: Float) {
        calm += dt
        if (calm < d.easeDelayMs / 1000f) return
        easeBack(d, dt)
        val heading = d.needsGravity || d.easeHeadingOnly
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
        // Following the camera it turns by Spin (or as far as the game's camera): no turn amount on top.
        shownMatrix(q, d.restNow, if (d.followsCamera) 1f else d.motionScale, out)
    }

    /**
     * Level without gravity: the pose starts again from straight (with gravity, Level saves the
     * tilt now as [GyroDisplay.rest], from [GyroPose.tiltFromUp]).
     */
    fun restart() = pose.straighten()

    /**
     * Mouse counts [dx], [dy] sent by anything but the live gyro ([MappingEngine.cameraSink]), on
     * the pump's thread. One that follows the camera ([GyroDisplay.followsCamera]) turns as the gyro turn that would send them would turn it:
     * about the gyro axis [routes] map to that mouse axis (roll for X, pitch for Y when none does),
     * at [gyro]'s sensitivity of that axis. The pose takes turns from both threads.
     */
    fun camera(dx: Int, dy: Int, routes: List<Mapping>, gyro: GyroSettings) {
        val d = display
        if (!d.followsCamera) return
        val deg = cameraDegrees(dx, dy, routes, gyro)
        if (d.turnsWith == TurnsWith.CAMERA) {
            // As the game's camera: sideways counts turn it right, up and down ones up; no roll.
            fun turn(code: Int): Float {
                val a = code and 0x7F
                if (a == 0) return 0f
                return deg[a - 1] * cameraSpeed(d, gyro, CAMERA_AXES[a - 1]) * (if (code and 0x80 != 0) -1f else 1f)
            }
            val map = cameraMapOf(routes, gyro)
            cameraMap = map
            pose.cameraTurn(turn(map and 0xFF), turn(map shr 8 and 0xFF))
            return
        }
        trackballTurn(
            d, deg[0] * cameraSpeed(d, gyro, MotionAxis.YAW), deg[1] * cameraSpeed(d, gyro, MotionAxis.PITCH),
            deg[2] * cameraSpeed(d, gyro, MotionAxis.ROLL), 1f,
        )
    }

    /**
     * A trackball's turn by gyro turns [yaw], [pitch], [roll] (deg/s over [dt], or degrees with
     * [dt] 1): about each of its fixed axes by the turn [GyroDisplay.trackballAxes] picks for it.
     */
    private fun trackballTurn(d: GyroDisplay, yaw: Float, pitch: Float, roll: Float, dt: Float) {
        val m = d.trackballAxes
        fun about(i: Int): Float {
            val code = m shr (8 * i) and 0xFF
            val v = when (code and 0x7F) { 1 -> yaw; 2 -> pitch; 3 -> roll; else -> 0f }
            return if (code and 0x80 != 0) -v else v
        }
        // As the phone turns, about the view's fixed axes (the opposite of holding still against the world).
        pose.counterRotate(-about(0), -about(1), -about(2), dt)
    }

    /** How far it turns per degree the phone turns on [a]: as far as the game's camera (RWS), or Spin times. */
    private fun cameraSpeed(d: GyroDisplay, gyro: GyroSettings?, a: MotionAxis): Float =
        if (!d.trackballRws) d.trackballSpin else gyro?.let { it.rwsOf(it.sensitivityOf(a)) } ?: 1f

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

        private val CAMERA_AXES = arrayOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL)

        /** No routes known: roll moves the mouse sideways, pitch up and down (as [cameraDegrees] falls back to). */
        const val DEFAULT_CAMERA_MAP = 3 or (2 shl 8)

        /**
         * Which gyro axis [routes] put on mouse X (low byte) and on mouse Y (next byte), each 1 yaw,
         * 2 pitch, 3 roll, + 0x80 when it moves the mouse the other way (a negative scale or
         * sensitivity): the way the camera turns for that axis's turn. Roll for X and pitch for Y when
         * none does. Also in OVERLAY (bytes 149, 150) for the PC's overlays.
         */
        fun cameraMapOf(routes: List<Mapping>, gyro: GyroSettings): Int {
            fun code(motion: MouseMotion, fallback: MotionAxis): Int {
                val m = routes.firstOrNull { it.source is InputSource.Gyro && (it.target as? OutputTarget.MouseAxis)?.motion == motion }
                val a = (m?.source as? InputSource.Gyro)?.axis ?: fallback
                val i = when (a) {
                    MotionAxis.YAW -> 1
                    MotionAxis.PITCH -> 2
                    MotionAxis.ROLL -> 3
                    else -> return 0
                }
                val perDeg = gyro.sensitivityOf(a) * ((m?.target as? OutputTarget.MouseAxis)?.scale ?: 1f)
                return i or (if (perDeg < 0f) 0x80 else 0)
            }
            return code(MouseMotion.MOVE_X, MotionAxis.ROLL) or (code(MouseMotion.MOVE_Y, MotionAxis.PITCH) shl 8)
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
