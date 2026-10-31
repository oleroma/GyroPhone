package com.oleroma.gyrophone.core.model

/**
 * Turns the settings from before tap and long press mappings into those mappings, so each input
 * does what it did:
 * - A touch button's "Press waits for a double tap" (with a double tap mapping): its press
 *   mappings become tap and long press mappings, the long press time being the workspace's old
 *   tap length (the old "touchpad over buttons" tap time), past which the waiting press used to come at once.
 * - A touch button's release tap time: its release mappings become tap mappings, the long press
 *   time being the release tap time (unless the first rule set it).
 * - A release tap time of the touchpad or a swipe button's surface: its release mappings become
 *   tap mappings (releases now fire on every lift).
 * The old settings are then cleared, so running it again changes nothing.
 */
object ButtonActivators {
    /** [set] upgraded; the same instance when nothing changed. */
    fun upgrade(set: Profile): Profile {
        if (set.workspaces.none { it.isLegacy }) return set
        val workspaces = set.workspaces.map { w ->
            if (!w.isLegacy) return@map w
            var own = w.mappings
            val controls = w.touchControls.map { c ->
                var out = c
                if (c.isLegacy) {
                    val (o, long) = rewrite(c, own, w)
                    own = o
                    out = out.copy(doubleTapWaits = false, releaseTapMs = 0, longPressMs = long ?: c.longPressMs)
                }
                if (c.swipes && (c.surface?.releaseTapMs ?: w.touchpad.releaseTapMs) > 0) {
                    own = releasesToTaps(own, c.id)
                    out = out.copy(surface = out.surface?.copy(releaseTapMs = null))
                }
                out
            }
            var touchpad = w.touchpad
            if (touchpad.releaseTapMs > 0) {
                own = releasesToTaps(own, "")
                touchpad = touchpad.copy(releaseTapMs = 0)
            }
            w.copy(touchControls = controls, mappings = own, touchpad = touchpad)
        }
        return set.copy(workspaces = workspaces)
    }

    /** What "touchpad over buttons" took as the longest tap, before it went. */
    private const val LEGACY_TAP_MS = 200

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
                    longMs = LEGACY_TAP_MS
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
