package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * When in a press a mapping fires: PRESS as the press starts (held while it lasts), LIFT as it
 * lifts (one pulse). On a Touch direction reaching the direction is the press and leaving it
 * (or lifting) is the lift.
 */
@Serializable
enum class FiresOn(val label: String) { PRESS("Press"), LIFT("Lift") }

/**
 * Which press counts. SINGLE: a press that is not the second of a double; with Duration Any it is
 * every press (Hold fires on every press, Release on every lift). DOUBLE: the second press, one
 * that comes within the second tap window after a quick lift.
 */
@Serializable
enum class Taps(val label: String) { SINGLE("Single"), DOUBLE("Double") }

/**
 * How long the press lasts. ANY: any press. QUICK: lifted within the tap window (the second
 * press of a double: within the second tap release window) and stayed clean. LONG: held past
 * it. A press is Quick or Long, never both.
 */
@Serializable
enum class Duration(val label: String) { ANY("Any"), QUICK("Quick"), LONG("Long") }

/**
 * How the output behaves once the trigger fires. HELD: on while the trigger is on (a Lift
 * trigger is a short pulse). FIRE_ONCE: a short pulse as the trigger starts, ends or both
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

/** A named combination of the three trigger choices; [touchLabel] is its name on a Touch direction. */
enum class TriggerPreset(val label: String, val touchLabel: String, val fires: FiresOn, val taps: Taps, val duration: Duration) {
    HOLD("Hold", "Drag", FiresOn.PRESS, Taps.SINGLE, Duration.ANY),
    LONG_PRESS("Long press", "Long drag", FiresOn.PRESS, Taps.SINGLE, Duration.LONG),
    RELEASE("Release", "Release", FiresOn.LIFT, Taps.SINGLE, Duration.ANY),
    TAP("Tap", "Swipe", FiresOn.LIFT, Taps.SINGLE, Duration.QUICK),
    DOUBLE_HOLD("Double tap hold", "Swipe hold", FiresOn.PRESS, Taps.DOUBLE, Duration.ANY),
    DOUBLE_TAP("Double tap", "Double swipe", FiresOn.LIFT, Taps.DOUBLE, Duration.QUICK),
}

/**
 * A mapping's trigger: when it fires on its action ([fires] × [taps] × [duration]), where a finger
 * counts ([where], touch buttons and sticks only) and how its output behaves ([output], [fireAt]).
 * [dontWait]: a Tap (Lift, Single, Quick) fires at once on every quick lift, even when the action
 * has a Double mapping (like a double click in Windows). [repeat]: a Touch direction's Drag +
 * Fire once fires again every swipe distance of travel. The default is Hold, Held, on the button:
 * the output follows the action.
 */
@Serializable
data class Trigger(
    val fires: FiresOn = FiresOn.PRESS,
    val taps: Taps = Taps.SINGLE,
    val duration: Duration = Duration.ANY,
    val dontWait: Boolean = false,
    val output: Output = Output.HELD,
    val fireAt: FireAt = FireAt.START,
    val where: Where = Where.BUTTON,
    val repeat: Boolean = false,
) {
    /** Which slot of its action this fills: one mapping per action and slot per level or layer ([Mapping.slot]). */
    val slot: TriggerSlot get() = TriggerSlot(fires, taps, duration, dontWait && isTap, where)

    /** It needs the action's tap, double and long press tracking (not just pressed or not). */
    val gestures: Boolean get() = fires != FiresOn.PRESS || taps != Taps.SINGLE || duration != Duration.ANY

    /** It fires as a pulse (on a lift), not for as long as the press. */
    val pulse: Boolean get() = fires == FiresOn.LIFT

    /** It is a Tap (Lift, Single, Quick): the only trigger [dontWait] applies to. */
    val isTap: Boolean get() = fires == FiresOn.LIFT && taps == Taps.SINGLE && duration == Duration.QUICK

    /** The preset it matches, or null for a custom one. */
    val preset: TriggerPreset? get() = TriggerPreset.entries.firstOrNull { it.fires == fires && it.taps == taps && it.duration == duration }

    /** The output just follows the trigger ([Output.HELD]). */
    val plain: Boolean get() = output == Output.HELD

    /** Hold, Held, on the button: the mapping follows its action as it is. */
    val isDefault: Boolean get() = this == DEFAULT

    /** This trigger with [p]'s three choices. */
    fun with(p: TriggerPreset): Trigger = copy(fires = p.fires, taps = p.taps, duration = p.duration).fixed()

    /** Drops options that no longer apply: Don't wait off a non-Tap, repeat off a non-Drag. */
    fun fixed(): Trigger {
        var t = this
        if (t.dontWait && !t.isTap) t = t.copy(dontWait = false)
        if (t.repeat && t.gestures) t = t.copy(repeat = false)
        return t
    }

    companion object {
        val DEFAULT = Trigger()
        val TAP = Trigger(fires = FiresOn.LIFT, duration = Duration.QUICK)

        /** A Tap that never waits for a double ([dontWait]). */
        val TAP_NOW = TAP.copy(dontWait = true)
        val LONG_PRESS = Trigger(duration = Duration.LONG)
        val RELEASE = Trigger(fires = FiresOn.LIFT)
        val DOUBLE_TAP = Trigger(fires = FiresOn.LIFT, taps = Taps.DOUBLE, duration = Duration.QUICK)
        val DOUBLE_PRESS = Trigger(taps = Taps.DOUBLE)

        /** A Touch direction's swipe: fires once every swipe distance of travel. */
        val SWIPE = Trigger(output = Output.FIRE_ONCE, repeat = true)
    }
}

/** The parts of a [Trigger] that say which press it is: an action has one mapping list per slot. */
data class TriggerSlot(val fires: FiresOn, val taps: Taps, val duration: Duration, val now: Boolean, val where: Where) {
    companion object {
        val DEFAULT = Trigger.DEFAULT.slot
    }
}

/**
 * Reads a mapping's trigger, also as saved by the earlier triggers build ("when" = PRESS / TAP /
 * LONG_PRESS / RELEASE, "taps" = ANY / SINGLE / DOUBLE), converting it to the same trigger.
 */
object TriggerJson : JsonTransformingSerializer<Trigger>(Trigger.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject || "when" !in element && element["taps"]?.str() != "ANY") return element
        val on = element["when"]?.str() ?: "PRESS"
        val taps = element["taps"]?.str() ?: "ANY"
        val out = element.toMutableMap()
        out.remove("when")
        val double = taps == "DOUBLE"
        out["taps"] = JsonPrimitive(if (double) "DOUBLE" else "SINGLE")
        when (on) {
            "TAP" -> {
                out["fires"] = JsonPrimitive("LIFT"); out["duration"] = JsonPrimitive("QUICK")
                if (taps == "ANY") out["dontWait"] = JsonPrimitive(true)
            }

            "LONG_PRESS" -> out["duration"] = JsonPrimitive("LONG")
            "RELEASE" -> out["fires"] = JsonPrimitive("LIFT")
        }
        return JsonObject(out)
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.jsonPrimitive?.contentOrNull
}
