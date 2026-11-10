package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/**
 * "Fade when phone rests" (the PC overlays' `OverlayRestFade`, on the phone for its gyro displays):
 * the phone lies within [flatWithinDeg] of a display's Level tilt ([GyroDisplay.levelUp]), turns
 * slower than [stillBelowDps] and gets no input, all for [quietMs]: that display fades to [fadeTo]
 * of its opacity over [fadeMs], and comes back at once on any input or movement.
 */
@Serializable
data class RestFade(
    val on: Boolean = true,
    val flatWithinDeg: Float = 10f,
    val stillBelowDps: Float = 0.5f,
    val quietMs: Int = 3000,
    val fadeTo: Float = 0.3f,
    val fadeMs: Int = 500,
)
