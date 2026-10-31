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
 * lift. OUTSIDE: while a finger that touched down on the control is outside its area. ZONE (shown as Area): while
 * any finger is inside its area, wherever it touched down.
 */
@Serializable
enum class TouchAreaKind(val label: String) { HELD("On touch"), OUTSIDE("Outside"), ZONE("Area") }

/**
 * A mappable input of a touch surface (the workspace touchpad), per finger count
 * ([InputSource.Surface]). TAP: a short pulse on a still, quick lift; DOUBLE_TAP: a second tap
 * soon after (within the workspace's double-tap window; with one finger it is held from that touch
 * down until it lifts, so moving drags with it held); TAP_DRAG (one finger): held from a tap's
 * lift while a second touch may follow, through that touch (a drag); HOLD: while exactly that
 * many fingers are down; LONG_PRESS: once they have stayed still for the long-press time, until
 * they lift; RELEASE: a pulse each time the fingers all lift. A tap waits out the double-tap
 * window only when its double tap does something else (see [ButtonGesture]). MOTION_X / Y:
 * the fingers' travel (cursor, scroll, a stick from the touch-down point, switching apps);
 * SWIPE_*: a pulse each time the fingers have travelled a distance since turning that way; HOLD_*:
 * held as [TouchpadSettings.holdMode] says (direction pies, distances and the latch are in
 * [TouchpadSettings]). PINCH: the spread change (zoom); PINCH_IN / OUT: held while pinching.
 */
@Serializable
enum class SurfaceInput(val label: String, val motion: Boolean = false) {
    TAP("Tap"), DOUBLE_TAP("Double tap"), TAP_DRAG("Tap-drag"), HOLD("Hold"), LONG_PRESS("Long press"),
    RELEASE("Release"),
    MOTION_X("Move X", motion = true), MOTION_Y("Move Y", motion = true),
    SWIPE_LEFT("Swipe ←"), SWIPE_RIGHT("Swipe →"), SWIPE_UP("Swipe ↑"), SWIPE_DOWN("Swipe ↓"),
    HOLD_LEFT("Swipe hold ←"), HOLD_RIGHT("Swipe hold →"), HOLD_UP("Swipe hold ↑"), HOLD_DOWN("Swipe hold ↓"),
    PINCH("Pinch", motion = true), PINCH_IN("Pinch in"), PINCH_OUT("Pinch out"),
}

/** One of the finger count's press gestures from before mapping triggers: now a trigger on its [SurfaceInput.HOLD]. */
val SurfaceInput.gesture: Boolean
    get() = this == SurfaceInput.TAP || this == SurfaceInput.DOUBLE_TAP || this == SurfaceInput.TAP_DRAG ||
            this == SurfaceInput.LONG_PRESS || this == SurfaceInput.RELEASE

/** The direction of a swipe input (Swipe ←→↑↓), else null. */
val SurfaceInput.swipeDirection: Direction?
    get() = when (this) {
        SurfaceInput.SWIPE_UP -> Direction.UP
        SurfaceInput.SWIPE_RIGHT -> Direction.RIGHT
        SurfaceInput.SWIPE_DOWN -> Direction.DOWN
        SurfaceInput.SWIPE_LEFT -> Direction.LEFT
        else -> null
    }

/** The direction of a held direction input (Hold ←→↑↓), else null. */
val SurfaceInput.holdDirection: Direction?
    get() = when (this) {
        SurfaceInput.HOLD_UP -> Direction.UP
        SurfaceInput.HOLD_RIGHT -> Direction.RIGHT
        SurfaceInput.HOLD_DOWN -> Direction.DOWN
        SurfaceInput.HOLD_LEFT -> Direction.LEFT
        else -> null
    }

/**
 * The gestures every button-like input offers (touch buttons, controller and keyboard keys, touch
 * surfaces): a tap is a short press, fired on its lift; a double tap a second press within the
 * double-tap window after a tap, held while that press is held (never a tap or long press too);
 * a long press one held for the long-press time, until it lifts (never a tap); a release fires on
 * every lift. A tap waits out the double-tap window only when the double tap does something else,
 * so a tap and double tap mapped alike (a double click) stay instant.
 */
@Serializable
enum class ButtonGesture(val label: String) {
    TAP("Tap"), DOUBLE_TAP("Double tap"), LONG_PRESS("Long press"), RELEASE("Release"),
}

/** Something on the phone side that produces input. */
@Serializable
sealed interface InputSource {
    /** A key or button of any input device attached to the phone (GameSir X5, keyboard...). */
    @Serializable
    @SerialName("key")
    data class Key(val keyCode: Int) : InputSource

