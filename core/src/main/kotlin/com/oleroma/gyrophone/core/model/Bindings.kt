package com.oleroma.gyrophone.core.model

/** One remappable input as the editor shows it: a label and the phone-side sources it stands for. */
data class BindableInput(
    val label: String,
    /** sources[0] is canonical; edits write mappings for every source (e.g. LT = LTRIGGER axis, BRAKE axis, L2 key). */
    val sources: List<InputSource>,
    /** True for a full analog stick axis: only PadAxis targets make sense. */
    val analog: Boolean = false,
    /** A swipe axis: mouse motion and scrolling make sense too ([Describe.swipeTargets]). */
    val swipe: Boolean = false,
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
     * Inputs a touch control exposes: BUTTON -> [Touch(id)], in swipe mode also the swipe X and Y
     * axes and the four swipe directions; STICK -> X axis, Y axis (analog),
     * Left, Right, Up, Down (halves), and with 8 zones the four diagonals; FULLSCREEN -> none.
     */
    fun touchInputs(control: TouchControl): List<BindableInput> = when (control.kind) {
        TouchKind.BUTTON -> {
            val press = BindableInput(control.label.ifBlank { control.id }, listOf(InputSource.Touch(control.id)))
            if (!control.swipes) listOf(press) else {
                fun s(axis: StickAxis, half: AxisHalf = AxisHalf.FULL) = listOf(InputSource.TouchSwipe(control.id, axis, half))
                listOf(
                    press,
                    BindableInput("Swipe X", s(StickAxis.X), analog = true, swipe = true),
                    BindableInput("Swipe Y", s(StickAxis.Y), analog = true, swipe = true),
                    BindableInput("Swipe left", s(StickAxis.X, AxisHalf.NEGATIVE)),
                    BindableInput("Swipe right", s(StickAxis.X, AxisHalf.POSITIVE)),
                    BindableInput("Swipe up", s(StickAxis.Y, AxisHalf.NEGATIVE)),
                    BindableInput("Swipe down", s(StickAxis.Y, AxisHalf.POSITIVE)),
                )
            }
        }
        TouchKind.STICK -> {
            fun s(axis: StickAxis, half: AxisHalf = AxisHalf.FULL) = listOf(InputSource.TouchStick(control.id, axis, half))
            listOf(
                BindableInput("X axis", s(StickAxis.X), analog = true),
                BindableInput("Y axis", s(StickAxis.Y), analog = true),
                BindableInput("Left", s(StickAxis.X, AxisHalf.NEGATIVE)),
                BindableInput("Right", s(StickAxis.X, AxisHalf.POSITIVE)),
                BindableInput("Up", s(StickAxis.Y, AxisHalf.NEGATIVE)),
                BindableInput("Down", s(StickAxis.Y, AxisHalf.POSITIVE)),
            ) + if (control.zones != StickZones.EIGHT) emptyList() else StickDiagonal.entries.map {
                BindableInput(it.label, listOf(InputSource.TouchStickDiagonal(control.id, it)))
            }
        }
        TouchKind.FULLSCREEN -> emptyList()
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
    fun applyStickPreset(mappings: List<Mapping>, stickInputs: List<BindableInput>, preset: StickPreset): List<Mapping> {
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
