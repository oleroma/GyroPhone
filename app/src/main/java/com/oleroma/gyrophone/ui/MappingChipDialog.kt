package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.AxisRange
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.PresetLevel
import com.oleroma.gyrophone.core.model.SetValue
import com.oleroma.gyrophone.core.model.Trigger
import kotlin.math.roundToInt

/** When each trigger starts, under the When chips ([swipe]: a swipe direction's). */
private fun triggerHint(t: Trigger, swipe: Boolean): String = if (swipe) when (t) {
    Trigger.HOLD -> "Starts as the swipe reaches its hold distance, ends when you let go."
    Trigger.TAP -> "Each quick swipe, as you swipe."
    Trigger.LONG_PRESS -> "Starts once the swipe is held for the long-press time."
    Trigger.RELEASE -> "When you lift while in this zone (sliding on into the next zone does not fire it)."
    Trigger.DOUBLE_TAP -> "A second quick swipe soon after the first."
    Trigger.PRESS -> ""
} else when (t) {
    Trigger.HOLD -> "Starts at touch-down, ends when you let go."
    Trigger.PRESS -> ""
    Trigger.TAP -> "When you let go before the long-press time."
    Trigger.LONG_PRESS -> "Starts at the long-press time (then it is no tap), ends when you let go."
    Trigger.RELEASE -> "Every time you let go."
    Trigger.DOUBLE_TAP -> "A second touch soon after a tap; ends when you let go."
}

/** The Where choices' names. */
internal const val WHERE_TOUCH = "Touch"
internal const val WHERE_AREA = "Inside"
internal const val WHERE_OUTSIDE = "Outside"

/** What a swipe chip's direction [d] (Any, ↑ → ↓ ←) means. */
private fun directionHint(d: String?): String = when (d) {
    "Any" -> "Any way: how far the finger is from where it touched down, whichever way it went. Its zones go by that distance."
    else -> "Only toward $d, inside its pie (the Swipe ⚙ sets each arrow's own pie and distances)."
}

private fun whereHint(w: String?): String = when (w) {
    WHERE_AREA -> "Any finger inside the button, wherever it touched down (sliding in counts)."
    WHERE_OUTSIDE -> "A finger that touched down on the button, now outside it."
    else -> "A touch that starts on the button."
}

/**
 * When each trigger starts Inside ([inside]) or Outside: the same rules as a touch, with entering
 * as the touch-down and leaving as the lift.
 */
private fun areaHint(t: Trigger, inside: Boolean): String {
    val into = if (inside) "slides in" else "leaves the button"
    val back = if (inside) "slides out or lifts" else "comes back or lifts"
    return when (t) {
        Trigger.HOLD -> "Starts as a finger $into, ends as it $back."
        Trigger.TAP -> "When a finger $into and $back before the long-press time."
        Trigger.LONG_PRESS -> "Starts once a finger has stayed for the long-press time (then it is no tap), ends as it $back."
        Trigger.DOUBLE_TAP -> "A second time soon after a quick one (then that is no tap); ends as it $back."
        Trigger.RELEASE -> "Every time a finger $back."
        Trigger.PRESS -> ""
    }
}

private fun howHint(h: How): String = when (h) {
    How.HELD -> "Pressed for as long as it lasts."
    How.ONCE -> "One short press as it starts, however long it lasts."
    How.TOGGLE -> "Each time it starts it turns on, the next time off."
    How.REPEAT -> "That many short presses one after another as it starts (×2 on a mouse button: a double click)."
}

/** A swipe direction's On swipe Toggle with Tap: the latch. */
private const val LATCH_HINT =
    "It stays on after you lift. Swiping the same way carries on, another direction takes over, and a tap on this surface ends it (that tap does nothing else)."

/** A swipe direction's On swipe Toggle without Tap. */
private const val SWIPE_TOGGLE_HINT =
    "A swipe this way turns it on, and it stays on after you lift; the next swipe this way turns it off. Tick Tap to make it a latch instead."

/**
 * An axis chip's Range ([AxisRange]) as its dialog shows it: [current] (null = none set), stored
 * values shown through [shown] and back through [stored], on a slider over [bounds] (typed up to
 * [typed]) in steps of [step], each value written by [format]. A range equal to [identity] is
 * kept as none; [initial] is what a chip without one starts from.
 */
