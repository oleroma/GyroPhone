package com.oleroma.gyrophone.core.model

import com.oleroma.gyrophone.core.hid.AndroidKeyToHid
import kotlinx.serialization.Serializable

/**
 * What the physical devices attached to the phone do: any game pad's buttons and sticks
 * ([controller]), a keyboard's keys ([keyboard]) and a mouse's buttons, wheel and movement
 * ([mouse]). Presets live beside the workspaces, not in them: a workspace pins one
 * ([Workspace.devicePreset]) and uses it while it is active; a workspace pinning none leaves the
 * devices doing nothing. Mappings use the workspace's layer numbers ([Mapping.set]).
 */
@Serializable
data class DevicePreset(
    val id: String,
    val name: String,
    val controller: List<Mapping> = emptyList(),
    val keyboard: List<Mapping> = emptyList(),
    val mouse: List<Mapping> = emptyList(),
    /** Keys and buttons (controller, keyboard, mouse) with gesture timings of their own. */
    val timings: List<KeyTiming> = emptyList(),
    /** The sticks' tuning by [PhysicalSticks] id ("ls", "rs"); one not listed has the defaults. */
    val sticks: Map<String, StickTuning> = emptyMap(),
    /** The triggers' tuning by [PhysicalSticks] id ("lt", "rt"); one not listed has the defaults. */
    val triggers: Map<String, TriggerTuning> = emptyMap(),
    /** Keys and buttons with settings of their own while held ([HeldButton]): gyro sensitivity and acceleration, touchpad speed. */
    val held: List<HeldButton> = emptyList(),
) {
    /** Control [id]'s settings while held, or null. */
    fun held(id: String): HeldButton? = held.firstOrNull { it.id == id }

    /** Control [id] (pressed by [sources]) with its settings in mapping set [set] [change]d; one left with none is dropped. */
    fun withHeld(id: String, sources: List<InputSource>, set: Int, change: (ButtonGyro) -> ButtonGyro): DevicePreset {
        val h = (held(id) ?: HeldButton(id, sources)).withGyro(set, change).copy(sources = sources)
        val rest = held.filter { it.id != id }
        return copy(held = if (h.gyro.isEmpty()) rest else rest + h)
    }

    /** Stick [id]'s tuning in effect. */
    fun stick(id: String): StickTuning = sticks[id] ?: StickTuning()

    /** Trigger [id]'s tuning in effect. */
    fun trigger(id: String): TriggerTuning = triggers[id] ?: TriggerTuning()

    fun withStick(id: String, t: StickTuning): DevicePreset =
        copy(sticks = if (t == StickTuning()) sticks - id else sticks + (id to t))

    fun withTrigger(id: String, t: TriggerTuning): DevicePreset =
        copy(triggers = if (t == TriggerTuning()) triggers - id else triggers + (id to t))

    /** [keyCode]'s own timings, or null: the workspace's. */
    fun timing(keyCode: Int): KeyTiming? = timings.firstOrNull { it.keyCode == keyCode }

    /** [keyCode] with its timings [change]d; one left with none of its own is dropped. */
    fun withTiming(keyCode: Int, change: (KeyTiming) -> KeyTiming): DevicePreset {
        val t = change(timing(keyCode) ?: KeyTiming(keyCode))
        val rest = timings.filter { it.keyCode != keyCode }
        return copy(timings = if (t.longPressMs == null && t.doubleTapMs == null) rest else rest + t)
    }

    fun mappings(device: PhysicalDevice): List<Mapping> = when (device) {
        PhysicalDevice.CONTROLLER -> controller
        PhysicalDevice.KEYBOARD -> keyboard
        PhysicalDevice.MOUSE -> mouse
    }

    fun withMappings(device: PhysicalDevice, m: List<Mapping>): DevicePreset = when (device) {
        PhysicalDevice.CONTROLLER -> copy(controller = m)
        PhysicalDevice.KEYBOARD -> copy(keyboard = m)
        PhysicalDevice.MOUSE -> copy(mouse = m)
    }
}

/**
 * A physical key's or button's own settings while held, per mapping set ([ButtonGyro], like a touch
 * button's [TouchControl.gyro]): [id] is its device and drawing's control ("CONTROLLER:lt"),
 * [sources] the inputs that press it (any of them held = it is held).
 */
