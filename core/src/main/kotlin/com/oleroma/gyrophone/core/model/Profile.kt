package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min

@Serializable
enum class TouchKind { BUTTON, STICK }

@Serializable
enum class TouchShape { CIRCLE, SQUARE }

/** Centre (0..1 of the pad area) and size (fraction of the pad area's shorter side). */
@Serializable
data class Placement(val x: Float, val y: Float, val size: Float)

/**
 * A control drawn on the touchscreen. Position is the centre, normalised to the pad area (0..1);
 * size is the diameter (circle) or side length (square) as a fraction of the pad area's shorter
 * side (its height in landscape). [x], [y] and [size] are the landscape placement; [portrait] is
 * the portrait one, or null to derive it from the landscape placement.
 */
@Serializable
data class TouchControl(
    val id: String,
    val kind: TouchKind,
    val label: String,
    val x: Float,
    val y: Float,
    val size: Float = 0.16f,
    val shape: TouchShape = TouchShape.CIRCLE,
    /** Portrait placement; null = derived from the landscape one. Keep last (positional callers). */
    val portrait: Placement? = null,
) {
    companion object {
        const val MIN_SIZE = 0.06f
        const val MAX_SIZE = 0.6f
        /**
         * Derived portrait layout: the landscape layout squeezed into the lower part (the top is
         * left for the touchpad), sizes scaled down, centres pulled in so nothing leaves the width.
         */
        const val PORTRAIT_TOP = 0.45f
        const val PORTRAIT_SIZE_SCALE = 0.75f
    }
}

/**
 * The explicit portrait placement, else the builtin one for a control still at its builtin
 * landscape placement (copies of the Virtual gamepad saved before portrait existed), else null.
 */
private fun TouchControl.portraitOrBuiltin(): Placement? = portrait ?: BuiltinProfiles.builtinPortrait(this)

fun TouchControl.placeX(inPortrait: Boolean): Float {
    if (!inPortrait) return x
    portraitOrBuiltin()?.let { return it.x }
    // Portrait sizes are fractions of the width, so the half-extent in x is half the size.
    val half = size * TouchControl.PORTRAIT_SIZE_SCALE / 2f
    return x.coerceIn(half, 1f - half)
}

fun TouchControl.placeY(inPortrait: Boolean): Float =
    if (!inPortrait) y else portraitOrBuiltin()?.y ?: (TouchControl.PORTRAIT_TOP + y * (1f - TouchControl.PORTRAIT_TOP))

fun TouchControl.placeSize(inPortrait: Boolean): Float =
    if (!inPortrait) size else portraitOrBuiltin()?.size ?: (size * TouchControl.PORTRAIT_SIZE_SCALE)

/** Resolved placement (allocates; editor use). */
fun TouchControl.placement(inPortrait: Boolean): Placement =
    Placement(placeX(inPortrait), placeY(inPortrait), placeSize(inPortrait))

/**
 * Copy with the given orientation's placement replaced. Editing landscape first pins the
 * current (possibly derived) portrait placement, so the portrait layout does not move.
 */
fun TouchControl.withPlacement(inPortrait: Boolean, x: Float, y: Float, size: Float): TouchControl =
    if (inPortrait) copy(portrait = Placement(x, y, size))
    else copy(x = x, y = y, size = size, portrait = portrait ?: placement(true))

/** Hit test in pixels of the pad area; [slop] enlarges the hit area relative to the drawn shape. */
fun TouchControl.contains(
    px: Float, py: Float, screenW: Float, screenH: Float,
    slop: Float = 1.15f, inPortrait: Boolean = false,
): Boolean {
    val dx = px - placeX(inPortrait) * screenW
    val dy = py - placeY(inPortrait) * screenH
    val half = placeSize(inPortrait) * min(screenW, screenH) / 2f * slop
    return when (shape) {
        TouchShape.CIRCLE -> dx * dx + dy * dy <= half * half
        TouchShape.SQUARE -> abs(dx) <= half && abs(dy) <= half
    }
}

@Serializable
enum class GyroMode { OFF, ALWAYS, HOLD_OR_TOGGLE }

/** How the phone is held. AUTO follows the screen rotation. */
@Serializable
enum class GyroOrientation(val label: String) {
    AUTO("Auto (screen rotation)"),
    LANDSCAPE_LEFT("Landscape, top on the left"),
    LANDSCAPE_RIGHT("Landscape, top on the right"),
    PORTRAIT("Portrait"),
    FLAT("Flat, screen up"),
}

