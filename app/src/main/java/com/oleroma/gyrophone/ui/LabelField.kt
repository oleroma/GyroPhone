package com.oleroma.gyrophone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A control's label, edited in place where its name shows (the mapping editor's title): text,
 * emoji from the keyboard, or symbols from the ☺ picker ([LABEL_SYMBOLS]). Labels are plain text,
 * so the PC overlays show them too. Empty: [placeholder] shows (the label then shows the mapping).
 */
@Composable
internal fun LabelField(value: String, placeholder: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.widthIn(min = 48.dp, max = 160.dp)) {
            BasicTextField(
                value = value,
                onValueChange = { onChange(it.take(MAX_LABEL)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
                    inner()
                },
            )
        }
        TextButton(onClick = { picking = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
            Text("☺", fontSize = 18.sp)
        }
    }
    if (picking) SymbolPicker(onPick = { onChange((value + it).take(MAX_LABEL)) }, onClear = { onChange("") }, onDismiss = { picking = false })
}

/** A grid of symbols by group; each tap adds one to the label (the picker stays open for more). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolPicker(onPick: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to the label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Emoji also come from the keyboard.", style = MaterialTheme.typography.bodySmall)
                for ((group, symbols) in LABEL_SYMBOLS) {
                    Text(group, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (sym in symbols) {
                            Box(
                                Modifier.size(40.dp).clickable { onPick(sym) },
                                contentAlignment = Alignment.Center,
                            ) { Text(sym, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(2.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onClear() }) { Text("Clear label") } },
    )
}

private const val MAX_LABEL = 24

/** Symbols offered for labels, by group: plain Unicode, so every font and the PC overlays draw them. */
internal val LABEL_SYMBOLS: List<Pair<String, List<String>>> = listOf(
    "Arrows" to listOf("↑", "↓", "←", "→", "↖", "↗", "↙", "↘", "⇧", "⇩", "⇦", "⇨", "⟲", "⟳", "⤒", "⤓"),
    "Keys" to listOf("⌫", "⌦", "⏎", "⇥", "␣", "⎋", "⌃", "⌥", "⇪", "⊞", "⌘", "⏏"),
    "Pad" to listOf("Ⓐ", "Ⓑ", "Ⓧ", "Ⓨ", "△", "○", "✕", "□", "◀", "▶", "▲", "▼", "Ⓛ", "Ⓡ", "☰", "⧉"),
    "Mouse and media" to listOf("🖱", "⏯", "⏮", "⏭", "🔊", "🔉", "🔇", "🎤", "📷", "🎥"),
    "Game" to listOf("🎯", "🔫", "💣", "🗡", "🛡", "🏃", "🦘", "👊", "🧎", "🔄", "🔍", "🗺", "🎒", "💊", "❤", "⚡", "🔥", "⭐", "⚙", "🚗"),
    "Shapes" to listOf("●", "○", "■", "□", "◆", "◇", "▲", "★", "☆", "✚", "✖", "✔", "⬤", "◉"),
)
