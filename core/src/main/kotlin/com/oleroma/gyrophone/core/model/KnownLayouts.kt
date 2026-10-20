package com.oleroma.gyrophone.core.model

/** Physical face-button convention of an attached controller. */
enum class FaceLayout { XBOX, PLAYSTATION, NINTENDO }

/** Diagram the Controller screen draws for a face layout when nothing more specific is known. */
val FaceLayout.diagram: DiagramStyle
    get() = when (this) {
        FaceLayout.XBOX -> DiagramStyle.XBOX
        FaceLayout.PLAYSTATION -> DiagramStyle.PLAYSTATION
        FaceLayout.NINTENDO -> DiagramStyle.NINTENDO
    }

data class KnownController(
    val name: String,
    val match: DeviceMatch,
    val layout: FaceLayout,
    val diagram: DiagramStyle = layout.diagram,
)

/**
 * Controllers recognised when attached to the phone. Android already normalises any standard
 * (XInput-class or HID) game pad to KEYCODE_BUTTON_* by position, so unknown devices still get
 * the standard mapping; this table only adds names and face-button conventions.
 */
object KnownLayouts {
    val controllers: List<KnownController> = listOf(
        KnownController("GameSir X5", DeviceMatch(nameContains = "GameSir X5"), FaceLayout.XBOX, DiagramStyle.GAMESIR_X5),
        KnownController("Razer Kishi", DeviceMatch(nameContains = "Kishi"), FaceLayout.XBOX),
        KnownController("Backbone One", DeviceMatch(nameContains = "Backbone"), FaceLayout.XBOX),
        KnownController("DualSense", DeviceMatch(nameContains = "DualSense"), FaceLayout.PLAYSTATION),
        KnownController("GameSir", DeviceMatch(vendorId = 0x3537), FaceLayout.XBOX),
        KnownController("Xbox controller", DeviceMatch(vendorId = 0x045E), FaceLayout.XBOX),
        KnownController("PlayStation controller", DeviceMatch(vendorId = 0x054C), FaceLayout.PLAYSTATION),
        KnownController("Nintendo controller", DeviceMatch(vendorId = 0x057E), FaceLayout.NINTENDO),
        KnownController("8BitDo", DeviceMatch(vendorId = 0x2DC8), FaceLayout.XBOX),
        KnownController("Razer controller", DeviceMatch(vendorId = 0x1532), FaceLayout.XBOX),
        KnownController("Backbone", DeviceMatch(vendorId = 0x358A), FaceLayout.XBOX),
    )

    fun identify(vendorId: Int, productId: Int, name: String): KnownController? =
        controllers.firstOrNull { it.match.matches(vendorId, productId, name) }

    /** Standard mapping from Android's normalised game pad events to the host game pad. */
    fun standardControllerMappings(layout: FaceLayout = FaceLayout.XBOX): List<Mapping> {
        val face = when (layout) {
            // Android reports buttons by position, so south stays A on every layout. Nintendo pads
            // label south "B"; users who want label-matching can swap in the profile editor.
            FaceLayout.XBOX, FaceLayout.PLAYSTATION, FaceLayout.NINTENDO -> listOf(
                AndroidKey.BUTTON_A to GamepadButton.A,
                AndroidKey.BUTTON_B to GamepadButton.B,
                AndroidKey.BUTTON_X to GamepadButton.X,
                AndroidKey.BUTTON_Y to GamepadButton.Y,
            )
        }
        val buttons = face + listOf(
            AndroidKey.BUTTON_L1 to GamepadButton.LB,
            AndroidKey.BUTTON_R1 to GamepadButton.RB,
            AndroidKey.BUTTON_SELECT to GamepadButton.BACK,
            AndroidKey.BUTTON_START to GamepadButton.START,
            AndroidKey.BUTTON_THUMBL to GamepadButton.LS,
            AndroidKey.BUTTON_THUMBR to GamepadButton.RS,
            AndroidKey.BUTTON_MODE to GamepadButton.GUIDE,
            AndroidKey.DPAD_UP to GamepadButton.DPAD_UP,
            AndroidKey.DPAD_DOWN to GamepadButton.DPAD_DOWN,
            AndroidKey.DPAD_LEFT to GamepadButton.DPAD_LEFT,
            AndroidKey.DPAD_RIGHT to GamepadButton.DPAD_RIGHT,
        )
        val axes = listOf(
            InputSource.Axis(AndroidAxis.X) to OutputTarget.PadAxis(GamepadAxis.LX),
            InputSource.Axis(AndroidAxis.Y) to OutputTarget.PadAxis(GamepadAxis.LY),
            InputSource.Axis(AndroidAxis.Z) to OutputTarget.PadAxis(GamepadAxis.RX),
            InputSource.Axis(AndroidAxis.RZ) to OutputTarget.PadAxis(GamepadAxis.RY),
            InputSource.Axis(AndroidAxis.LTRIGGER) to OutputTarget.PadAxis(GamepadAxis.LT),
            InputSource.Axis(AndroidAxis.BRAKE) to OutputTarget.PadAxis(GamepadAxis.LT),
            InputSource.Axis(AndroidAxis.RTRIGGER) to OutputTarget.PadAxis(GamepadAxis.RT),
            InputSource.Axis(AndroidAxis.GAS) to OutputTarget.PadAxis(GamepadAxis.RT),
            InputSource.Key(AndroidKey.BUTTON_L2) to OutputTarget.PadAxis(GamepadAxis.LT),
            InputSource.Key(AndroidKey.BUTTON_R2) to OutputTarget.PadAxis(GamepadAxis.RT),
            InputSource.Axis(AndroidAxis.HAT_X, AxisHalf.NEGATIVE) to OutputTarget.Pad(GamepadButton.DPAD_LEFT),
            InputSource.Axis(AndroidAxis.HAT_X, AxisHalf.POSITIVE) to OutputTarget.Pad(GamepadButton.DPAD_RIGHT),
            InputSource.Axis(AndroidAxis.HAT_Y, AxisHalf.NEGATIVE) to OutputTarget.Pad(GamepadButton.DPAD_UP),
            InputSource.Axis(AndroidAxis.HAT_Y, AxisHalf.POSITIVE) to OutputTarget.Pad(GamepadButton.DPAD_DOWN),
        )
        return buttons.map { (key, button) -> Mapping(InputSource.Key(key), OutputTarget.Pad(button)) } +
            axes.map { (src, dst) -> Mapping(src, dst) }
    }
}
