package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TouchKind { BUTTON, STICK }

/**
 * A control drawn on the touchscreen. Position is the centre, normalised to the screen (0..1),
 * size is the diameter as a fraction of the screen height.
 */
@Serializable
data class TouchControl(
    val id: String,
    val kind: TouchKind,
    val label: String,
    val x: Float,
    val y: Float,
    val size: Float = 0.16f,
)

@Serializable
enum class GyroMode { OFF, ALWAYS, HOLD_OR_TOGGLE }

@Serializable
enum class SensorAxis { X, Y, Z }

@Serializable
data class GyroSettings(
    val mode: GyroMode = GyroMode.HOLD_OR_TOGGLE,
    /** Mouse counts per degree of rotation. */
    val sensitivity: Float = 16f,
    /** Derive yaw/pitch axes from the display rotation (landscape either way round). */
    val autoOrient: Boolean = true,
    val yawAxis: SensorAxis = SensorAxis.X,
    val yawInvert: Boolean = true,
    val pitchAxis: SensorAxis = SensorAxis.Y,
    val pitchInvert: Boolean = false,
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
