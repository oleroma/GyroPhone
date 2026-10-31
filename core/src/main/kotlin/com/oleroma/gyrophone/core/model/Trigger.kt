package com.oleroma.gyrophone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * When a mapping fires on its action. On a Press action (a touch button, a key, a surface's
 * finger count): PRESS when the press starts, held while it lasts; TAP on a lift within the tap
 * window; LONG_PRESS once the press has lasted past the tap window, until it lifts; RELEASE on
 * every lift. A Touch direction names them Drag, Swipe, Long drag and Release ([touchLabel]):
 * reaching the direction is the press, leaving it the lift.
 */
@Serializable
enum class When(val label: String, val touchLabel: String) {
    PRESS("Press", "Drag"),
    TAP("Tap", "Swipe"),
    LONG_PRESS("Long press", "Long drag"),
    RELEASE("Release", "Release");

    /** It fires as a pulse (on a lift), not for as long as the press. */
    val pulse: Boolean get() = this == TAP || this == RELEASE
}

/**
 * Which presses count. ANY: every press, either press of a double too, and it never waits.
 * SINGLE: presses that are not part of a double; a Single Tap waits out the double window, but
 * only when the action has a Double mapping. DOUBLE: the second press of a double (one that
 * comes within the double window after a tap).
 */
@Serializable
enum class Taps(val label: String) { ANY("Any"), SINGLE("Single"), DOUBLE("Double") }

/**
 * How the output behaves once the trigger fires. HELD: on while the trigger is on (a Tap or
 * Release is a short pulse). FIRE_ONCE: a short pulse as the trigger starts, ends or both
 * ([Trigger.fireAt]). TOGGLE: each time the trigger fires it flips on or off. STICKY: on until the
 * next other button-like output (a key, a pad button or a mouse button) is pressed and let go;
 * fired again before then, it lets go.
 */
@Serializable
enum class Output(val label: String) {
    HELD("Held"), FIRE_ONCE("Fire once"), TOGGLE("Toggle"), STICKY("Sticky"),
}

/** When a [Output.FIRE_ONCE] output pulses: as the trigger starts, as it ends, or both. */
@Serializable
enum class FireAt(val label: String) { START("At start"), END("At end"), BOTH("Both") }

/**
 * Where a finger counts for a touch button's or stick's mapping. BUTTON: it touched down on the
 * control and is still down. AREA: it is inside the control's area, wherever it touched down
 * (sliding in counts as pressing). OUTSIDE: it touched down on the control and is now outside it.
 * Each Where runs its own taps and timings.
 */
@Serializable
enum class Where(val label: String) { BUTTON("On button"), AREA("Area"), OUTSIDE("Outside") }

/**
 * A mapping's trigger: when it fires on its action ([on], [taps]), where a finger counts
 * ([where], touch buttons and sticks only) and how its output behaves ([output], [fireAt]).
 * [repeat]: a Touch direction's Drag + Fire once fires again every swipe distance of travel.
 * The default is Press + Any, Held, on the button: the output follows the action.
 */
@Serializable
data class Trigger(
    @SerialName("when") val on: When = When.PRESS,
    val taps: Taps = Taps.ANY,
    val output: Output = Output.HELD,
    val fireAt: FireAt = FireAt.START,
    val where: Where = Where.BUTTON,
    val repeat: Boolean = false,
) {
    /** Which slot of its action this fills: one mapping per action and slot per level or layer ([Mapping.slot]). */
    val slot: TriggerSlot get() = TriggerSlot(on, taps, where)

    /** It needs the action's tap, double and long press tracking (not just pressed or not). */
    val gestures: Boolean get() = on != When.PRESS || taps != Taps.ANY

    /** The output just follows the trigger ([Output.HELD]). */
    val plain: Boolean get() = output == Output.HELD

    /** Press + Any, Held, on the button: the mapping follows its action as it is. */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = Trigger()
        val TAP = Trigger(on = When.TAP)
        val LONG_PRESS = Trigger(on = When.LONG_PRESS)
        val RELEASE = Trigger(on = When.RELEASE)
        val DOUBLE_TAP = Trigger(on = When.TAP, taps = Taps.DOUBLE)
        val DOUBLE_PRESS = Trigger(taps = Taps.DOUBLE)

        /** A Touch direction's swipe: fires once every swipe distance of travel. */
        val SWIPE = Trigger(output = Output.FIRE_ONCE, repeat = true)
    }
}

/** The parts of a [Trigger] that say which press it is: an action has one mapping list per slot. */
data class TriggerSlot(val on: When, val taps: Taps, val where: Where) {
    companion object {
        val DEFAULT = Trigger.DEFAULT.slot
    }
}
