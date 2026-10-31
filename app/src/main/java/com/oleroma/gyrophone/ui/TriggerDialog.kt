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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.FireAt
import com.oleroma.gyrophone.core.model.Output
import com.oleroma.gyrophone.core.model.Taps
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.When
import com.oleroma.gyrophone.core.model.Where

/**
 * A chip's trigger: When it fires on its action, which Taps count, how its Output behaves and,
 * on a touch button or stick ([hasWhere]), Where a finger counts. Each pick applies at once.
 */
@Composable
internal fun TriggerDialog(
    title: String,
    trigger: Trigger,
    hasWhere: Boolean,
    onChange: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Choices("When", When.entries, trigger.on, { it.label }) { onChange(trigger.copy(on = it)) }
                Hint(
                    when (trigger.on) {
                        When.PRESS -> "As the press starts, for as long as it lasts."
                        When.TAP -> "A short press, on its lift. A press is a tap or a long press, never both."
                        When.LONG_PRESS -> "Once the press has lasted past the tap window, until it lifts."
                        When.RELEASE -> "On every lift."
                    },
                )
                Choices("Taps", Taps.entries, trigger.taps, { it.label }) { onChange(trigger.copy(taps = it)) }
                Hint(
                    when (trigger.taps) {
                        Taps.ANY -> "Every press, either press of a double too. Never waits."
                        Taps.SINGLE -> "Not part of a double. A single tap waits out the double window when the action has a Double mapping."
                        Taps.DOUBLE -> "The second press, within the double window after a tap."
                    },
                )
                Choices("Output", Output.entries, trigger.output, { it.label }) { onChange(trigger.copy(output = it)) }
                if (trigger.output == Output.FIRE_ONCE) {
                    Choices(null, FireAt.entries, trigger.fireAt, { it.label }) { onChange(trigger.copy(fireAt = it)) }
                }
                Hint(
                    when (trigger.output) {
                        Output.HELD -> "On while the trigger is on."
                        Output.FIRE_ONCE -> "A short pulse as the trigger starts, ends or both."
                        Output.TOGGLE -> "Each time it fires it turns on or off."
                        Output.STICKY -> "On until the next other key or button is pressed and let go. Fired again, it lets go."
                    },
                )
                if (hasWhere) {
                    Choices("Where", Where.entries, trigger.where, { it.label }) { onChange(trigger.copy(where = it)) }
                    Hint(
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
private fun <T> Choices(label: String?, options: List<T>, value: T, name: (T) -> String, onPick: (T) -> Unit) {
    if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) FilterChip(selected = o == value, onClick = { onPick(o) }, label = { Text(name(o)) })
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
