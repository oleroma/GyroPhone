package com.oleroma.gyrophone.core.model

/** Profiles that ship with the app. Users copy and edit them; the originals stay read-only. */
object BuiltinProfiles {

    /** Prebuilt landscape virtual game pad, Xbox layout. */
    val virtualGamepadControls: List<TouchControl> = listOf(
        TouchControl("ls", TouchKind.STICK, "LS", 0.14f, 0.62f, 0.38f),
        TouchControl("rs", TouchKind.STICK, "RS", 0.66f, 0.74f, 0.30f),
        TouchControl("a", TouchKind.BUTTON, "A", 0.88f, 0.70f),
        TouchControl("b", TouchKind.BUTTON, "B", 0.95f, 0.52f),
        TouchControl("x", TouchKind.BUTTON, "X", 0.81f, 0.52f),
        TouchControl("y", TouchKind.BUTTON, "Y", 0.88f, 0.34f),
        TouchControl("up", TouchKind.BUTTON, "↑", 0.34f, 0.62f, 0.12f),
        TouchControl("down", TouchKind.BUTTON, "↓", 0.34f, 0.86f, 0.12f),
        TouchControl("left", TouchKind.BUTTON, "←", 0.28f, 0.74f, 0.12f),
        TouchControl("right", TouchKind.BUTTON, "→", 0.40f, 0.74f, 0.12f),
        TouchControl("lb", TouchKind.BUTTON, "LB", 0.10f, 0.14f, 0.13f),
        TouchControl("lt", TouchKind.BUTTON, "LT", 0.22f, 0.14f, 0.13f),
        TouchControl("rb", TouchKind.BUTTON, "RB", 0.90f, 0.14f, 0.13f),
        TouchControl("rt", TouchKind.BUTTON, "RT", 0.78f, 0.14f, 0.13f),
        TouchControl("view", TouchKind.BUTTON, "View", 0.42f, 0.30f, 0.10f),
        TouchControl("menu", TouchKind.BUTTON, "Menu", 0.58f, 0.30f, 0.10f),
        TouchControl("gyro", TouchKind.BUTTON, "Gyro", 0.50f, 0.52f, 0.12f),
    )

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

    /** Touchscreen game pad plus any attached controller, gyro on the "Gyro" touch button. */
    val virtualGamepad = Profile(
        id = "builtin.virtual_gamepad",
        name = "Virtual gamepad",
        mappings = touchMappings() + KnownLayouts.standardControllerMappings(),
        touchControls = virtualGamepadControls,
    )

    /** Attached controller (e.g. GameSir X5) passed through; L3 holds gyro-to-mouse. */
    val controllerPassthrough = Profile(
        id = "builtin.controller",
        name = "Controller passthrough",
        mappings = KnownLayouts.standardControllerMappings() +
            Mapping(InputSource.Key(AndroidKey.BUTTON_THUMBR), OutputTarget.GyroToggle),
        controllers = KnownLayouts.controllers.map { it.match },
    )

    /** Controller as keyboard + mouse for games without controller support (WASD layout). */
    val keyboardMouse = Profile(
        id = "builtin.kbm",
        name = "Keyboard + mouse (WASD)",
        mappings = listOf(
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
        ),
        gyro = GyroSettings(mode = GyroMode.ALWAYS),
    )

    val all: List<Profile> = listOf(virtualGamepad, controllerPassthrough, keyboardMouse)

    /** Picks the profile for an attached controller: a user profile that matches it, else a builtin. */
    fun forController(profiles: List<Profile>, vendorId: Int, productId: Int, name: String): Profile? =
        profiles.firstOrNull { p -> p.controllers.any { it.matches(vendorId, productId, name) } }
}
