package com.oleroma.gyrophone.core.model

import com.oleroma.gyrophone.core.hid.HidUsage

/** One remappable input as the editor shows it: a label and the phone-side sources it stands for. */
data class BindableInput(
    val label: String,
    /** sources[0] is canonical; edits write mappings for every source (e.g. LT = LTRIGGER axis, BRAKE axis, L2 key). */
    val sources: List<InputSource>,
    /** True for a full analog stick axis: only PadAxis targets make sense. */
    val analog: Boolean = false,
    /** A swipe axis: mouse motion and scrolling make sense too ([Describe.swipeTargets]). */
    val swipe: Boolean = false,
    /** Targets offered only for this input (switching apps, zoom), on top of the usual ones. */
    val extra: List<OutputTarget> = emptyList(),
    /** A mouse's movement: only mouse motion and scrolling make sense. */
    val mouseMotion: Boolean = false,
)

/**
 * Editing helpers that read and rewrite a mapping list per [BindableInput]: a workspace's touch
 * mappings ([Workspace.mappings]) or an attached pad's ([AttachedPad.mappings]).
 */
object Bindings {
    private val triggerAxes = setOf(AndroidAxis.LTRIGGER, AndroidAxis.RTRIGGER, AndroidAxis.GAS, AndroidAxis.BRAKE)

    /** Equality of sources, treating FULL and POSITIVE of a trigger axis as the same source. */
    fun sameSource(a: InputSource, b: InputSource): Boolean {
        if (a == b) return true
        if (a !is InputSource.Axis || b !is InputSource.Axis) return false
        return a.axis == b.axis && a.axis in triggerAxes &&
                a.half != AxisHalf.NEGATIVE && b.half != AxisHalf.NEGATIVE
    }

    /** Whether [mapping] belongs to [input] in Base (other sets are edited through [inSet]). */
    fun belongs(mapping: Mapping, input: BindableInput): Boolean =
        mapping.set == MappingSet.BASE && input.sources.any { sameSource(it, mapping.source) }

    /**
     * Edits set [set] of [mappings] with the Base-only helpers: [edit] sees that set's mappings as
     * Base (and Base's as set [set]), and gets them back in their own sets, new ones in [set].
     */
    fun inSet(mappings: List<Mapping>, set: Int, edit: (List<Mapping>) -> List<Mapping>): List<Mapping> =
        viewSet(edit(viewSet(mappings, set)), set)

    /** [mappings] with sets [set] and Base swapped: applied twice, the list as it was. */
    fun viewSet(mappings: List<Mapping>, set: Int): List<Mapping> =
        if (set == MappingSet.BASE) mappings else mappings.map {
            when (it.set) {
                set -> it.copy(set = MappingSet.BASE)
                MappingSet.BASE -> it.copy(set = set)
                else -> it
            }
        }

    /** [input] (a held direction of a surface) in length zone [zone] ([InputSource.Surface.zone]). */
    fun zoneInput(input: BindableInput, zone: Int): BindableInput =
        input.copy(sources = input.sources.map { s -> if (s is InputSource.Surface) s.copy(zone = zone) else s })

    /**
     * How many length zones each held direction of surface [id] has mapped ([Direction.ordinal];
     * at least 1), with any finger count, in any set.
     */
    fun holdZoneCounts(mappings: List<Mapping>, id: String): IntArray {
        val n = IntArray(4) { 1 }
        for (m in mappings) {
            val s = m.source as? InputSource.Surface ?: continue
            if (s.id != id) continue
            val d = s.input.holdDirection ?: continue
            n[d.ordinal] = maxOf(n[d.ordinal], (s.zone + 1).coerceAtMost(TouchpadSettings.MAX_HOLD_ZONES))
        }
        return n
    }

