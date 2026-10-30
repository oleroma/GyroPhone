package com.oleroma.gyrophone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.GamePreset
import com.oleroma.gyrophone.core.model.GameSens
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToLong

/** Slider top for real world sensitivity. */
private const val MAX_RWS = 3f

private const val RWS_HINT = "Real world sensitivity: how many degrees the game's camera turns per degree the phone turns (1 = the same)."

/**
 * A gyro sensitivity slider: in real world sensitivity once [g]'s game turn is known
 * ([GyroSettings.gameCountsPer360]), else in counts per degree. [sens] and [onDone] are counts
 * per degree either way. [label] has no number: "Sensitivity".
 */
@Composable
internal fun GyroSensSlider(
    key: String,
    label: String,
    sens: Float,
    g: GyroSettings,
    overridden: Boolean,
    onReset: (() -> Unit)?,
    hint: String,
    onDone: (Float) -> Unit,
) {
    val c = g.gameCountsPer360
    if (c <= 0f) {
        OverrideSlider(key, "$label %.0f", sens, 0f..MAX_GYRO_SENS, 1f, overridden, onReset, hint, onDone = onDone)
        return
    }
    val tight = if (g.tighteningThreshold > 0f) {
        " Exact above the jitter cutoff (%.1f°/s): slower turns move less.".format(g.tighteningThreshold)
    } else ""
    OverrideSlider(
        key + "rws", "$label %.2f RWS", GameSens.rws(sens, c), 0f..MAX_RWS, 0.05f, overridden, onReset,
        "%.1f counts per degree in %s. %s%s %s".format(sens, g.gameName.ifEmpty { "this game" }, RWS_HINT, tight, hint),
    ) { v -> onDone(GameSens.sensOf(v, c)) }
}

/**
 * The game's full turn of [level] ([GyroSettings.gameCountsPer360]): what it is and a button to
 * set it from a preset, by hand or by measuring in the game ([GameSensDialog]).
 */
@Composable
internal fun GameTurnRow(runtime: GamepadRuntime, level: WorkspaceLevel, own: Boolean, onReset: () -> Unit) {
    val g = level.ws.gyro
    var open by remember { mutableStateOf(false) }
    OverrideRow(
        if (g.gameCountsPer360 > 0f) "Game: ${g.gameName.ifEmpty { "set" }}" else "Game: not set", own, onReset,
        hint = if (g.gameCountsPer360 > 0f) {
            "%.0f mouse counts turn the camera once around; gyro sensitivities show in RWS. ".format(g.gameCountsPer360) + RWS_HINT
        } else "Set the game's full turn to see and set gyro sensitivity as real world sensitivity (RWS) instead of counts per degree.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { open = true }) { Text(if (g.gameCountsPer360 > 0f) "Change game" else "Set game") }
            if (g.gameCountsPer360 > 0f) TextButton(onClick = {
                level.edit { it.copy(gyro = it.gyro.copy(gameCountsPer360 = 0f, gameName = "")) }
            }) { Text("Clear") }
        }
    }
    if (open) GameSensDialog(runtime, g, onDismiss = { open = false }) { c, name ->
        level.edit { it.copy(gyro = it.gyro.copy(gameCountsPer360 = c, gameName = name)) }
        open = false
    }
}

private fun number(t: String): Double? = t.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }

private fun fmt(d: Double): String = "%.6f".format(d).trimEnd('0').trimEnd('.', ',')

/**
 * Sets the game's full turn: a preset (built-in or the person's own) with the in-game
 * sensitivity, counts per turn typed in, or measured ([MeasureTurnDialog]). Own presets are
 * added and removed here. [onSet] gets counts per turn and a name for it.
 */
