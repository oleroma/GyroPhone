package com.oleroma.gyrophone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * When a mapping fires ("When" in the editor); how long its output lasts is its [How]. The
 * trigger is part of the mapping's source: a button-like input ([Triggers.base]) has one source
 * per trigger ([Triggers.with]). HOLD: from touch-down (a swipe: from the direction engaging)
 * to the lift. TAP: at the lift of a press shorter than the long-press time (a swipe: the quick
 * swipe). LONG_PRESS: from the long-press time to the lift. RELEASE: at every lift. DOUBLE_TAP:
 * from a second press soon after a tap until it lifts (a swipe: a second quick swipe soon after).
 * PRESS: a pulse at every touch-down; no longer offered (On touch + Once does it), still read.
 */
@Serializable
enum class Trigger(val label: String, val badge: String) {
    @SerialName("hold") HOLD("On touch", ""),
    @SerialName("press") PRESS("Press", "Press"),
    @SerialName("tap") TAP("Tap", "Tap"),
    @SerialName("long") LONG_PRESS("Long press", "Long"),
    @SerialName("release") RELEASE("Release", "Release"),
    @SerialName("double") DOUBLE_TAP("Double tap", "2×"),
    ;

    /** Its How can be Held: it lasts while something is held (Tap and Release fire at a lift). */
    val holds: Boolean get() = this == HOLD || this == LONG_PRESS || this == DOUBLE_TAP
}

object Triggers {
    /** The triggers the editor offers, in its order. */
    val OFFERED = listOf(Trigger.HOLD, Trigger.TAP, Trigger.LONG_PRESS, Trigger.DOUBLE_TAP, Trigger.RELEASE)