    /**
     * [mappings] with no empty length zone before a used one: per surface, finger count and held
     * direction, the zones mapped in any set are numbered again from 0 in order.
     */
    fun compactZones(mappings: List<Mapping>): List<Mapping> {
        val used = HashMap<InputSource.Surface, java.util.TreeSet<Int>>()
        for (m in mappings) {
            val s = m.source as? InputSource.Surface ?: continue
            if (s.input.holdDirection == null) continue
            used.getOrPut(s.copy(zone = 0)) { java.util.TreeSet() }.add(s.zone)
        }
        if (used.values.all { it.last() == it.size - 1 }) return mappings
        return mappings.map { m ->
            val s = m.source as? InputSource.Surface ?: return@map m
            val zones = used[s.copy(zone = 0)] ?: return@map m
            val z = zones.headSet(s.zone).size
            if (z == s.zone) m else m.copy(source = s.copy(zone = z))
        }
    }

    /** [mappings] without set [set]'s, and without the layer actions that use it. */
    fun dropSet(mappings: List<Mapping>, set: Int): List<Mapping> =
        mappings.filterNot { it.set == set || (it.target as? OutputTarget.Layer)?.set == set }

    /** Distinct targets mapped from any of input.sources, in list order. */
    fun targets(mappings: List<Mapping>, input: BindableInput): List<OutputTarget> =
        mappings.filter { belongs(it, input) }.map { it.target }.distinct()

    /** Distinct chips (target and trigger) mapped from any of input.sources, in list order. */
    fun chips(mappings: List<Mapping>, input: BindableInput): List<TargetChip> =
        mappings.filter { belongs(it, input) }.map { TargetChip(it.target, it.trigger) }.distinct()

    /**
     * Replaces every mapping whose source is one of input.sources with sources x [chips]. Keeps
     * other mappings and their order; new ones go where the first removed one was, else at the end.
     */
    fun setChips(mappings: List<Mapping>, input: BindableInput, chips: List<TargetChip>): List<Mapping> {
        val first = mappings.indexOfFirst { belongs(it, input) }
        val kept = mappings.filterNot { belongs(it, input) }
        val added = input.sources.flatMap { s -> chips.distinct().map { Mapping(s, it.target, trigger = it.trigger) } }
        val at = if (first < 0) kept.size else first
        return kept.subList(0, at) + added + kept.subList(at, kept.size)
    }

    /** [setChips] with [targets], each keeping the triggers it has; a new one Press, Held. */
    fun setTargets(mappings: List<Mapping>, input: BindableInput, targets: List<OutputTarget>): List<Mapping> {
        val had = chips(mappings, input)
        return setChips(mappings, input, targets.distinct().flatMap { t ->
            had.filter { it.target == t }.ifEmpty { listOf(TargetChip(t)) }
        })
    }

    /** Adds a Press, Held chip of [target] unless there is one. */
    fun addTarget(mappings: List<Mapping>, input: BindableInput, target: OutputTarget): List<Mapping> {
        val current = chips(mappings, input)
        return if (TargetChip(target) in current) mappings else setChips(mappings, input, current + TargetChip(target))
    }

    /** Removes every chip of [target]. */
    fun removeTarget(mappings: List<Mapping>, input: BindableInput, target: OutputTarget): List<Mapping> =
        setChips(mappings, input, chips(mappings, input).filter { it.target != target })

    /** Removes [chip]. */
    fun removeChip(mappings: List<Mapping>, input: BindableInput, chip: TargetChip): List<Mapping> =
        setChips(mappings, input, chips(mappings, input) - chip)

    /** [chip] with [trigger] instead, in its place (one chip when another already has that trigger). */
    fun setTrigger(mappings: List<Mapping>, input: BindableInput, chip: TargetChip, trigger: Trigger): List<Mapping> =
        setChips(mappings, input, chips(mappings, input).map { if (it == chip) it.copy(trigger = trigger) else it })

