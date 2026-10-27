package com.oleroma.gyrophone.core.model

/**
 * Turns the settings from before tap and long press mappings into those mappings, so each input
 * does what it did:
 * - A touch button's "Press waits for a double tap" (with a double tap mapping): its press
 *   mappings become tap and long press mappings, the long press time being the workspace's old
 *   tap length ([Workspace.buttonTapMs]), past which the waiting press used to come at once.
 * - A touch button's release tap time: its release mappings become tap mappings, the long press
 *   time being the release tap time (unless the first rule set it).
 * - A release tap time of the touchpad or a swipe button's surface: its release mappings become
 *   tap mappings (releases now fire on every lift).
 * The old settings are then cleared, so running it again changes nothing.
 */
object ButtonActivators {
    /** [set] (stored, without mappings) and [profiles] upgraded; the same instances when nothing changed. */
    fun upgrade(set: Profile, profiles: List<MappingProfile>): Pair<Profile, List<MappingProfile>> {
        if (set.workspaces.none { it.isLegacy }) return set to profiles
        var result = profiles
        val workspaces = set.workspaces.map { w ->
            if (!w.isLegacy) return@map w
            val profile = MappingProfiles.of(w, result)
            var touch = profile?.touch.orEmpty()
            var pad = profile?.touchpad
            var own = w.mappings
            val controls = w.touchControls.map { c ->
                var out = c
                if (c.isLegacy) {
                    val (t, longT) = rewrite(c, touch, w)
                    val (o, longO) = rewrite(c, own, w)
                    touch = t; own = o
                    out = out.copy(
                        doubleTapWaits = false,
                        releaseTapMs = 0,
                        longPressMs = longT ?: longO ?: c.longPressMs
                    )
                }
                if (c.swipes && (c.surface?.releaseTapMs ?: w.touchpad.releaseTapMs) > 0) {
                    touch = releasesToTaps(touch, c.id); own = releasesToTaps(own, c.id)
                    out = out.copy(surface = out.surface?.copy(releaseTapMs = null))
                }
                out
            }
            var touchpad = w.touchpad
            if (touchpad.releaseTapMs > 0) {
                pad = pad?.let { releasesToTaps(it, "") }
                own = releasesToTaps(own, "")
                touchpad = touchpad.copy(releaseTapMs = 0)
            }
            if (profile != null && (touch != profile.touch || pad != profile.touchpad)) {
                result = result.map { if (it.id == profile.id) it.copy(touch = touch, touchpad = pad) else it }
            }
            w.copy(touchControls = controls, mappings = own, touchpad = touchpad)
        }
        return set.copy(workspaces = workspaces) to result
    }

    private val TouchControl.isLegacy: Boolean get() = doubleTapWaits || releaseTapMs > 0

    private val Workspace.isLegacy: Boolean
        get() = touchpad.releaseTapMs > 0 ||
                touchControls.any { it.isLegacy || (it.swipes && (it.surface?.releaseTapMs ?: 0) > 0) }

    /** [mappings] with the release inputs of surface [id] (\"\" = the touchpad) as taps. */
    private fun releasesToTaps(mappings: List<Mapping>, id: String): List<Mapping> = mappings.map { m ->
        val s = m.source
        if (s is InputSource.Surface && s.id == id && s.input == SurfaceInput.RELEASE) m.copy(source = s.copy(input = SurfaceInput.TAP)) else m
    }.distinct()

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