internal class ChipRange(
    val current: AxisRange?,
    val initial: AxisRange,
    val identity: AxisRange?,
    val bounds: ClosedFloatingPointRange<Float>,
    val typed: ClosedFloatingPointRange<Float>,
    val step: Float,
    val format: (Float) -> String,
    val hint: String,
    val shown: (Float) -> Float = { it },
    val stored: (Float) -> Float = { it },
)

/** How Toggle on a value chip ([ChipValue]). */
private const val VALUE_TOGGLE_HINT = "Keeps the value when you lift your finger, until one of the ends below. A sensitivity " +
    "with no end stays for good as this workspace's slider override: the settings themselves don't change, and the " +
    "sensitivity's \"own\" list in Settings shows it (Reset children clears it). The button shows a sliders icon in its top-left corner."

/** How Held on a value chip ([ChipValue]). */
private const val VALUE_HELD_HINT = "The value counts only while your finger is down, and goes back when you lift it."

/**
 * A value chip on a Position or Move input ([Mapping.setValue], [Mapping.perDp]) as its dialog
 * shows it: How is only Held or Toggle (it keeps the value), [initial] the ends a new Toggle starts
 * with; [move]: a Move input's chip (Per dp, and Tap among the ends); the per-dp value stored
 * [perDp] (null = [defaultPerDp]), shown through [shown] and written by [format], its slider up to
 * [max] (shown).
 */
internal class ChipValue(
    val initial: SetValue,
    val move: Boolean,
    val perDp: Float?,
    val defaultPerDp: Float,
    val max: Float,
    val step: Float,
    val format: (Float) -> String,
    val shown: (Float) -> Float = { it },
    val stored: (Float) -> Float = { it },
)