    /**
     * Inputs a touch control exposes: BUTTON -> [Touch(id)] (its press, whose taps, long press,
     * release, area and outside are its mappings' triggers); in swipe mode its press, then its
     * surface's inputs for 1–4 fingers, one finger's press being the button's ([swipeInputs]);
     * STICK -> X axis, Y axis (analog), Left, Right, Up, Down (halves), with 8 zones the four
     * diagonals, then its press; FULLSCREEN, DISPLAY -> none.
     */
    fun touchInputs(control: TouchControl): List<BindableInput> = when (control.kind) {
        TouchKind.BUTTON -> {
            val press = BindableInput(control.label.ifBlank { control.id }, listOf(InputSource.Touch(control.id)))
            // Swipe mode: a touch surface, with the touchpad's inputs per finger count.
            if (!control.swipes) listOf(press) else listOf(press) +
                    (1..4).flatMap { n ->
                        surfaceInputs(n, control.id).filterNot { n == 1 && isOneFingerHold(it) }
                            .map { it.copy(label = "${n}f " + it.label) }
                    } + gyroInputs(control.id) + motionInputs(control.id)
        }

        TouchKind.STICK -> {
            fun s(axis: StickAxis, half: AxisHalf = AxisHalf.FULL) =
                listOf(InputSource.TouchStick(control.id, axis, half))
            listOf(
                BindableInput("X axis", s(StickAxis.X), analog = true),
                BindableInput("Y axis", s(StickAxis.Y), analog = true),
                BindableInput("Left", s(StickAxis.X, AxisHalf.NEGATIVE)),
                BindableInput("Right", s(StickAxis.X, AxisHalf.POSITIVE)),
                BindableInput("Up", s(StickAxis.Y, AxisHalf.NEGATIVE)),
                BindableInput("Down", s(StickAxis.Y, AxisHalf.POSITIVE)),
            ) + (if (control.zones != StickZones.EIGHT) emptyList() else StickDiagonal.entries.map {
                BindableInput(it.label, listOf(InputSource.TouchStickDiagonal(control.id, it)))
            }) + BindableInput("Press", listOf(InputSource.Touch(control.id)))
        }

        TouchKind.FULLSCREEN, TouchKind.DISPLAY -> emptyList()
    }

    /**
     * A swipe button's inputs with [fingers] down: its surface's, one finger's hold being the
     * button's press ([InputSource.Touch], labelled Hold). The touchpad ([id] "") keeps its own hold.
     */
    /**
     * The gyro axes of touch control [id] ("" = the workspace's): what yaw, pitch and roll do (a
     * button's only while it is pressed). Mouse motion, scrolling and game pad axes make sense.
     */
    fun gyroInputs(id: String = ""): List<BindableInput> = MotionAxis.entries.filter { it != MotionAxis.NONE }.map { a ->
        BindableInput("Gyro " + a.label.lowercase(), listOf(InputSource.Gyro(a, id)), swipe = true)
    }

