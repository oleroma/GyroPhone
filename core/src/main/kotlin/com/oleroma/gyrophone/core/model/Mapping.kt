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
enum class GamepadAxis {
    LX, LY, RX, RY, LT, RT;

    val isTrigger: Boolean get() = this == LT || this == RT
}

@Serializable
enum class AxisHalf { FULL, POSITIVE, NEGATIVE }

@Serializable
enum class StickAxis { X, Y }

/** Relative mouse output of a swipe axis ([OutputTarget.MouseAxis]). */
@Serializable
enum class MouseMotion(val label: String) {
    MOVE_X("Mouse move X"), MOVE_Y("Mouse move Y"), WHEEL("Scroll"), HWHEEL("Scroll sideways"),
}

/** A diagonal zone of a touch stick split into 8 directions ([StickZones.EIGHT]). */
@Serializable
enum class StickDiagonal(val label: String) {
    UP_LEFT("Up-left"), UP_RIGHT("Up-right"), DOWN_LEFT("Down-left"), DOWN_RIGHT("Down-right"),
}

@Serializable
enum class MouseButton(val bit: Int) { LEFT(0), RIGHT(1), MIDDLE(2), BACK(3), FORWARD(4) }

/**
 * When an [InputSource.TouchArea] is held. HELD (sticks): from touch-down on the stick until
 * lift. OUTSIDE: while a finger that touched down on the control is outside its area. ZONE: while
 * any finger is inside its area, wherever it touched down.
 */
@Serializable
enum class TouchAreaKind(val label: String) { HELD("On touch"), OUTSIDE("Outside"), ZONE("Zone") }

/**
 * A mappable input of a touch surface (the workspace touchpad), per finger count
 * ([InputSource.Surface]). TAP: a short pulse on a still, quick lift; DOUBLE_TAP: a second tap
 * soon after; TAP_DRAG: held from a tap's lift while a second touch may follow, through that
 * touch (a drag); HOLD: while exactly that many fingers are down; RELEASE: a pulse when the
 * fingers all lift (only after a quick touch with [TouchpadSettings.releaseTapMs]). MOTION_X / Y:
 * the fingers' travel (cursor, scroll, a stick from the touch-down point, switching apps);
 * SWIPE_*: a pulse once per touch when the travel passes a distance; HOLD_*: held while past half
 * of it in that direction. PINCH: the spread change (zoom); PINCH_IN / OUT: held while pinching.
 */
@Serializable
enum class SurfaceInput(val label: String, val motion: Boolean = false) {
    TAP("Tap"), DOUBLE_TAP("Double tap"), TAP_DRAG("Tap-drag"), HOLD("Hold"), RELEASE("Release"),
    MOTION_X("Move X", motion = true), MOTION_Y("Move Y", motion = true),
    SWIPE_LEFT("Swipe ←"), SWIPE_RIGHT("Swipe →"), SWIPE_UP("Swipe ↑"), SWIPE_DOWN("Swipe ↓"),
    HOLD_LEFT("Hold ←"), HOLD_RIGHT("Hold →"), HOLD_UP("Hold ↑"), HOLD_DOWN("Hold ↓"),
    PINCH("Pinch", motion = true), PINCH_IN("Pinch in"), PINCH_OUT("Pinch out"),
}

/** Something on the phone side that produces input. */
@Serializable
sealed interface InputSource {
    /** A key or button of any input device attached to the phone (GameSir X5, keyboard...). */
    @Serializable
    @SerialName("key")
    data class Key(val keyCode: Int) : InputSource

    /** An analog axis of an attached device. [half] picks one direction for axis-to-button use. */
    @Serializable
    @SerialName("axis")
    data class Axis(val axis: Int, val half: AxisHalf = AxisHalf.FULL) : InputSource

    /** A virtual touchscreen button, by [TouchControl.id]. */
    @Serializable
    @SerialName("touch")
    data class Touch(val id: String) : InputSource

    /** A touch button's release: a short press when the button lets go. */
    @Serializable
    @SerialName("touch_release")
    data class TouchRelease(val id: String) : InputSource

    /** A touch button's double tap: held while the second press is held. */
    @Serializable
    @SerialName("touch_double")
    data class TouchDoubleTap(val id: String) : InputSource

    /** One axis of a virtual touchscreen stick. */
    @Serializable
    @SerialName("touch_stick")
    data class TouchStick(val id: String, val axis: StickAxis, val half: AxisHalf = AxisHalf.FULL) : InputSource

    /** A diagonal zone of a virtual touchscreen stick with 8 zones. */
    @Serializable
    @SerialName("touch_stick_diagonal")
    data class TouchStickDiagonal(val id: String, val diagonal: StickDiagonal) : InputSource

