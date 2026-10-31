package com.oleroma.gyrophone.core.model

/**
 * Old sticky settings turned into Toggle mappings ([How.TOGGLE] and its "Also ends on"):
 * a key's [KeyMode.TOGGLE] becomes a Toggle, its [KeyMode.NEXT_KEY] a Toggle that also ends on the
 * next input, and a surface's latch ([TouchpadSettings.latch], per direction too) makes its
 * swipe directions' On swipe chips Toggles that also end on a tap (and on its timer).
 */
object ToggleUpgrade {
    /** [m] with an old key mode as options; [m] itself when it has none. */
    fun keyMode(m: Mapping): Mapping {
        val t = m.target as? OutputTarget.Key ?: return m
        if (t.mode == KeyMode.HOLD) return m
        val o = (m.options ?: MapOptions()).copy(how = How.TOGGLE, toggle = false)
        return m.copy(
            target = t.copy(mode = KeyMode.HOLD),
            options = if (t.mode == KeyMode.NEXT_KEY) o.copy(endsOnNextInput = true) else o,
        )
    }

    /** [ms] with old key modes as options ([keyMode]); [ms] itself when there are none. */
    fun keyModes(ms: List<Mapping>): List<Mapping> =
        if (ms.none { (it.target as? OutputTarget.Key)?.mode.let { k -> k != null && k != KeyMode.HOLD } }) ms
        else ms.map(::keyMode)

    private fun hasLatch(s: TouchpadSettings) = s.latch || s.dirs.orEmpty().any { it.latch == true }

    private fun hasLatch(s: SurfaceSettings?) = s != null && (s.latch != null || s.latchReleaseMs != null || s.dirs.orEmpty().any { it.latch != null })

    /**
     * [w] (with its settings applied) with its latches as Toggle chips and its latch settings
     * cleared; [w] itself when it has none.
     */
    fun latches(w: Workspace): Workspace {
        if (!hasLatch(w.touchpad) && w.touchControls.none { hasLatch(it.surface) }) return w
        val pads = HashMap<String, TouchpadSettings>()
        pads[""] = w.touchpad
        for (c in w.touchControls) pads[c.id] = c.surfaceSettings(w.touchpad)
        val mappings = w.mappings.map { m ->
            val s = m.source as? InputSource.Surface ?: return@map m
            val d = s.input.holdDirection ?: return@map m
            if (s.on != Trigger.HOLD) return@map m
            val pad = pads[s.id] ?: return@map m
            if (!pad.latchOf(d)) return@map m
            val o = m.options ?: MapOptions()
            if (o.mode == How.TOGGLE) return@map m
            m.copy(
                options = o.copy(
                    how = How.TOGGLE, toggle = false, endsOnTap = true,
                    endsAfterMs = pad.latchReleaseMs.coerceIn(0, MapOptions.MAX_TOGGLE_MS),
                ),
            )
        }
        return w.copy(
            mappings = mappings,
            touchpad = clear(w.touchpad),
            touchControls = w.touchControls.map { c ->
                val sf = c.surface
                if (!hasLatch(sf)) c else c.copy(
                    surface = sf!!.copy(latch = null, latchReleaseMs = null, dirs = sf.dirs?.map { it.copy(latch = null) }),
                )
            },
        )
    }

    private fun clear(s: TouchpadSettings): TouchpadSettings =
        if (!hasLatch(s) && s.latchReleaseMs == 0) s
        else s.copy(latch = false, latchReleaseMs = 0, dirs = s.dirs?.map { it.copy(latch = null) }?.takeUnless { l -> l.all { it.isEmpty } })
}
