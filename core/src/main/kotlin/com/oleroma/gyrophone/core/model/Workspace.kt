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
    /**
     * Mappings of the touch controls ([InputSource.Touch] / [InputSource.TouchStick] sources) and,
     * once [TouchpadSettings.mapped], of the touchpad ([InputSource.isTouchpad]).
     */
    val mappings: List<Mapping> = emptyList(),
    val touchpad: TouchpadSettings = TouchpadSettings(),
    val gyro: GyroSettings = GyroSettings(),
    /** Opacity of the on-screen controls in play ([Profile.MIN_CONTROL_OPACITY]..1). */
    val controlOpacity: Float = 1f,
    /**
     * A finger on a button drives the touchpad from touch-down; the button presses only on a still
     * lift within [buttonTapMs]. Off: buttons press on touch-down. Click-through buttons
     * ([TouchControl.clickThrough]) always press on touch-down and drive the touchpad.
     */
    val touchpadOverButtons: Boolean = false,
    /** Touchpad over buttons: the longest touch that still presses the button, in ms. */
    val buttonTapMs: Int = DEFAULT_BUTTON_TAP_MS,
    /**
     * Touch keys mapped to keyboard modifiers latch: tap = the next key only, double tap = locked,
     * hold = held while down.
     */
    val stickyModifiers: Boolean = false,
    /** Physical game pads that act in this workspace; a connected pad not listed does nothing here. */
    val pads: List<AttachedPad> = emptyList(),
    /**
     * The [MappingProfile] a set saved before workspaces owned their mappings used; only read when
     * moving its mappings in ([WorkspaceMappings.fromProfiles]), null since.
     */
    val mappingProfileId: String? = null,
    /** Physical keyboard mappings. */
    val keyboard: List<Mapping> = emptyList(),
    /** How haptic targets without a feel of their own feel ([OutputTarget.Haptic]). */
    val haptics: HapticFeel = HapticFeel(),
    /** When haptic ticks without an edge of their own play ([OutputTarget.Haptic.edge]). */
    val hapticEdge: HapticEdge = HapticEdge.PRESS,
    /** Touch buttons: how long a press must be held to be a long press, in ms ([TouchControl.longPressMs]). */
    val longPressMs: Int = DEFAULT_LONG_PRESS_MS,
    /** Touch buttons: the longest gap between the taps of a double tap, in ms ([TouchControl.doubleTapMs]). */
    val doubleTapMs: Int = DEFAULT_DOUBLE_TAP_MS,
    /** The named mapping sets above Base ([Mapping.set], [OutputTarget.Layer]), by id. */
    val sets: List<MappingSet> = emptyList(),
    /**
     * Controller mappings ([InputSource.Key] / [InputSource.Axis] sources): what every attached pad
     * without [AttachedPad.own] mappings acts with ([WorkspaceMappings]).
     */
    val controller: List<Mapping> = emptyList(),
) {
    /** The first attached pad that matches a device, or null (the device does nothing here). */
    fun padFor(vendorId: Int, productId: Int, name: String): AttachedPad? =
        pads.firstOrNull { it.matches(vendorId, productId, name) }

    companion object {
        const val DEFAULT_BUTTON_TAP_MS = 200
        const val MIN_BUTTON_TAP_MS = 80
        const val MAX_BUTTON_TAP_MS = 500
        const val DEFAULT_LONG_PRESS_MS = 400
        const val MIN_LONG_PRESS_MS = 100
        const val MAX_LONG_PRESS_MS = 1500
        const val DEFAULT_DOUBLE_TAP_MS = 300
        const val MIN_DOUBLE_TAP_MS = 100
        const val MAX_DOUBLE_TAP_MS = 800
    }
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
    /**
     * Android key / axis sources ([InputSource.Key], [InputSource.Axis]) to PC outputs: its
     * [own] or the workspace's [Workspace.controller] set in a resolved set, empty when stored.
     */
    val mappings: List<Mapping> = emptyList(),
    /**
     * The pad profile ([PadPreset]) last loaded into [mappings]; null on pads attached before pad
     * profiles were stored. Saving that profile again updates every pad that names it.
     */
    val presetId: String? = null,
    /**
     * This pad's own mappings, used instead of the workspace's controller set; null = the
     * workspace's. Only pads that mapped differently from the first pad of their workspace before
     * mapping profiles have them, so nothing changed for them on update.
     */
    val own: List<Mapping>? = null,
) {
    fun matches(vendorId: Int, productId: Int, name: String): Boolean =
        match?.matches(vendorId, productId, name) ?: true

    companion object {
        const val ANY_NAME = "Any gamepad"
    }
}

/** Touch-control sources live in a workspace; everything else belongs to an attached pad. */
val InputSource.isTouch: Boolean
    get() = this is InputSource.Touch || this is InputSource.TouchStick || this is InputSource.TouchStickDiagonal ||
            this is InputSource.TouchSwipe || this is InputSource.TouchRelease || this is InputSource.TouchDoubleTap ||
            this is InputSource.TouchTap || this is InputSource.TouchLongPress ||
            this is InputSource.TouchArea || this is InputSource.Surface

/** A source of the workspace touchpad ([InputSource.Surface] with no control id). */
val InputSource.isTouchpad: Boolean
    get() = this is InputSource.Surface && id.isEmpty()

/**
 * This workspace with its touchpad mappings: as it is once resolved ([TouchpadSettings.mapped]),
 * else with the ones its touchpad switches stand for ([Bindings.defaultTouchpadMappings]).
 */
fun Workspace.withTouchpadMappings(): Workspace =
    if (touchpad.mapped) this
    else copy(mappings = mappings + Bindings.defaultTouchpadMappings(touchpad), touchpad = touchpad.copy(mapped = true))