    /**
     * A swipe on a touch button in swipe mode ([TouchControl.swipe]). FULL: the axis, as relative
     * motion for [OutputTarget.MouseAxis] and as the distance from the touch-down point (a floating
     * stick) for everything else; a half: that direction of the distance, pressed past half of it.
     */
    @Serializable
    @SerialName("touch_swipe")
    data class TouchSwipe(val id: String, val axis: StickAxis, val half: AxisHalf = AxisHalf.FULL) : InputSource

    /** Where fingers are relative to a touch control's area (see [TouchAreaKind]); the pad screen drives it. */
    @Serializable
    @SerialName("touch_area")
    data class TouchArea(val id: String, val kind: TouchAreaKind) : InputSource

    /** An input of a touch surface with [fingers] (1..4) down; [id] "" = the workspace touchpad. */
    @Serializable
    @SerialName("surface")
    data class Surface(val fingers: Int, val input: SurfaceInput, val id: String = "") : InputSource
}

/** Something the host PC receives. */
@Serializable
sealed interface OutputTarget {
    @Serializable
    @SerialName("pad")
    data class Pad(val button: GamepadButton) : OutputTarget

    /** Game pad axis; [scale] is applied to the source value (-1 inverts, digital sources give ±scale). */
    @Serializable
    @SerialName("pad_axis")
    data class PadAxis(val axis: GamepadAxis, val scale: Float = 1f) : OutputTarget

    /** Keyboard key by HID usage ID (see HidUsage). */
    @Serializable
    @SerialName("key")
    data class Key(val usage: Int) : OutputTarget

    @Serializable
    @SerialName("mouse")
    data class Mouse(val button: MouseButton) : OutputTarget

    /**
     * Mouse motion or scrolling that follows a swipe axis ([InputSource.TouchSwipe] FULL), scaled
     * by the button's swipe sensitivity. Scrolling is natural (the page follows the finger);
     * [scale] -1 reverses.
     */
    @Serializable
    @SerialName("mouse_axis")
    data class MouseAxis(val motion: MouseMotion, val scale: Float = 1f) : OutputTarget

    /** Mouse wheel, +1 up / -1 down per press. */
    @Serializable
    @SerialName("wheel")
    data class Wheel(val step: Int) : OutputTarget

    /** From before the hold / toggle mode went: now works as a ratchet ([GyroPause]). */
    @Serializable
    @SerialName("gyro_hold")
    data object GyroHold : OutputTarget

    /**
     * Toggle gyro: each press swaps "ratchet disables" and "ratchet enables" (until the workspace
     * changes). Does nothing while gyro is off ([GyroMode.SENSOR_OFF]).
     */
    @Serializable
    @SerialName("gyro_toggle")
    data object GyroToggle : OutputTarget

    /**
     * Ratchet: while the source is held, gyro in "ratchet disables" pauses (re-centre the phone) and
     * gyro in "ratchet enables" engages. See [GyroMode].
     */
    @Serializable
    @SerialName("gyro_pause")
    data object GyroPause : OutputTarget

    /**
     * Windows app switching from a surface's Move X: Alt is held while the fingers stay down and
     * Tab (moving right) or Shift+Tab (moving left) is sent per step of travel.
     */
    @Serializable
    @SerialName("switch_apps")
    data object SwitchApps : OutputTarget

    /** Zoom from a surface's Pinch: Ctrl + wheel, spreading apart zooms in. */
    @Serializable
    @SerialName("zoom")
    data object Zoom : OutputTarget

    /**
     * The phone vibrates (only while a PC is connected): a tick when the source engages (and
     * again when it lets go with [onRelease], such as leaving a zone), or while it is [held] (an
     * axis: stronger the further it is pushed). [feel] null = the workspace's ([Workspace.haptics]).
     */
    @Serializable
    @SerialName("haptic")
    data class Haptic(val held: Boolean = false, val feel: HapticFeel? = null, val onRelease: Boolean = false) :
        OutputTarget

    /**
     * A haptic tick each time the source's value crosses a step of [percent] % (detents along an
     * axis; a button ticks on press and release). [feel] as in [Haptic].
     */
    @Serializable
    @SerialName("haptic_step")
    data class HapticStep(val percent: Int = DEFAULT_STEP_PERCENT, val feel: HapticFeel? = null) : OutputTarget {
        companion object {
            const val DEFAULT_STEP_PERCENT = 10
            const val MIN_STEP_PERCENT = 2
            const val MAX_STEP_PERCENT = 50
        }
    }
}

@Serializable
data class Mapping(val source: InputSource, val target: OutputTarget)

/**
 * How a haptic target feels. [system]: Android's own click effects (they follow the phone's
 * touch feedback setting), [strength] picking light / medium / strong; else the motor at
 * [strength] (1..255). [lengthMs]: a tick's length (custom only).
 */
@Serializable
data class HapticFeel(val system: Boolean = true, val strength: Int = 160, val lengthMs: Int = 20) {
    companion object {
        const val MIN_LENGTH_MS = 5
        const val MAX_LENGTH_MS = 200
    }
}
