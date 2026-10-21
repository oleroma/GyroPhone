package com.oleroma.gyrophone.core.model

import com.oleroma.gyrophone.core.hid.HidUsage

/** Profiles that ship with the app. Users copy and edit them; the originals stay read-only. */
object BuiltinProfiles {

    /**
     * Landscape placements of the Virtual gamepad builtin from before workspaces (see
     * [builtinPortrait]). Kept so copies saved back then still get their designed portrait
     * layout; the Controller preset ([controllerWorkspace]) has its own placements.
     */
    val virtualGamepadControls: List<TouchControl> = listOf(
        TouchControl("ls", TouchKind.STICK, "LS", 0.14f, 0.62f, 0.38f),
        TouchControl("rs", TouchKind.STICK, "RS", 0.66f, 0.74f, 0.30f),
        TouchControl("a", TouchKind.BUTTON, "A", 0.88f, 0.70f),
        TouchControl("b", TouchKind.BUTTON, "B", 0.95f, 0.52f),
        TouchControl("x", TouchKind.BUTTON, "X", 0.81f, 0.52f),
        TouchControl("y", TouchKind.BUTTON, "Y", 0.88f, 0.34f),
        TouchControl("up", TouchKind.BUTTON, "↑", 0.34f, 0.62f, 0.12f, TouchShape.SQUARE),
        TouchControl("down", TouchKind.BUTTON, "↓", 0.34f, 0.86f, 0.12f, TouchShape.SQUARE),
        TouchControl("left", TouchKind.BUTTON, "←", 0.28f, 0.74f, 0.12f, TouchShape.SQUARE),
        TouchControl("right", TouchKind.BUTTON, "→", 0.40f, 0.74f, 0.12f, TouchShape.SQUARE),
        TouchControl("lb", TouchKind.BUTTON, "LB", 0.10f, 0.14f, 0.13f, TouchShape.SQUARE),
        TouchControl("lt", TouchKind.BUTTON, "LT", 0.22f, 0.14f, 0.13f, TouchShape.SQUARE),
        TouchControl("rb", TouchKind.BUTTON, "RB", 0.90f, 0.14f, 0.13f, TouchShape.SQUARE),
        TouchControl("rt", TouchKind.BUTTON, "RT", 0.78f, 0.14f, 0.13f, TouchShape.SQUARE),
        TouchControl("view", TouchKind.BUTTON, "View", 0.42f, 0.30f, 0.10f, TouchShape.SQUARE),
        TouchControl("menu", TouchKind.BUTTON, "Menu", 0.58f, 0.30f, 0.10f, TouchShape.SQUARE),
        TouchControl("gyro", TouchKind.BUTTON, "Gyro", 0.50f, 0.52f, 0.12f),
    )

    /** Portrait placements of [virtualGamepadControls]: controls in the lower half, touchpad space above. */
    internal val virtualGamepadPortrait: Map<String, Placement> = mapOf(
        "lb" to Placement(0.11f, 0.12f, 0.18f), "lt" to Placement(0.31f, 0.12f, 0.18f),
        "rt" to Placement(0.69f, 0.12f, 0.18f), "rb" to Placement(0.89f, 0.12f, 0.18f),
        "view" to Placement(0.30f, 0.54f, 0.13f), "gyro" to Placement(0.50f, 0.54f, 0.16f),
        "menu" to Placement(0.70f, 0.54f, 0.13f),
        "ls" to Placement(0.25f, 0.70f, 0.40f),
        "x" to Placement(0.61f, 0.70f, 0.14f), "y" to Placement(0.76f, 0.628f, 0.14f),
        "b" to Placement(0.91f, 0.70f, 0.14f), "a" to Placement(0.76f, 0.772f, 0.14f),
        "up" to Placement(0.22f, 0.845f, 0.11f), "down" to Placement(0.22f, 0.955f, 0.11f),
        "left" to Placement(0.10f, 0.90f, 0.11f), "right" to Placement(0.34f, 0.90f, 0.11f),
        "rs" to Placement(0.70f, 0.90f, 0.26f),
    )

