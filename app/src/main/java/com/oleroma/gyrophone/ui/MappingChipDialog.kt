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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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

private fun howHint(h: How): String = when (h) {
    How.HELD -> "Pressed for as long as it lasts."
    How.ONCE -> "One short press as it starts, however long it lasts."
    How.TOGGLE -> "Each time it starts it turns on, the next time off."
}

/**
 * The settings of one mapping (a chip): When it starts ([triggers] offered, named by
 * [triggerLabel]; one = none to pick), How it outputs, and its options. [swipe]: a swipe direction's. [onApply] gets the trigger and options picked (null = none); [onRemove]
 * removes the mapping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MappingChipDialog(
    title: String,
    triggers: List<Trigger>,
    trigger: Trigger,
    triggerLabel: (Trigger) -> String,
    swipe: Boolean,
    options: MapOptions?,
    onApply: (Trigger, MapOptions?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val o0 = options ?: MapOptions()
    var t by remember { mutableStateOf(trigger) }
    var how by remember { mutableStateOf(o0.mode) }
    var cycle by remember { mutableStateOf(o0.cycle) }
    var start by remember { mutableIntStateOf(o0.startDelayMs) }
    var end by remember { mutableIntStateOf(o0.endDelayMs) }
    var minHold by remember { mutableIntStateOf(o0.minHoldMs) }
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
                if (triggers.size > 1) {
                    Text("When", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (x in triggers) FilterChip(selected = t == x, onClick = { t = x }, label = { Text(triggerLabel(x)) })
                    }
                    hint(triggerHint(t, swipe))
                }
                // Tap and Release start at a lift: nothing is held, so Held is Once there.
                val hows = How.entries.filter { it != How.HELD || t.holds }
                val shown = if (how == How.HELD && !t.holds) How.ONCE else how
                Text("How", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (x in hows) FilterChip(selected = shown == x, onClick = { how = x }, label = { Text(x.label) })
                }
                hint(howHint(shown))
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
                val o = MapOptions(
                    // At a lift Held and Once are the same short press: kept as Held (no options).
                    how = if (how == How.ONCE && !t.holds) How.HELD else how, cycle = cycle,
                    startDelayMs = start, endDelayMs = end, minHoldMs = minHold,
                )
                onApply(t, o.takeUnless { it.none })
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
