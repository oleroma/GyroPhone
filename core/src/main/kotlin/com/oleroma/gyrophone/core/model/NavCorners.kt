package com.oleroma.gyrophone.core.model

/**
 * The corner controls every [Profile] has ([Profile.corners], kind [TouchKind.FULLSCREEN]):
 * tap [PREV] = previous workspace, tap [NEXT] = next workspace, both together = fullscreen pad.
 * They send nothing to the PC, are shared by all workspaces and can be moved and resized. The old
 * gyro corner ([GYRO]) became a Toggle gyro touch button in each workspace ([gyroCornerToButtons]).
 */
object NavCorners {
    const val PREV = "nav_prev"
    const val NEXT = "nav_next"
    const val GYRO = "nav_gyro"
    const val SIZE = 0.08f
    const val PREV_LABEL = "←"
    const val NEXT_LABEL = "→"
    /** Id and label of the touch button that replaces the gyro corner. */
    const val GYRO_BUTTON = "gyro_toggle"
    const val GYRO_LABEL = "G"

    /** Top left and top right, in both orientations. */
    fun defaults(): List<TouchControl> = listOf(
        corner(PREV, PREV_LABEL, 0.03f, 0.06f, Placement(0.05f, 0.03f, SIZE), SIZE),
        corner(NEXT, NEXT_LABEL, 0.97f, 0.06f, Placement(0.95f, 0.03f, SIZE), SIZE),
    )

    /**
     * [corners] with ← and → (else the defaults), and an old gyro corner kept until
     * [gyroCornerToButtons] converts it. Anything else is dropped.
     */
    fun normalize(corners: List<TouchControl>): List<TouchControl> {
        val prev = corners.firstOrNull { it.id == PREV }
        val next = corners.firstOrNull { it.id == NEXT }
        if (prev == null || next == null) return defaults()
        val out = listOfNotNull(prev, next, corners.firstOrNull { it.id == GYRO })
        return if (out == corners) corners else out
    }

    /**
     * [p] (with its mappings resolved) without the old gyro corner, which becomes a touch
     * button in every workspace, where the corner was, mapped to a Toggle [OutputTarget.GyroPause]
     * (skipped in a workspace that already has a control with that id).
     */
    fun gyroCornerToButtons(p: Profile): Profile {
        val corner = p.corners.firstOrNull { it.id == GYRO } ?: return p
        val corners = p.corners.filter { it.id != GYRO }
        val button = corner.copy(id = GYRO_BUTTON, kind = TouchKind.BUTTON, label = GYRO_LABEL)
        val mapping = Mapping(InputSource.Touch(GYRO_BUTTON), OutputTarget.GyroPause, trigger = Trigger(output = Output.TOGGLE))
        return p.copy(
            corners = corners,
            workspaces = p.workspaces.map { w ->
                if (w.touchControls.any { it.id == GYRO_BUTTON }) w
                else w.copy(touchControls = w.touchControls + button, mappings = w.mappings + mapping)
            },
        )
    }

    /**
     * Turns the fullscreen corners of a profile saved before workspaces (any two FULLSCREEN
     * controls) into ← / →, the left one (landscape) becoming ←. Fewer than two: the defaults.
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
