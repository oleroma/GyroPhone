package com.oleroma.gyrophone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Buttons of the game pad the host sees. Bit index = HID button number - 1. */
@Serializable
enum class GamepadButton(val bit: Int, val label: String) {
    A(0, "A"), B(1, "B"), X(2, "X"), Y(3, "Y"),
    LB(4, "LB"), RB(5, "RB"), BACK(6, "View"), START(7, "Menu"),
    LS(8, "LS"), RS(9, "RS"), GUIDE(10, "Guide"),
    M1(11, "M1"), M2(12, "M2"), M3(13, "M3"), M4(14, "M4"), SHARE(15, "Share"),

    // D-pad buttons are folded into the hat switch, not the button bitmask.
    DPAD_UP(-1, "Up"), DPAD_DOWN(-1, "Down"), DPAD_LEFT(-1, "Left"), DPAD_RIGHT(-1, "Right");
}

@Serializable
enum class GamepadAxis { LX, LY, RX, RY, LT, RT;
    val isTrigger: Boolean get() = this == LT || this == RT
}

@Serializable
enum class AxisHalf { FULL, POSITIVE, NEGATIVE }

@Serializable
enum class StickAxis { X, Y }

/** A diagonal zone of a touch stick split into 8 directions ([StickZones.EIGHT]). */
@Serializable
enum class StickDiagonal(val label: String) {
    UP_LEFT("Up-left"), UP_RIGHT("Up-right"), DOWN_LEFT("Down-left"), DOWN_RIGHT("Down-right"),
}

@Serializable
enum class MouseButton(val bit: Int) { LEFT(0), RIGHT(1), MIDDLE(2), BACK(3), FORWARD(4) }

/** Something on the phone side that produces input. */
@Serializable
sealed interface InputSource {
    /** A key or button of any input device attached to the phone (GameSir X5, keyboard...). */
    @Serializable @SerialName("key")
    data class Key(val keyCode: Int) : InputSource

    /** An analog axis of an attached device. [half] picks one direction for axis-to-button use. */
    @Serializable @SerialName("axis")
    data class Axis(val axis: Int, val half: AxisHalf = AxisHalf.FULL) : InputSource

    /** A virtual touchscreen button, by [TouchControl.id]. */
    @Serializable @SerialName("touch")
    data class Touch(val id: String) : InputSource

    /** One axis of a virtual touchscreen stick. */
    @Serializable @SerialName("touch_stick")
    data class TouchStick(val id: String, val axis: StickAxis, val half: AxisHalf = AxisHalf.FULL) : InputSource

    /** A diagonal zone of a virtual touchscreen stick with 8 zones. */
    @Serializable @SerialName("touch_stick_diagonal")
    data class TouchStickDiagonal(val id: String, val diagonal: StickDiagonal) : InputSource
}

/** Something the host PC receives. */
@Serializable
sealed interface OutputTarget {
    @Serializable @SerialName("pad")
    data class Pad(val button: GamepadButton) : OutputTarget

    /** Game pad axis; [scale] is applied to the source value (-1 inverts, digital sources give ±scale). */
    @Serializable @SerialName("pad_axis")
    data class PadAxis(val axis: GamepadAxis, val scale: Float = 1f) : OutputTarget

    /** Keyboard key by HID usage ID (see HidUsage). */
    @Serializable @SerialName("key")
    data class Key(val usage: Int) : OutputTarget

    @Serializable @SerialName("mouse")
    data class Mouse(val button: MouseButton) : OutputTarget

    /** Mouse wheel, +1 up / -1 down per press. */
    @Serializable @SerialName("wheel")
    data class Wheel(val step: Int) : OutputTarget

    /** From before the hold / toggle mode went: now works as a ratchet ([GyroPause]). */
    @Serializable @SerialName("gyro_hold")
    data object GyroHold : OutputTarget

    /**
     * Toggle gyro: each press swaps "ratchet disables" and "ratchet enables" (until the workspace
     * changes). Does nothing while gyro is off ([GyroMode.SENSOR_OFF]).
     */
    @Serializable @SerialName("gyro_toggle")
    data object GyroToggle : OutputTarget

    /**
     * Ratchet: while the source is held, gyro in "ratchet disables" pauses (re-centre the phone) and
     * gyro in "ratchet enables" engages. See [GyroMode].
     */
    @Serializable @SerialName("gyro_pause")
    data object GyroPause : OutputTarget
}

@Serializable
data class Mapping(val source: InputSource, val target: OutputTarget)