/** Rotation of the phone in the player's frame. */
@Serializable
enum class MotionAxis(val label: String) {
    /** Turning left/right around the vertical axis. */
    YAW("Yaw"),
    /** Tilting the far edge up/down. */
    PITCH("Pitch"),
    /** Tilting like a steering wheel. */
    ROLL("Roll"),
    NONE("None"),
}

@Serializable
data class GyroSettings(
    val mode: GyroMode = GyroMode.HOLD_OR_TOGGLE,
    /** Mouse counts per degree of rotation. */
    val sensitivity: Float = 16f,
    val orientation: GyroOrientation = GyroOrientation.AUTO,
    /** Motion that moves the cursor horizontally: yaw right / pitch up / roll clockwise = right. */
    val mouseX: MotionAxis = MotionAxis.YAW,
    val invertX: Boolean = false,
    val scaleX: Float = 1f,
    /** Motion that moves the cursor vertically: pitch up / yaw right / roll clockwise = up. */
    val mouseY: MotionAxis = MotionAxis.PITCH,
    val invertY: Boolean = false,
    val scaleY: Float = 1f,
    /** Below this speed (deg/s) motion is scaled down, killing hand jitter. 0 disables. */
    val tighteningThreshold: Float = 1.5f,
    /** Below this speed (deg/s) motion is averaged over a short window. 0 disables. */
    val smoothingThreshold: Float = 4f,
    /**
     * Axis mapping used while the phone is held in portrait. Null = same as the fields above,
     * which are the landscape mapping (and the only one in profiles saved before this existed).
     */
    val portrait: GyroAxes? = null,
) {
    val landscapeAxes: GyroAxes get() = GyroAxes(mouseX, invertX, scaleX, mouseY, invertY, scaleY)

    fun axesFor(portrait: Boolean): GyroAxes = (if (portrait) this.portrait else null) ?: landscapeAxes

    /** Returns a copy with [axes] as the mapping for that orientation. */
    fun withAxes(portrait: Boolean, axes: GyroAxes): GyroSettings =
        if (portrait) copy(portrait = axes)
        else copy(mouseX = axes.mouseX, invertX = axes.invertX, scaleX = axes.scaleX,
            mouseY = axes.mouseY, invertY = axes.invertY, scaleY = axes.scaleY)
}

/** Which motion drives each mouse axis; see [GyroSettings.mouseX] and [GyroSettings.mouseY]. */
@Serializable
data class GyroAxes(
    val mouseX: MotionAxis = MotionAxis.YAW,
    val invertX: Boolean = false,
    val scaleX: Float = 1f,
    val mouseY: MotionAxis = MotionAxis.PITCH,
    val invertY: Boolean = false,
    val scaleY: Float = 1f,
)

/** The pad screen's empty background as a laptop touchpad. */
@Serializable
data class TouchpadSettings(
    val enabled: Boolean = true,
    /** Mouse counts per dp of finger travel at low speed. */
    val sensitivity: Float = 1.5f,
    /** Extra gain for fast swipes: up to 1 + this. 0 = linear. */
    val acceleration: Float = 1f,
    val tapToClick: Boolean = true,
    /** Tap then touch and move drags with the left button held. */
    val tapDrag: Boolean = true,
    val twoFingerScroll: Boolean = true,
    /** Wheel notches per Touchpad.SCROLL_DP_PER_NOTCH dp of two-finger travel. */
    val scrollSpeed: Float = 1f,
    /** Content follows the fingers (finger down scrolls up), like a phone or Windows touchpad. */
    val naturalScroll: Boolean = true,
)

/** Matches an Android InputDevice. Null fields match anything. */
@Serializable
data class DeviceMatch(
    val vendorId: Int? = null,
    val productId: Int? = null,
    val nameContains: String? = null,
) {
    fun matches(vendor: Int, product: Int, name: String): Boolean =
        (vendorId == null || vendorId == vendor) &&
            (productId == null || productId == product) &&
            (nameContains == null || name.contains(nameContains, ignoreCase = true))
}

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val mappings: List<Mapping>,
    val touchControls: List<TouchControl> = emptyList(),
    val gyro: GyroSettings = GyroSettings(),
    /** Auto-select this profile when a matching controller is attached to the phone. */
    val controllers: List<DeviceMatch> = emptyList(),
    /** Windows executables this profile belongs to (used by the companion for auto-switching). */
    val games: List<String> = emptyList(),
    /** The pad screen's background as a touchpad. */
    val touchpad: TouchpadSettings = TouchpadSettings(),
)