    /** A [ButtonGesture] of a key or button of an attached device (see [Key]). */
    @Serializable
    @SerialName("key_gesture")
    data class KeyGesture(val keyCode: Int, val gesture: ButtonGesture) : InputSource

    /** An analog axis of an attached device. [half] picks one direction for axis-to-button use. */
    @Serializable
    @SerialName("axis")
    data class Axis(val axis: Int, val half: AxisHalf = AxisHalf.FULL) : InputSource

    /** A virtual touchscreen button, by [TouchControl.id]: held from touch-down to lift. */
    @Serializable
    @SerialName("touch")
    data class Touch(val id: String) : InputSource

    /**
     * A touch button's tap: a press shorter than the long-press time ([TouchControl.longPress]),
     * fired as a short press when it lifts. With a [TouchDoubleTap] mapping it waits out the
     * double-tap window first, and the taps of a double tap are not taps.
     */
    @Serializable
    @SerialName("touch_tap")
    data class TouchTap(val id: String) : InputSource

    /** A touch button's long press: on once held past the long-press time, until it lifts. */
    @Serializable
    @SerialName("touch_long")
    data class TouchLongPress(val id: String) : InputSource

    /** A touch button's release: a short press each time the button lets go. */
    @Serializable
    @SerialName("touch_release")
    data class TouchRelease(val id: String) : InputSource

    /**
     * A touch button's double tap: a second press within the double-tap window
     * ([TouchControl.doubleTap]) after a tap, held while that press is held.
     */
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

    /**
     * An input of a touch surface with [fingers] (1..4) down; [id] "" = the workspace touchpad.
     * [zone]: for a held direction (Hold ←→↑↓), its length zone from 0 (the first, from the hold
     * distance on; see [TouchpadSettings.holdZone]); 0 for every other input.
     */
    @Serializable
    @SerialName("surface")
    data class Surface(val fingers: Int, val input: SurfaceInput, val id: String = "", val zone: Int = 0) : InputSource

    /**
     * The phone turning about gyro [axis] (yaw right, pitch up, roll clockwise as the player sees
     * it are +), in °/s, while gyro is on. [id] "" = the workspace's, always; else touch button
     * [id]'s, only while it is pressed: it then takes that axis over from the workspace (of
     * several, the last pressed).
     */
    @Serializable
    @SerialName("gyro")
    data class Gyro(val axis: MotionAxis, val id: String = "") : InputSource

    /**
     * How far the phone is tilted ([com.oleroma.gyrophone.core.engine.TiltShake]): X sideways
     * (+ right, turning it like a wheel), Y forward and back (+ back, the top edge toward you;
     * forward pushes a stick up), full at the tilt range. [half] as for an [Axis]. [id] "" = the
     * workspace's, always; else touch button [id]'s, only while it is pressed.
     */
    @Serializable
    @SerialName("tilt")
    data class Tilt(val axis: StickAxis, val half: AxisHalf = AxisHalf.FULL, val id: String = "") : InputSource

    /** A shake of the phone: a short press. [id] as for [Tilt]. */
    @Serializable
    @SerialName("shake")
    data class Shake(val id: String = "") : InputSource
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

    /** Keyboard key by HID usage ID (see HidUsage), held as [mode] says. */
    @Serializable
    @SerialName("key")
    data class Key(val usage: Int, val mode: KeyMode = KeyMode.HOLD) : OutputTarget

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

    /** A ratchet ([GyroPause]) of gyro [axis] only, as that axis's ratchet mode says ([GyroSettings.modeOf]). */
    @Serializable
    @SerialName("gyro_pause_axis")
    data class GyroPauseAxis(val axis: MotionAxis) : OutputTarget

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
     * The phone vibrates (only while a PC is connected): a tick when the source engages and/or
     * lets go (entering / leaving a zone) as [edge] says, or while it is [held] (an axis: stronger
     * the further it is pushed). [feel] and [edge] null = the workspace's ([Workspace.haptics],
     * [Workspace.hapticEdge]).
     */
    @Serializable
    @SerialName("haptic")
    data class Haptic(val held: Boolean = false, val feel: HapticFeel? = null, val edge: HapticEdge? = null) :
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

    /**
     * A layer action on the mapping sets ([Mapping.set]). Not [override]: the mapping set — every
     * input does what set [set] maps, and nothing if it maps nothing; it clears the overrides.
     * [override]: set [set] is laid over the current set — inputs it maps do that, every other
     * input keeps the current set's; pressed again (or any mapping set) it goes. [whileHeld]: only
     * while the source is held; on release things go back. Inputs held at the change keep pressing
     * what they pressed until released, unless they follow layers ([TouchControl.followLayers]);
     * [switchHeld] is no longer used (it was this per action). The source that holds the action always keeps it; with
     * [whileHeld] it also presses what the new set maps it to (not that set's layer actions).
     */
    @Serializable
    @SerialName("layer")
    data class Layer(
        val set: Int,
        val override: Boolean = false,
        val whileHeld: Boolean = false,
        val switchHeld: Boolean = false,
        /**
         * It works as its mapping's [Output] says ([Mapping.trigger]): Held while held, Fire once
         * switches to it, Toggle turns it on and off, Sticky for the next button. False: from
         * before triggers, [whileHeld] says ([TriggerUpgrade] turns it into an output).
         */
        val follows: Boolean = false,
    ) : OutputTarget

    /** Does nothing: in a set used as an override, it stops an input falling through to the set below. */
    @Serializable
    @SerialName("nothing")
    data object NoOutput : OutputTarget
}