    private val virtualGamepadById: Map<String, TouchControl> = virtualGamepadControls.associateBy { it.id }

    /**
     * The builtin portrait placement of [c] if it is a Virtual gamepad control still at its
     * builtin landscape placement, else null. Gives copies saved before portrait existed the
     * designed portrait layout instead of a derived one. Allocation-free.
     */
    internal fun builtinPortrait(c: TouchControl): Placement? {
        val b = virtualGamepadById[c.id] ?: return null
        if (b.kind != c.kind || b.x != c.x || b.y != c.y || b.size != c.size) return null
        return virtualGamepadPortrait[c.id]
    }

    private fun p(x: Float, y: Float, size: Float) = Placement(x, y, size)

    /** A control with explicit landscape and portrait placements. */
    private fun control(
        id: String, kind: TouchKind, label: String, land: Placement, port: Placement,
        shape: TouchShape = TouchShape.CIRCLE,
    ) = TouchControl(id, kind, label, land.x, land.y, land.size, shape, port)

    private fun touch(id: String, target: OutputTarget) = Mapping(InputSource.Touch(id), target)

    private fun anyPad(mappings: List<Mapping>) = AttachedPad(id = "pad1", mappings = mappings)

    // Controller

    /**
     * Emulator-overlay game pad. Landscape: left stick lower left with the d-pad below and inward
     * of it, ABXY lower right with the right stick below and inward of it, shoulders under the
     * corner arrows, View / Menu top centre, Gyro between the right stick and ABXY. Portrait: the
     * same groups in the lower half, shoulders at the top.
     */
    private val controllerControls: List<TouchControl> = run {
        val b = TouchKind.BUTTON
        val sq = TouchShape.SQUARE
        listOf(
            control("ls", TouchKind.STICK, "LS", p(0.13f, 0.50f, 0.36f), p(0.25f, 0.70f, 0.40f)),
            control("rs", TouchKind.STICK, "RS", p(0.70f, 0.78f, 0.30f), p(0.70f, 0.90f, 0.26f)),
            control("a", b, "A", p(0.87f, 0.71f, 0.14f), p(0.76f, 0.772f, 0.14f)),
            control("b", b, "B", p(0.945f, 0.56f, 0.14f), p(0.91f, 0.70f, 0.14f)),
            control("x", b, "X", p(0.795f, 0.56f, 0.14f), p(0.61f, 0.70f, 0.14f)),
            control("y", b, "Y", p(0.87f, 0.41f, 0.14f), p(0.76f, 0.628f, 0.14f)),
            control("up", b, "↑", p(0.28f, 0.675f, 0.12f), p(0.22f, 0.845f, 0.11f), sq),
            control("down", b, "↓", p(0.28f, 0.925f, 0.12f), p(0.22f, 0.955f, 0.11f), sq),
            control("left", b, "←", p(0.2175f, 0.80f, 0.12f), p(0.10f, 0.90f, 0.11f), sq),
            control("right", b, "→", p(0.3425f, 0.80f, 0.12f), p(0.34f, 0.90f, 0.11f), sq),
            control("lb", b, "LB", p(0.05f, 0.19f, 0.12f), p(0.11f, 0.12f, 0.18f), sq),
            control("lt", b, "LT", p(0.12f, 0.19f, 0.12f), p(0.31f, 0.12f, 0.18f), sq),
            control("rb", b, "RB", p(0.95f, 0.19f, 0.12f), p(0.89f, 0.12f, 0.18f), sq),
            control("rt", b, "RT", p(0.88f, 0.19f, 0.12f), p(0.69f, 0.12f, 0.18f), sq),
            control("view", b, "View", p(0.44f, 0.13f, 0.10f), p(0.38f, 0.54f, 0.13f), sq),
            control("menu", b, "Menu", p(0.56f, 0.13f, 0.10f), p(0.62f, 0.54f, 0.13f), sq),
            control("gyro", b, "Gyro", p(0.66f, 0.47f, 0.13f), p(0.48f, 0.90f, 0.13f)),
        )
    }

