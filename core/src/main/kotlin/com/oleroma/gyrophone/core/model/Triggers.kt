package com.oleroma.gyrophone.core.model

/**
 * When a mapping fires ("When" in the editor). The trigger is part of the mapping's source: a
 * button-like input ([Triggers.base]) has one source per trigger ([Triggers.with]).
 * HOLD: from press to lift. PRESS: a pulse at every press. TAP: a pulse at the lift of a press
 * shorter than the long-press time. LONG_PRESS: from the long-press time to the lift. RELEASE: a
 * pulse at every lift. DOUBLE_TAP: from a second press soon after a tap until it lifts.
 */
enum class Trigger(val label: String, val badge: String) {
    HOLD("Hold", ""),
    PRESS("Press", "Press"),
    TAP("Tap", "Tap"),
    LONG_PRESS("Long press", "Long"),
    RELEASE("Release", "Release"),
    DOUBLE_TAP("Double tap", "2×"),
}

object Triggers {
    private fun gesture(t: Trigger): ButtonGesture? = when (t) {
        Trigger.HOLD -> null
        Trigger.PRESS -> ButtonGesture.PRESS
        Trigger.TAP -> ButtonGesture.TAP
        Trigger.LONG_PRESS -> ButtonGesture.LONG_PRESS
        Trigger.RELEASE -> ButtonGesture.RELEASE
        Trigger.DOUBLE_TAP -> ButtonGesture.DOUBLE_TAP
    }

    private fun surfaceInput(t: Trigger): SurfaceInput = when (t) {
        Trigger.HOLD -> SurfaceInput.HOLD
        Trigger.PRESS -> SurfaceInput.PRESS
        Trigger.TAP -> SurfaceInput.TAP
        Trigger.LONG_PRESS -> SurfaceInput.LONG_PRESS
        Trigger.RELEASE -> SurfaceInput.RELEASE
        Trigger.DOUBLE_TAP -> SurfaceInput.DOUBLE_TAP
    }

    private fun ofGesture(g: ButtonGesture): Trigger = when (g) {
        ButtonGesture.PRESS -> Trigger.PRESS
        ButtonGesture.TAP -> Trigger.TAP
        ButtonGesture.LONG_PRESS -> Trigger.LONG_PRESS
        ButtonGesture.RELEASE -> Trigger.RELEASE
        ButtonGesture.DOUBLE_TAP -> Trigger.DOUBLE_TAP
    }

    private fun ofSurface(i: SurfaceInput): Trigger? = when (i) {
        SurfaceInput.HOLD -> Trigger.HOLD
        SurfaceInput.PRESS -> Trigger.PRESS
        SurfaceInput.TAP -> Trigger.TAP
        SurfaceInput.LONG_PRESS -> Trigger.LONG_PRESS
        SurfaceInput.RELEASE -> Trigger.RELEASE
        SurfaceInput.DOUBLE_TAP -> Trigger.DOUBLE_TAP
        else -> null
    }

    /** Whether [base] (a held source) offers every [Trigger]: keys, touch buttons and surface finger counts. */
    fun supports(base: InputSource): Boolean = when (base) {
        is InputSource.Key, is InputSource.Touch -> true
        is InputSource.Surface -> base.input == SurfaceInput.HOLD && base.zone == 0 && base.fingers in 1..4
        else -> false
    }

    /** [s]'s trigger: HOLD for a held source and anything without triggers. */
    fun of(s: InputSource): Trigger = when (s) {
        is InputSource.KeyGesture -> ofGesture(s.gesture)
        is InputSource.TouchTap -> Trigger.TAP
        is InputSource.TouchDoubleTap -> Trigger.DOUBLE_TAP
        is InputSource.TouchLongPress -> Trigger.LONG_PRESS
        is InputSource.TouchRelease -> Trigger.RELEASE
        is InputSource.Surface -> if (s.zone == 0) ofSurface(s.input) ?: Trigger.HOLD else Trigger.HOLD
        else -> Trigger.HOLD
    }

    /** The held source [s] is a trigger of ([s] itself when it has none). A button's one-finger gestures are its press's. */
    fun base(s: InputSource): InputSource = when (s) {
        is InputSource.KeyGesture -> InputSource.Key(s.keyCode)
        is InputSource.TouchTap -> InputSource.Touch(s.id)
        is InputSource.TouchDoubleTap -> InputSource.Touch(s.id)
        is InputSource.TouchLongPress -> InputSource.Touch(s.id)
        is InputSource.TouchRelease -> InputSource.Touch(s.id)
        is InputSource.Surface -> when {
            s.zone != 0 || ofSurface(s.input) == null -> s
            s.id.isNotEmpty() && s.fingers == 1 -> InputSource.Touch(s.id)
            else -> s.copy(input = SurfaceInput.HOLD)
        }

        else -> s
    }

    /** The source of [base] (a held source, see [supports]) firing on [t]; null when it has no such trigger. */
    fun with(base: InputSource, t: Trigger): InputSource? {
        if (t == Trigger.HOLD) return base
        return when (base) {
            is InputSource.Key -> InputSource.KeyGesture(base.keyCode, gesture(t)!!)
            // Every touch button is a touch surface: its gestures are its one-finger ones.
            is InputSource.Touch -> InputSource.Surface(1, surfaceInput(t), base.id)
            is InputSource.Surface -> if (supports(base)) base.copy(input = surfaceInput(t)) else null
            else -> null
        }
    }

    /** [base]'s sources, one per trigger it offers (only itself when it has none). */
    fun all(base: InputSource): List<InputSource> =
        if (!supports(base)) listOf(base) else Trigger.entries.mapNotNull { with(base, it) }
}
