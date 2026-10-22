package com.oleroma.gyrophone.core.model

/**
 * The corner controls every [Profile] has ([Profile.corners], kind [TouchKind.FULLSCREEN]):
 * tap [PREV] = previous workspace, tap [NEXT] = next workspace, both together = fullscreen pad,
 * tap [GYRO] = the current workspace's gyro between always on and always off (hidden with
 * [Profile.gyroCorner]). They send nothing to the PC, are shared by all workspaces and can be
 * moved and resized.
 */
object NavCorners {
    const val PREV = "nav_prev"
    const val NEXT = "nav_next"
    const val GYRO = "nav_gyro"
    const val SIZE = 0.08f
    const val PREV_LABEL = "←"
    const val NEXT_LABEL = "→"
    const val GYRO_LABEL = "G"

    /** Top left and top right, the gyro corner left of →, in both orientations. */
    fun defaults(): List<TouchControl> = listOf(
        corner(PREV, PREV_LABEL, 0.03f, 0.06f, Placement(0.05f, 0.03f, SIZE), SIZE),
        corner(NEXT, NEXT_LABEL, 0.97f, 0.06f, Placement(0.95f, 0.03f, SIZE), SIZE),
        gyroDefault(),
    )

    fun gyroDefault(): TouchControl = corner(GYRO, GYRO_LABEL, 0.90f, 0.06f, Placement(0.83f, 0.03f, SIZE), SIZE)

    /**
     * [corners] with ← and → (else the defaults) and the gyro corner (added at its default place,
     * sized like the others, when a profile saved before it lacks it). Anything else is dropped.
     */
    fun normalize(corners: List<TouchControl>): List<TouchControl> {
        val prev = corners.firstOrNull { it.id == PREV }
        val next = corners.firstOrNull { it.id == NEXT }
        if (prev == null || next == null) return defaults()
        val gyro = corners.firstOrNull { it.id == GYRO } ?: gyroDefault().let { g ->
            g.copy(size = prev.size, portrait = g.portrait?.copy(size = prev.placeSize(true)))
        }
        val out = listOf(prev, next, gyro)
        return if (out == corners) corners else out
    }

    /**
     * Turns the fullscreen corners of a profile saved before workspaces (any two FULLSCREEN
     * controls) into ← / →, the left one (landscape) becoming ←, plus the gyro corner. Fewer than
     * two: the defaults.
     */
    fun fromLegacy(old: List<TouchControl>): List<TouchControl> {
        val two = old.filter { it.kind == TouchKind.FULLSCREEN }
        if (two.size < 2) return defaults()
        val (left, right) = two.take(2).sortedBy { it.x }
        return normalize(listOf(
            left.copy(id = PREV, label = PREV_LABEL),
            right.copy(id = NEXT, label = NEXT_LABEL),
        ))
    }

    private fun corner(id: String, label: String, x: Float, y: Float, portrait: Placement, size: Float) =
        TouchControl(id, TouchKind.FULLSCREEN, label, x, y, size, TouchShape.CIRCLE, portrait)
}
