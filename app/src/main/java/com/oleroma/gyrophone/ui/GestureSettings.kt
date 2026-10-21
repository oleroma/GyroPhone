package com.oleroma.gyrophone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.FingerGestures
import com.oleroma.gyrophone.core.model.GestureAction
import com.oleroma.gyrophone.core.model.GestureActions
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchpadGesture
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** The 3- and 4-finger gesture list of a workspace's touchpad settings; tapping one opens a picker. */
@Composable
fun TouchpadGestureList(runtime: GamepadRuntime, settings: TouchpadSettings, onChange: (TouchpadSettings) -> Unit) {
    var picking by remember { mutableStateOf<Pair<Int, TouchpadGesture>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (fingers in listOf(3, 4)) {
            val set = settings.gesturesFor(fingers)
            Text("$fingers fingers", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            TouchpadGesture.entries.forEach { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { picking = fingers to g }.padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.label, Modifier.weight(0.4f))
                    Text(GestureActions.describe(set.action(g)), Modifier.weight(0.6f), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        OutlinedButton(onClick = {
            onChange(settings.copy(gestures3 = FingerGestures.WINDOWS_3, gestures4 = FingerGestures.WINDOWS_4))
        }) { Text("Reset to Windows defaults") }
    }

    picking?.let { (fingers, g) ->
        GestureActionDialog(
            runtime = runtime,
            title = "$fingers fingers: ${g.label.lowercase()}",
            current = settings.gesturesFor(fingers).action(g),
            onPick = { a ->
                picking = null
                onChange(
                    if (fingers >= 4) settings.copy(gestures4 = settings.gestures4.with(g, a))
                    else settings.copy(gestures3 = settings.gestures3.with(g, a)),
                )
            },
            onDismiss = { picking = null },
        )
    }
}

/** Picks a preset Windows action, a mouse click, a custom key combo or nothing. */
@Composable
private fun GestureActionDialog(
    runtime: GamepadRuntime,
    title: String,
    current: GestureAction,
    onPick: (GestureAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = current as? GestureAction.Keys
    var mods by remember { mutableIntStateOf(start?.modifiers ?: 0) }
    var key by remember { mutableIntStateOf(start?.key ?: 0) }
    var choosingKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Windows actions", style = MaterialTheme.typography.titleSmall)
                GestureActions.presets.forEach { (name, a) ->
                    Choice(name + if (a is GestureAction.Keys) "  (" + GestureActions.combo(a.modifiers, a.key) + ")" else "",
                        a == current) { onPick(a) }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Mouse", style = MaterialTheme.typography.titleSmall)
                MouseButton.entries.forEach { b ->
                    val a = GestureAction.Mouse(b)
                    Choice(GestureActions.describe(a), a == current) { onPick(a) }
                }
                Choice("None", current == GestureAction.None) { onPick(GestureAction.None) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Key combo", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(GestureActions.CTRL to "Ctrl", GestureActions.SHIFT to "Shift", GestureActions.ALT to "Alt", GestureActions.WIN to "Win")
                        .forEach { (bit, label) ->
                            FilterChip(selected = mods and bit != 0, onClick = { mods = mods xor bit }, label = { Text(label) })
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Key: " + (if (key == 0) "none" else HidUsage.names[key] ?: "0x%02X".format(key)), Modifier.weight(1f))
                    OutlinedButton(onClick = { choosingKey = true }) { Text("Choose") }
                    if (key != 0) TextButton(onClick = { key = 0 }) { Text("Clear") }
                }
                OutlinedButton(
                    onClick = { onPick(GestureAction.Keys(mods, key)) },
                    enabled = mods != 0 || key != 0,
                ) { Text("Use " + GestureActions.combo(mods, key)) }
            }
        },
    )

    if (choosingKey) {
        // The full target picker, keyboard keys only (it also captures a key from an attached keyboard).
        TargetPickerDialog(
            analog = false,
            runtime = runtime,
            onPick = { t ->
                if (t is OutputTarget.Key) {
                    if (HidUsage.isModifier(t.usage)) mods = mods or modifierFlag(t.usage) else key = t.usage
                }
            },
            onDismiss = { choosingKey = false },
        )
    }
}

/** Left and right modifier usages both map to the left-hand bit. */
private fun modifierFlag(usage: Int): Int = HidUsage.modifierBit(usage).let { it or (it shr 4) } and 0x0F

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}
