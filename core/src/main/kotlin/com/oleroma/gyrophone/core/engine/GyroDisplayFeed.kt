package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
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

    /** Seconds since the phone stopped turning or the ratchet was let go ([GyroDisplay.easeAfterRatchet]). */
    private var calm = 0f

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
        // Turning the compass on takes the way the phone faces as heading 0.
        if (compass && !wasCompass) pose.aimHere()
        wasCompass = compass
        if (pressed && d.resetOnRatchet) {
            // With gravity the tilt is the world's: only the heading can go back.
            if (d.gravity || d.resetHeadingOnly) {
                if (compass) pose.aimHere() else pose.turnHeading(-pose.heading())
            } else pose.reset()
        }
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
        if (!d.ease || compass) return
        val busy = if (d.easeAfterRatchet) held else sqrt(yaw * yaw + pitch * pitch + roll * roll) > STILL_DPS
        calm = if (busy) 0f else calm + dtSeconds
        if (busy || calm < d.easeDelayMs / 1000f) return
        // By time: an exponential, 95% of the way back after easeSeconds.
        val amount = if (d.easeBySpeed) d.easeSpeed * DEG_TO_RAD * dtSeconds else 3f * dtSeconds / d.easeSeconds.coerceAtLeast(0.05f)
        if (d.gravity) pose.easeHeading(d.easeBySpeed, amount) else pose.easeAll(d.easeBySpeed, amount)
    }

    /**
     * Mouse counts [dx], [dy] sent by anything but the live gyro ([MappingEngine.cameraSink]), on
     * the pump's thread. A trackball turns as the gyro turn that would send them would turn it:
     * about the gyro axis [routes] map to that mouse axis (roll for X, pitch for Y when none does),
     * at [gyro]'s sensitivity of that axis. The pose takes turns from both threads.
     */
    fun camera(dx: Int, dy: Int, routes: List<Mapping>, gyro: GyroSettings) {
        val d = display
        if (!d.isTrackball) return
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
            // The phone's turn that sends n counts, then as far as that turn spins the trackball.
            val perDeg = gyro.sensitivityOf(a) * ((m?.target as? OutputTarget.MouseAxis)?.scale ?: 1f)
            if (perDeg != 0f) deg[i] += n / perDeg * trackballGain(d, gyro, a)
        }
        add(dx, MouseMotion.MOVE_X, MotionAxis.ROLL)
        // The gyro's Y is up +, the counts' down +.
        add(-dy, MouseMotion.MOVE_Y, MotionAxis.PITCH)
        pose.counterRotate(deg[0], deg[1], deg[2], 1f)
    }

    /**
     * How far a trackball turns per degree the phone turns on [a]: with RWS as far as the game's
     * camera ([GyroSettings.rwsOf]; as the phone while the game's turn is unknown), else Spin times.
     */
    private fun trackballGain(d: GyroDisplay, gyro: GyroSettings?, a: MotionAxis): Float =
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
        /** How fast the world pose's heading eases back to facing the player. */
        const val HEADING_EASE_S = 0.5f

        /** Slower than this (deg/s, all axes) counts as the phone being still. */
        const val STILL_DPS = 5f

        private const val DEG_TO_RAD = (Math.PI / 180).toFloat()
    }
}
