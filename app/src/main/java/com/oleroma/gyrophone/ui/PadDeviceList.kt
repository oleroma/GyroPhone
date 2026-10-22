package com.oleroma.gyrophone.ui

import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.ControllerDiagram
import com.oleroma.gyrophone.core.model.ControllerDiagrams
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.PadDevice
import com.oleroma.gyrophone.core.model.PadDevices
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.diagram
import com.oleroma.gyrophone.runtime.AttachedController

/* The game pads the pad menu lists, and how their buttons are drawn. */

/**
 * The devices the menu lists: those attached to any workspace of [profile], then connected
 * controllers that none of their specific matches covers.
 */
internal fun menuDevices(profile: Profile, controllers: List<AttachedController>): List<PadDevice> {
    val attached = PadDevices.inProfile(profile)
    val extra = controllers
        // An "any game pad" device does not hide a connected model: it can still get its own place.
        .filter { c -> attached.none { d -> d.match?.matches(c.vendorId, c.productId, c.name) == true } }
        .map { deviceOf(it) }
        .distinctBy { it.match }
    return attached + extra
}

/** [c] as a device: matched by vendor / product id when both are known, else by name. */
internal fun deviceOf(c: AttachedController): PadDevice {
    val match = if (c.vendorId != 0 && c.productId != 0) {
        DeviceMatch(vendorId = c.vendorId, productId = c.productId)
    } else {
        DeviceMatch(nameContains = c.name)
    }
    return PadDevice(match, c.known?.name ?: c.name, c.known?.layout ?: FaceLayout.XBOX)
}

/** Whether a controller matching [device] is connected to the phone. */
internal fun isConnected(device: PadDevice, controllers: List<AttachedController>): Boolean =
    controllers.any { c -> device.match?.matches(c.vendorId, c.productId, c.name) ?: true }

/** The buttons of [pad]: the connected device's own, else its known model's, else its family's. */
internal fun padDiagram(pad: AttachedPad, device: AttachedController?): ControllerDiagram = when {
    device != null -> ControllerDiagrams.forDevice(device.known, device.keys, device.axes)
    else -> {
        val known = pad.match?.let { m -> KnownLayouts.controllers.firstOrNull { it.match == m } }
        if (known != null) ControllerDiagrams.forDevice(known) else ControllerDiagrams.of(pad.family.diagram)
    }
}

/** The connected controller [pad] acts for, if any. */
internal fun connectedDevice(pad: AttachedPad, controllers: List<AttachedController>): AttachedController? =
    controllers.firstOrNull { pad.matches(it.vendorId, it.productId, it.name) }

internal fun familyTitle(f: FaceLayout): String = when (f) {
    FaceLayout.XBOX -> "Xbox"
    FaceLayout.PLAYSTATION -> "PlayStation"
    FaceLayout.NINTENDO -> "Nintendo"
}
