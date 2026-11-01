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
import androidx.compose.material3.AlertDialog
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
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
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
internal const val WHERE_AREA = "Area"
internal const val WHERE_OUTSIDE = "Outside"

private fun whereHint(w: String?): String = when (w) {
    WHERE_AREA -> "Any finger inside the button, wherever it touched down (sliding in counts)."
    WHERE_OUTSIDE -> "A finger that touched down on the button, now outside it."
    else -> "A touch that starts on the button."
}

/** When each trigger starts on an Area ([inside]) or Outside. */
private fun areaHint(t: Trigger, inside: Boolean): String {
    val into = if (inside) "slides in" else "leaves the button"
    val back = if (inside) "slides out or lifts" else "comes back or lifts"
    return when (t) {
        Trigger.HOLD -> "Starts as a finger $into, ends as it $back."
        Trigger.TAP -> "When a finger $into and $back before the long-press time."
        Trigger.LONG_PRESS -> "Starts once a finger has stayed there for the long-press time."
        Trigger.DOUBLE_TAP -> "A second time soon after a quick one; ends as it $back."
        Trigger.RELEASE -> "When the finger $back."
        Trigger.PRESS -> ""
    }
}

private fun howHint(h: How): String = when (h) {
    How.HELD -> "Pressed for as long as it lasts."
    How.ONCE -> "One short press as it starts, however long it lasts."
    How.TOGGLE -> "Each time it starts it turns on, the next time off."
}

/** A swipe direction's On swipe Toggle with Tap: the latch. */
private const val LATCH_HINT =
    "It stays on after you lift. Swiping the same way carries on, another direction takes over, and a tap on this surface ends it (that tap does nothing else)."

/** A swipe direction's On swipe Toggle without Tap. */
private const val SWIPE_TOGGLE_HINT =
    "A swipe this way turns it on, and it stays on after you lift; the next swipe this way turns it off. Tick Tap to make it a latch instead."

/**
 * The settings of one mapping (a chip): When it starts ([triggers] offered, named by
 * [triggerLabel]; one = none to pick), How it outputs, and its options. [swipe]: a swipe direction's. [onApply] gets the trigger and options picked (null = none); [onRemove]
 * removes the mapping. [onTargetSettings] (a buzz's): a button that opens the target's own settings.
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
    onApply: (Trigger, MapOptions?, Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onTargetSettings: (() -> Unit)? = null,
    /** Where the chip can be (Touch, Area, Outside), and where it is; none = no choice. */
    wheres: List<String> = emptyList(),
    where: Int = 0,
) {
    val o0 = options ?: MapOptions()
    var t by remember { mutableStateOf(trigger) }
    var w by remember { mutableIntStateOf(where) }
    var how by remember { mutableStateOf(o0.mode) }
    var cycle by remember { mutableStateOf(o0.cycle) }
    var start by remember { mutableIntStateOf(o0.startDelayMs) }
    var end by remember { mutableIntStateOf(o0.endDelayMs) }
    var minHold by remember { mutableIntStateOf(o0.minHoldMs) }
    var endsOnTap by remember { mutableStateOf(o0.endsOnTap) }
    var endsOnLetGo by remember { mutableStateOf(o0.endsOnLetGo) }
    var endsAfter by remember { mutableIntStateOf(o0.endsAfterMs) }
    var endsOnNext by remember { mutableStateOf(o0.endsOnNextInput) }
    var endsOnSame by remember { mutableStateOf(o0.endsOnSame) }
    var advanced by remember { mutableStateOf(start > 0 || end > 0 || minHold > 0) }
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
    fun msSlider(label: String, value: Int, onChange: (Int) -> Unit) {
        Text("$label: " + if (value == 0) "off" else "$value ms", style = MaterialTheme.typography.labelLarge)
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
                if (wheres.size > 1) {
                    Text("Where", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        wheres.forEachIndexed { i, label -> FilterChip(selected = w == i, onClick = { w = i }, label = { Text(label) }) }
                    }
                    hint(whereHint(wheres.getOrNull(w)))
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
                val hows = How.entries.filter { (it != How.HELD || t.holds) && (it != How.TOGGLE || !analog || how == How.TOGGLE) }
                val shown = if (how == How.HELD && !t.holds) How.ONCE else how
                val latch = swipe && t == Trigger.HOLD
                Text("How", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (x in hows) FilterChip(selected = shown == x, onClick = { how = x }, label = { Text(x.label) })
                }
                hint(if (shown == How.TOGGLE && latch) (if (endsOnTap) LATCH_HINT else SWIPE_TOGGLE_HINT) else howHint(shown))
                if (shown == How.TOGGLE) {
                    Text("Also ends on", style = MaterialTheme.typography.titleSmall)
                    checkRow("Tap (touching this " + (if (swipe) "surface" else "button") + " again)", endsOnTap) {
                        endsOnTap = it
                    }
                    checkRow("\"Let go\" mapping (any button mapped to (Let go))", endsOnLetGo) { endsOnLetGo = it }
                    checkRow("Next input (another key or button pressed and let go, like a sticky Shift)", endsOnNext) { endsOnNext = it }
                    checkRow("Same keystroke (this key or button pressed by any other mapping)", endsOnSame) { endsOnSame = it }
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
                switchRow("Cycle", cycle) { cycle = it }
                hint("The mappings of this action with Cycle on take turns: each time it fires, the next one.")
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (advanced) "Advanced ▴" else "Advanced ▾")
                }
                if (advanced) {
                    msSlider("Start delay", start) { start = it }
                    msSlider("Hold at least", minHold) { minHold = it }
                    msSlider("End delay", end) { end = it }
                    hint("Start delay: it starts this much later. Hold at least: it lasts at least this long. End delay: it lasts this much longer after you let go.")
                }
                TextButton(onClick = onRemove) { Text("Remove this mapping", color = colors.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val toggle = how == How.TOGGLE
                val o = MapOptions(
                    // At a lift Held and Once are the same short press: kept as Held (no options).
                    how = if (how == How.ONCE && !t.holds) How.HELD else how, cycle = cycle,
                    startDelayMs = start, endDelayMs = end, minHoldMs = minHold,
                    endsOnTap = toggle && endsOnTap,
                    endsOnLetGo = toggle && endsOnLetGo,
                    endsAfterMs = if (toggle) endsAfter else 0,
                    endsOnNextInput = toggle && endsOnNext,
                    endsOnSame = toggle && endsOnSame,
                )
                onApply(t, o.takeUnless { it.none }, w)
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
