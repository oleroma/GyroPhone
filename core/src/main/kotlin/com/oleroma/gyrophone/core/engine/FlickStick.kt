package com.oleroma.gyrophone.core.engine

import kotlin.math.atan2

/**
 * Flick stick: a stick that turns the game camera by the way it points. Pushed out to the edge,
 * the camera turns at once to face where it points (up = straight ahead, right = 90° right,
 * down = behind); turning the stick round while it stays out turns the camera with it, degree
 * for degree. Let go, nothing moves. The gyro keeps the fine aim.
 *
 * Pure state, no clock: [update] gives the degrees to turn for each new stick position, so the
 * turn goes out with the same event that moved the stick (no smoothing, no wait).
 */
class FlickStick {
    private var engaged = false
    private var lastAngle = 0f
    private var restCounts = 0f

    /** Whether the stick is out past the edge now (a flick done, rotating). */
    val out: Boolean get() = engaged

    /**
     * The stick at [x], [y] (each -1..1, Y + down as the HID pad has it), pushed [push] (0..1
     * past its deadzone): the degrees to turn the camera now (+ right). A flick starts at
     * [ENGAGE] and ends under [RELEASE], so a stick resting at the edge doesn't flick again.
     */
    fun update(x: Float, y: Float, push: Float): Float {
        if (!engaged) {
            if (push < ENGAGE) return 0f
            engaged = true
            lastAngle = angleOf(x, y)
            return lastAngle
        }
        if (push < RELEASE) {
            engaged = false
            return 0f
        }
        val a = angleOf(x, y)
        var d = a - lastAngle
        if (d > 180f) d -= 360f else if (d < -180f) d += 360f
        lastAngle = a
        return d
    }

    /** [degrees] as whole mouse counts at [countsPerDegree], the part of a count left over kept for the next turn. */
    fun counts(degrees: Float, countsPerDegree: Float): Int {
        if (degrees == 0f) return 0
        val total = restCounts + degrees * countsPerDegree
        val whole = total.toInt()
        restCounts = total - whole
        return whole
    }

    fun reset() {
        engaged = false
        restCounts = 0f
    }

    companion object {
        /** How far out (0..1 past the deadzone) a flick starts. */
        const val ENGAGE = 0.9f

        /** Under this a flick ends: the stick came back in. */
        const val RELEASE = 0.7f

        /** Degrees from straight up, clockwise (+ right), -180..180. */
        fun angleOf(x: Float, y: Float): Float = Math.toDegrees(atan2(x.toDouble(), (-y).toDouble())).toFloat()
    }
}