@Serializable
data class HeldButton(val id: String, val sources: List<InputSource>, val gyro: List<ButtonGyro> = emptyList()) {
    /** Its settings with mapping sets [sets] in use (Base first): each set's own over the ones below. */
    fun gyroIn(sets: List<Int>): ButtonGyro =
        sets.fold(ButtonGyro()) { r, s -> gyro.firstOrNull { it.set == s }?.let { r.under(it) } ?: r }

    /** Copy with set [set]'s settings changed by [change] (one that sets nothing goes). */
    fun withGyro(set: Int, change: (ButtonGyro) -> ButtonGyro): HeldButton {
        val g = change(gyro.firstOrNull { it.set == set } ?: ButtonGyro(set))
        return copy(gyro = gyro.filterNot { it.set == set } + listOfNotNull(g.takeIf { it.any }))
    }
}

/**
 * A physical key's or button's own gesture timings ([InputSource.KeyGesture]); null = the
 * workspace's ([Workspace.longPressMs], [Workspace.doubleTapMs]).
 */
@Serializable
data class KeyTiming(val keyCode: Int, val longPressMs: Int? = null, val doubleTapMs: Int? = null)

/** How a stick's travel past its inner deadzone turns into output ([StickTuning.curve]). */
@Serializable
enum class StickCurve(val label: String) {
    LINEAR("Linear"),
    /** Finer near the centre: travel squared. */
    SOFT("Soft"),
    /** Quicker off the centre: the square root of travel. */
    AGGRESSIVE("Aggressive");

    fun apply(t: Float): Float = when (this) {
        LINEAR -> t
        SOFT -> t * t
        AGGRESSIVE -> kotlin.math.sqrt(t)
    }
}

/**
 * A physical stick's tuning, kept in its [DevicePreset]. The stick is read as one round vector:
 * nothing below [inner] (a share of full travel), full from [outer] on, travel between them
 * shaped by [curve] and scaled by [sensitivity]. Its directions (Left, Right, Up, Down) are pies
 * [pieX] (left and right) and [pieY] (up and down) degrees wide around their axis: wider than 90
 * overlap, so a diagonal presses both. [outerRing]: how far it is pushed (between the deadzones,
 * before the curve) for its Outer inputs ([AxisStage.OUTER]).
 */
@Serializable
data class StickTuning(
    val inner: Float = 0.10f,
    val outer: Float = 0.95f,
    val curve: StickCurve = StickCurve.LINEAR,
    val sensitivity: Float = 1f,
    val outerRing: Float = 0.85f,
    val pieX: Float = 135f,
    val pieY: Float = 135f,
) {
    companion object {
        const val MIN_PIE = 30f
        const val MAX_PIE = 180f
    }
}

/**
 * A physical trigger's tuning, kept in its [DevicePreset]: its Press ([AxisStage.PRESS]) is on from
 * [press] of its pull, its Full pull ([AxisStage.FULL_PULL]) from [full].
 */
@Serializable
data class TriggerTuning(val press: Float = 0.5f, val full: Float = 0.95f)

/** The sticks and triggers a [DevicePreset] tunes, by id, with their axes. */
object PhysicalSticks {
    /** Stick id to its X and Y axes. */
    val sticks: Map<String, Pair<Int, Int>> = linkedMapOf(
        "ls" to (AndroidAxis.X to AndroidAxis.Y),
        "rs" to (AndroidAxis.Z to AndroidAxis.RZ),
    )

    /** [sticks] on a pad whose right stick is RX / RY ([rightStickOnRx]); its Z / RZ are then triggers. */
    val sticksRx: Map<String, Pair<Int, Int>> = linkedMapOf(
        "ls" to (AndroidAxis.X to AndroidAxis.Y),
        "rs" to (AndroidAxis.RX to AndroidAxis.RY),
    )

    /**
     * Whether a pad puts its right stick on RX / RY ([has]: it reports that axis). Pads on
     * Generic.kl do (and sometimes put triggers on Z / RZ); Z / RZ are the stick only without
     * RX / RY, or when the triggers have axes of their own (LTRIGGER, RTRIGGER, GAS, BRAKE).
     */
    fun rightStickOnRx(has: (Int) -> Boolean): Boolean {
        val triggers = has(AndroidAxis.LTRIGGER) || has(AndroidAxis.RTRIGGER) || has(AndroidAxis.GAS) || has(AndroidAxis.BRAKE)
        val zStick = has(AndroidAxis.Z) && has(AndroidAxis.RZ) &&
            (!has(AndroidAxis.RX) || !has(AndroidAxis.RY) || triggers)
        return !zStick && has(AndroidAxis.RX) && has(AndroidAxis.RY)
    }

