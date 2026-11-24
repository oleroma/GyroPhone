package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/**
 * A curve frame ([TouchKind.CURVE]): a rectangle on the pad that holds its own gyro acceleration
 * [curve] and lays it over the workspace's acceleration for as long as the frame is in the
 * workspace. The settings' curve (its parent) never changes; deleting the frame removes the
 * override. A held button that sets its own acceleration still wins while held; one that follows
 * the workspace gets the frame's ([CurveFrames.over]).
 *
 * [axis]: [MotionAxis.NONE] = every axis (it replaces the axes' own curves too, as an all-axes
 * sensitivity slider replaces their own sensitivities), else that axis only. The frame's graph
 * spans 0..[maxSpeed] deg/s across and 1×..[maxGain] up; its points stay inside that range.
 * [edited]: when its curve was last changed (ms since 1970): where two frames set the same axis,
 * the one changed last wins.
 */
@Serializable
data class CurveFrame(
    val curve: GyroCurve = ramp(DEFAULT_MAX_SPEED, DEFAULT_MAX_GAIN),
    val axis: MotionAxis = MotionAxis.NONE,
    val maxSpeed: Float = DEFAULT_MAX_SPEED,
    val maxGain: Float = DEFAULT_MAX_GAIN,
    val edited: Long = 0L,
) {
    /** [curve] kept inside the frame's range (each point's speed and gain clamped), tidied. */
    fun inRange(c: GyroCurve = curve): GyroCurve = c.copy(
        points = c.points.map { CurvePoint(it.speed.coerceIn(0f, speedCap), it.gain.coerceIn(GyroCurve.MIN_GAIN, gainCap)) },
        range = speedCap,
    ).tidy()

    /** The range, within what a curve can hold. */
    val speedCap: Float get() = maxSpeed.coerceIn(MIN_SPEED, GyroCurve.MAX_SPEED)
    val gainCap: Float get() = maxGain.coerceIn(MIN_GAIN_TOP, GyroCurve.MAX_GAIN)

    /** With a new range: the curve pulled inside it. */
    fun withRange(speed: Float, gain: Float): CurveFrame {
        val f = copy(maxSpeed = speed, maxGain = gain)
        return f.copy(curve = f.inRange())
    }

    companion object {
        const val DEFAULT_MAX_SPEED = 300f
        const val DEFAULT_MAX_GAIN = 3f
        const val MIN_SPEED = 50f
        const val MIN_GAIN_TOP = 1.25f

        /** A new frame's curve: two points, 1× near the left and halfway up near the right. */
        fun ramp(maxSpeed: Float, maxGain: Float): GyroCurve = GyroCurve(
            listOf(CurvePoint(maxSpeed * 0.1f, 1f), CurvePoint(maxSpeed * 0.9f, 1f + (maxGain - 1f) / 2f)),
            range = maxSpeed,
        ).tidy()
    }
}

/** Curve frames laid over gyro settings. */
object CurveFrames {
    /** [w]'s curve frames, the one changed last at the end (it wins). */
    fun of(w: Workspace): List<TouchControl> {
        if (w.touchControls.none { it.kind == TouchKind.CURVE }) return emptyList()
        return w.touchControls.filter { it.kind == TouchKind.CURVE && it.curveFrame != null }.sortedBy { it.curveFrame!!.edited }
    }

    /**
     * [g] with [frames]' curves over it, in order (the last wins an axis). [live]: a curve being
     * dragged now by its frame id, in place of its saved one; a frame with a live curve counts as
     * the one changed last.
     */
    fun over(g: GyroSettings, frames: List<TouchControl>, live: Map<String, GyroCurve> = emptyMap()): GyroSettings {
        if (frames.isEmpty()) return g
        var r = g
        val order = if (live.isEmpty()) frames else frames.filter { it.id !in live } + frames.filter { it.id in live }
        for (c in order) {
            val f = c.curveFrame ?: continue
            val curve = live[c.id] ?: f.curve
            r = if (f.axis == MotionAxis.NONE) {
                r.copy(accel = curve, yawAccel = null, pitchAccel = null, rollAccel = null)
            } else {
                r.withAccel(f.axis, curve)
            }
        }
        return r
    }
}
