package com.oleroma.gyrophone.core.model

/**
 * Turns mappings from before mapping triggers into the same thing done with triggers, one to
 * one, so each input does what it did: a touch button's or key's Tap, Double tap, Long press and
 * Release inputs become its press with that trigger, a surface's taps the finger count's press,
 * a touch control's On touch, Area and Outside its press with that Where, key modes, gyro toggle,
 * the old layer kinds and a haptic's edge an [Output]. What has no new form is dropped. Running it
 * again changes nothing.
 */
object TriggerUpgrade {
    /** [w] with every mapping list upgraded; the same instance when nothing changed. */
    fun workspace(w: Workspace): Workspace {
        val mappings = mappings(w.mappings, w.hapticEdge)
        val keyboard = mappings(w.keyboard, w.hapticEdge)
        val mouse = mappings(w.mouse, w.hapticEdge)
        val pads = w.pads.map { p ->
            val ms = mappings(p.mappings, w.hapticEdge)
            val own = p.own?.let { mappings(it, w.hapticEdge) }
            if (ms === p.mappings && own === p.own) p else p.copy(mappings = ms, own = own)
        }
        if (mappings === w.mappings && keyboard === w.keyboard && mouse === w.mouse &&
            pads.indices.all { pads[it] === w.pads[it] }
        ) return w
        return w.copy(mappings = mappings, keyboard = keyboard, mouse = mouse, pads = pads)
    }

    /** [p] with every workspace and the Defaults upgraded; the same instance when nothing changed. */
    fun profile(p: Profile): Profile {
        val workspaces = p.workspaces.map(::workspace)
        val defaults = workspace(p.defaults)
        if (defaults === p.defaults && workspaces.indices.all { workspaces[it] === p.workspaces[it] }) return p
        return p.copy(workspaces = workspaces, defaults = defaults)
    }

    /** [ms] upgraded, each mapping once; [edge] is the workspace's haptic edge for haptics that follow it. */
    fun mappings(ms: List<Mapping>, edge: HapticEdge = HapticEdge.PRESS): List<Mapping> {
        if (ms.none { needs(it) }) return ms
        // A tap waited for a double tap, and a double press was never a long press, only when the
        // input had one: then they are Single.
        val doubled = HashSet<InputSource>()
        for (m in ms) {
            val g = gestureOf(m.source)
            if (g != null && (g.second == Old.DOUBLE || g.second == Old.DOUBLE_PRESS)) doubled += g.first
            else if (g == null && m.trigger.taps == Taps.DOUBLE) doubled += m.source
        }
        return ms.flatMap { m ->
            val up = upgrade(m, edge, doubled)
            // A tap-drag clicked on every tap and held through a touch soon after: a Tap and a Double press.
            if (m.source.let { it is InputSource.Surface && it.input == SurfaceInput.TAP_DRAG }) {
                listOf(up.copy(trigger = up.trigger.copy(on = When.TAP, taps = Taps.ANY)), up)
            } else listOf(up)
        }.distinct()
    }

    private enum class Old { TAP, DOUBLE, DOUBLE_PRESS, LONG, RELEASE }

    /** The press a gesture source belongs to and which gesture it was. */
    private fun gestureOf(s: InputSource): Pair<InputSource, Old>? = when (s) {
        is InputSource.TouchTap -> InputSource.Touch(s.id) to Old.TAP
        is InputSource.TouchDoubleTap -> InputSource.Touch(s.id) to Old.DOUBLE
        is InputSource.TouchLongPress -> InputSource.Touch(s.id) to Old.LONG
        is InputSource.TouchRelease -> InputSource.Touch(s.id) to Old.RELEASE
        is InputSource.KeyGesture -> InputSource.Key(s.keyCode) to when (s.gesture) {
            ButtonGesture.TAP -> Old.TAP
            ButtonGesture.DOUBLE_TAP -> Old.DOUBLE
            ButtonGesture.LONG_PRESS -> Old.LONG
            ButtonGesture.RELEASE -> Old.RELEASE
        }

        is InputSource.Surface -> {
            val old = when (s.input) {
                SurfaceInput.TAP -> Old.TAP
                SurfaceInput.DOUBLE_TAP -> Old.DOUBLE
                SurfaceInput.TAP_DRAG -> Old.DOUBLE_PRESS
                SurfaceInput.LONG_PRESS -> Old.LONG
                SurfaceInput.RELEASE -> Old.RELEASE
                else -> null
            }
            // A button's one-finger press is the button's own.
            old?.let { (if (s.fingers == 1 && s.id.isNotEmpty()) InputSource.Touch(s.id) else s.copy(input = SurfaceInput.HOLD, zone = 0)) to it }
        }

        else -> null
    }

