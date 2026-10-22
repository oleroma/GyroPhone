package com.oleroma.gyrophone.core.engine

/**
 * A physical input device attached to the phone, as Android reports it (InputDevice). A [keyboard]
 * uses the workspace's keyboard mappings, whatever pads are attached.
 */
data class PadDevice(val id: Int, val vendorId: Int, val productId: Int, val name: String, val keyboard: Boolean = false)