    /** Trigger id to the axes its pull may come as. */
    val triggers: Map<String, Pair<Int, Int>> = linkedMapOf(
        "lt" to (AndroidAxis.LTRIGGER to AndroidAxis.BRAKE),
        "rt" to (AndroidAxis.RTRIGGER to AndroidAxis.GAS),
    )
}

/** The kinds of physical device a [DevicePreset] maps. */
enum class PhysicalDevice(val label: String) { CONTROLLER("Controller"), KEYBOARD("Keyboard"), MOUSE("Mouse") }

object DevicePresets {
    /** The id prefix of a physical control's stand-in in the editors ("dev:CONTROLLER:a"). */
    private const val STAND_IN_PREFIX = "dev:CONTROLLER:"

    /**
     * [p] with the mappings an older editor saved on a controller button's touch-button stand-in
     * (InputSource.Touch "dev:CONTROLLER:<id>", which never fire) moved to that button's press;
     * the stand-in's other touch and surface inputs are dropped.
     */
    fun fixStandIns(p: DevicePreset): DevicePreset {
        fun standIn(s: InputSource): String? = when (s) {
            is InputSource.Touch -> s.id
            is InputSource.TouchTap -> s.id
            is InputSource.TouchDoubleTap -> s.id
            is InputSource.TouchLongPress -> s.id
            is InputSource.TouchRelease -> s.id
            is InputSource.TouchArea -> s.id
            is InputSource.Surface -> s.id
            else -> null
        }?.takeIf { it.startsWith(STAND_IN_PREFIX) }
        if (p.controller.none { standIn(it.source) != null }) return p
        val controls = (PhysicalLayouts.controllerFront.controls + PhysicalLayouts.controllerBack.controls).associateBy { it.id }
        val fixed = p.controller.flatMap { m ->
            val id = standIn(m.source) ?: return@flatMap listOf(m)
            if (m.source !is InputSource.Touch) return@flatMap emptyList()
            // A stick's press is its Click; a button's its first input (Press), a trigger's its Pull.
            val inputs = controls[id.removePrefix(STAND_IN_PREFIX)]?.inputs.orEmpty()
            val press = inputs.firstOrNull { it.label == "Click" } ?: inputs.firstOrNull()
                ?: return@flatMap emptyList()
            press.sources.map { m.copy(source = it) }
        }.distinct()
        return p.copy(controller = fixed)
    }

    fun newId(taken: Collection<String>): String {
        var n = 1
        while ("preset$n" in taken) n++
        return "preset$n"
    }

    /** "Preset 1", "Preset 2"…: the first not in [taken]. */
    fun newName(taken: Collection<String>): String {
        var n = 1
        while ("Preset $n" in taken) n++
        return "Preset $n"
    }

    /** A new preset where every device does what it is: pad to pad, keys to the same keys, mouse to mouse. */
    fun standard(id: String, name: String): DevicePreset =
        DevicePreset(id, name, KnownLayouts.standardControllerMappings(), standardKeyboard(), standardMouse())

    /** Every key of a keyboard to the same key on the PC. */
    fun standardKeyboard(): List<Mapping> =
        PhysicalLayouts.keyboardRows.flatten().mapNotNull { k ->
            val code = k.code ?: return@mapNotNull null
            AndroidKeyToHid.usage(code)?.let { Mapping(InputSource.Key(code), OutputTarget.Key(it)) }
        }.distinct()