/**
 * The settings of one mapping (a chip): When it starts ([triggers] offered, named by
 * [triggerLabel]; one = none to pick), How it outputs, and its options. [swipe]: a swipe direction's. [onApply] gets the trigger and options picked (null = none); [onRemove]
 * removes the mapping. [onTargetSettings] (a buzz's): a button that opens the target's own settings.
 * [targetOptions]: the target's own settings shown in the dialog (Set orientation's Granular).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MappingChipDialog(
    title: String,
    triggers: List<Trigger>,
    trigger: Trigger,
    /** What trigger [Trigger] is called where the chip is (an index of [wheres]; 0 when there are none). */
    triggerLabel: (Int, Trigger) -> String,
    swipe: Boolean,
    analog: Boolean,
    options: MapOptions?,
    onApply: (Trigger, MapOptions?, Int, AxisRange?, Float?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onTargetSettings: (() -> Unit)? = null,
    targetOptions: (@Composable () -> Unit)? = null,
    /** Where the chip can be (Touch, Area, Outside; a swipe's directions), and where it is; none = no choice. */
    wheres: List<String> = emptyList(),
    where: Int = 0,
    /** What the choice of [wheres] is called ("Direction" for a swipe's); null = "Where". */
    whereTitle: String? = null,
    /** An axis chip's output range; null = it has none. */
    range: ChipRange? = null,
    /** Saves the chip (as it is mapped now) as a chip preset; null = none. */
    onSavePreset: (() -> Unit)? = null,
    /** A value chip on a Position or Move input: Held or Toggle (keeps the value) and its Per dp; null = none. */
    value: ChipValue? = null,
    /** A chip on a Position input: its next touch already ends a Toggle, so there is no Tap end. */
    position: Boolean = false,
) {
    var perDp by remember { mutableStateOf(value?.let { it.shown(it.perDp ?: it.defaultPerDp) } ?: 0f) }
    val o0 = options ?: MapOptions()
    val r0 = range?.let { it.current ?: it.initial }
    var rFrom by remember { mutableStateOf(r0?.let { range.shown(it.from) } ?: 0f) }
    var rTo by remember { mutableStateOf(r0?.let { range.shown(it.to) } ?: 0f) }
    var t by remember { mutableStateOf(trigger) }
    var w by remember { mutableIntStateOf(where) }
    var how by remember { mutableStateOf(o0.mode) }
    var cycle by remember { mutableStateOf(o0.cycle) }
    var repeat by remember { mutableIntStateOf(o0.repeat.coerceIn(MapOptions.MIN_REPEAT, MapOptions.MAX_REPEAT)) }
    var start by remember { mutableIntStateOf(o0.startDelayMs) }
    var end by remember { mutableIntStateOf(o0.endDelayMs) }
    var minHold by remember { mutableIntStateOf(o0.minHoldMs) }
    var endsOnTap by remember { mutableStateOf(o0.endsOnTap) }
    var endsOnLetGo by remember { mutableStateOf(o0.endsOnLetGo) }
    var endsAfter by remember { mutableIntStateOf(o0.endsAfterMs) }
    var endsOnNext by remember { mutableStateOf(o0.endsOnNextInput) }
    var endsOnSame by remember { mutableStateOf(o0.endsOnSame) }
    var advanced by remember { mutableStateOf(start > 0 || end > 0 || minHold != MapOptions.DEFAULT_MIN_HOLD_MS) }
    val colors = MaterialTheme.colorScheme

    @Composable
    fun hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

    @Composable
    fun switchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }

    @Composable
    fun checkRow(label: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = value, onCheckedChange = onChange, enabled = enabled)
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }

    @Composable
    fun msSlider(label: String, value: Int, default: Int = 0, onChange: (Int) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label: " + if (value == 0) "off" else "$value ms", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f))
            if (default != 0 && value != default) TextButton(onClick = { onChange(default) }) { Text("Reset to $default ms") }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it / 10f).roundToInt() * 10) },
            valueRange = 0f..MapOptions.MAX_DELAY_MS.toFloat(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onTargetSettings != null) {
                    OutlinedButton(onClick = onTargetSettings, modifier = Modifier.fillMaxWidth()) { Text("Buzz settings") }
                }
                targetOptions?.invoke()
                if (range != null) {
                    Text("Range " + range.format(rFrom) + " – " + range.format(rTo), style = MaterialTheme.typography.titleSmall)
                    TwoHandleSlider(
                        rFrom, rTo, range.bounds, range.step,
                        onChange = { a, b -> rFrom = a; rTo = b },
                        onDone = { a, b -> rFrom = a; rTo = b },
                        typedRange = range.typed,
                        fromTitle = "Range from", toTitle = "Range to",
                    )
                    hint(range.hint)
                }
                if (value != null) {
                    if (value.move) {
                        Text("Per dp: " + value.format(perDp), style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = perDp.coerceIn(0f, value.max),
                            onValueChange = { perDp = (it / value.step).roundToInt() * value.step },
                            valueRange = 0f..value.max,
                        )
                        hint("How much the value changes for each dp the finger slides (right or up raises it). A tap changes nothing.")
                    }
                }
                if (wheres.size > 1) {
                    Text(whereTitle ?: "Where", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        wheres.forEachIndexed { i, label ->
                            FilterChip(selected = w == i, onClick = { w = i }, label = { if (whereTitle == "Direction") DirectionLabel(label) else Text(label) })
                        }
                    }
                    hint(
                        when (whereTitle) {
                            "Direction" -> directionHint(wheres.getOrNull(w))
                            "Axis" -> "Which axis of the card this chip follows."
                            else -> whereHint(wheres.getOrNull(w))
                        }
                    )
                }
                if (triggers.size > 1) {
                    Text("When", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (x in triggers) FilterChip(selected = t == x, onClick = { t = x }, label = { Text(triggerLabel(w, x)) })
                    }
                    hint(if (wheres.getOrNull(w).let { it == WHERE_AREA || it == WHERE_OUTSIDE }) areaHint(t, wheres[w] == WHERE_AREA) else triggerHint(t, swipe))
                }
                // Tap and Release start at a lift: nothing is held, so Held is Once there.
                // An analog output (a stick, a trigger, mouse motion) follows its input: no Toggle.
                // A value chip on Position or Move: Held, or Toggle to keep the value.
                val hows = if (value != null) listOf(How.HELD, How.TOGGLE) else How.entries.filter {
                    (it != How.HELD || t.holds) && (it != How.TOGGLE || !analog || how == How.TOGGLE) && (it != How.REPEAT || !analog || how == How.REPEAT)
                }
                val shown = if (how == How.HELD && !t.holds) How.ONCE else how
                val latch = swipe && t == Trigger.HOLD
                Text("How", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (x in hows) FilterChip(selected = shown == x, onClick = {
                        // A value chip's first Toggle starts with its usual ends (a stick or trigger: Let go).
                        if (value != null && x == How.TOGGLE && how != How.TOGGLE && !endsOnTap && !endsOnLetGo && !endsOnNext && endsAfter == 0) {
                            endsOnLetGo = value.initial.endsOnLetGo
                        }
                        how = x
                    }, label = { Text(x.label) })
                }
                hint(
                    when {
                        value != null && shown == How.TOGGLE -> VALUE_TOGGLE_HINT
                        value != null -> VALUE_HELD_HINT
                        shown == How.TOGGLE && latch -> if (endsOnTap) LATCH_HINT else SWIPE_TOGGLE_HINT
                        else -> howHint(shown)
                    }
                )
                if (shown == How.REPEAT) {
                    Text("Times", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in MapOptions.MIN_REPEAT..MapOptions.MAX_REPEAT) {
                            FilterChip(selected = repeat == n, onClick = { repeat = n }, label = { Text("×$n") })
                        }
                    }
                }
                if (t == Trigger.DOUBLE_TAP) {
                    hint("With a Held chip and others on Double tap, the second touch decides: a quick lift fires the others, staying down or moving fires the Held one.")
                }
                if (shown == How.TOGGLE) {
                    Text("Also ends on", style = MaterialTheme.typography.titleSmall)
                    // A value chip: a tap on a Position input sets the value, so only Move ends on one; no keystroke to match.
                    if (if (value != null) value.move else !position) checkRow(
                        if (value != null) "Tap (a tap on this button)" else "Tap (touching this " + (if (swipe) "surface" else "button") + " again)",
                        endsOnTap,
                    ) { endsOnTap = it }
                    checkRow("\"Let go\" mapping (any button mapped to (Let go))", endsOnLetGo) { endsOnLetGo = it }
                    checkRow("Next input (another key or button pressed and let go, like a sticky Shift)", endsOnNext) { endsOnNext = it }
                    if (value == null) checkRow("Same keystroke (this key or button pressed by any other mapping)", endsOnSame) { endsOnSame = it }
                    Text(
                        "Timer: " + if (endsAfter == 0) "off" else "%.1f s".format(endsAfter / 1000f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = endsAfter / 1000f,
                        onValueChange = { endsAfter = ((it * 10f).roundToInt() * 100) },
                        valueRange = 0f..MapOptions.MAX_TOGGLE_MS / 1000f,
                    )
                }
                // A value chip sends a value, not presses: no Cycle, delays or hold time.
                if (value == null) {
                    switchRow("Cycle", cycle) { cycle = it }
                    hint("The mappings of this action with Cycle on take turns: each time it fires, the next one.")
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(if (advanced) "Advanced ▴" else "Advanced ▾")
                    }
                }
                if (advanced && value == null) {
                    msSlider("Start delay", start) { start = it }
                    msSlider("Hold time", minHold, MapOptions.DEFAULT_MIN_HOLD_MS) { minHold = it }
                    msSlider("End delay", end) { end = it }
                    hint("Start delay: it starts this much later. Hold time: it lasts at least this long (80 ms by default, so apps that check keys now and then, like OBS hotkeys, see a quick tap). End delay: it lasts this much longer after you let go.")
                }
                if (onSavePreset != null) TextButton(onClick = onSavePreset) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PresetIcon(PresetLevel.CHIP, colors.primary)
                        Text("Save as chip preset")
                    }
                }
                TextButton(onClick = onRemove) { Text("Remove this mapping", color = colors.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val toggle = how == How.TOGGLE
                val o = MapOptions(
                    // At a lift Held and Once are the same short press: kept as Held (no options).
                    how = if (how == How.ONCE && !t.holds) How.HELD else how, cycle = cycle && value == null,
                    startDelayMs = if (value == null) start else 0, endDelayMs = if (value == null) end else 0,
                    minHoldMs = if (value == null) minHold else MapOptions.DEFAULT_MIN_HOLD_MS,
                    endsOnTap = toggle && endsOnTap && (if (value != null) value.move else !position),
                    endsOnLetGo = toggle && endsOnLetGo,
                    endsAfterMs = if (toggle) endsAfter else 0,
                    endsOnNextInput = toggle && endsOnNext,
                    endsOnSame = toggle && endsOnSame && value == null,
                    repeat = if (how == How.REPEAT) repeat else 2,
                )
                val r = range?.let { AxisRange(it.stored(rFrom), it.stored(rTo)) }?.takeUnless { it == range.identity }
                val pd = value?.takeIf { it.move }?.let { v -> v.stored(perDp).takeUnless { kotlin.math.abs(it - v.defaultPerDp) < 1e-6f } }
                onApply(t, o.takeUnless { it.none }, w, r, pd)
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
