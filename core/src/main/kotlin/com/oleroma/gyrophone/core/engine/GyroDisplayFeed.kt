package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.MotionAxis

/**
 * The data one gyro display ([com.oleroma.gyrophone.core.model.TouchKind.DISPLAY], control [id])
 * draws, gathered from gyro samples ([feed], the gyro thread) and accelerometer readings ([up]);
 * the UI reads it once per frame. Only what its [display] kind needs is kept up to date.
 */
class GyroDisplayFeed(val id: String, display: GyroDisplay) {
    /** Replaced from the UI when its settings change; read once per sample. */
    @Volatile var display: GyroDisplay = display

    /** The phone in gyro mode: turned since the last reset. */
    val pose = GyroPose()

    /** The pose against the world ([GyroPose.fuse]): the phone in gravity mode, the horizon. */
    val world = GyroPose()

    /** The graph's speeds (GRAPH only). */
    val trace: GyroTrace? = if (display.kind == GyroDisplayKind.GRAPH) GyroTrace() else null

    /** The heading tape's degrees (right +), gathered from yaw. */
    @Volatile var heading = 0f
        private set

    /**
     * One sample: rates in deg/s ([GyroMouse.motion]) over [dtSeconds]; [axes] the gyro axes that
     * move the mouse ([MappingEngine.gyroAxes]); a ratchet was just [pressed]; one is [held].
     */
    fun feed(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float, axes: Int, pressed: Boolean, held: Boolean) {
        val d = display
        if (pressed && d.resetOnRatchet) {
            pose.reset()
            heading = 0f
        }
        if (dtSeconds == 0f) return
        when (d.kind) {
            GyroDisplayKind.PHONE -> if (d.gravity) {
                world.fuse(yaw, pitch, roll, dtSeconds, HEADING_EASE_S)
            } else if (axes != 0) {
                pose.rotate(
                    if (axes and MappingEngine.gyroBit(MotionAxis.YAW) != 0) yaw else 0f,
                    if (axes and MappingEngine.gyroBit(MotionAxis.PITCH) != 0) pitch else 0f,
                    if (axes and MappingEngine.gyroBit(MotionAxis.ROLL) != 0) roll else 0f,
                    dtSeconds,
                )
            }

            GyroDisplayKind.HORIZON -> {
                world.fuse(yaw, pitch, roll, dtSeconds, HEADING_EASE_S)
                heading = ((heading + yaw * dtSeconds) % 360f + 360f) % 360f
            }

            GyroDisplayKind.GRAPH -> trace?.add(yaw, pitch, roll, dtSeconds, held)
            GyroDisplayKind.RATCHET_RING -> Unit
        }
    }

    /** An accelerometer reading in the phone's frame (right, up, toward the player) and gravity [g] in its units. */
    fun up(x: Float, y: Float, z: Float, g: Float) {
        if (display.needsGravity) world.setUp(x, y, z, g)
    }

    companion object {
        /** How fast the world pose's heading eases back to facing the player. */
        const val HEADING_EASE_S = 0.5f
    }
}