    /** Mouse buttons, wheel and movement to the same on the PC. */
    fun standardMouse(): List<Mapping> = listOf(
        Mapping(InputSource.Key(AndroidKey.MOUSE_LEFT), OutputTarget.Mouse(MouseButton.LEFT)),
        Mapping(InputSource.Key(AndroidKey.MOUSE_RIGHT), OutputTarget.Mouse(MouseButton.RIGHT)),
        Mapping(InputSource.Key(AndroidKey.MOUSE_MIDDLE), OutputTarget.Mouse(MouseButton.MIDDLE)),
        Mapping(InputSource.Key(AndroidKey.MOUSE_BACK), OutputTarget.Mouse(MouseButton.BACK)),
        Mapping(InputSource.Key(AndroidKey.MOUSE_FORWARD), OutputTarget.Mouse(MouseButton.FORWARD)),
        Mapping(InputSource.Key(AndroidKey.MOUSE_WHEEL_UP), OutputTarget.Wheel(1)),
        Mapping(InputSource.Key(AndroidKey.MOUSE_WHEEL_DOWN), OutputTarget.Wheel(-1)),
        Mapping(InputSource.Axis(AndroidAxis.MOUSE_X), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
        Mapping(InputSource.Axis(AndroidAxis.MOUSE_Y), OutputTarget.MouseAxis(MouseMotion.MOVE_Y)),
    )

    /** The ids of the workspaces of [set] that pin preset [id]. */
    fun pinnedBy(set: Profile, id: String): List<String> =
        set.workspaces.filter { it.devicePreset == id }.map { it.id }

    /** [set] with [preset] added or, by id, replaced. */
    fun put(set: Profile, preset: DevicePreset): Profile {
        val i = set.devicePresets.indexOfFirst { it.id == preset.id }
        return set.copy(
            devicePresets = if (i < 0) set.devicePresets + preset
            else set.devicePresets.mapIndexed { k, p -> if (k == i) preset else p },
        )
    }

    /** [set] without preset [id]; the workspaces that pinned it pin none. */
    fun remove(set: Profile, id: String): Profile = set.copy(
        devicePresets = set.devicePresets.filterNot { it.id == id },
        workspaces = set.workspaces.map { if (it.devicePreset == id) it.copy(devicePreset = null) else it },
    )

    /** [set] with exactly the workspaces [workspaceIds] pinning preset [id] (they leave their old one). */
    fun pin(set: Profile, id: String, workspaceIds: Set<String>): Profile = set.copy(
        workspaces = set.workspaces.map { w ->
            when {
                w.id in workspaceIds -> w.copy(devicePreset = id)
                w.devicePreset == id -> w.copy(devicePreset = null)
                else -> w
            }
        },
    )
}

/**
 * The physical devices as the device screens draw them, each control with the inputs its mapping
 * screen shows. Positions are fractions of a box of the given aspect (width / height).
 */
object PhysicalLayouts {
    /** A key of the keyboard drawing: [code] null is a gap; [width] in key units. */
    data class KeyCell(val code: Int?, val label: String, val width: Float = 1f)

    /** A button's press and, from its first key, its tap, double tap, long press and release. */
    fun buttonInputs(codes: List<Int>, press: String = "Press"): List<BindableInput> {
        val keys = codes.map { InputSource.Key(it) }
        return listOf(BindableInput(press, keys)) + ButtonGesture.entries.map { g ->
            BindableInput(g.label, codes.map { InputSource.KeyGesture(it, g) })
        }
    }

    private fun stickInputs(x: Int, y: Int, click: Int) = listOf(
        BindableInput("X axis", listOf(InputSource.Axis(x)), analog = true),
        BindableInput("Y axis", listOf(InputSource.Axis(y)), analog = true),
        BindableInput("Left", listOf(InputSource.Axis(x, AxisHalf.NEGATIVE))),
        BindableInput("Right", listOf(InputSource.Axis(x, AxisHalf.POSITIVE))),
        BindableInput("Up", listOf(InputSource.Axis(y, AxisHalf.NEGATIVE))),
        BindableInput("Down", listOf(InputSource.Axis(y, AxisHalf.POSITIVE))),
        BindableInput("Outer", listOf(InputSource.Axis(x, stage = AxisStage.OUTER))),
        BindableInput("Outer left", listOf(InputSource.Axis(x, AxisHalf.NEGATIVE, AxisStage.OUTER))),
        BindableInput("Outer right", listOf(InputSource.Axis(x, AxisHalf.POSITIVE, AxisStage.OUTER))),
        BindableInput("Outer up", listOf(InputSource.Axis(y, AxisHalf.NEGATIVE, AxisStage.OUTER))),
        BindableInput("Outer down", listOf(InputSource.Axis(y, AxisHalf.POSITIVE, AxisStage.OUTER))),
    ) + buttonInputs(listOf(click), "Click").let { b -> b.map { if (it.label == "Click") it else it.copy(label = "Click " + it.label.lowercase()) } }

