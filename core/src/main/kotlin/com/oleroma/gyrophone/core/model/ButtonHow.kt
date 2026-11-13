package com.oleroma.gyrophone.core.model

/**
 * A button's How ([TouchControl.how], a physical key's [KeyTiming.how]): the How its chips follow
 * when they don't set their own ([MapOptions.how] null). Held (the default) changes nothing. A chip
 * takes it only where it can: an analog output (a stick axis, mouse motion, a setting) never
 * does, and a value chip (on Position or Move) only takes Toggle. A button's Toggle also keeps its
 * gyro cards and gyro settings on from one touch to the next (the engine's latch).
 */
object ButtonHow {
    /** The touch control a mapping's source belongs to (its press, surface or area), or null. */
    fun ownerOf(s: InputSource): String? = when (s) {
        is InputSource.Touch -> s.id
        is InputSource.Surface -> s.id.takeIf { it.isNotEmpty() }
        is InputSource.TouchArea -> s.id
        else -> null
    }

    /** The physical key a mapping's source is, or null. */
    fun keyOf(s: InputSource): Int? = when (s) {
        is InputSource.Key -> s.keyCode
        is InputSource.KeyGesture -> s.keyCode
        else -> null
    }

    /** A chip of [target] on [source] can take How [how] from its button. */
    fun takes(target: OutputTarget, source: InputSource, how: How): Boolean = when {
        target is OutputTarget.MouseAxis || target is OutputTarget.HapticStep || (target is OutputTarget.Haptic && target.held) -> false
        target.valueTarget -> how == How.TOGGLE && source.slides
        else -> !source.slides
    }

    /** A chip's options with its button's How [how] (and Repeat count [repeat]) where it follows the button. */
    fun options(o: MapOptions?, target: OutputTarget, source: InputSource, how: How?, repeat: Int): MapOptions? {
        if (how == null || how == How.HELD) return o
        if (o != null && (o.how != null || o.toggle)) return o
        if (!takes(target, source, how)) return o
        return (o ?: MapOptions()).copy(how = how, repeat = repeat)
    }

    /** [m] with its button's How where it follows it. */
    fun apply(m: Mapping, how: How?, repeat: Int): Mapping {
        val o = options(m.options, m.target, m.source, how, repeat)
        return if (o === m.options) m else m.copy(options = o)
    }

    /**
     * [w]'s touch mappings with each button's How where its chips follow it. A Toggle button's
     * chips on its plain press ([stateOf]) are left Held: the engine presses them while the button
     * is on. Its other following chips (tap, long press, ...) toggle by themselves and end as the
     * button's "Also ends on" says.
     */
    fun touch(w: Workspace, ms: List<Mapping>): List<Mapping> {
        val hows = w.touchControls.filter { it.how != null && it.how != How.HELD }.associateBy { it.id }
        if (hows.isEmpty()) return ms
        return ms.map { m ->
            val c = ownerOf(m.source)?.let(hows::get) ?: return@map m
            if (stateOf(c, m)) return@map m
            val a = apply(m, c.how, c.repeat)
            if (a === m || c.how != How.TOGGLE) a
            else a.copy(options = a.options!!.copy(
                endsOnLetGo = c.endsOnLetGo, endsAfterMs = c.endsAfterMs, endsOnNextInput = c.endsOnNextInput, endsOnSame = c.endsOnSame,
            ))
        }
    }

    /**
     * Mapping [m] follows Toggle button [c]'s state: on [c]'s plain press, with no How of its own,
     * an output that can toggle. It is pressed while [c] is on (its Initial state, turned by each
     * touch, back on an ending, by its toggle sync).
     */
    fun stateOf(c: TouchControl, m: Mapping): Boolean {
        if (c.how != How.TOGGLE || c.kind != TouchKind.BUTTON) return false
        val s = m.source
        if (s !is InputSource.Touch || s.id != c.id) return false
        val o = m.options
        if (o != null && (o.how != null || o.toggle)) return false
        return takes(m.target, s, How.TOGGLE)
    }

    /** [w]'s touch mappings that follow a Toggle button's state ([stateOf]), by that button's id. */
    fun stateChips(w: Workspace, ms: List<Mapping>): Map<Mapping, String> {
        val toggles = w.touchControls.filter { it.how == How.TOGGLE && it.kind == TouchKind.BUTTON }.associateBy { it.id }
        if (toggles.isEmpty()) return emptyMap()
        val out = HashMap<Mapping, String>()
        for (m in ms) {
            val s = m.source as? InputSource.Touch ?: continue
            val c = toggles[s.id] ?: continue
            if (stateOf(c, m)) out[m] = c.id
        }
        return out
    }

    /** A device's mappings [ms] with each physical key's How ([Workspace.keyTimings]) where its chips follow it. */
    fun keys(w: Workspace, ms: List<Mapping>): List<Mapping> {
        val hows = w.keyTimings.filter { it.how != null && it.how != How.HELD }.associateBy { it.keyCode }
        if (hows.isEmpty()) return ms
        return ms.map { m -> keyOf(m.source)?.let(hows::get)?.let { k -> apply(m, k.how, k.repeat) } ?: m }
    }

    /** Physical held button [b] toggles: one of its keys has How Toggle. */
    fun toggles(w: Workspace, b: HeldButton): Boolean = b.sources.any { s ->
        val k = keyOf(s) ?: return@any false
        w.keyTimings.any { it.keyCode == k && it.how == How.TOGGLE }
    }
}
