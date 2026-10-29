package com.oleroma.gyrophone.core.engine

/**
 * A physical input device attached to the phone, as Android reports it (InputDevice). A [keyboard]
 * uses the workspace's keyboard mappings, whatever pads are attached; a [mouse] its mouse
 * mappings (buttons and wheel notches as [com.oleroma.gyrophone.core.model.AndroidKey] MOUSE_ keys,
 * movement through MappingEngine.mouseMove). [analogLeftTrigger] /
 * [analogRightTrigger]: the device has an analog trigger axis on that side (LTRIGGER or BRAKE,
 * RTRIGGER or GAS). When its mappings also bind that axis to a game pad trigger, its digital
 * L2 / R2 key (which may fire early in the pull) does not drive LT / RT; other keys bound to a
 * trigger, and an L2 / R2 key whose axis is not bound to one, still do.
 */
data class PadDevice(
    val id: Int,
    val vendorId: Int,
    val productId: Int,
    val name: String,
    val keyboard: Boolean = false,
    val mouse: Boolean = false,
    val analogLeftTrigger: Boolean = false,
    val analogRightTrigger: Boolean = false,
)
