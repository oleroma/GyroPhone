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
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Trigger
import kotlin.math.roundToInt

/** What each trigger does, under the When chips. */
private fun triggerHint(t: Trigger): String = when (t) {
    Trigger.HOLD -> "Held from touch-down until you let go."
    Trigger.PRESS -> "A short press as you touch down, however long you hold."
    Trigger.TAP -> "A short press as you let go, if you let go before the long-press time."
    Trigger.LONG_PRESS -> "Held from the long-press time until you let go (then it is no tap)."
    Trigger.RELEASE -> "A short press every time you let go."
    Trigger.DOUBLE_TAP -> "Held from a second touch soon after a tap until you let go."
}

/**
 * The settings of one mapping (a chip): when it fires ([triggers] offered; Hold only = none to
 * pick) and its options. [onApply] gets the trigger and options picked (null = none); [onRemove]
 * removes the mapping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MappingChipDialog(
    title: String,
    triggers: List<Trigger>,
    trigger: Trigger,
    options: MapOptions?,
    onApply: (Trigger, MapOptions?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val o0 = options ?: MapOptions()
    var t by remember { mutableStateOf(trigger) }
    var toggle by remember { mutableStateOf(o0.toggle) }
    var turbo by remember { mutableStateOf(o0.turbo) }
    var rate by remember { mutableIntStateOf(o0.rate) }
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
                        for (x in triggers) FilterChip(selected = t == x, onClick = { t = x }, label = { Text(x.label) })
                    }
                    hint(triggerHint(t))
                }
                switchRow("Toggle", toggle) { toggle = it }
                hint("Each time it fires it turns on, the next time off.")
                switchRow("Turbo", turbo) { turbo = it }
                if (turbo) {
                    Text("$rate presses a second", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = rate.toFloat(),
                        onValueChange = { rate = it.roundToInt() },
                        valueRange = MapOptions.MIN_TURBO_RATE.toFloat()..MapOptions.MAX_TURBO_RATE.toFloat(),
                    )
                }
                hint("While on, it presses and lets go over and over.")
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
                    toggle = toggle, turbo = turbo, turboRate = if (turbo) rate else MapOptions.DEFAULT_TURBO_RATE, cycle = cycle,
                    startDelayMs = start, endDelayMs = end, minHoldMs = minHold,
                )
                onApply(t, o.takeUnless { it.none })
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