    private fun controllerMappings(): List<Mapping> {
        fun s(id: String, axis: StickAxis, target: GamepadAxis) =
            Mapping(InputSource.TouchStick(id, axis), OutputTarget.PadAxis(target))
        fun t(id: String, target: OutputTarget) = touch(id, target)
        return listOf(
            s("ls", StickAxis.X, GamepadAxis.LX), s("ls", StickAxis.Y, GamepadAxis.LY),
            s("rs", StickAxis.X, GamepadAxis.RX), s("rs", StickAxis.Y, GamepadAxis.RY),
            t("a", OutputTarget.Pad(GamepadButton.A)), t("b", OutputTarget.Pad(GamepadButton.B)),
            t("x", OutputTarget.Pad(GamepadButton.X)), t("y", OutputTarget.Pad(GamepadButton.Y)),
            t("up", OutputTarget.Pad(GamepadButton.DPAD_UP)), t("down", OutputTarget.Pad(GamepadButton.DPAD_DOWN)),
            t("left", OutputTarget.Pad(GamepadButton.DPAD_LEFT)), t("right", OutputTarget.Pad(GamepadButton.DPAD_RIGHT)),
            t("lb", OutputTarget.Pad(GamepadButton.LB)), t("rb", OutputTarget.Pad(GamepadButton.RB)),
            t("lt", OutputTarget.PadAxis(GamepadAxis.LT)), t("rt", OutputTarget.PadAxis(GamepadAxis.RT)),
            t("view", OutputTarget.Pad(GamepadButton.BACK)), t("menu", OutputTarget.Pad(GamepadButton.START)),
            t("gyro", OutputTarget.GyroHold),
        )
    }

    /** Touchscreen game pad plus any attached controller, gyro on the "Gyro" touch button. */
    val controllerWorkspace = Workspace(
        id = "controller",
        name = "Controller",
        touchControls = controllerControls,
        mappings = controllerMappings(),
        pads = listOf(anyPad(KnownLayouts.standardControllerMappings())),
    )

    // WASD

    /**
     * Keyboard-and-mouse shooter layout: an 8-way stick on WASD at the left thumb with Shift, Q,
     * E, Esc and Tab above it, a 1-4 row at the top, LMB / RMB / Space / Ctrl / R / F and the gyro
     * Ratchet at the right thumb.
     */
    private val wasdControls: List<TouchControl> = run {
        val b = TouchKind.BUTTON
        val sq = TouchShape.SQUARE
        val numbers = (1..4).map { n ->
            control("k$n", b, "$n", p(0.38f + (n - 1) * 0.08f, 0.12f, 0.11f), p(0.27f + n * 0.13f, 0.56f, 0.11f), sq)
        }
        listOf(
            control("move", TouchKind.STICK, "WASD", p(0.14f, 0.64f, 0.38f), p(0.25f, 0.81f, 0.40f)),
            control("esc", b, "Esc", p(0.05f, 0.17f, 0.10f), p(0.08f, 0.56f, 0.11f), sq),
            control("tab", b, "Tab", p(0.12f, 0.17f, 0.10f), p(0.22f, 0.56f, 0.11f), sq),
            control("shift", b, "Shift", p(0.05f, 0.31f, 0.12f), p(0.08f, 0.645f, 0.12f), sq),
            control("q", b, "Q", p(0.13f, 0.31f, 0.12f), p(0.22f, 0.645f, 0.12f), sq),
            control("e", b, "E", p(0.21f, 0.31f, 0.12f), p(0.36f, 0.645f, 0.12f), sq),
        ) + numbers + listOf(
            control("r", b, "R", p(0.91f, 0.32f, 0.12f), p(0.50f, 0.645f, 0.12f), sq),
            control("f", b, "F", p(0.80f, 0.38f, 0.12f), p(0.64f, 0.645f, 0.12f), sq),
            control("lmb", b, "LMB", p(0.78f, 0.64f, 0.20f), p(0.65f, 0.79f, 0.24f)),
            control("rmb", b, "RMB", p(0.91f, 0.54f, 0.14f), p(0.88f, 0.75f, 0.18f)),
            control("space", b, "Space", p(0.91f, 0.80f, 0.16f), p(0.88f, 0.89f, 0.20f)),
            control("ctrl", b, "Ctrl", p(0.78f, 0.90f, 0.12f), p(0.64f, 0.94f, 0.13f), sq),
            control("ratchet", b, "Ratchet", p(0.64f, 0.74f, 0.14f), p(0.86f, 0.645f, 0.14f)),
        )
    }

