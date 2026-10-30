package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.MotionAxis
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

    /** It was in gravity mode at the last sample: switching starts it straight. */
    private var wasGravity = display.gravity

    /**
     * One sample: rates in deg/s ([GyroMouse.motion]) over [dtSeconds]; [axes] the gyro axes that
     * move the mouse ([MappingEngine.gyroAxes]); a ratchet was just [pressed]; one is [held].
     */
    fun feed(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float, axes: Int, pressed: Boolean, held: Boolean) {
        val d = display
        if (d.kind == GyroDisplayKind.GRAPH) {
            if (dtSeconds > 0f) trace?.add(yaw, pitch, roll, dtSeconds, held)
            return
        }
        if (!d.kind.pose) return
        if (d.gravity != wasGravity) {
            wasGravity = d.gravity
            pose.reset()
        }
        val compass = d.needsCompass
        if (pressed && d.resetOnRatchet) {
            // With gravity the tilt is the world's: only the heading can go back.
            if (d.gravity || d.resetHeadingOnly) {
                if (!compass) pose.turnHeading(-pose.heading())
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
        if (calm < d.easeDelayMs / 1000f) return
        // By time: an exponential, 95% of the way back after easeSeconds.
        val amount = if (d.easeBySpeed) d.easeSpeed * DEG_TO_RAD * dtSeconds else 3f * dtSeconds / d.easeSeconds.coerceAtLeast(0.05f)
        if (d.gravity) pose.easeHeading(d.easeBySpeed, amount) else pose.easeAll(d.easeBySpeed, amount)
    }

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
