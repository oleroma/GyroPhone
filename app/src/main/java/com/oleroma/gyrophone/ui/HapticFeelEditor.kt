package com.oleroma.gyrophone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.HapticFeel
import kotlin.math.roundToInt

/** Strengths the System chips set, one inside each of [Describe.systemLevel]'s bands. */
private val SYSTEM_LEVELS = listOf("Light" to 60, "Medium" to 140, "Strong" to 230)

/**
 * Edits a [HapticFeel]: the phone's own System clicks (Light / Medium / Strong) or a Custom
 * strength and, for ticks (not [held]), length. Sliders report through [onChange] when let go.
 */
@Composable
fun HapticFeelEditor(feel: HapticFeel, held: Boolean, onChange: (HapticFeel) -> Unit, onTry: () -> Unit) {
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = feel.system, onClick = { onChange(feel.copy(system = true)) }, label = { Text("System") })
            FilterChip(selected = !feel.system, onClick = { onChange(feel.copy(system = false)) }, label = { Text("Custom") })
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onTry) { Text("Try") }
        }
        if (feel.system) {
            val level = Describe.systemLevel(feel.strength)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((name, strength) in SYSTEM_LEVELS) {
                    FilterChip(
                        selected = level == name,
                        onClick = { onChange(feel.copy(strength = strength)) },
                        label = { Text(name) },
                    )
                }
            }
        } else {
            var strength by remember(feel) { mutableFloatStateOf(feel.strength.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Strength " + strength.roundToInt(), Modifier.padding(end = 8.dp))
                Slider(
                    value = strength,
                    onValueChange = { strength = it.roundToInt().toFloat() },
                    valueRange = 1f..255f,
                    onValueChangeFinished = { onChange(feel.copy(strength = strength.roundToInt())) },
                )
            }
            if (!held) {
                var length by remember(feel) { mutableFloatStateOf(feel.lengthMs.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Length " + length.roundToInt() + " ms", Modifier.padding(end = 8.dp))
                    Slider(
                        value = length,
                        onValueChange = { length = it.roundToInt().toFloat() },
                        valueRange = HapticFeel.MIN_LENGTH_MS.toFloat()..HapticFeel.MAX_LENGTH_MS.toFloat(),
                        onValueChangeFinished = { onChange(feel.copy(lengthMs = length.roundToInt())) },
                    )
                }
            }
        }
    }
}
