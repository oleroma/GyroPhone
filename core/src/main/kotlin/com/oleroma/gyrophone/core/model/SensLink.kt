package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/**
 * A sensitivity that follows another one instead of holding its own value: [scale] times the
 * sensitivity [from] names, at the same level (a sibling) or a named one of the workspace.
 *
 * - [SHARED]: the level's shared Sensitivity (the gyro's every-axis one, the touchpad's).
 * - A gyro axis's name ([MotionAxis.YAW], [MotionAxis.PITCH], [MotionAxis.ROLL]): that axis's
 *   sensitivity in effect at the same level (for a gyro axis only).
 * - "X" / "Y": the same finger count's Move X / Move Y (for a Move only).
 * - "n1".."n4": the workspace's named sensitivity (Gyro sens N for the gyro, Touch sens N for the
 *   touchpad), set by a slider ([OutputTarget.GyroSens.named], [OutputTarget.TouchSens.named]);
 *   until one sets it, it is the level's shared Sensitivity.
 *
 * It is worked out into a plain number whenever something it reads changes (never per motion
 * sample), always from its source, so a chain never compounds.
 */
@Serializable
data class SensLink(val from: String, val scale: Float = 1f) {
    /** The named sensitivity it reads (1..[NAMED]), or 0 for none. */
    val named: Int get() = namedOf(from)

    /** The gyro axis it reads, or null. */
    val axis: MotionAxis? get() = MotionAxis.entries.firstOrNull { it != MotionAxis.NONE && it.name == from }

    /** What it reads, for showing: "Yaw", "Sensitivity", "Move Y", "Gyro sens 1" ([touch]: a Move's or touchpad's). */
    fun source(touch: Boolean): String = when {
        named > 0 -> (if (touch) "Touch sens " else "Gyro sens ") + named
        axis != null -> axis!!.label
        from == X -> "Move X"
        from == Y -> "Move Y"
        else -> "Sensitivity"
    }

    /** It, for showing: "Yaw ×0.5" (a scale of 1 left out: "Gyro sens 1"). */
    fun label(touch: Boolean): String = source(touch) + if (scale == 1f) "" else " ×" + fmt(scale)

    companion object {
        /** [f] with at most two decimals, trailing zeros dropped. */
        fun fmt(f: Float): String = "%.2f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.')

        const val SHARED = "sens"
        const val X = "X"
        const val Y = "Y"

        /** How many named sensitivities each workspace has, of each kind. */
        const val NAMED = 4

        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 4f

        fun named(n: Int): String = "n$n"

        fun namedOf(from: String): Int =
            if (from.length >= 2 && from[0] == 'n') from.substring(1).toIntOrNull()?.takeIf { it in 1..NAMED } ?: 0 else 0

        fun axis(a: MotionAxis): String = a.name

        /** A factor saved by the old Scale parent switch: read as that share of the shared Sensitivity. */
        fun legacy(scale: Float?): SensLink? = scale?.let { SensLink(SHARED, it) }

        /** Nothing named is set: every named sensitivity is its level's shared one. */
        val NO_NAMED: (Int) -> Float? = { null }
    }
}
