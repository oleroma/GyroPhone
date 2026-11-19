package com.oleroma.gyrophone.core.engine

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * A gyro sample measured in a gyro space ([GyroSpace]): the phone's own yaw, pitch and roll
 * (deg/s, stored [com.oleroma.gyrophone.core.model.MotionAxis] names, turned for the screen and
 * the base orientation, as [GyroMouse.motion] gives them) against the world's up in the phone's
 * frame (right, up, toward the player, unit length).
 *
 * The phone runs it in [GyroMouse.inSpace]; in PC gyro mode the companion runs the same (its
 * GyroSpaces.cs) with the up the phone sends with each sample. Float math in the same order on
 * both sides; the shared vectors (gyro-math-v1.json, "spaces") check it.
 */
object GyroSpaces {
    /** Space ordinals as the wire carries them. */
    const val LOCAL = 0
    const val WORLD = 1
    const val PLAYER = 2

    /**
     * World space: within this much of vertical (the length of the right axis laid flat), the
     * phone's own pitch and roll; from there to [FLAT_FULL] blended into the world's, so they
     * never jump as the phone's right edge passes straight up or down.
     */
    const val FLAT_MIN = 0.1f
    const val FLAT_FULL = 0.35f

    /** Player space's yaw easing (JoyShockMapper's relax factor). */
    const val PLAYER_RELAX = 1.41f

    /**
     * [yaw], [pitch], [roll] measured in [space] (a [GyroSpace] ordinal) against up ([ux], [uy],
     * [uz]) into [out] (yaw, pitch, roll). Local, or no up (all 0), leaves them as they are.
     */
    fun inSpace(yaw: Float, pitch: Float, roll: Float, space: Int, ux: Float, uy: Float, uz: Float, out: FloatArray) {
        out[0] = yaw; out[1] = pitch; out[2] = roll
        if (space == LOCAL || (ux == 0f && uy == 0f && uz == 0f)) return
        // The turn as a vector in the phone's frame (right, up, toward the player).
        val wr = pitch; val wu = -yaw; val wt = -roll
        if (space == WORLD) {
            // Shown yaw (stored as roll): the turn around gravity.
            out[2] = -(wr * ux + wu * uy + wt * uz)
            // The right axis laid flat; near straight up or down (phone on its side) the phone's own pitch and roll.
            var rx = 1f - ux * ux; var ry = -ux * uy; var rz = -ux * uz
            val n = sqrt(rx * rx + ry * ry + rz * rz)
            val b = ((n - FLAT_MIN) / (FLAT_FULL - FLAT_MIN)).coerceIn(0f, 1f)
            if (b <= 0f) return
            rx /= n; ry /= n; rz /= n
            // The flat axis along the screen: right × up. Turning about it raises the right edge
            // (shown roll, stored as yaw); lying flat that is the phone's own.
            val tx = ry * uz - rz * uy; val ty = rz * ux - rx * uz; val tz = rx * uy - ry * ux
            val wy = wr * tx + wu * ty + wt * tz
            val wp = wr * rx + wu * ry + wt * rz
            out[0] = yaw + (wy - yaw) * b
            out[1] = pitch + (wp - pitch) * b
        } else if (space == PLAYER) {
            // JoyShockMapper's player space: shown yaw (stored as roll) from the turns about the
            // screen's vertical axis and through the screen, by how gravity lies in their plane,
            // eased (×1.41) but never faster than both together. Pitch and roll stay the phone's.
            val world = -(wu * uy + wt * uz)
            val most = sqrt(wu * wu + wt * wt)
            out[2] = sign(world) * min(abs(world) * PLAYER_RELAX, most)
        }
    }
}