    private fun wasdMappings(): List<Mapping> {
        val move = wasdControls.first { it.id == "move" }
        val stick = Bindings.applyStickPreset(emptyList(), Bindings.touchInputs(move), Bindings.StickPreset.WASD)
        fun key(id: String, usage: Int) = touch(id, OutputTarget.Key(usage))
        return stick + listOf(
            key("esc", 0x29), key("tab", 0x2B), key("shift", HidUsage.LEFT_SHIFT),
            key("q", 0x14), key("e", 0x08), key("r", 0x15), key("f", 0x09),
            key("k1", 0x1E), key("k2", 0x1F), key("k3", 0x20), key("k4", 0x21),
            touch("lmb", OutputTarget.Mouse(MouseButton.LEFT)), touch("rmb", OutputTarget.Mouse(MouseButton.RIGHT)),
            key("space", 0x2C), key("ctrl", HidUsage.LEFT_CTRL),
            touch("ratchet", OutputTarget.GyroPause),
        )
    }

    /** Keyboard + mouse for games without controller support (WASD layout); gyro always on. */
    val wasdWorkspace = Workspace(
        id = "wasd",
        name = "WASD",
        touchControls = wasdControls,
        mappings = wasdMappings(),
        gyro = GyroSettings(mode = GyroMode.ALWAYS),
        pads = listOf(anyPad(kbmPadMappings())),
    )

    // Keyboard

    /** One key of the Keyboard preset: control id, label and HID usage. */
    private class Key(val id: String, val label: String, val usage: Int)

    /** A slot in a keyboard row, [width] in key units; a null [key] is a gap. */
    private class Cell(val key: Key?, val width: Float)

    /** The space bar is a run of this many keys. */
    private const val SPACE_KEYS = 4

    private val keys: Map<String, Key> = buildMap {
        fun k(name: String, label: String, usage: Int) = put(name, Key("key_$name", label, usage))
        k("esc", "Esc", 0x29)
        for (n in 1..12) k("f$n", "F$n", 0x3A + n - 1)
        k("grave", "`", 0x35)
        "1234567890".forEachIndexed { i, c -> k("$c", "$c", 0x1E + i) }
        k("minus", "-", 0x2D); k("equal", "=", 0x2E); k("bksp", "Bksp", 0x2A)
        k("tab", "Tab", 0x2B)
        ('a'..'z').forEachIndexed { i, c -> k("$c", c.uppercase(), 0x04 + i) }
        k("lbracket", "[", 0x2F); k("rbracket", "]", 0x30); k("backslash", "\\", 0x31)
        k("caps", "Caps", 0x39); k("semicolon", ";", 0x33); k("quote", "'", 0x34); k("enter", "Enter", 0x28)
        k("lshift", "Shift", HidUsage.LEFT_SHIFT); k("rshift", "Shift", HidUsage.RIGHT_SHIFT)
        k("comma", ",", 0x36); k("period", ".", 0x37); k("slash", "/", 0x38)
        k("lctrl", "Ctrl", HidUsage.LEFT_CTRL); k("win", "Win", HidUsage.LEFT_GUI); k("lalt", "Alt", HidUsage.LEFT_ALT)
        for (n in 1..SPACE_KEYS) k("space$n", "Space", 0x2C)
        k("ralt", "Alt", HidUsage.RIGHT_ALT); k("rctrl", "Ctrl", HidUsage.RIGHT_CTRL)
        k("up", "↑", 0x52); k("down", "↓", 0x51); k("left", "←", 0x50); k("right", "→", 0x4F)
    }