    /** A D-pad direction: its key (with gestures) and the hat axis half some pads report instead. */
    private fun dpad(label: String, code: Int, hat: Int, half: AxisHalf): List<BindableInput> {
        val b = buttonInputs(listOf(code))
        return listOf(b[0].copy(sources = b[0].sources + InputSource.Axis(hat, half))) + b.drop(1)
    }

    /** The back buttons (P1 and P2 on the right grip, P3 and P4 on the left) and the keys each may come as. */
    val paddles: Map<String, List<Int>> = mapOf(
        "p1" to listOf(AndroidKey.BUTTON_C, AndroidKey.BUTTON_1),
        "p2" to listOf(AndroidKey.BUTTON_Z, AndroidKey.BUTTON_1 + 1),
        "p3" to listOf(AndroidKey.BUTTON_1 + 2),
        "p4" to listOf(AndroidKey.BUTTON_1 + 3),
    )

    /** The controller drawings' box: width / height (a symmetric-stick gamepad seen from above). */
    const val CONTROLLER_ASPECT = 1.5f

    /** The top view's touchpad, drawn but not mapped: left, top, right, bottom. */
    val TOUCHPAD = listOf(0.335f, 0.06f, 0.665f, 0.40f)

    /** The controller's top, laid out symmetrically: D-pad and face buttons high, both sticks low. */
    val controllerFront: ControllerDiagram = run {
        val a = CONTROLLER_ASPECT
        fun round(id: String, label: String, kind: PhysicalKind, x: Float, y: Float, d: Float, inputs: List<BindableInput>) =
            PhysicalControl(id, label, kind, x, y, d / a, d, inputs)
        fun face(id: String, label: String, code: Int, dx: Float, dy: Float) =
            round(id, label, PhysicalKind.FACE, 0.80f + dx * 0.065f, 0.34f + dy * 0.065f * a, 0.088f, buttonInputs(listOf(code)))
        // D-pad: four keys around its centre, each a square of side [k].
        val (cx, cy, k) = Triple(0.20f, 0.34f, 0.088f)
        fun pad(id: String, label: String, code: Int, dx: Float, dy: Float, hat: Int, half: AxisHalf) = PhysicalControl(
            id, label, PhysicalKind.DPAD, cx + dx * k / a, cy + dy * k, k / a, k, dpad(label, code, hat, half),
        )
        ControllerDiagram(
            "Top", DiagramStyle.XBOX, a,
            listOf(
                face("a", "A", AndroidKey.BUTTON_A, 0f, 1f),
                face("b", "B", AndroidKey.BUTTON_B, 1f, 0f),
                face("x", "X", AndroidKey.BUTTON_X, -1f, 0f),
                face("y", "Y", AndroidKey.BUTTON_Y, 0f, -1f),
                round("ls", "LS", PhysicalKind.STICK, 0.35f, 0.56f, 0.20f, stickInputs(AndroidAxis.X, AndroidAxis.Y, AndroidKey.BUTTON_THUMBL)),
                round("rs", "RS", PhysicalKind.STICK, 0.65f, 0.56f, 0.20f, stickInputs(AndroidAxis.Z, AndroidAxis.RZ, AndroidKey.BUTTON_THUMBR)),
                pad("up", "↑", AndroidKey.DPAD_UP, 0f, -1f, AndroidAxis.HAT_Y, AxisHalf.NEGATIVE),
                pad("down", "↓", AndroidKey.DPAD_DOWN, 0f, 1f, AndroidAxis.HAT_Y, AxisHalf.POSITIVE),
                pad("left", "←", AndroidKey.DPAD_LEFT, -1f, 0f, AndroidAxis.HAT_X, AxisHalf.NEGATIVE),
                pad("right", "→", AndroidKey.DPAD_RIGHT, 1f, 0f, AndroidAxis.HAT_X, AxisHalf.POSITIVE),
                round("select", "View", PhysicalKind.SYSTEM, 0.29f, 0.14f, 0.08f, buttonInputs(listOf(AndroidKey.BUTTON_SELECT))),
                round("start", "Menu", PhysicalKind.SYSTEM, 0.71f, 0.14f, 0.08f, buttonInputs(listOf(AndroidKey.BUTTON_START))),
                round("home", "Home", PhysicalKind.SYSTEM, 0.5f, 0.56f, 0.085f, buttonInputs(listOf(AndroidKey.BUTTON_MODE))),
            ),
        )
    }

