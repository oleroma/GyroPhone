package com.oleroma.gyrophone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.Duration
import com.oleroma.gyrophone.core.model.FireAt
import com.oleroma.gyrophone.core.model.FiresOn
import com.oleroma.gyrophone.core.model.Output
import com.oleroma.gyrophone.core.model.Taps
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.TriggerPreset
import com.oleroma.gyrophone.core.model.Where

/**
 * A chip's trigger: a preset (Hold, Long press, Release, Tap, Double tap hold, Double tap; on a
 * Touch direction [direction] Drag, Long drag, Release, Swipe, Swipe hold, Double swipe) or the
 * three choices under it (Fires on, Taps, Duration; other mixes show as Custom), a Tap's Don't
 * wait, how its Output behaves and, on a touch button or stick ([hasWhere]), Where a finger
 * counts. A Fire once Drag may repeat every swipe distance. Each pick applies at once.
 */
@Composable
internal fun TriggerDialog(
    title: String,
    trigger: Trigger,
    hasWhere: Boolean,
    direction: Boolean,
    onChange: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    val window = if (direction) "swipe window" else "tap window"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val presets: List<TriggerPreset?> = TriggerPreset.entries + listOf(null)
                Choices("Trigger", presets, trigger.preset, { p ->
                    if (p == null) "Custom" else if (direction) p.touchLabel else p.label
                }, enabled = { it != null }) { p -> if (p != null) onChange(trigger.with(p)) }
                TriggerHint(
                    when (trigger.preset) {
                        TriggerPreset.HOLD -> if (direction) "While the finger is in the direction." else "On every press, for as long as it lasts."
                        TriggerPreset.LONG_PRESS -> "Once the press has lasted past the $window, until it lifts."
                        TriggerPreset.RELEASE -> if (direction) "As the finger leaves the direction or lifts." else "On every lift."
                        TriggerPreset.TAP -> "A quick press, on its lift (within the $window). A press is quick or long, never both."
                        TriggerPreset.DOUBLE_HOLD -> "A second press soon after a quick one, for as long as it lasts."
                        TriggerPreset.DOUBLE_TAP -> "A second quick press soon after a quick one, on its lift."
                        null -> "A custom mix of the choices below."
                    },
                )
                Choices("Fires on", FiresOn.entries, trigger.fires, { it.label }) { onChange(trigger.copy(fires = it).fixed()) }
                Choices("Taps", Taps.entries, trigger.taps, { it.label }) { onChange(trigger.copy(taps = it).fixed()) }
                Choices("Duration", Duration.entries, trigger.duration, { it.label }) { onChange(trigger.copy(duration = it).fixed()) }
                TriggerHint(
                    when (trigger.duration) {
                        Duration.ANY -> if (trigger.taps == Taps.SINGLE) "Any press, the second of a double too." else "Any second press."
                        Duration.QUICK -> "Lifted within the $window (a second press: within the second tap release window)."
                        Duration.LONG -> "Held past the $window (a second press: past the second tap release window)."
                    },
                )
                if (trigger.isTap) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Don't wait", Modifier.weight(1f))
                        Switch(checked = trigger.dontWait, onCheckedChange = { onChange(trigger.copy(dontWait = it)) })
                    }
                    TriggerHint(
                        if (trigger.dontWait) "Fires at once on every quick lift, even when a double is mapped (like a double click)."
                        else "Waits out the second tap window when a double is mapped, so it never fires as part of a double.",
                    )
                }
                Choices("Output", Output.entries, trigger.output, { it.label }) {
                    onChange(trigger.copy(output = it, repeat = trigger.repeat && it == Output.FIRE_ONCE))
                }
                if (trigger.output == Output.FIRE_ONCE) {
                    Choices(null, FireAt.entries, trigger.fireAt, { it.label }) { onChange(trigger.copy(fireAt = it)) }
                    if (direction && !trigger.gestures) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Repeat every swipe distance", Modifier.weight(1f))
                        Switch(checked = trigger.repeat, onCheckedChange = { onChange(trigger.copy(repeat = it)) })
                    }
                }
                TriggerHint(
                    when (trigger.output) {
                        Output.HELD -> "On while the trigger is on."
                        Output.FIRE_ONCE -> "A short pulse as the trigger starts, ends or both."
                        Output.TOGGLE -> "Each time it fires it turns on or off."
                        Output.STICKY -> "On until the next other key or button is pressed and let go. Fired again, it lets go."
                    },
                )
                if (hasWhere) {
                    Choices("Where", Where.entries, trigger.where, { it.label }) { onChange(trigger.copy(where = it)) }
                    TriggerHint(
                        when (trigger.where) {
                            Where.BUTTON -> "The finger touched down on it and is still down."
                            Where.AREA -> "The finger is inside its area, wherever it touched down."
                            Where.OUTSIDE -> "The finger touched down on it and is now outside it."
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (trigger.isDefault) null else {
            { TextButton(onClick = { onChange(Trigger.DEFAULT) }) { Text("Reset") } }
        },
    )
}

@Composable
private fun <T> Choices(
    label: String?,
    options: List<T>,
    value: T,
    name: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onPick: (T) -> Unit,
) {
    if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) {
            // A choice that can't be picked (Custom) shows only while it is the value.
            if (!enabled(o) && o != value) continue
            FilterChip(selected = o == value, onClick = { onPick(o) }, label = { Text(name(o)) })
        }
    }
}

@Composable
private fun TriggerHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