    private fun k(name: String, width: Float = 1f) = Cell(keys[name] ?: error("no key $name"), width)
    private fun ks(vararg names: String) = names.map { k(it) }
    private fun gap(width: Float) = Cell(null, width)
    private fun spaces(width: Float) = (1..SPACE_KEYS).map { k("space$it", width) }

    /** Logitech K380 rows, 15 key units wide; arrows as an inverted T at the bottom right. */
    private val keyboardLandscapeRows: List<List<Cell>> = listOf(
        listOf(k("esc", 1.5f)) + (1..12).map { k("f$it", 1.125f) },
        ks("grave", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "minus", "equal") + k("bksp", 2f),
        listOf(k("tab", 1.5f)) + ks("q", "w", "e", "r", "t", "y", "u", "i", "o", "p", "lbracket", "rbracket") +
            k("backslash", 1.5f),
        listOf(k("caps", 1.75f)) + ks("a", "s", "d", "f", "g", "h", "j", "k", "l", "semicolon", "quote") +
            k("enter", 2.25f),
        listOf(k("lshift", 2f)) + ks("z", "x", "c", "v", "b", "n", "m", "comma", "period", "slash") +
            ks("rshift", "up") + gap(1f),
        listOf(k("lctrl", 1.25f), k("win", 1.25f), k("lalt", 1.25f)) + spaces(1.25f) +
            listOf(k("ralt", 1.25f), k("rctrl", 1.25f), gap(0.75f)) + ks("left", "down", "right"),
    )

