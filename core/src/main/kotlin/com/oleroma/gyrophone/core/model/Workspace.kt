package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/**
 * One complete setup inside a [Profile]: the on-screen controls and their [mappings], the
 * background touchpad, gyro settings, and the physical game [pads] attached to it. The screen and
 * the attached pads work at the same time and feed the same PC outputs.
 */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val touchControls: List<TouchControl> = emptyList(),
    /** Mappings of the touch controls ([InputSource.Touch] / [InputSource.TouchStick] sources). */
    val mappings: List<Mapping> = emptyList(),
    val touchpad: TouchpadSettings = TouchpadSettings(),
    val gyro: GyroSettings = GyroSettings(),
    /** Opacity of the on-screen controls in play ([Profile.MIN_CONTROL_OPACITY]..1). */
    val controlOpacity: Float = 1f,
    /**
     * A finger that lands on a button and moves becomes a touchpad finger; a still tap presses the
     * button on lift, a still hold presses it after a moment. Off: buttons press on touch-down.
     */
    val touchpadOverButtons: Boolean = false,
    /**
     * Touch keys mapped to keyboard modifiers latch: tap = the next key only, double tap = locked,
     * hold = held while down.
     */
    val stickyModifiers: Boolean = false,
    /** Physical game pads that act in this workspace; a connected pad not listed does nothing here. */
    val pads: List<AttachedPad> = emptyList(),
) {
    /** The first attached pad that matches a device, or null (the device does nothing here). */
    fun padFor(vendorId: Int, productId: Int, name: String): AttachedPad? =
        pads.firstOrNull { it.matches(vendorId, productId, name) }
}

/** A physical game pad model attached to a [Workspace], with its own mappings. */
@Serializable
data class AttachedPad(
    val id: String,
    /** Which devices this is; null = any game pad (profiles migrated from before workspaces). */
    val match: DeviceMatch? = null,
    /** Display name: the model ("DualSense") or "Any gamepad". */
    val name: String = ANY_NAME,
    /** Face-button convention the mapping started from; see [KnownLayouts.familyMappings]. */
    val family: FaceLayout = FaceLayout.XBOX,
    /** Android key / axis sources ([InputSource.Key], [InputSource.Axis]) to PC outputs. */
    val mappings: List<Mapping> = emptyList(),
    /**
     * The pad profile ([PadPreset]) last loaded into [mappings]; null on pads attached before pad
     * profiles were stored. Saving that profile again updates every pad that names it.
     */
    val presetId: String? = null,
) {
    fun matches(vendorId: Int, productId: Int, name: String): Boolean =
        match?.matches(vendorId, productId, name) ?: true

    companion object {
        const val ANY_NAME = "Any gamepad"
    }
}

/** Touch-control sources live in a workspace; everything else belongs to an attached pad. */
val InputSource.isTouch: Boolean
    get() = this is InputSource.Touch || this is InputSource.TouchStick
