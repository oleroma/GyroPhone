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
) {
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
 * A physical key's or button's own gesture timings ([InputSource.KeyGesture]); null = the
 * workspace's ([Workspace.longPressMs], [Workspace.doubleTapMs]).
 */
@Serializable
data class KeyTiming(val keyCode: Int, val longPressMs: Int? = null, val doubleTapMs: Int? = null)

/** The kinds of physical device a [DevicePreset] maps. */
enum class PhysicalDevice(val label: String) { CONTROLLER("Controller"), KEYBOARD("Keyboard"), MOUSE("Mouse") }

object DevicePresets {
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
