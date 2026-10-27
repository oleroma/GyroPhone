package com.oleroma.gyrophone.core.model

/**
 * Turns the touch button switches from before tap and long press mappings into those mappings,
 * so each button does what it did:
 * - "Press waits for a double tap" (with a double tap mapping): the press mappings become tap and
 *   long press mappings, the long press time being the workspace's old tap length
 *   ([Workspace.buttonTapMs]), past which the waiting press used to come at once.
 * - A release tap time: the release mappings become tap mappings, the long press time being the
 *   release tap time (unless the first rule set it).
 * The switches are then cleared, so running it again changes nothing.
 */
object ButtonActivators {
    /** [set] (stored, without mappings) and [profiles] upgraded; the same instances when nothing changed. */
    fun upgrade(set: Profile, profiles: List<MappingProfile>): Pair<Profile, List<MappingProfile>> {
        if (set.workspaces.none { w -> w.touchControls.any { it.isLegacy } }) return set to profiles
        var result = profiles
        val workspaces = set.workspaces.map { w ->
            if (w.touchControls.none { it.isLegacy }) return@map w
            val profile = MappingProfiles.of(w, result)
            var touch = profile?.touch.orEmpty()
            var own = w.mappings
            val controls = w.touchControls.map { c ->
                if (!c.isLegacy) return@map c
                val (t, longT) = rewrite(c, touch, w)
                val (o, longO) = rewrite(c, own, w)
                touch = t; own = o
                c.copy(doubleTapWaits = false, releaseTapMs = 0, longPressMs = longT ?: longO ?: c.longPressMs)
            }
            if (profile != null && touch != profile.touch) {
                result = result.map { if (it.id == profile.id) it.copy(touch = touch) else it }
            }
            w.copy(touchControls = controls, mappings = own)
        }
        return set.copy(workspaces = workspaces) to result
    }

    private val TouchControl.isLegacy: Boolean get() = doubleTapWaits || releaseTapMs > 0

    /** [mappings] with [c]'s old behaviour as tap / long press mappings, and the long press time it needs (null = no change). */
    private fun rewrite(c: TouchControl, mappings: List<Mapping>, w: Workspace): Pair<List<Mapping>, Int?> {
        val id = c.id
        val waited = c.doubleTapWaits && mappings.any { it.source == InputSource.TouchDoubleTap(id) }
        val timedRelease = c.releaseTapMs > 0
        var longMs: Int? = null
        val out = mappings.flatMap { m ->
            when {
                waited && m.source == InputSource.Touch(id) -> {
                    longMs = w.buttonTapMs
                    listOf(m.copy(source = InputSource.TouchTap(id)), m.copy(source = InputSource.TouchLongPress(id)))
                }

                timedRelease && m.source == InputSource.TouchRelease(id) -> {
                    if (longMs == null && !waited) longMs = c.releaseTapMs
                    listOf(m.copy(source = InputSource.TouchTap(id)))
                }

                else -> listOf(m)
            }
        }.distinct()
        return out to longMs?.coerceIn(Workspace.MIN_LONG_PRESS_MS, Workspace.MAX_LONG_PRESS_MS)
    }
}
