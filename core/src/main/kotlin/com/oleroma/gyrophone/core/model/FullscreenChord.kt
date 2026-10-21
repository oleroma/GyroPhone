package com.oleroma.gyrophone.core.model

/**
 * The optional full-screen chord: two small [TouchKind.FULLSCREEN] controls. Pressing both at
 * once hides the app's tabs and pad buttons; pressing both again brings them back. They send
 * nothing to the PC and are placed, resized and removed like other controls.
 */
object FullscreenChord {
    const val SIZE = 0.08f
    const val LABEL = "⛶"
    private const val PREFIX = "fullscreen"

    fun has(profile: Profile): Boolean = ids(profile).size >= 2

    /** Ids of the profile's chord corners. */
    fun ids(profile: Profile): List<String> =
        profile.touchControls.filter { it.kind == TouchKind.FULLSCREEN }.map { it.id }

    /** Adds the two corners at the top left and top right (both orientations), replacing any old ones. */
    fun add(profile: Profile): Profile {
        val rest = remove(profile)
        val a = Bindings.newTouchId(rest.touchControls, PREFIX)
        val b = Bindings.newTouchId(rest.touchControls + corner(a, 0f, 0f, null), PREFIX)
        return rest.copy(
            touchControls = rest.touchControls +
                corner(a, 0.03f, 0.06f, Placement(0.05f, 0.03f, SIZE)) +
                corner(b, 0.97f, 0.06f, Placement(0.95f, 0.03f, SIZE)),
        )
    }

    fun remove(profile: Profile): Profile =
        profile.copy(touchControls = profile.touchControls.filterNot { it.kind == TouchKind.FULLSCREEN })

    private fun corner(id: String, x: Float, y: Float, portrait: Placement?) =
        TouchControl(id, TouchKind.FULLSCREEN, LABEL, x, y, SIZE, TouchShape.CIRCLE, portrait)
}