    /** The controller's underside, as held: triggers and bumpers along the top edge, four back buttons on the grips. */
    val controllerBack: ControllerDiagram = run {
        val a = CONTROLLER_ASPECT
        fun rect(id: String, label: String, kind: PhysicalKind, x: Float, y: Float, w: Float, h: Float, inputs: List<BindableInput>) =
            PhysicalControl(id, label, kind, x, y, w / a, h, inputs)
        fun trigger(code: Int, axis: Int, alt: Int) = listOf(
            BindableInput("Pull", listOf(InputSource.Axis(axis), InputSource.Axis(alt), InputSource.Key(code))),
            BindableInput("Press", listOf(InputSource.Axis(axis, stage = AxisStage.PRESS), InputSource.Axis(alt, stage = AxisStage.PRESS))),
            BindableInput("Full pull", listOf(InputSource.Axis(axis, stage = AxisStage.FULL_PULL), InputSource.Axis(alt, stage = AxisStage.FULL_PULL))),
        ) + ButtonGesture.entries.map { g -> BindableInput(g.label, listOf(InputSource.KeyGesture(code, g))) }
        fun paddle(id: String, x: Float, y: Float) =
            rect(id, id.uppercase(), PhysicalKind.FACE, x, y, 0.15f, 0.15f, buttonInputs(paddles.getValue(id)))
        ControllerDiagram(
            "Back", DiagramStyle.XBOX, a,
            listOf(
                rect("lt", "LT", PhysicalKind.TRIGGER, 0.20f, 0.14f, 0.27f, 0.14f, trigger(AndroidKey.BUTTON_L2, AndroidAxis.LTRIGGER, AndroidAxis.BRAKE)),
                rect("rt", "RT", PhysicalKind.TRIGGER, 0.80f, 0.14f, 0.27f, 0.14f, trigger(AndroidKey.BUTTON_R2, AndroidAxis.RTRIGGER, AndroidAxis.GAS)),
                rect("lb", "LB", PhysicalKind.SHOULDER, 0.22f, 0.30f, 0.33f, 0.08f, buttonInputs(listOf(AndroidKey.BUTTON_L1))),
                rect("rb", "RB", PhysicalKind.SHOULDER, 0.78f, 0.30f, 0.33f, 0.08f, buttonInputs(listOf(AndroidKey.BUTTON_R1))),
                paddle("p3", 0.11f, 0.56f),
                paddle("p4", 0.15f, 0.80f),
                paddle("p1", 0.89f, 0.56f),
                paddle("p2", 0.85f, 0.80f),
            ),
        )
    }

    // Android key codes of a full keyboard.
    private fun letter(c: Char) = KeyCell(29 + (c - 'a'), c.uppercase())
    private fun gap(width: Float) = KeyCell(null, "", width)

