package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.GyroSettings
import kotlin.math.roundToInt

/** A typed factor's range: wider than the slider's. */
private const val MIN_TYPED_SCALE = 0.01f
private const val MAX_TYPED_SCALE = 10f
private const val SCALE_STEP = 0.05f

/**
 * A sensitivity of an end child (a gyro axis, a button's axis while held, a Move X / Y, a
 * button's touchpad speed while held) that is either an exact value ([exact], the usual slider)
 * or a factor of what it inherits (× Parent): [scale] in effect (null: exact), times [base], the
 * nearest exact value above, shown by [show] ("16", "1.25 RWS"). [onMode] picks one (true: × Parent);
 * [onScale] sets the factor.
 */
@Composable
internal fun ScaledSens(
    key: String,
    label: String,
    scale: Float?,
    base: Float,
    show: (Float) -> String,
    overridden: Boolean,
    onReset: () -> Unit,
    hint: String,
    onMode: (Boolean) -> Unit,
    onScale: (Float) -> Unit,
    exact: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = scale == null, onClick = { if (scale != null) onMode(false) }, label = { Text("Value") })
            FilterChip(selected = scale != null, onClick = { if (scale == null) onMode(true) }, label = { Text("× Parent") })
        }
        if (scale == null) exact()
        else ScaleSlider(key, label, scale, base, show, overridden, onReset, hint, onScale)
    }
}

/** The factor of a [ScaledSens]: a slider and a typed value, with what it comes to. */
@Composable
private fun ScaleSlider(
    key: String,
    label: String,
    scale: Float,
    base: Float,
    show: (Float) -> String,
    overridden: Boolean,
    onReset: () -> Unit,
    hint: String,
    onDone: (Float) -> Unit,
) {
    var v by remember(key, scale) { mutableStateOf(scale) }
    var typed by remember(key, scale) { mutableStateOf(fmtScale(scale)) }
    fun commit() {
        val t = typed.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
        if (t == null) { typed = fmtScale(v); return }
        val c = t.coerceIn(MIN_TYPED_SCALE, MAX_TYPED_SCALE)
        v = c
        typed = fmtScale(c)
        if (c != scale) onDone(c)
    }
    OverrideRow(
        "$label × ${fmtScale(v)} of ${show(base)} = ${show(base * v)}", overridden, onReset,
        "$hint × Parent: this factor times what it would take from above (${show(base)} now), " +
            "so it follows when that changes. A factor further up gives way to this one.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(
                value = v.coerceIn(GyroSettings.MIN_SCALE, GyroSettings.MAX_SCALE),
                onValueChange = {
                    v = (it / SCALE_STEP).roundToInt() * SCALE_STEP
                    typed = fmtScale(v)
                },
                valueRange = GyroSettings.MIN_SCALE..GyroSettings.MAX_SCALE,
                onValueChangeFinished = { onDone(v) },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                prefix = { Text("×") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.width(96.dp).onFocusChanged { if (!it.isFocused) commit() },
            )
        }
    }
}

private fun fmtScale(f: Float): String = "%.2f".format(f).trimEnd('0').trimEnd('.', ',').ifEmpty { "0" }
