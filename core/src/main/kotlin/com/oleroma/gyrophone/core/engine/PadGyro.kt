package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.MotionAxis

/**
 * The PC's DualShock 4 gyro and accelerometer from the phone's motion, by the workspace's pad axes
 * ([GyroSettings.padYaw], [GyroSettings.padPitch], [GyroSettings.padRoll] and their Invert).
 *
 * The phone's axes come as [GyroMouse.motion] gives them (stored [MotionAxis] names: yaw around the
 * screen's vertical axis, pitch around its horizontal one, roll through the screen). Each is first
 * turned into the pad's own sense as if the screen were the pad's face, so the default mapping is
 * "the phone is the pad": pad pitch = pitch, pad yaw = through the screen, pad roll = along it.
 * The result is the DualShock 4's frame: x right (pitch), y out of the face (yaw), z toward the
 * player (roll), turns by the right-hand rule.
 */
object PadGyro {
    /** Pad pitch, yaw and roll (DualShock 4 x, y, z) into [out] from the phone's [yaw], [pitch], [roll] (any unit). */
    fun map(g: GyroSettings, yaw: Float, pitch: Float, roll: Float, out: FloatArray) {
        out[0] = axis(g.padPitch, g.padPitchInvert, yaw, pitch, roll)
        out[1] = axis(g.padYaw, g.padYawInvert, yaw, pitch, roll)
        out[2] = axis(g.padRoll, g.padRollInvert, yaw, pitch, roll)
    }

    private fun axis(src: MotionAxis, invert: Boolean, yaw: Float, pitch: Float, roll: Float): Float {
        // The phone's frame (right, screen-up, out of the screen) is (pitch, -yaw, -roll); the pad's
        // face-frame is (right, out of the screen, toward the player = -screen-up).
        val v = when (src) {
            MotionAxis.PITCH -> pitch
            MotionAxis.ROLL -> -roll
            MotionAxis.YAW -> yaw
            MotionAxis.NONE -> 0f
        }
        return if (invert) -v else v
    }
}