    fun gesture(t: Trigger): ButtonGesture? = when (t) {
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

    /** Held direction [d]'s quick swipe. */
    private fun swipeOf(d: Direction): SurfaceInput = when (d) {
        Direction.UP -> SurfaceInput.SWIPE_UP
        Direction.RIGHT -> SurfaceInput.SWIPE_RIGHT
        Direction.DOWN -> SurfaceInput.SWIPE_DOWN
        Direction.LEFT -> SurfaceInput.SWIPE_LEFT
    }

    private fun holdOf(d: Direction): SurfaceInput = when (d) {
        Direction.UP -> SurfaceInput.HOLD_UP
        Direction.RIGHT -> SurfaceInput.HOLD_RIGHT
        Direction.DOWN -> SurfaceInput.HOLD_DOWN
        Direction.LEFT -> SurfaceInput.HOLD_LEFT
    }

    /** A surface's held direction (any zone): its triggers are the swipe's. */
    private fun isDirection(s: InputSource) =
        s is InputSource.Surface && s.input.holdDirection != null && s.on == Trigger.HOLD && s.fingers in 1..4


    /** Whether [base] (a held source) offers triggers: keys, touch buttons, surface finger counts, swipe directions, Area and Outside. */
    fun supports(base: InputSource): Boolean = when (base) {
        is InputSource.Key, is InputSource.Touch -> true
        // Inside, Outside and a stick's On touch: the same gestures as a button's touch.
        is InputSource.TouchArea -> base.on == Trigger.HOLD
        is InputSource.Surface -> base.on == Trigger.HOLD && base.fingers in 1..4 &&
            ((base.input == SurfaceInput.HOLD && base.zone == 0) || base.input.holdDirection != null)

        else -> false
    }

    /** The triggers [base] offers ([supports]), in the editor's order; HOLD only for others. */
    fun offered(base: InputSource): List<Trigger> = when {
        !supports(base) -> listOf(Trigger.HOLD)
        // A later length zone has no swipe of its own.
        base is InputSource.Surface && base.zone != 0 -> listOf(Trigger.HOLD, Trigger.LONG_PRESS, Trigger.RELEASE)
        else -> OFFERED
    }

    /** What the editor calls [t] on [base]: a swipe direction's are its own ("Quick swipe"); every Where shares the rest. */
    fun label(base: InputSource, t: Trigger): String = if (!isDirection(base)) t.label else when (t) {
        Trigger.HOLD -> "On swipe"
        Trigger.TAP -> "Quick swipe"
        Trigger.LONG_PRESS -> "Long hold"
        Trigger.DOUBLE_TAP -> "Double swipe"
        else -> t.label
    }

    /** [t]'s badge on a chip of [base] ("" for On touch / On swipe). */
    fun badge(base: InputSource, t: Trigger): String = when {
        isDirection(base) && t == Trigger.TAP -> "Quick"
        else -> t.badge
    }

    /** Whether [base] is a swipe direction (its triggers are named for swipes). */
    fun isSwipe(base: InputSource): Boolean = isDirection(base)

    /** [s]'s trigger: HOLD for a held source and anything without triggers. */
    fun of(s: InputSource): Trigger = when (s) {
        is InputSource.KeyGesture -> ofGesture(s.gesture)
        is InputSource.TouchTap -> Trigger.TAP
        is InputSource.TouchDoubleTap -> Trigger.DOUBLE_TAP
        is InputSource.TouchLongPress -> Trigger.LONG_PRESS
        is InputSource.TouchRelease -> Trigger.RELEASE
        is InputSource.TouchArea -> s.on
        is InputSource.Surface -> when {
            s.on != Trigger.HOLD -> s.on
            s.input.swipeDirection != null -> Trigger.TAP
            s.zone == 0 -> ofSurface(s.input) ?: Trigger.HOLD
            else -> Trigger.HOLD
        }

        else -> Trigger.HOLD
    }

    /** The held source [s] is a trigger of ([s] itself when it has none). A button's one-finger gestures are its press's. */
    fun base(s: InputSource): InputSource = when (s) {
        is InputSource.KeyGesture -> InputSource.Key(s.keyCode)
        is InputSource.TouchTap -> InputSource.Touch(s.id)
        is InputSource.TouchDoubleTap -> InputSource.Touch(s.id)
        is InputSource.TouchLongPress -> InputSource.Touch(s.id)
        is InputSource.TouchRelease -> InputSource.Touch(s.id)
        is InputSource.TouchArea -> if (s.on == Trigger.HOLD) s else s.copy(on = Trigger.HOLD)
        is InputSource.Surface -> {
            val d = s.input.swipeDirection
            when {
                d != null -> s.copy(input = holdOf(d), on = Trigger.HOLD)
                s.on != Trigger.HOLD -> s.copy(on = Trigger.HOLD)
                s.zone != 0 || ofSurface(s.input) == null -> s
                s.id.isNotEmpty() && s.fingers == 1 -> InputSource.Touch(s.id)
                else -> s.copy(input = SurfaceInput.HOLD)
            }
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
            is InputSource.TouchArea -> if (supports(base) && t in OFFERED) base.copy(on = t) else null
            is InputSource.Surface -> when {
                t !in offered(base) && t != Trigger.PRESS -> null
                isDirection(base) -> {
                    val d = base.input.holdDirection!!
                    when (t) {
                        Trigger.TAP -> base.copy(input = swipeOf(d))
                        Trigger.DOUBLE_TAP -> base.copy(input = swipeOf(d), on = Trigger.DOUBLE_TAP)
                        Trigger.LONG_PRESS, Trigger.RELEASE -> base.copy(on = t)
                        else -> null
                    }
                }

                supports(base) -> base.copy(input = surfaceInput(t))
                else -> null
            }

            else -> null
        }
    }

    /** [base]'s sources, one per trigger it offers (only itself when it has none). */
    fun all(base: InputSource): List<InputSource> = offered(base).mapNotNull { with(base, it) }

    /**
     * [list] with the Press trigger (no longer offered) turned into On touch + Once, the same
     * thing, and old key modes as Toggles ([ToggleUpgrade.keyModes]). Returns [list] itself when
     * there is none.
     */
    fun upgrade(list: List<Mapping>): List<Mapping> {
        val ms = ToggleUpgrade.keyModes(list)
        if (ms.none { of(it.source) == Trigger.PRESS }) return ms
        return ms.map { m ->
            if (of(m.source) != Trigger.PRESS) m
            else m.copy(source = base(m.source), options = (m.options ?: MapOptions()).copy(how = How.ONCE, toggle = false))
        }
    }
}
