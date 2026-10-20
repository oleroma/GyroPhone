package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/** Body shape and labels used to draw a physical controller. */
@Serializable
enum class DiagramStyle { XBOX, PLAYSTATION, NINTENDO, GAMESIR_X5, GENERIC }

enum class PhysicalKind { FACE, SHOULDER, TRIGGER, STICK, DPAD, SYSTEM }

/** A control drawn on the diagram. Geometry is normalised to the diagram box (0..1 each axis; box aspect = ControllerDiagram.aspect). */
data class PhysicalControl(
    val id: String,
    val label: String,
    val kind: PhysicalKind,
    /** Centre, normalised. */
    val x: Float,
    val y: Float,
    /** Size, normalised to the box width / height. */
    val w: Float,
    val h: Float,
    /** Stick: X axis, Y axis, Left, Right, Up, Down, Click; D-pad: Up, Down, Left, Right; others: one input. */
    val inputs: List<BindableInput>,
)

/** Graphical model of a physical controller; [aspect] is box width / height. */
data class ControllerDiagram(val name: String, val style: DiagramStyle, val aspect: Float, val controls: List<PhysicalControl>)

/** Diagrams of attached controllers and their standard-action defaults. */
object ControllerDiagrams {

    /** Ids every diagram contains. */
    val standardIds: List<String> = listOf(
        "a", "b", "x", "y", "lb", "rb", "lt", "rt", "ls", "rs", "dpad", "select", "start", "home",
    )

    private class Labels(
        val south: String, val east: String, val west: String, val north: String,
        val lb: String, val rb: String, val lt: String, val rt: String,
        val select: String, val start: String, val home: String,
    )

    /** Positions in box fractions; sizes in units of box height (converted with the aspect). */
    private class Layout(
        val aspect: Float,
        val ls: Pair<Float, Float>, val rs: Pair<Float, Float>, val stick: Float,
        val dpad: Pair<Float, Float>, val dpadSize: Float,
        val face: Pair<Float, Float>, val faceSpread: Float, val faceSize: Float,
        val lb: Pair<Float, Float>, val rb: Pair<Float, Float>, val bumper: Pair<Float, Float>,
        val lt: Pair<Float, Float>, val rt: Pair<Float, Float>, val trigger: Pair<Float, Float>,
        val select: Pair<Float, Float>, val start: Pair<Float, Float>, val small: Float,
        val home: Pair<Float, Float>, val homeSize: Float,
    )

    private val xboxLabels = Labels("A", "B", "X", "Y", "LB", "RB", "LT", "RT", "View", "Menu", "Xbox")
    private val psLabels = Labels("Cross", "Circle", "Square", "Triangle", "L1", "R1", "L2", "R2", "Create", "Options", "PS")
    private val nintendoLabels = Labels("B", "A", "Y", "X", "L", "R", "ZL", "ZR", "−", "+", "Home")
    private val genericLabels = Labels("A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Select", "Start", "Home")

    // Xbox: left stick high, D-pad low; right stick low, ABXY high.
    private val xboxLayout = Layout(
        aspect = 1.45f,
        ls = 0.27f to 0.42f, rs = 0.62f to 0.66f, stick = 0.21f,
        dpad = 0.38f to 0.66f, dpadSize = 0.20f,
        face = 0.73f to 0.42f, faceSpread = 0.105f, faceSize = 0.095f,
        lb = 0.24f to 0.165f, rb = 0.76f to 0.165f, bumper = 0.24f to 0.065f,
        lt = 0.22f to 0.06f, rt = 0.78f to 0.06f, trigger = 0.14f to 0.10f,
        select = 0.43f to 0.42f, start = 0.57f to 0.42f, small = 0.065f,
        home = 0.5f to 0.29f, homeSize = 0.10f,
    )

    // DualSense: D-pad and face buttons high, symmetric sticks low, touchpad between.
    private val psLayout = Layout(
        aspect = 1.55f,
        ls = 0.37f to 0.66f, rs = 0.63f to 0.66f, stick = 0.20f,
        dpad = 0.24f to 0.42f, dpadSize = 0.21f,
        face = 0.76f to 0.42f, faceSpread = 0.11f, faceSize = 0.095f,
        lb = 0.24f to 0.165f, rb = 0.76f to 0.165f, bumper = 0.24f to 0.065f,
        lt = 0.24f to 0.06f, rt = 0.76f to 0.06f, trigger = 0.16f to 0.10f,
        select = 0.34f to 0.27f, start = 0.66f to 0.27f, small = 0.06f,
        home = 0.5f to 0.68f, homeSize = 0.07f,
    )

