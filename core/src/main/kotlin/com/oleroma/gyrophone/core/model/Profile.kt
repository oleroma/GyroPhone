package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class TouchKind { BUTTON, STICK }

@Serializable
enum class TouchShape { CIRCLE, SQUARE }

/**
 * A control drawn on the touchscreen. Position is the centre, normalised to the screen (0..1),
 * size is the diameter (circle) or side length (square) as a fraction of the screen height.
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
) {
    companion object {
        const val MIN_SIZE = 0.06f
        const val MAX_SIZE = 0.6f
    }
}

/** Hit test in pixels; [slop] enlarges the hit area relative to the drawn shape. */
fun TouchControl.contains(px: Float, py: Float, screenW: Float, screenH: Float, slop: Float = 1.15f): Boolean {
    val dx = px - x * screenW
    val dy = py - y * screenH
    val half = size * screenH / 2f * slop
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
)
