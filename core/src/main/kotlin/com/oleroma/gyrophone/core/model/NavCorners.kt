package com.oleroma.gyrophone.core.model

/**
 * The old ← / → corners ([Profile.corners], kind [TouchKind.FULLSCREEN]) and the global ← / →
 * buttons that replaced them ([toButtons]): tap = previous / next workspace, long press =
 * fullscreen. The old gyro corner ([GYRO]) became a Toggle gyro touch button in each workspace
 * ([gyroCornerToButtons]).
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
     * [p] (with its mappings resolved) without the old gyro corner. A shown one becomes a touch
     * button in every workspace, where the corner was, mapped to [OutputTarget.GyroToggle]
     * (skipped in a workspace that already has a control with that id).
     */
    fun gyroCornerToButtons(p: Profile): Profile {
        val corner = p.corners.firstOrNull { it.id == GYRO } ?: return p
        val corners = p.corners.filter { it.id != GYRO }
        if (!p.gyroCorner) return p.copy(corners = corners)
        val button = corner.copy(id = GYRO_BUTTON, kind = TouchKind.BUTTON, label = GYRO_LABEL)
        val mapping = Mapping(InputSource.Touch(GYRO_BUTTON), OutputTarget.GyroToggle)
        return p.copy(
            corners = corners,
            workspaces = p.workspaces.map { w ->
                if (w.touchControls.any { it.id == GYRO_BUTTON }) w
                else w.copy(touchControls = w.touchControls + button, mappings = w.mappings + mapping)
            },
        )
    }

    /** Size sync id ([TouchControl.sizeSync]) of the ← / → buttons made from the corners. */
    const val SIZE_SYNC = "nav"

    /**
     * The ← / → buttons: [prev] and [next] as global touch buttons ([TouchControl.global]) whose
     * tap shows the previous / next workspace and whose long press switches fullscreen, same size
     * together ([SIZE_SYNC]) when [sameSize], at [opacity].
     */
    fun buttons(prev: TouchControl, next: TouchControl, sameSize: Boolean = true, opacity: Float? = null): Pair<List<TouchControl>, List<Mapping>> {
        fun button(c: TouchControl, label: String) = c.copy(
            kind = TouchKind.BUTTON,
            label = label,
            global = true,
            group = null,
            sizeSync = if (sameSize) SIZE_SYNC else null,
            opacity = opacity,
        )
        val controls = listOf(button(prev, PREV_LABEL), button(next, NEXT_LABEL))
        val mappings = listOf(
            Mapping(InputSource.TouchTap(prev.id), OutputTarget.PreviousWorkspace),
            Mapping(InputSource.TouchLongPress(prev.id), OutputTarget.Fullscreen),
            Mapping(InputSource.TouchTap(next.id), OutputTarget.NextWorkspace),
            Mapping(InputSource.TouchLongPress(next.id), OutputTarget.Fullscreen),
        )
        return controls to mappings
    }

    /** The built-in "Workspace arrows" preset: ← / → at the default corner places ([buttons]). */
    fun arrowsPreset(): Pair<List<TouchControl>, List<Mapping>> {
        val (prev, next) = defaults()
        return buttons(prev, next)
    }

    /**
     * [p] with its ← / → corners turned into ← / → buttons in every workspace ([buttons]), same
     * place, size and opacity (at least the old lowest, 25 %), the corners then gone
     * ([Profile.navButtons]). A workspace with a control of its own under that id keeps it.
     */
    fun toButtons(p: Profile): Profile {
        if (p.navButtons) return p
        val prev = p.corners.firstOrNull { it.id == PREV }
        val next = p.corners.firstOrNull { it.id == NEXT }
        val done = p.copy(corners = emptyList(), navButtons = true)
        if (prev == null || next == null) return done
        val (controls, mappings) = buttons(prev, next, p.cornerSameSize, p.cornerOpacity.coerceIn(MIN_OPACITY, 1f))
        return done.copy(workspaces = p.workspaces.map { w ->
            var out = w
            for (c in controls) {
                if (out.touchControls.any { it.id == c.id }) continue
                out = out.copy(
                    touchControls = out.touchControls + c,
                    mappings = out.mappings + mappings.filter { MappingProfiles.touchId(it.source) == c.id },
                )
            }
            ControlGroups.tidy(out)
        })
    }

    /** The lowest opacity the corners had in play. */
    private const val MIN_OPACITY = 0.25f

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