    // Pro Controller: Xbox-like offset sticks, −/+ above, Home right of centre.
    private val nintendoLayout = Layout(
        aspect = 1.45f,
        ls = 0.27f to 0.42f, rs = 0.62f to 0.66f, stick = 0.20f,
        dpad = 0.38f to 0.66f, dpadSize = 0.19f,
        face = 0.73f to 0.42f, faceSpread = 0.105f, faceSize = 0.095f,
        lb = 0.24f to 0.165f, rb = 0.76f to 0.165f, bumper = 0.24f to 0.065f,
        lt = 0.22f to 0.06f, rt = 0.78f to 0.06f, trigger = 0.14f to 0.10f,
        select = 0.41f to 0.30f, start = 0.59f to 0.30f, small = 0.06f,
        home = 0.56f to 0.44f, homeSize = 0.075f,
    )

    // Telescopic GameSir X5: two grips with the phone in between, shoulders along the top edge.
    private val x5Layout = Layout(
        aspect = 3.2f,
        ls = 0.085f to 0.38f, rs = 0.905f to 0.76f, stick = 0.26f,
        dpad = 0.095f to 0.76f, dpadSize = 0.26f,
        face = 0.905f to 0.38f, faceSpread = 0.13f, faceSize = 0.115f,
        lb = 0.12f to 0.075f, rb = 0.88f to 0.075f, bumper = 0.21f to 0.08f,
        lt = 0.045f to 0.075f, rt = 0.955f to 0.075f, trigger = 0.2f to 0.1f,
        select = 0.17f to 0.20f, start = 0.83f to 0.20f, small = 0.07f,
        home = 0.835f to 0.93f, homeSize = 0.075f,
    )

    private fun key(code: Int) = InputSource.Key(code)
    private fun axis(a: Int, half: AxisHalf = AxisHalf.FULL) = InputSource.Axis(a, half)

    private fun stickInputs(x: Int, y: Int, click: Int) = listOf(
        BindableInput("X axis", listOf(axis(x)), analog = true),
        BindableInput("Y axis", listOf(axis(y)), analog = true),
        BindableInput("Left", listOf(axis(x, AxisHalf.NEGATIVE))),
        BindableInput("Right", listOf(axis(x, AxisHalf.POSITIVE))),
        BindableInput("Up", listOf(axis(y, AxisHalf.NEGATIVE))),
        BindableInput("Down", listOf(axis(y, AxisHalf.POSITIVE))),
        BindableInput("Click", listOf(key(click))),
    )

    private val dpadInputs = listOf(
        BindableInput("Up", listOf(key(AndroidKey.DPAD_UP), axis(AndroidAxis.HAT_Y, AxisHalf.NEGATIVE))),
        BindableInput("Down", listOf(key(AndroidKey.DPAD_DOWN), axis(AndroidAxis.HAT_Y, AxisHalf.POSITIVE))),
        BindableInput("Left", listOf(key(AndroidKey.DPAD_LEFT), axis(AndroidAxis.HAT_X, AxisHalf.NEGATIVE))),
        BindableInput("Right", listOf(key(AndroidKey.DPAD_RIGHT), axis(AndroidAxis.HAT_X, AxisHalf.POSITIVE))),
    )

    private fun build(name: String, style: DiagramStyle, l: Layout, t: Labels): ControllerDiagram {
        val a = l.aspect
        fun round(id: String, label: String, kind: PhysicalKind, at: Pair<Float, Float>, d: Float, inputs: List<BindableInput>) =
            PhysicalControl(id, label, kind, at.first, at.second, d / a, d, inputs)
        fun rect(id: String, label: String, kind: PhysicalKind, at: Pair<Float, Float>, size: Pair<Float, Float>, inputs: List<BindableInput>) =
            PhysicalControl(id, label, kind, at.first, at.second, size.first / a, size.second, inputs)
        fun button(label: String, code: Int) = listOf(BindableInput(label, listOf(key(code))))
        fun face(id: String, label: String, code: Int, dx: Float, dy: Float) = round(
            id, label, PhysicalKind.FACE, (l.face.first + dx * l.faceSpread / a) to (l.face.second + dy * l.faceSpread),
            l.faceSize, button(label, code),
        )
        val controls = listOf(
            face("a", t.south, AndroidKey.BUTTON_A, 0f, 1f),
            face("b", t.east, AndroidKey.BUTTON_B, 1f, 0f),
            face("x", t.west, AndroidKey.BUTTON_X, -1f, 0f),
            face("y", t.north, AndroidKey.BUTTON_Y, 0f, -1f),
            rect("lb", t.lb, PhysicalKind.SHOULDER, l.lb, l.bumper, button(t.lb, AndroidKey.BUTTON_L1)),
            rect("rb", t.rb, PhysicalKind.SHOULDER, l.rb, l.bumper, button(t.rb, AndroidKey.BUTTON_R1)),
            rect(
                "lt", t.lt, PhysicalKind.TRIGGER, l.lt, l.trigger,
                listOf(BindableInput(t.lt, listOf(axis(AndroidAxis.LTRIGGER), axis(AndroidAxis.BRAKE), key(AndroidKey.BUTTON_L2)))),
            ),
            rect(
                "rt", t.rt, PhysicalKind.TRIGGER, l.rt, l.trigger,
                listOf(BindableInput(t.rt, listOf(axis(AndroidAxis.RTRIGGER), axis(AndroidAxis.GAS), key(AndroidKey.BUTTON_R2)))),
            ),
            round("ls", "LS", PhysicalKind.STICK, l.ls, l.stick, stickInputs(AndroidAxis.X, AndroidAxis.Y, AndroidKey.BUTTON_THUMBL)),
            round("rs", "RS", PhysicalKind.STICK, l.rs, l.stick, stickInputs(AndroidAxis.Z, AndroidAxis.RZ, AndroidKey.BUTTON_THUMBR)),
            round("dpad", "D-pad", PhysicalKind.DPAD, l.dpad, l.dpadSize, dpadInputs),
            round("select", t.select, PhysicalKind.SYSTEM, l.select, l.small, button(t.select, AndroidKey.BUTTON_SELECT)),
            round("start", t.start, PhysicalKind.SYSTEM, l.start, l.small, button(t.start, AndroidKey.BUTTON_START)),
            round("home", t.home, PhysicalKind.SYSTEM, l.home, l.homeSize, button(t.home, AndroidKey.BUTTON_MODE)),
        )
        return ControllerDiagram(name, style, a, controls)
    }