    private fun needs(m: Mapping): Boolean {
        val s = m.source
        if (gestureOf(s) != null || s is InputSource.TouchArea) return true
        return when (val t = m.target) {
            is OutputTarget.Key -> t.mode != KeyMode.HOLD
            OutputTarget.GyroHold, OutputTarget.GyroToggle -> true
            is OutputTarget.Layer -> !t.follows
            is OutputTarget.Haptic -> t.edge != null || (!t.held && m.trigger.isDefault)
            else -> false
        }
    }

    private fun upgrade(m: Mapping, edge: HapticEdge, doubled: Set<InputSource>): Mapping {
        var source = m.source
        var trigger = m.trigger
        val g = gestureOf(source)
        if (g != null) {
            source = g.first
            trigger = when (g.second) {
                Old.TAP -> trigger.copy(on = When.TAP, taps = if (g.first in doubled) Taps.SINGLE else Taps.ANY)
                Old.DOUBLE -> trigger.copy(on = When.TAP, taps = Taps.DOUBLE)
                Old.DOUBLE_PRESS -> trigger.copy(on = When.PRESS, taps = Taps.DOUBLE)
                Old.LONG -> trigger.copy(on = When.LONG_PRESS, taps = if (g.first in doubled) Taps.SINGLE else Taps.ANY)
                Old.RELEASE -> trigger.copy(on = When.RELEASE)
            }
        }
        if (source is InputSource.TouchArea) {
            val where = when (source.kind) {
                TouchAreaKind.HELD -> Where.BUTTON
                TouchAreaKind.ZONE -> Where.AREA
                TouchAreaKind.OUTSIDE -> Where.OUTSIDE
            }
            trigger = trigger.copy(where = where)
            source = InputSource.Touch(source.id)
        }
        // Only an untouched output takes the target's old kind; the trigger's own wins.
        val fresh = trigger.output == Output.HELD
        val target = when (val t = m.target) {
            is OutputTarget.Key -> if (t.mode == KeyMode.HOLD) t else {
                if (fresh) trigger = trigger.copy(output = if (t.mode == KeyMode.TOGGLE) Output.TOGGLE else Output.STICKY)
                t.copy(mode = KeyMode.HOLD)
            }

            OutputTarget.GyroHold -> OutputTarget.GyroPause
            OutputTarget.GyroToggle -> {
                if (fresh) trigger = trigger.copy(output = Output.TOGGLE)
                OutputTarget.GyroPause
            }

            is OutputTarget.Layer -> if (t.follows) t else {
                if (fresh && !t.whileHeld) {
                    // A set switch fired once; an override went on and off with each press.
                    trigger = trigger.copy(output = if (t.override) Output.TOGGLE else Output.FIRE_ONCE)
                }
                OutputTarget.Layer(t.set, override = t.override, follows = true)
            }

            is OutputTarget.Haptic -> {
                if (fresh && !t.held) {
                    val at = when (t.edge ?: edge) {
                        HapticEdge.PRESS -> FireAt.START
                        HapticEdge.RELEASE -> FireAt.END
                        HapticEdge.BOTH -> FireAt.BOTH
                    }
                    trigger = trigger.copy(output = Output.FIRE_ONCE, fireAt = at)
                }
                t.copy(edge = null)
            }

            else -> t
        }
        return Mapping(source, target, m.set, trigger)
    }
}
