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
    /** [w] with every mapping list upgraded, latches turned Sticky; the same instance when nothing changed. */
    fun workspace(w: Workspace): Workspace {
        val latched = latches(w)
        if (latched !== w) return workspace(latched)
        val layered = buttonGyroLayers(w)
        if (layered !== w) return workspace(layered)
        val mappings = mappings(w.mappings, w.hapticEdge)
        val keyboard = mappings(w.keyboard, w.hapticEdge)
        val mouse = mappings(w.mouse, w.hapticEdge)
        val pads = w.pads.map { p ->
            val ms = mappings(p.mappings, w.hapticEdge)
            if (ms === p.mappings) p else p.copy(mappings = ms)
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
                listOf(up.copy(trigger = up.trigger.copy(fires = FiresOn.LIFT, taps = Taps.SINGLE, duration = Duration.QUICK, dontWait = true)), up)
            } else listOf(up)
        }.distinct()
    }

    /**
     * [w] with each button's own gyro settings ([TouchControl.gyro], from before layers held them)
     * as a layer named after the button, held while it is pressed: an override layer with those
     * gyro settings and no mappings, so the button still does what it did. One per button and set
     * it had them in, while there is room ([MappingSet.MAX]); past that they are dropped.
     */
    fun buttonGyroLayers(w: Workspace): Workspace {
        if (w.touchControls.none { it.gyro.isNotEmpty() }) return w
        val sets = w.sets.toMutableList()
        val added = ArrayList<Mapping>()
        for (c in w.touchControls) for (g in c.gyro) {
            if (!g.any) continue
            val id = (1..MappingSet.MAX).firstOrNull { i -> sets.none { it.id == i } } ?: break
            val under = if (g.set == MappingSet.BASE) emptyList() else listOf(g.set)
            val before = WorkspaceDefaults.layered(w, MappingSet.BASE, under)
            val after = before.copy(gyro = g.over(before.gyro))
            sets += WorkspaceDefaults.editLayer(MappingSet(id, c.label.ifBlank { "Button" }), before, after)
            added += Mapping(InputSource.Touch(c.id), OutputTarget.Layer(id, override = true, follows = true), set = g.set)
        }
        return w.copy(
            sets = sets,
            mappings = w.mappings + added,
            touchControls = w.touchControls.map { if (it.gyro.isEmpty()) it else it.copy(gyro = emptyList()) },
        )
    }

    /**
     * [w] with each latched direction (the touchpad's and each swipe button's) as Sticky outputs of
     * its Touch direction's plain mappings, and the latch settings cleared.
     */
    private fun latches(w: Workspace): Workspace {
        fun latchedDirs(t: TouchpadSettings?): Int = if (t == null) 0 else
            Direction.entries.fold(0) { m, d -> if (t.latchOf(d)) m or (1 shl d.ordinal) else m }
        fun latchedDirs(s: SurfaceSettings?): Int = if (s == null) 0 else
            Direction.entries.fold(0) { m, d ->
                if (s.dirs?.getOrNull(d.ordinal)?.latch ?: s.latch ?: false) m or (1 shl d.ordinal) else m
            }
        val byId = HashMap<String, Int>()
        latchedDirs(w.touchpad).let { if (it != 0) byId[""] = it }
        for (c in w.touchControls) latchedDirs(c.surface).let { if (it != 0) byId[c.id] = it }
        val hasOwn = w.touchpad.latch || w.touchpad.dirs.orEmpty().any { it.latch != null } ||
                w.touchControls.any { c -> c.surface?.latch != null || c.surface?.dirs.orEmpty().any { it.latch != null } }
        if (byId.isEmpty() && !hasOwn) return w
        val mappings = w.mappings.map { m ->
            val s = m.source as? InputSource.Surface ?: return@map m
            val d = s.input.holdDirection ?: return@map m
            val dirs = byId[s.id] ?: return@map m
            if (dirs and (1 shl d.ordinal) != 0 && m.trigger.output == Output.HELD) m.copy(trigger = m.trigger.copy(output = Output.STICKY)) else m
        }
        fun clear(t: TouchpadSettings) = t.copy(latch = false, latchReleaseMs = 0, dirs = t.dirs?.map { it.copy(latch = null) }?.takeUnless { l -> l.all { it.isEmpty } })
        fun clear(s: SurfaceSettings) = s.copy(latch = null, latchReleaseMs = null, dirs = s.dirs?.map { it.copy(latch = null) }?.takeUnless { l -> l.all { it.isEmpty } })
        return w.copy(
            mappings = mappings,
            touchpad = clear(w.touchpad),
            touchControls = w.touchControls.map { c -> c.surface?.let { c.copy(surface = clear(it)) } ?: c },
        )
    }

    /**
     * What the engine runs: a Touch direction's repeating Fire once ([Trigger.repeat]) is its swipe
     * input (which pulses every swipe distance), the rest as they are.
     */
    fun lower(ms: List<Mapping>): List<Mapping> {
        if (ms.none { it.trigger.repeat }) return ms
        return ms.map { m ->
            val s = m.source as? InputSource.Surface
            val d = s?.input?.holdDirection
            if (!m.trigger.repeat || s == null || d == null) m
            else Mapping(s.copy(input = SWIPES[d.ordinal], zone = 0), m.target, m.set, Trigger(where = m.trigger.where))
        }.distinct()
    }

    private val SWIPES = arrayOf(SurfaceInput.SWIPE_UP, SurfaceInput.SWIPE_RIGHT, SurfaceInput.SWIPE_DOWN, SurfaceInput.SWIPE_LEFT)
    private val HOLDS = arrayOf(SurfaceInput.HOLD_UP, SurfaceInput.HOLD_RIGHT, SurfaceInput.HOLD_DOWN, SurfaceInput.HOLD_LEFT)

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
        if (s is InputSource.Surface && s.input.swipeDirection != null) return true
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
                // A tap waited for a double only when its own input had one: otherwise it never waits
                // (a double the button inherits must not hold it up).
                Old.TAP -> trigger.copy(fires = FiresOn.LIFT, taps = Taps.SINGLE, duration = Duration.QUICK, dontWait = g.first !in doubled)
                Old.DOUBLE -> trigger.copy(fires = FiresOn.LIFT, taps = Taps.DOUBLE, duration = Duration.QUICK)
                Old.DOUBLE_PRESS -> trigger.copy(fires = FiresOn.PRESS, taps = Taps.DOUBLE, duration = Duration.ANY)
                Old.LONG -> trigger.copy(fires = FiresOn.PRESS, taps = Taps.SINGLE, duration = Duration.LONG)
                Old.RELEASE -> trigger.copy(fires = FiresOn.LIFT, taps = Taps.SINGLE, duration = Duration.ANY)
            }
        }
        // A swipe is its Touch direction's Drag, firing once every swipe distance.
        val swiped = (source as? InputSource.Surface)?.input?.swipeDirection
        if (swiped != null && source is InputSource.Surface) {
            source = source.copy(input = HOLDS[swiped.ordinal], zone = 0)
            if (trigger.output == Output.HELD) trigger = trigger.copy(output = Output.FIRE_ONCE, repeat = true)
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
