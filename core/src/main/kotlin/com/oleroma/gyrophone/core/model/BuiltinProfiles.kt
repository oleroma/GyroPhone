package com.oleroma.gyrophone.core.model

/** Profiles that ship with the app. Users copy and edit them; the originals stay read-only. */
object BuiltinProfiles {

    /** Prebuilt virtual game pad, Xbox layout (landscape placement; see [virtualGamepadPortrait]). */
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
    private val virtualGamepadPortrait: Map<String, Placement> = mapOf(
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

    private fun touchMappings(): List<Mapping> {
        fun t(id: String, target: OutputTarget) = Mapping(InputSource.Touch(id), target)
        fun s(id: String, axis: StickAxis, target: GamepadAxis) =
            Mapping(InputSource.TouchStick(id, axis), OutputTarget.PadAxis(target))
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

    private fun anyPad(mappings: List<Mapping>) = AttachedPad(id = "pad1", mappings = mappings)

    /** Touchscreen game pad plus any attached controller, gyro on the "Gyro" touch button. */
    val controllerWorkspace = Workspace(
        id = "controller",
        name = "Controller",
        touchControls = virtualGamepadControls.map { it.copy(portrait = virtualGamepadPortrait[it.id]) },
        mappings = touchMappings(),
        pads = listOf(anyPad(KnownLayouts.standardControllerMappings())),
    )

    /** Keyboard + mouse for games without controller support (WASD layout); gyro always on. */
    val wasdWorkspace = Workspace(
        id = "wasd",
        name = "WASD",
        gyro = GyroSettings(mode = GyroMode.ALWAYS),
        pads = listOf(anyPad(kbmPadMappings())),
    )

    /** A full keyboard (K380 layout) under a touchpad strip. */
    val keyboardWorkspace = Workspace(
        id = "keyboard",
        name = "Keyboard",
        touchpadOverButtons = true,
        stickyModifiers = true,
    )

    /** The profile a fresh install starts in: Controller, WASD and Keyboard workspaces. */
    val default = Profile(
        id = "builtin.default",
        name = "Default",
        workspaces = listOf(controllerWorkspace, wasdWorkspace, keyboardWorkspace),
    )

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