    fun of(style: DiagramStyle): ControllerDiagram = when (style) {
        DiagramStyle.XBOX -> build("Xbox controller", style, xboxLayout, xboxLabels)
        DiagramStyle.PLAYSTATION -> build("PlayStation controller", style, psLayout, psLabels)
        DiagramStyle.NINTENDO -> build("Nintendo controller", style, nintendoLayout, nintendoLabels)
        DiagramStyle.GAMESIR_X5 -> build("GameSir X5", style, x5Layout, xboxLabels)
        DiagramStyle.GENERIC -> build("Game controller", style, xboxLayout, genericLabels)
    }

    /** Extra BUTTON_1..BUTTON_16 keys a generic device may report, drawn along the bottom edge. */
    private fun extraButtons(keys: Set<Int>, aspect: Float): List<PhysicalControl> {
        val codes = (AndroidKey.BUTTON_1 until AndroidKey.BUTTON_1 + 16).filter { it in keys }
        if (codes.isEmpty()) return emptyList()
        val step = minOf(0.07f, 0.9f / codes.size)
        val d = minOf(0.07f, step * aspect * 0.9f)
        val start = 0.5f - step * (codes.size - 1) / 2f
        return codes.mapIndexed { i, code ->
            val n = code - AndroidKey.BUTTON_1 + 1
            PhysicalControl(
                "m$n", "M$n", PhysicalKind.FACE, start + i * step, 0.93f, d / aspect, d,
                listOf(BindableInput("M$n", listOf(InputSource.Key(code)))),
            )
        }
    }

    /** Diagram for an attached device; known=null -> GENERIC. supportedKeys/axes (empty = unknown) filter GENERIC controls whose sources the device doesn't have. */
    fun forDevice(known: KnownController?, supportedKeys: Set<Int> = emptySet(), supportedAxes: Set<Int> = emptySet()): ControllerDiagram {
        if (known != null) return of(known.diagram).copy(name = known.name)
        val base = of(DiagramStyle.GENERIC)
        fun supported(s: InputSource) = when (s) {
            is InputSource.Key -> supportedKeys.isEmpty() || s.keyCode in supportedKeys
            is InputSource.Axis -> supportedAxes.isEmpty() || s.axis in supportedAxes
            else -> true
        }
        val kept = base.controls.filter { c -> c.inputs.any { input -> input.sources.any(::supported) } }
        return base.copy(controls = kept + extraButtons(supportedKeys, base.aspect))
    }

    private val standardProfile by lazy { Profile("standard", "Standard", KnownLayouts.standardControllerMappings()) }

    /** Default (standard action) targets for a physical input, from KnownLayouts.standardControllerMappings. */
    fun defaultTargets(input: BindableInput): List<OutputTarget> = Bindings.targets(standardProfile, input)

    /** Resets one control of a profile to standard actions. */
    fun resetControl(profile: Profile, control: PhysicalControl): Profile =
        control.inputs.fold(profile) { p, input -> Bindings.setTargets(p, input, defaultTargets(input)) }

    /** Resets every control of [diagram] to standard actions. */
    fun resetAll(profile: Profile, diagram: ControllerDiagram): Profile =
        diagram.controls.fold(profile) { p, c -> resetControl(p, c) }
}
