package com.oleroma.gyrophone.core.model

/** One remappable input as the editor shows it: a label and the phone-side sources it stands for. */
data class BindableInput(
    val label: String,
    /** sources[0] is canonical; edits write mappings for every source (e.g. LT = LTRIGGER axis, BRAKE axis, L2 key). */
    val sources: List<InputSource>,
    /** True for a full analog stick axis: only PadAxis targets make sense. */
    val analog: Boolean = false,
)

/** Editing helpers that read and rewrite a profile's mappings per [BindableInput]. */
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

    /** Distinct targets mapped from any of input.sources, in profile order. */
    fun targets(profile: Profile, input: BindableInput): List<OutputTarget> =
        profile.mappings.filter { belongs(it, input) }.map { it.target }.distinct()

    /**
     * Replaces every mapping whose source is one of input.sources with sources x targets. Keeps
     * other mappings and their order; new ones go where the first removed one was, else at the end.
     */
    fun setTargets(profile: Profile, input: BindableInput, targets: List<OutputTarget>): Profile {
        val first = profile.mappings.indexOfFirst { belongs(it, input) }
        val kept = profile.mappings.filterNot { belongs(it, input) }
        val added = input.sources.flatMap { s -> targets.distinct().map { Mapping(s, it) } }
        val at = if (first < 0) kept.size else first
        val mappings = kept.subList(0, at) + added + kept.subList(at, kept.size)
        return profile.copy(mappings = mappings)
    }

    fun addTarget(profile: Profile, input: BindableInput, target: OutputTarget): Profile {
        val current = targets(profile, input)
        return if (target in current) profile else setTargets(profile, input, current + target)
    }

    fun removeTarget(profile: Profile, input: BindableInput, target: OutputTarget): Profile =
        setTargets(profile, input, targets(profile, input) - target)

    /** Inputs a touch control exposes: BUTTON -> [Touch(id)]; STICK -> X axis, Y axis (analog), Left, Right, Up, Down (halves). */
    fun touchInputs(control: TouchControl): List<BindableInput> = when (control.kind) {
        TouchKind.BUTTON -> listOf(BindableInput(control.label.ifBlank { control.id }, listOf(InputSource.Touch(control.id))))
        TouchKind.STICK -> {
            fun s(axis: StickAxis, half: AxisHalf = AxisHalf.FULL) = listOf(InputSource.TouchStick(control.id, axis, half))
            listOf(
                BindableInput("X axis", s(StickAxis.X), analog = true),
                BindableInput("Y axis", s(StickAxis.Y), analog = true),
                BindableInput("Left", s(StickAxis.X, AxisHalf.NEGATIVE)),
                BindableInput("Right", s(StickAxis.X, AxisHalf.POSITIVE)),
                BindableInput("Up", s(StickAxis.Y, AxisHalf.NEGATIVE)),
                BindableInput("Down", s(StickAxis.Y, AxisHalf.POSITIVE)),
            )
        }
    }

    /** Drops a touch control and all of its mappings (on delete). */
    fun removeTouchControl(profile: Profile, id: String): Profile = profile.copy(
        touchControls = profile.touchControls.filterNot { it.id == id },
        mappings = profile.mappings.filterNot {
            val s = it.source
            (s is InputSource.Touch && s.id == id) || (s is InputSource.TouchStick && s.id == id)
        },
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
     * [touchInputs] or a physical stick's inputs; extra inputs are left alone) for a preset.
     */
    fun applyStickPreset(profile: Profile, stickInputs: List<BindableInput>, preset: StickPreset): Profile {
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
        var p = profile
        for (i in 0 until 6) p = setTargets(p, stickInputs[i], plan[i])
        return p
    }
}