    /** Portrait: the same keys phone-style, 11 per row in eight rows. */
    private val keyboardPortraitRows: List<List<Cell>> = listOf(
        listOf(k("esc")) + (1..10).map { k("f$it") },
        ks("f11", "f12", "grave", "minus", "equal", "lbracket", "rbracket", "backslash", "semicolon", "quote", "slash"),
        ks("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "bksp"),
        ks("tab", "q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        ks("caps", "a", "s", "d", "f", "g", "h", "j", "k", "l", "enter"),
        ks("lshift", "z", "x", "c", "v", "b", "n", "m", "comma", "period", "rshift"),
        ks("lctrl", "win", "lalt") + spaces(1f) + ks("ralt", "rctrl", "up"),
        listOf(gap(8f)) + ks("left", "down", "right"),
    )

    /**
     * Places [rows] of square keys of side [size]: [units] key units across the width less
     * [margin] on each side, row centres [pitch] apart from [top] (fractions of the pad area).
     */
    private fun layRows(
        rows: List<List<Cell>>, units: Float, margin: Float, top: Float, pitch: Float, size: Float,
    ): Map<String, Placement> {
        val unit = (1f - 2f * margin) / units
        val out = LinkedHashMap<String, Placement>()
        rows.forEachIndexed { r, row ->
            var at = 0f
            for (cell in row) {
                cell.key?.let { out[it.id] = Placement(margin + (at + cell.width / 2f) * unit, top + pitch * (r + 0.5f), size) }
                at += cell.width
            }
        }
        return out
    }

    private val keyboardControls: List<TouchControl> = run {
        // Landscape: six rows in the lower 65%. Portrait: eight rows in the lower 40%.
        val land = layRows(keyboardLandscapeRows, 15f, 0.01f, 0.34f, 0.11f, 0.096f)
        val port = layRows(keyboardPortraitRows, 11f, 0.01f, 0.59f, 0.05f, 0.08f)
        val byId = keys.values.associateBy { it.id }
        land.map { (id, l) ->
            control(id, TouchKind.BUTTON, byId.getValue(id).label, l,
                port[id] ?: error("$id has no portrait place"), TouchShape.SQUARE)
        }
    }

    private fun keyboardMappings(): List<Mapping> {
        val byId = keys.values.associateBy { it.id }
        return keyboardControls.map { touch(it.id, OutputTarget.Key(byId.getValue(it.id).usage)) }
    }

    /** A full keyboard (K380 layout) under a touchpad strip; no gyro, no pads. */
    val keyboardWorkspace = Workspace(
        id = "keyboard",
        name = "Keyboard",
        touchControls = keyboardControls,
        mappings = keyboardMappings(),
        gyro = GyroSettings(mode = GyroMode.OFF),
        touchpadOverButtons = true,
        stickyModifiers = true,
    )

    /** Workspaces offered by "Add workspace" and put in every new profile. */
    val presets: List<Workspace> get() = listOf(controllerWorkspace, wasdWorkspace, keyboardWorkspace)

    /** An empty workspace: touchpad only, no controls, no pads. */
    fun blankWorkspace(name: String = "Workspace") = Workspace(id = "ws", name = name)

    /** A new profile with the three preset workspaces. */
    fun newProfile(id: String, name: String) = Profile(id = id, name = name, workspaces = presets)

    /** The profile a fresh install starts in: Controller, WASD and Keyboard workspaces. */
    val default = newProfile("builtin.default", "Default")

    /** Today's controller-as-keyboard-and-mouse mapping (left stick = WASD, triggers = mouse). */
    fun kbmPadMappings(): List<Mapping> = listOf(
        Mapping(InputSource.Axis(AndroidAxis.Y, AxisHalf.NEGATIVE), OutputTarget.Key(0x1A)), // W
        Mapping(InputSource.Axis(AndroidAxis.Y, AxisHalf.POSITIVE), OutputTarget.Key(0x16)), // S
        Mapping(InputSource.Axis(AndroidAxis.X, AxisHalf.NEGATIVE), OutputTarget.Key(0x04)), // A
        Mapping(InputSource.Axis(AndroidAxis.X, AxisHalf.POSITIVE), OutputTarget.Key(0x07)), // D
        Mapping(InputSource.Key(AndroidKey.BUTTON_A), OutputTarget.Key(0x2C)), // Space
        Mapping(InputSource.Key(AndroidKey.BUTTON_B), OutputTarget.Key(0x06)), // C
        Mapping(InputSource.Key(AndroidKey.BUTTON_X), OutputTarget.Key(0x15)), // R
        Mapping(InputSource.Key(AndroidKey.BUTTON_Y), OutputTarget.Key(0x08)), // E
        Mapping(InputSource.Key(AndroidKey.BUTTON_L1), OutputTarget.Key(0x14)), // Q
        Mapping(InputSource.Key(AndroidKey.BUTTON_R1), OutputTarget.Key(0x09)), // F
        Mapping(InputSource.Key(AndroidKey.BUTTON_THUMBL), OutputTarget.Key(0xE1)), // Shift
        Mapping(InputSource.Key(AndroidKey.BUTTON_START), OutputTarget.Key(0x29)), // Esc
        Mapping(InputSource.Key(AndroidKey.BUTTON_SELECT), OutputTarget.Key(0x2B)), // Tab
        Mapping(InputSource.Axis(AndroidAxis.LTRIGGER, AxisHalf.POSITIVE), OutputTarget.Mouse(MouseButton.RIGHT)),
        Mapping(InputSource.Axis(AndroidAxis.RTRIGGER, AxisHalf.POSITIVE), OutputTarget.Mouse(MouseButton.LEFT)),
        Mapping(InputSource.Key(AndroidKey.BUTTON_L2), OutputTarget.Mouse(MouseButton.RIGHT)),
        Mapping(InputSource.Key(AndroidKey.BUTTON_R2), OutputTarget.Mouse(MouseButton.LEFT)),
        Mapping(InputSource.Key(AndroidKey.DPAD_UP), OutputTarget.Wheel(1)),
        Mapping(InputSource.Key(AndroidKey.DPAD_DOWN), OutputTarget.Wheel(-1)),
    )

    val all: List<Profile> = listOf(default)

    /** Ids of the builtins before workspaces; a saved active id among them means [default]. */
    val retiredIds: Set<String> = setOf("builtin.virtual_gamepad", "builtin.controller", "builtin.kbm")

    /** Picks the profile for an attached controller: a user profile that matches it, else a builtin. */
    fun forController(profiles: List<Profile>, vendorId: Int, productId: Int, name: String): Profile? =
        profiles.firstOrNull { p -> p.controllers.any { it.matches(vendorId, productId, name) } }
}