    /** The "Gyro mouse" preset of touch control [id] ("" = the workspace's): roll moves the mouse left and right, pitch up and down. */
    fun gyroMouseMappings(id: String = ""): List<Mapping> = listOf(
        Mapping(InputSource.Gyro(MotionAxis.ROLL, id), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
        Mapping(InputSource.Gyro(MotionAxis.PITCH, id), OutputTarget.MouseAxis(MouseMotion.MOVE_Y)),
    )

    /**
     * The phone's tilt and shake for touch control [id] ("" = the workspace's): a full tilt axis
     * drives a game pad axis, each direction and a shake press anything.
     */
    fun motionInputs(id: String = ""): List<BindableInput> = listOf(
        BindableInput("Tilt sideways", listOf(InputSource.Tilt(StickAxis.X, AxisHalf.FULL, id)), analog = true),
        BindableInput("Tilt forward and back", listOf(InputSource.Tilt(StickAxis.Y, AxisHalf.FULL, id)), analog = true),
        BindableInput("Tilt left", listOf(InputSource.Tilt(StickAxis.X, AxisHalf.NEGATIVE, id))),
        BindableInput("Tilt right", listOf(InputSource.Tilt(StickAxis.X, AxisHalf.POSITIVE, id))),
        BindableInput("Tilt forward", listOf(InputSource.Tilt(StickAxis.Y, AxisHalf.NEGATIVE, id))),
        BindableInput("Tilt back", listOf(InputSource.Tilt(StickAxis.Y, AxisHalf.POSITIVE, id))),
        BindableInput("Shake", listOf(InputSource.Shake(id))),
    )

    fun swipeInputs(control: TouchControl, fingers: Int): List<BindableInput> {
        val surface = surfaceInputs(fingers, control.id)
        if (fingers != 1 || control.id.isEmpty()) return surface
        val press = BindableInput("Press", listOf(InputSource.Touch(control.id)))
        return surface.map { if (isOneFingerHold(it)) press else it }
    }

    private fun isOneFingerHold(input: BindableInput): Boolean =
        (input.sources.first() as? InputSource.Surface)?.let { it.fingers == 1 && it.input == SurfaceInput.HOLD } == true

    /**
     * [w] with each swipe button's one-finger press as the button's own press (its one-finger
     * surface hold becomes [InputSource.Touch]), and "press only on a tap" turned into Tap triggers
     * on its plain press mappings (then cleared). The same instance when nothing changes.
     */
    fun matchSwipeModes(w: Workspace): Workspace {
        val buttons = w.touchControls.filter { it.kind == TouchKind.BUTTON && it.swipes }.associateBy { it.id }
        if (buttons.isEmpty()) return w
        var changed = false
        val mappings = w.mappings.map { m ->
            val s = m.source
            when {
                s is InputSource.Touch && buttons[s.id]?.swipeTapOnRelease == true && m.trigger.isDefault ->
                    m.copy(trigger = Trigger.TAP).also { changed = true }

                s is InputSource.Surface && s.id in buttons && s.fingers == 1 && s.zone == 0 && s.input == SurfaceInput.HOLD ->
                    m.copy(source = InputSource.Touch(s.id)).also { changed = true }

                else -> m
            }
        }.distinct()
        val controls = w.touchControls.map { c ->
            if (c.swipes && c.swipeTapOnRelease) c.copy(swipeTapOnRelease = false).also { changed = true } else c
        }
        return if (changed) w.copy(touchControls = controls, mappings = mappings) else w
    }

    /** The surface inputs offered with [fingers] down: its press (with the taps as triggers), moves, Touch directions, pinch with two. */
    fun surfaceInputs(fingers: Int, id: String = ""): List<BindableInput> = SurfaceInput.entries.filter { i ->
        when {
            // Taps are the press's triggers, swipes a Touch direction's repeating Fire once.
            i.gesture || i.swipeDirection != null -> false
            i == SurfaceInput.PINCH || i == SurfaceInput.PINCH_IN || i == SurfaceInput.PINCH_OUT -> fingers == 2
            else -> true
        }
    }.map { i ->
        val extra = when (i) {
            SurfaceInput.MOTION_X -> listOf(OutputTarget.SwitchApps)
            SurfaceInput.PINCH -> listOf(OutputTarget.Zoom)
            else -> emptyList()
        }
        BindableInput(
            if (i == SurfaceInput.HOLD) "Press" else i.label,
            listOf(InputSource.Surface(fingers, i, id)),
            analog = i.motion,
            swipe = i.motion && i != SurfaceInput.PINCH,
            extra = extra
        )
    }

    /**
     * The touchpad mappings that do what [s]'s switches and 3 / 4 finger gestures did before
     * touchpad mappings: tap = left click (tap-drag and double tap with tap and drag), one finger
     * moves the cursor, two-finger tap = right click, two fingers scroll, pinch zooms.
     */
    fun defaultTouchpadMappings(s: TouchpadSettings): List<Mapping> = TriggerUpgrade.mappings(legacyTouchpadMappings(s))

    /**
     * [defaultTouchpadMappings] as they were before mapping triggers, with the surface's own tap
     * inputs: what a [com.oleroma.gyrophone.core.engine.Touchpad] made without mappings runs.
     */
    internal fun legacyTouchpadMappings(s: TouchpadSettings): List<Mapping> = buildList {
        fun add(n: Int, i: SurfaceInput, t: OutputTarget) = add(Mapping(InputSource.Surface(n, i), t))
        val tap = SurfaceInput.TAP
        val left = OutputTarget.Mouse(MouseButton.LEFT)
        if (s.tapToClick) {
            if (s.tapDrag) {
                add(1, SurfaceInput.TAP_DRAG, left); add(1, SurfaceInput.DOUBLE_TAP, left)
            } else add(1, tap, left)
            add(2, tap, OutputTarget.Mouse(MouseButton.RIGHT))
        }
        add(1, SurfaceInput.MOTION_X, OutputTarget.MouseAxis(MouseMotion.MOVE_X))
        add(1, SurfaceInput.MOTION_Y, OutputTarget.MouseAxis(MouseMotion.MOVE_Y))
        if (s.twoFingerScroll) {
            add(2, SurfaceInput.MOTION_Y, OutputTarget.MouseAxis(MouseMotion.WHEEL))
            if (s.horizontalScroll) add(2, SurfaceInput.MOTION_X, OutputTarget.MouseAxis(MouseMotion.HWHEEL))
        }
        if (s.pinchZoom) add(2, SurfaceInput.PINCH, OutputTarget.Zoom)
        if (!s.gestures) {
            // Without gestures, three and four fingers tapped a middle click.
            if (s.tapToClick) for (n in 3..4) add(n, tap, OutputTarget.Mouse(MouseButton.MIDDLE))
            return@buildList
        }
        for (n in 3..4) {
            val g = s.gesturesFor(n)
            if (s.tapToClick) for (t in gestureTargets(g.tap)) add(n, tap, t)
            val switches = g.swipeLeft == GestureAction.SwitchApps || g.swipeRight == GestureAction.SwitchApps
            if (switches) add(n, SurfaceInput.MOTION_X, OutputTarget.SwitchApps)
            for ((input, action) in listOf(
                SurfaceInput.SWIPE_UP to g.swipeUp, SurfaceInput.SWIPE_DOWN to g.swipeDown,
                SurfaceInput.SWIPE_LEFT to g.swipeLeft, SurfaceInput.SWIPE_RIGHT to g.swipeRight,
            )) {
                val sideways = input == SurfaceInput.SWIPE_LEFT || input == SurfaceInput.SWIPE_RIGHT
                if (sideways && action == GestureAction.SwitchApps) continue
                for (t in gestureTargets(action)) add(n, input, t)
            }
        }
    }

    /**
     * [m] with a swipe-button source from before touch surfaces ([InputSource.TouchSwipe]) as
     * the surface input that does its job: an axis is Move X / Y, a half the held direction.
     */
    fun upgradeSwipe(m: Mapping): Mapping {
        val s = m.source as? InputSource.TouchSwipe ?: return m
        val input = when (s.half) {
            AxisHalf.FULL -> if (s.axis == StickAxis.X) SurfaceInput.MOTION_X else SurfaceInput.MOTION_Y
            AxisHalf.NEGATIVE -> if (s.axis == StickAxis.X) SurfaceInput.HOLD_LEFT else SurfaceInput.HOLD_UP
            AxisHalf.POSITIVE -> if (s.axis == StickAxis.X) SurfaceInput.HOLD_RIGHT else SurfaceInput.HOLD_DOWN
        }
        return m.copy(source = InputSource.Surface(1, input, s.id))
    }

    /** [ms] with every old swipe source upgraded ([upgradeSwipe]), each mapping once. */
    fun upgradeSwipes(ms: List<Mapping>): List<Mapping> =
        if (ms.none { it.source is InputSource.TouchSwipe }) ms else ms.map(::upgradeSwipe).distinct()

    /** What a 3 / 4 finger [GestureAction] pressed: its keys (modifiers first) or mouse button. */
    fun gestureTargets(a: GestureAction): List<OutputTarget> = when (a) {
        GestureAction.None -> emptyList()
        is GestureAction.Mouse -> listOf(OutputTarget.Mouse(a.button))
        is GestureAction.Keys -> (0 until 8).filter { a.modifiers and (1 shl it) != 0 }
            .map { OutputTarget.Key(HidUsage.LEFT_CTRL + it) } + listOfNotNull(a.key.takeIf { it != 0 }
            ?.let { OutputTarget.Key(it) })
        // An up / down swipe set to switch apps was one Alt+Tab.
        GestureAction.SwitchApps -> listOf(OutputTarget.Key(HidUsage.LEFT_CTRL + 2), OutputTarget.Key(0x2B))
    }

    /** Drops a touch control and all of its mappings (on delete); a group left with one member ends. */
    fun removeTouchControl(workspace: Workspace, id: String): Workspace = ControlGroups.tidy(
        workspace.copy(
            touchControls = workspace.touchControls.filterNot { it.id == id },
            mappings = workspace.mappings.filterNot { MappingProfiles.touchId(it.source) == id },
        ),
    )

    /** Fresh id not used by any control: prefix + n. */
    fun newTouchId(controls: List<TouchControl>, prefix: String): String {
        val used = controls.mapTo(HashSet()) { it.id }
        var n = 1
        while ("$prefix$n" in used) n++
        return "$prefix$n"
    }

    enum class StickPreset(val label: String) {
        LEFT_STICK("Left stick"), RIGHT_STICK("Right stick"), WASD("WASD"), ARROWS("Arrow keys"), DPAD("D-pad"), NONE("None"),
    }

    /**
     * Rewrites the six stick inputs (X axis, Y axis, Left, Right, Up, Down, as returned by
     * [touchInputs] or a physical stick's inputs) for a preset. Diagonals are cleared, so they
     * press both neighbours; other extra inputs (a stick's click) are left alone.
     */
    fun applyStickPreset(
        mappings: List<Mapping>,
        stickInputs: List<BindableInput>,
        preset: StickPreset
    ): List<Mapping> {
        require(stickInputs.size >= 6) { "Expected X, Y, Left, Right, Up, Down inputs" }
        fun axes(x: GamepadAxis, y: GamepadAxis) = listOf(
            listOf(OutputTarget.PadAxis(x)), listOf(OutputTarget.PadAxis(y)),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )

        fun halves(left: OutputTarget, right: OutputTarget, up: OutputTarget, down: OutputTarget) = listOf(
            emptyList(), emptyList(), listOf(left), listOf(right), listOf(up), listOf(down),
        )

        fun key(usage: Int) = OutputTarget.Key(usage)
        fun pad(b: GamepadButton) = OutputTarget.Pad(b)
        val plan: List<List<OutputTarget>> = when (preset) {
            StickPreset.LEFT_STICK -> axes(GamepadAxis.LX, GamepadAxis.LY)
            StickPreset.RIGHT_STICK -> axes(GamepadAxis.RX, GamepadAxis.RY)
            StickPreset.WASD -> halves(key(0x04), key(0x07), key(0x1A), key(0x16))
            StickPreset.ARROWS -> halves(key(0x50), key(0x4F), key(0x52), key(0x51))
            StickPreset.DPAD -> halves(
                pad(GamepadButton.DPAD_LEFT), pad(GamepadButton.DPAD_RIGHT),
                pad(GamepadButton.DPAD_UP), pad(GamepadButton.DPAD_DOWN),
            )

            StickPreset.NONE -> List(6) { emptyList() }
        }
        var m = mappings
        for (i in 0 until 6) m = setTargets(m, stickInputs[i], plan[i])
        for (input in stickInputs.drop(6)) {
            if (input.sources.all { it is InputSource.TouchStickDiagonal }) m = setTargets(m, input, emptyList())
        }
        return m
    }
}

/** One chip of an action: a target and the trigger it fires on. */
data class TargetChip(val target: OutputTarget, val trigger: Trigger = Trigger.DEFAULT)