    /**
     * A full-size keyboard, 23 key units wide: the main block (15), then after a half-unit gap
     * the navigation keys (3) and after another the number pad (4). The pad's + and Enter take one
     * row each, with a gap under them.
     */
    val keyboardRows: List<List<KeyCell>> = listOf(
        listOf(KeyCell(111, "Esc"), gap(1f)) + (1..4).map { KeyCell(130 + it, "F$it") } + gap(0.5f) +
            (5..8).map { KeyCell(130 + it, "F$it") } + gap(0.5f) + (9..12).map { KeyCell(130 + it, "F$it") } +
            listOf(gap(0.5f), KeyCell(120, "PrtSc"), KeyCell(116, "ScrLk"), KeyCell(121, "Pause")),
        listOf(KeyCell(68, "`")) + (1..9).map { KeyCell(7 + it, "$it") } + listOf(
            KeyCell(7, "0"), KeyCell(69, "-"), KeyCell(70, "="), KeyCell(67, "Bksp", 2f), gap(0.5f),
            KeyCell(124, "Ins"), KeyCell(122, "Home"), KeyCell(92, "PgUp"), gap(0.5f),
            KeyCell(143, "Num"), KeyCell(154, "/"), KeyCell(155, "*"), KeyCell(156, "−"),
        ),
        listOf(KeyCell(61, "Tab", 1.5f)) + "qwertyuiop".map(::letter) + listOf(
            KeyCell(71, "["), KeyCell(72, "]"), KeyCell(73, "\\", 1.5f), gap(0.5f),
            KeyCell(112, "Del"), KeyCell(123, "End"), KeyCell(93, "PgDn"), gap(0.5f),
            KeyCell(151, "7"), KeyCell(152, "8"), KeyCell(153, "9"), KeyCell(157, "+"),
        ),
        listOf(KeyCell(115, "Caps", 1.75f)) + "asdfghjkl".map(::letter) + listOf(
            KeyCell(74, ";"), KeyCell(75, "'"), KeyCell(66, "Enter", 2.25f), gap(4f),
            KeyCell(148, "4"), KeyCell(149, "5"), KeyCell(150, "6"),
        ),
        listOf(KeyCell(59, "Shift", 2.25f)) + "zxcvbnm".map(::letter) + listOf(
            KeyCell(55, ","), KeyCell(56, "."), KeyCell(76, "/"), KeyCell(60, "Shift", 2.75f), gap(1.5f),
            KeyCell(19, "↑"), gap(1.5f), KeyCell(145, "1"), KeyCell(146, "2"), KeyCell(147, "3"), KeyCell(160, "Enter"),
        ),
        listOf(
            KeyCell(113, "Ctrl", 1.25f), KeyCell(117, "Win", 1.25f), KeyCell(57, "Alt", 1.25f), KeyCell(62, "Space", 6.25f),
            KeyCell(58, "Alt", 1.25f), KeyCell(118, "Win", 1.25f), KeyCell(82, "Menu", 1.25f), KeyCell(114, "Ctrl", 1.25f),
            gap(0.5f), KeyCell(21, "←"), KeyCell(20, "↓"), KeyCell(22, "→"), gap(0.5f),
            KeyCell(144, "0", 2f), KeyCell(158, "."),
        ),
    )

    /** How many key units [keyboardRows] spans. */
    const val KEYBOARD_UNITS = 23f

    /** A mouse control: [x], [y], [w], [h] are fractions of a box of aspect [MOUSE_ASPECT]. */
    data class MouseControl(val id: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val inputs: List<BindableInput>)

    const val MOUSE_ASPECT = 0.8f

    val mouseControls: List<MouseControl> = listOf(
        MouseControl("left", "Left", 0.31f, 0.2f, 0.27f, 0.3f, buttonInputs(listOf(AndroidKey.MOUSE_LEFT))),
        MouseControl("right", "Right", 0.69f, 0.2f, 0.27f, 0.3f, buttonInputs(listOf(AndroidKey.MOUSE_RIGHT))),
        MouseControl(
            "wheel", "Wheel", 0.5f, 0.2f, 0.09f, 0.22f,
            listOf(
                BindableInput("Wheel up", listOf(InputSource.Key(AndroidKey.MOUSE_WHEEL_UP))),
                BindableInput("Wheel down", listOf(InputSource.Key(AndroidKey.MOUSE_WHEEL_DOWN))),
                BindableInput("Tilt left", listOf(InputSource.Key(AndroidKey.MOUSE_WHEEL_LEFT))),
                BindableInput("Tilt right", listOf(InputSource.Key(AndroidKey.MOUSE_WHEEL_RIGHT))),
            ) + buttonInputs(listOf(AndroidKey.MOUSE_MIDDLE), "Click").map {
                if (it.label == "Click") it else it.copy(label = "Click " + it.label.lowercase())
            },
        ),
        MouseControl("forward", "5", 0.13f, 0.47f, 0.07f, 0.11f, buttonInputs(listOf(AndroidKey.MOUSE_FORWARD))),
        MouseControl("back", "4", 0.13f, 0.6f, 0.07f, 0.11f, buttonInputs(listOf(AndroidKey.MOUSE_BACK))),
        MouseControl(
            "move", "Move", 0.5f, 0.66f, 0.3f, 0.24f,
            listOf(
                BindableInput("Move X", listOf(InputSource.Axis(AndroidAxis.MOUSE_X)), mouseMotion = true),
                BindableInput("Move Y", listOf(InputSource.Axis(AndroidAxis.MOUSE_Y)), mouseMotion = true),
            ),
        ),
    )
}
