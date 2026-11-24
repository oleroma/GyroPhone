package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.engine.AimStatsData
import com.oleroma.gyrophone.core.engine.LogHist
import com.oleroma.gyrophone.core.engine.PressShake
import com.oleroma.gyrophone.core.engine.StatKey
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt

/** Presses needed before an input shows in the shake list (fewer say little). */
private const val MIN_PRESSES = 5

/** What [key] is called in workspace [w]. */
internal fun statName(key: Long, w: Workspace?): String {
    val v = StatKey.value(key)
    return when (StatKey.kind(key)) {
        StatKey.CLICK -> if (v == 1) "Left click" else "Right click"
        StatKey.KEY -> AndroidKey.names[v] ?: "Key $v"
        StatKey.TRIGGER -> when (v) {
            AndroidAxis.LTRIGGER, AndroidAxis.BRAKE -> "Left trigger"
            else -> "Right trigger"
        }
        StatKey.TOUCH -> w?.let { ws ->
            ws.touchControls.firstOrNull { it.id.hashCode() == v }
                ?.let { c -> WorkspaceMappings.buttonName(ws, c).ifBlank { "Button" } + " (screen)" }
        } ?: "Screen button"
        else -> "Input"
    }
}

private fun deg(v: Double?): String = when {
    v == null -> "–"
    v < 0.1 -> "%.2f°".format(v)
    v < 10 -> "%.1f°".format(v)
    else -> "${v.roundToInt()}°"
}

private fun dps(v: Double?): String = if (v == null) "–" else "${v.roundToInt()}°/s"

/** "typical a · 1 in 10 over b" of [h] in [fmt]. */
private fun spread(h: LogHist, fmt: (Double?) -> String): String =
    "typical ${fmt(h.quantile(0.5))}, 1 in 10 over ${fmt(h.quantile(0.9))}"

/** How much an input's presses throw the aim: what the list is ordered by. */
private fun harm(s: PressShake): Double = (s.before.quantile(0.5) ?: 0.0) + (s.after.quantile(0.5) ?: 0.0)

/**
 * The Stats page (the pad's Stats capsule), for the workspace in use: how fast you turn while aiming,
 * moving and firing, how much the Fire and Aim presses shake the aim, and which inputs shake it most.
 */
@Composable
fun StatsScreen(runtime: GamepadRuntime, modifier: Modifier) {
    val stats = runtime.stats
    DisposableEffect(stats) {
        stats.watch()
        onDispose { stats.unwatch() }
    }
    val collecting by stats.collecting.collectAsStateWithLifecycle()
    val view by stats.view.collectAsStateWithLifecycle()
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val ws = profile.workspaces.getOrNull(wsIndex)
    val v = view?.takeIf { it.workspaceId == ws?.id }
    val data = v?.data ?: AimStatsData()
    var confirmReset by remember { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Collect", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${(data.seconds / 60).roundToInt()} min with the gyro on, in ${ws?.name.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(collecting, onCheckedChange = { stats.setCollecting(it) })
        }
        Text(
            "Counts only while the gyro is on. Each workspace keeps its own stats. Shake is how far the aim was pushed " +
                "off the turn you were already making, so a steady track counts as none.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (v != null) {
            val choices = listOf(StatKey.LEFT_CLICK, StatKey.RIGHT_CLICK) +
                data.inputs.keys.filter { StatKey.kind(it) != StatKey.CLICK }.sortedBy { statName(it, ws) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RolePicker("Fire", v.fire, choices, ws) { stats.setRoles(fire = it, aim = null) }
                RolePicker("Aim", v.aim, choices, ws) { stats.setRoles(fire = null, aim = it) }
            }
            Text(
                "To pick a button here, press it once while collecting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatCard("Turning speed") {
                val total = data.firing.total + data.aiming.total + data.moving.total
                for ((name, h) in listOf("Aiming" to data.aiming, "Moving" to data.moving, "Firing" to data.firing)) {
                    val share = if (total > 0) (100 * h.total / total).roundToInt() else 0
                    Line(name, if (h.total <= 0) "–" else "${spread(h, ::dps)} · $share% of the time")
                }
                Note("Aiming: while Aim is held. Firing: while Fire is held. Moving: the rest.")
            }
            PressCard("Fire: " + statName(v.fire, ws), data.inputs[v.fire], "shots")
            PressCard("Aim: " + statName(v.aim, ws), data.inputs[v.aim], "presses")

            StatCard("What shakes your aim most") {
                val ranked = data.inputs.entries
                    .filter { StatKey.kind(it.key) != StatKey.CLICK && it.value.presses >= MIN_PRESSES }
                    .sortedByDescending { harm(it.value) }
                if (ranked.isEmpty()) Note("Nothing yet: each button shows after $MIN_PRESSES presses.")
                for ((key, s) in ranked) {
                    Line(
                        statName(key, ws) + " (${s.presses})",
                        "before ${deg(s.before.quantile(0.5))} · after ${deg(s.after.quantile(0.5))} · " +
                            "letting go ${deg(s.release.quantile(0.5))}",
                    )
                }
                Note("Typical shake of each press: in the 80 ms before it counts, the 150 ms after, and after letting go.")
            }
        }
        OutlinedButton(
            onClick = { confirmReset = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Reset stats") }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset stats for ${ws?.name.orEmpty()}?") },
            text = { Text("All its collected stats are cleared. Its Fire and Aim buttons stay.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; stats.reset() }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RolePicker(role: String, chosen: Long, choices: List<Long>, ws: Workspace?, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$role: " + statName(chosen, ws) + " ▾") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (k in choices) {
                DropdownMenuItem(
                    text = {
                        Text(
                            when (k) {
                                StatKey.LEFT_CLICK -> "Left click (whatever sends it)"
                                StatKey.RIGHT_CLICK -> "Right click (whatever sends it)"
                                else -> statName(k, ws)
                            },
                            fontWeight = if (k == chosen) FontWeight.Bold else null,
                        )
                    },
                    onClick = { open = false; onPick(k) },
                )
            }
        }
    }
}

@Composable
private fun PressCard(title: String, s: PressShake?, what: String) = StatCard(title) {
    if (s == null || s.presses == 0) {
        Note("No $what yet.")
        return@StatCard
    }
    Line("Counted", "${s.presses} $what")
    Line("Off your line before", spread(s.before, ::deg))
    Line("Kick after", spread(s.after, ::deg))
    Line("On letting go", spread(s.release, ::deg))
    Line("Sharpest jerk", spread(s.twitch, ::dps))
    Line("Turning when pressed", spread(s.speedAtPress, ::dps))
}

@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Line(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