/**
 * How a key target ([OutputTarget.Key]) holds its key. HOLD: while the source is held. TOGGLE:
 * each press turns it on or off. NEXT_KEY: a press holds it until the next other key has been
 * pressed and let go (a sticky Shift); pressed again before that, it lets go.
 */
@Serializable
enum class KeyMode(val label: String) { HOLD("Hold"), TOGGLE("Toggle"), NEXT_KEY("Next key only") }

/**
 * [source] presses [target] while mapping set [set] (0 = Base) is in use ([OutputTarget.Layer]),
 * as [trigger] says: when it fires on the source's action and how the output behaves.
 */
@Serializable
data class Mapping(
    val source: InputSource,
    val target: OutputTarget,
    val set: Int = 0,
    val trigger: Trigger = Trigger(),
) {
    /** What this mapping maps: its source and trigger slot ([Trigger.slot]); a layer or level mapping it again replaces it. */
    val slot: Slot get() = Slot(source, trigger.slot)
}

/** One mappable slot: an action ([source]) and which of its triggers ([Trigger.slot]). */
data class Slot(val source: InputSource, val trigger: TriggerSlot)

/**
 * A named mapping set of a [Workspace] (set 0, Base, needs none). [settings]: the workspace
 * settings this layer sets itself while it is in use ([Workspace.ownSettings] of it names them,
 * [WorkspaceDefaults.layerKeys] only); every other one follows the workspace. Null: none.
 */
@Serializable
data class MappingSet(val id: Int, val name: String = "", val settings: Workspace? = null) {
    val label: String get() = name.ifBlank { "Layer $id" }

    companion object {
        const val BASE = 0
        const val MAX = 9
    }
}

/**
 * Which mappings are in use: those of [full] with each of [overrides] (bottom to top) laid over
 * it. For each input (an action and trigger, [Mapping.slot]) the topmost override that maps it
 * wins, else [full]'s mappings. Mappings to
 * [OutputTarget.NoOutput] are dropped unless [keepNothing]. Base with no overrides on a list with
 * no other sets is [ms] itself.
 */
fun layered(ms: List<Mapping>, full: Int, overrides: List<Int>, keepNothing: Boolean = false): List<Mapping> {
    if (full == MappingSet.BASE && overrides.isEmpty() && ms.none { it.set != 0 || (!keepNothing && it.target == OutputTarget.NoOutput) }) return ms
    var out = ms.filter { it.set == full }
    for (o in overrides) {
        if (o == full) continue
        val top = ms.filter { it.set == o }
        if (top.isEmpty()) continue
        val slots = top.mapTo(HashSet()) { it.slot }
        out = out.filterNot { it.slot in slots } + top
    }
    return if (keepNothing) out else out.filter { it.target != OutputTarget.NoOutput }
}

/**
 * How a haptic target feels. [system]: Android's own click effects (they follow the phone's
 * touch feedback setting), [strength] picking light / medium / strong; else the motor at
 * [strength] (1..255). [lengthMs]: a tick's length (custom only).
 */
/** When a haptic tick ([OutputTarget.Haptic]) plays: as its source engages, lets go, or both. */
@Serializable
enum class HapticEdge(val label: String) {
    PRESS("On press / enter"),
    RELEASE("On release / exit"),
    BOTH("Both"),
}

@Serializable
data class HapticFeel(val system: Boolean = true, val strength: Int = 160, val lengthMs: Int = 20) {
    companion object {
        const val MIN_LENGTH_MS = 5
        const val MAX_LENGTH_MS = 200
    }
}
