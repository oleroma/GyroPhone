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

    /** Whether [mapping] belongs to [input]. */
    fun belongs(mapping: Mapping, input: BindableInput): Boolean =
        input.sources.any { sameSource(it, mapping.source) }

    /** Distinct targets mapped from any of input.sources, in list order. */
    fun targets(mappings: List<Mapping>, input: BindableInput): List<OutputTarget> =
        mappings.filter { belongs(it, input) }.map { it.target }.distinct()

    /**
     * Replaces every mapping whose source is one of input.sources with sources x targets. Keeps
     * other mappings and their order; new ones go where the first removed one was, else at the end.
     */
    fun setTargets(mappings: List<Mapping>, input: BindableInput, targets: List<OutputTarget>): List<Mapping> {
        val first = mappings.indexOfFirst { belongs(it, input) }
        val kept = mappings.filterNot { belongs(it, input) }
        val added = input.sources.flatMap { s -> targets.distinct().map { Mapping(s, it) } }
        val at = if (first < 0) kept.size else first
        return kept.subList(0, at) + added + kept.subList(at, kept.size)
    }

    fun addTarget(mappings: List<Mapping>, input: BindableInput, target: OutputTarget): List<Mapping> {
        val current = targets(mappings, input)
        return if (target in current) mappings else setTargets(mappings, input, current + target)
    }

    fun removeTarget(mappings: List<Mapping>, input: BindableInput, target: OutputTarget): List<Mapping> =
        setTargets(mappings, input, targets(mappings, input) - target)

    /**
     * Inputs a touch control exposes: BUTTON -> [Touch(id)] (its press), tap, double tap, long
     * press, release, outside and zone, in swipe mode also the swipe X and Y axes and the four swipe directions;
     * STICK -> X axis, Y axis (analog), Left, Right, Up, Down (halves), with 8 zones the four
     * diagonals, then on touch, outside and zone; FULLSCREEN -> none.
     */
    fun touchInputs(control: TouchControl): List<BindableInput> = when (control.kind) {
        TouchKind.BUTTON -> {
            val press = BindableInput(control.label.ifBlank { control.id }, listOf(InputSource.Touch(control.id)))
            val events = listOf(
                press,
                BindableInput("Tap", listOf(InputSource.TouchTap(control.id))),
                BindableInput("Double tap", listOf(InputSource.TouchDoubleTap(control.id))),
                BindableInput("Long press", listOf(InputSource.TouchLongPress(control.id))),
                BindableInput("Release", listOf(InputSource.TouchRelease(control.id))),
                area(control, TouchAreaKind.OUTSIDE),
                area(control, TouchAreaKind.ZONE),
            )
            // Swipe mode: a touch surface, with the touchpad's inputs per finger count.
            if (!control.swipes) events else events + (1..4).flatMap { n ->
                surfaceInputs(n, control.id).map { it.copy(label = "${n}f " + it.label) }
            }
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
            }) + TouchAreaKind.entries.map { area(control, it) }
        }

        TouchKind.FULLSCREEN -> emptyList()
    }

    private fun area(control: TouchControl, kind: TouchAreaKind) =
        BindableInput(kind.label, listOf(InputSource.TouchArea(control.id, kind)))

    /** The surface inputs offered with [fingers] down: taps (double tap and tap-drag with one), moves, swipes, pinch with two. */
    fun surfaceInputs(fingers: Int, id: String = ""): List<BindableInput> = SurfaceInput.entries.filter { i ->
        when (i) {
            SurfaceInput.DOUBLE_TAP, SurfaceInput.TAP_DRAG -> fingers == 1
            SurfaceInput.PINCH, SurfaceInput.PINCH_IN, SurfaceInput.PINCH_OUT -> fingers == 2
            else -> true
        }
    }.map { i ->
        val extra = when (i) {
            SurfaceInput.MOTION_X -> listOf(OutputTarget.SwitchApps)
            SurfaceInput.PINCH -> listOf(OutputTarget.Zoom)
            else -> emptyList()
        }
        BindableInput(
            i.label,
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
    fun defaultTouchpadMappings(s: TouchpadSettings): List<Mapping> = buildList {
        fun add(n: Int, i: SurfaceInput, t: OutputTarget) = add(Mapping(InputSource.Surface(n, i), t))
        val left = OutputTarget.Mouse(MouseButton.LEFT)
        if (s.tapToClick) {
            if (s.tapDrag) {
                add(1, SurfaceInput.TAP_DRAG, left); add(1, SurfaceInput.DOUBLE_TAP, left)
            } else add(1, SurfaceInput.TAP, left)
            add(2, SurfaceInput.TAP, OutputTarget.Mouse(MouseButton.RIGHT))
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
            if (s.tapToClick) for (n in 3..4) add(n, SurfaceInput.TAP, OutputTarget.Mouse(MouseButton.MIDDLE))
            return@buildList
        }
        for (n in 3..4) {
            val g = s.gesturesFor(n)
            if (s.tapToClick) for (t in gestureTargets(g.tap)) add(n, SurfaceInput.TAP, t)
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

    /** Drops a touch control and all of its mappings (on delete). */
    fun removeTouchControl(workspace: Workspace, id: String): Workspace = workspace.copy(
        touchControls = workspace.touchControls.filterNot { it.id == id },
        mappings = workspace.mappings.filterNot { MappingProfiles.touchId(it.source) == id },
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
