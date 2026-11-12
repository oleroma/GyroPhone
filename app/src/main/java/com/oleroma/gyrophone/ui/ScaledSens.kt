package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The slider's step. A factor runs from 0 (off) to 1 (the parent's value). */
private const val SCALE_STEP = 0.05f
private const val MIN_SCALE = 0f
private const val MAX_SCALE = 1f

/**
 * A sensitivity of an end child (a gyro axis, a button's axis while held, a Move X / Y, a
 * button's touchpad speed while held) that is either an exact value ([exact], the usual slider)
 * or, with the Scale parent switch on, a factor of what it inherits: [scale] in effect (null:
 * exact), times [base], the nearest exact value above, shown by [show] ("16", "1.25 RWS").
 * [onMode] flips the switch (true: scale the parent); [onScale] sets the factor.
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scale parent", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = scale != null, onCheckedChange = onMode)
        }
        if (scale == null) exact()
        else ScaleSlider(key, label, scale, base, show, overridden, onReset, hint, onScale)
    }
}

/** The factor of a [ScaledSens]: a 0 to 1 slider, with what it comes to. */
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
    // A factor saved before the slider stopped at 1 is shown as it is until the slider moves.
    var v by remember(key, scale) { mutableStateOf(scale) }
    OverrideRow(
        "$label × ${fmtScale(v)} of ${show(base)} = ${show(base * v)}", overridden, onReset,
        "$hint Scale parent: this share of what it would take from above (${show(base)} now), " +
            "so it follows when that changes. 0 turns it off. A scale further up gives way to this one.",
    ) {
        Slider(
            value = v.coerceIn(MIN_SCALE, MAX_SCALE),
            onValueChange = { v = (it / SCALE_STEP).roundToInt() * SCALE_STEP },
            valueRange = MIN_SCALE..MAX_SCALE,
            onValueChangeFinished = { onDone(v) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun fmtScale(f: Float): String = "%.2f".format(f).trimEnd('0').trimEnd('.', ',').ifEmpty { "0" }
