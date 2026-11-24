package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.core.model.CurveFrame
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.TouchControl
import kotlin.math.roundToInt

/**
 * A curve frame's settings ([TouchControl.curveFrame]): which axis its curve sets, its range across
 * (°/s) and up (×), Lines or Smooth, where the speed comes from, starting the curve again, and its
 * look like any control's. Its curve itself is edited on the pad, in play.
 */
@Composable
internal fun CurveFrameSettingsScreen(
    control: TouchControl,
    inPortrait: Boolean,
    workspaceOpacity: Float,
    onChange: (TouchControl) -> Unit,
    onSize: (Float) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val f = control.curveFrame ?: return
    val latest by rememberUpdatedState(control)
    var confirmDelete by remember { mutableStateOf(false) }
    // Changing what the frame sets makes it the newest frame: it wins its axis.
    fun set(nf: CurveFrame) = onChange(latest.copy(curveFrame = nf.copy(edited = System.currentTimeMillis())))
    SettingsDialog(
        title = control.label.ifBlank { "Curve frame" },
        subtitle = "Curve frame · settings",
        onClose = onClose,
        titleField = { LabelField(control.label, "Curve frame", { onChange(latest.copy(label = it)) }) },
        actions = {
            TextButton(onClick = onDuplicate) { Text("Duplicate") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Hint(
                "On the pad, in play, the frame starts locked so a stray touch can't change it: tap an empty spot to unlock it " +
                    "(a border shows), tap one again to lock it. Unlocked: drag a point to move it (the gyro follows as you drag), " +
                    "hold a point to remove it, hold an empty spot to add one. The frame's curve replaces this workspace's acceleration while the frame is " +
                    "on the pad; the curve in Settings stays as it is, and deleting the frame brings it back. A held button with its own " +
                    "acceleration still uses its own.",
            )
            Searchable("Sets", "axis yaw pitch roll all") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sets", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
                    SelectChip("All axes", f.axis == MotionAxis.NONE) { set(f.copy(axis = MotionAxis.NONE)) }
                    for (a in MotionAxis.shown) SelectChip(a.label, f.axis == a) { set(f.copy(axis = a)) }
                }
            }
            Hint(
                if (f.axis == MotionAxis.NONE) "All axes: every axis takes this curve, also one with its own in Settings."
                else "Only ${f.axis.label}; the other axes keep theirs.",
            )
            Searchable("Speed across", "range deg/s turning speed") {
                var v by remember(f.maxSpeed) { mutableFloatStateOf(f.speedCap) }
                Column {
                    Text("Across: 0 to %.0f°/s".format(v), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = v,
                        onValueChange = { v = ((it / 50f).roundToInt() * 50f).coerceIn(100f, GyroCurve.MAX_SPEED) },
                        onValueChangeFinished = { if (v != f.maxSpeed) set(f.withRange(v, f.maxGain)) },
                        valueRange = 100f..GyroCurve.MAX_SPEED,
                    )
                }
            }
            Searchable("Multiplier up", "range times gain top") {
                var v by remember(f.maxGain) { mutableFloatStateOf(f.gainCap) }
                Column {
                    Text("Up: 1× to %.2f×".format(v).replace(Regex("0+×$"), "×").replace(Regex("[.,]×$"), "×"), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = v,
                        onValueChange = { v = ((it * 4f).roundToInt() / 4f).coerceIn(CurveFrame.MIN_GAIN_TOP, GyroCurve.MAX_GAIN) },
                        onValueChangeFinished = { if (v != f.maxGain) set(f.withRange(f.maxSpeed, v)) },
                        valueRange = CurveFrame.MIN_GAIN_TOP..GyroCurve.MAX_GAIN,
                    )
                }
            }
            Hint("A smaller range gives finer control over each point. Points past a new range are pulled in to its edge.")
            Searchable("Between points", "lines smooth") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Between points", style = MaterialTheme.typography.labelLarge)
                    SelectChip("Lines", !f.curve.smooth) { set(f.copy(curve = f.curve.copy(smooth = false))) }
                    SelectChip("Smooth", f.curve.smooth) { set(f.copy(curve = f.curve.copy(smooth = true))) }
                }
            }
            Searchable("Speed from", "whole phone axis") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed from", style = MaterialTheme.typography.labelLarge)
                    SelectChip("Whole phone", !f.curve.axisSpeed) { set(f.copy(curve = f.curve.copy(axisSpeed = false))) }
                    SelectChip(if (f.axis == MotionAxis.NONE) "Each axis" else "This axis", f.curve.axisSpeed) {
                        set(f.copy(curve = f.curve.copy(axisSpeed = true)))
                    }
                }
            }
            SwitchRow(
                "Show values",
                f.showValues,
                "Each line also shows what it gives at the sensitivity in effect now. Off: only the multipliers (1×, 1.5× …).",
            ) { onChange(latest.copy(curveFrame = f.copy(showValues = it))) }
            Searchable("Start again", "reset curve points") {
                TextButton(onClick = {
                    set(f.copy(curve = CurveFrame.ramp(f.speedCap, f.gainCap).copy(smooth = f.curve.smooth, axisSpeed = f.curve.axisSpeed)))
                }) { Text("Start the curve again (two points)") }
            }
            LookSettings(control, inPortrait, onChange, onSize, workspaceOpacity)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this curve frame?") },
            text = { Text("Its curve goes with it, and the workspace's acceleration from Settings applies again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
