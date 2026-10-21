package com.oleroma.gyrophone.core.model

/**
 * The two corner controls every [Profile] has ([Profile.corners], kind [TouchKind.FULLSCREEN]):
 * tap [PREV] = previous workspace, tap [NEXT] = next workspace, both together = fullscreen pad.
 * They send nothing to the PC, are shared by all workspaces and can be moved and resized.
 */
object NavCorners {
    const val PREV = "nav_prev"
    const val NEXT = "nav_next"
    const val SIZE = 0.08f
    const val PREV_LABEL = "←"
    const val NEXT_LABEL = "→"

    /** Top left and top right, in both orientations. */
    fun defaults(): List<TouchControl> = listOf(
        corner(PREV, PREV_LABEL, 0.03f, 0.06f, Placement(0.05f, 0.03f, SIZE), SIZE),
        corner(NEXT, NEXT_LABEL, 0.97f, 0.06f, Placement(0.95f, 0.03f, SIZE), SIZE),
    )

    /**
     * Turns the fullscreen corners of a profile saved before workspaces (any two FULLSCREEN
     * controls) into ← / →, the left one (landscape) becoming ←. Fewer than two: the defaults.
     */
    fun fromLegacy(old: List<TouchControl>): List<TouchControl> {
        val two = old.filter { it.kind == TouchKind.FULLSCREEN }
        if (two.size < 2) return defaults()
        val (left, right) = two.take(2).sortedBy { it.x }
        return listOf(
            left.copy(id = PREV, label = PREV_LABEL),
            right.copy(id = NEXT, label = NEXT_LABEL),
        )
    }

    private fun corner(id: String, label: String, x: Float, y: Float, portrait: Placement, size: Float) =
        TouchControl(id, TouchKind.FULLSCREEN, label, x, y, size, TouchShape.CIRCLE, portrait)
}