@Composable
private fun GameSensDialog(runtime: GamepadRuntime, g: GyroSettings, onDismiss: () -> Unit, onSet: (Float, String) -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val own = profile.gamePresets
    var picked by remember { mutableStateOf<GamePreset?>(null) }
    var gameSens by remember { mutableStateOf("1") }
    var counts by remember { mutableStateOf(if (g.gameCountsPer360 > 0f) "%.0f".format(g.gameCountsPer360) else "") }
    var newName by remember { mutableStateOf("") }
    var newYaw by remember { mutableStateOf("") }
    var measure by remember { mutableStateOf(false) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Game's full turn") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Turn off mouse acceleration and use raw input in the game, so its turn per mouse count is fixed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Preset", style = MaterialTheme.typography.titleSmall)
                for (p in GameSens.builtin + own) {
                    Row(Modifier.fillMaxWidth().clickable { picked = p }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = picked == p, onClick = { picked = p })
                        Column(Modifier.weight(1f)) {
                            Text(p.name)
                            Text("${fmt(p.yaw)}° per count at sensitivity 1", style = MaterialTheme.typography.bodySmall)
                        }
                        if (p in own) TextButton(onClick = {
                            if (picked == p) picked = null
                            runtime.editActiveProfile { it.copy(gamePresets = it.gamePresets - p) }
                        }) { Text("✕") }
                    }
                }
                OutlinedTextField(
                    value = gameSens, onValueChange = { gameSens = it }, label = { Text("In-game sensitivity") },
                    singleLine = true, keyboardOptions = numeric,
                )
                val p = picked
                val s = number(gameSens)
                Button(enabled = p != null && s != null, onClick = {
                    onSet(GameSens.countsPer360(p!!.yaw, s!!), "${p.name}, sens ${fmt(s)}")
                }) { Text(if (p != null && s != null) "Use (%.0f counts per turn)".format(GameSens.countsPer360(p.yaw, s)) else "Use preset") }

                HorizontalDivider()
                Text("Counts per full turn", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = counts, onValueChange = { counts = it }, label = { Text("Counts") },
                        singleLine = true, keyboardOptions = numeric, modifier = Modifier.weight(1f),
                    )
                    val c = number(counts)
                    Button(enabled = c != null, onClick = { onSet(c!!.toFloat(), "${"%.0f".format(c)} counts per turn") }) { Text("Use") }
                }
                OutlinedButton(onClick = { measure = true }) { Text("Measure in the game…") }

                HorizontalDivider()
                Text("Add a preset", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Game") }, singleLine = true)
                OutlinedTextField(
                    value = newYaw, onValueChange = { newYaw = it }, label = { Text("Degrees per count at sensitivity 1") },
                    singleLine = true, keyboardOptions = numeric,
                )
                val y = number(newYaw)
                Button(enabled = newName.isNotBlank() && y != null, onClick = {
                    val added = GamePreset(newName.trim(), y!!)
                    runtime.editActiveProfile { it.copy(gamePresets = it.gamePresets.filter { o -> o.name != added.name } + added) }
                    picked = added; newName = ""; newYaw = ""
                }) { Text("Save preset") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (measure) MeasureTurnDialog(runtime, onDismiss = { measure = false }) { c, name -> measure = false; onSet(c, name) }
}

/** Streaming speeds of the measure, counts per second. */
private val SPEEDS = listOf("Slow" to 1000f, "Medium" to 3000f, "Fast" to 8000f)

/**
 * Measures the game's full turn: the phone sends steady mouse counts to the right (the gyro moves
 * nothing meanwhile) until the person taps Stop with the landmark back under the crosshair, nudges
 * it exact, and says how many turns it was. Verify sends exactly one turn. [onSet] gets counts per
 * turn; it can be saved as a preset with the in-game sensitivity it was measured at.
 */
@Composable
private fun MeasureTurnDialog(runtime: GamepadRuntime, onDismiss: () -> Unit, onSet: (Float, String) -> Unit) {
    DisposableEffect(Unit) {
        runtime.measureBegin()
        onDispose { runtime.measureEnd() }
    }
    val sent by runtime.turnSent.collectAsStateWithLifecycle()
    val running by runtime.turnRunning.collectAsStateWithLifecycle()
    var speed by remember { mutableIntStateOf(1) }
    var turns by remember { mutableIntStateOf(1) }
    var verifying by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var gameSens by remember { mutableStateOf("1") }
    // The result stays what was measured while Verify reuses the counter.
    var frozen by remember { mutableFloatStateOf(0f) }
    val result = if (verifying) frozen else if (sent > 0) sent.toFloat() / turns else 0f
    val rate = SPEEDS[speed].second

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Measure the full turn") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "1. In the game, aim the crosshair at a sharp vertical edge.\n" +
                        "2. Start: the camera turns on its own. Stop when the edge is back (after one or more turns).\n" +
                        "3. Nudge until the edge is exactly under the crosshair, and pick how many turns it was.\n" +
                        "4. Verify turns exactly once; the edge should land back on the crosshair.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEEDS.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = speed == i, onClick = { speed = i }, label = { Text(label) }, enabled = !running)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) Button(onClick = { runtime.measureStop() }) { Text("Stop") }
                    else Button(onClick = {
                        if (verifying) { runtime.measureBegin(); verifying = false }
                        runtime.measureStart(rate)
                    }) { Text(if (sent == 0L || verifying) "Start" else "Go on") }
                    if (!running && sent != 0L) TextButton(onClick = { runtime.measureBegin(); verifying = false }) { Text("Restart") }
                }
                Text("Sent %,d counts".format(sent))
                if (!verifying) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (n in listOf(-100, -10, -1, 1, 10, 100)) {
                            OutlinedButton(onClick = { runtime.measureNudge(n) }, enabled = !running) {
                                Text(if (n > 0) "+$n" else "$n")
                            }
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Turns")
                        for (t in 1..5) FilterChip(selected = turns == t, onClick = { turns = t }, label = { Text("$t") })
                    }
                }
                if (result > 0f) {
                    Text("%.1f counts per turn".format(result), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !running, onClick = {
                            frozen = result
                            verifying = true
                            runtime.measureVerify(result.roundToLong(), rate)
                        }) { Text("Verify") }
                        Button(enabled = !running, onClick = { onSet(result, "Measured") }) { Text("Use") }
                    }
                    HorizontalDivider()
                    Text("Save as a preset", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Game") }, singleLine = true)
                    OutlinedTextField(
                        value = gameSens, onValueChange = { gameSens = it }, label = { Text("In-game sensitivity while measuring") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    val s = number(gameSens)
                    Button(enabled = !running && name.isNotBlank() && s != null, onClick = {
                        val p = GamePreset(name.trim(), GameSens.yawOf(result, s!!))
                        runtime.editActiveProfile { it.copy(gamePresets = it.gamePresets.filter { o -> o.name != p.name } + p) }
                        onSet(result, "${p.name}, sens ${fmt(s)}")
                    }) { Text("Save and use") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
