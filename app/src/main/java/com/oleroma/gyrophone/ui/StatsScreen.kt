package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.engine.AimStatsData
import com.oleroma.gyrophone.core.engine.LogHist
import com.oleroma.gyrophone.core.engine.PressCurve
import com.oleroma.gyrophone.core.engine.PressShake
import com.oleroma.gyrophone.core.engine.ShakeParts
import com.oleroma.gyrophone.core.engine.StatKey
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Presses a press curve needs before its split is shown (fewer is mostly chance). */
private const val MIN_CURVES = 10


/** What [key] is called in workspace [w]. */
internal fun statName(key: Long, w: Workspace?): String {
    val v = StatKey.value(key)
    return when (StatKey.kind(key)) {
        StatKey.CLICK -> if (v == 1) "Left click" else "Right click"
        StatKey.FIRE_SPLIT -> if (v == 1) "Fire while aiming" else "Hip fire"
        StatKey.BASELINE -> if (v == 1) "Hand alone, aiming" else "Hand alone, not aiming"
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

private fun px(v: Double?): String = when {
    v == null -> "–"
    v < 10 -> "%.1f px".format(v)
    else -> "${v.roundToInt()} px"
}

private fun ms(b: Int): String = PressCurve.ms(b).let { if (it > 0) "+$it ms" else if (it == 0) "at the press" else "$it ms" }

/** The four channels: name, unit and their formatting. */
private val CHANNELS = listOf(
    Triple(PressCurve.SIDE, "↔ gyro sideways", ::deg),
    Triple(PressCurve.UP, "↕ gyro up/down", ::deg),
    Triple(PressCurve.X, "↔ cursor x", ::px),
    Triple(PressCurve.Y, "↕ cursor y", ::px),
)

/** Your hand-alone levels, from the two hand-alone curves. */
private class Hand(val aiming: PressCurve?, val moving: PressCurve?) {
    fun natural(c: PressCurve, ch: Int) = ShakeParts.natural(c, aiming, moving, ch)
}

/** Where the cursor goes sideways is what misses: inputs are ordered by what they add there at their worst moment. */
private fun harm(c: PressCurve, hand: Hand): Double {
    val ch = if (c.x.any { it != 0.0 }) PressCurve.X else PressCurve.SIDE
    val n = hand.natural(c, ch)
    return (0 until PressCurve.BINS).maxOf { ShakeParts.of(c, ch, it, n).press }
}

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
                data.inputs.keys.filter { StatKey.kind(it) == StatKey.KEY || StatKey.kind(it) == StatKey.TRIGGER || StatKey.kind(it) == StatKey.TOUCH }.sortedBy { statName(it, ws) }
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
                SpeedGraph(listOf(data.aiming, data.moving, data.firing))
                Note("Aiming: while Aim is held. Firing: while Fire is held. Moving: the rest. Graph: share of the time at each speed, 1 to 2000 °/s on a log scale; colors in the order above.")
            }
            val hand = Hand(data.inputs[StatKey.HAND_AIMING]?.curve, data.inputs[StatKey.HAND_MOVING]?.curve)
            StatCard("How it works") {
                Note(
                    "Shake is how far the aim was off its smooth path (your aim averaged over 150 ms either side). " +
                        "Every press is lined up at the moment it was pressed and averaged over all presses. Your hand's own " +
                        "shake comes at random times, so it cancels out; what the press does the same way every time adds up: " +
                        "its kick. What is left differs from press to press: beyond your hand alone it is the press's jitter.",
                )
                Note("Graphs: 1 s before to 1 s after the press (the line in the middle). Grey band: your hand alone. Line: the average kick. Shaded: how far single presses spread around it.")
            }
            HandCard(hand)
            ShakeCard("Fire while aiming", data.inputs[StatKey.FIRE_AIMED], hand, "shots")
            ShakeCard("Hip fire", data.inputs[StatKey.FIRE_HIP], hand, "shots")
            ShakeCard("Aim: " + statName(v.aim, ws), data.inputs[v.aim], hand, "presses")

            StatCard("What shakes your aim most") {
                val ranked = data.inputs.entries
                    .filter { StatKey.kind(it.key).let { k -> k == StatKey.KEY || k == StatKey.TRIGGER || k == StatKey.TOUCH } && it.value.curve.n >= MIN_CURVES }
                    .sortedByDescending { harm(it.value.curve, hand) }
                if (ranked.isEmpty()) Note("Nothing yet: each button shows after $MIN_CURVES presses.")
                for ((key, s) in ranked) RankRow(statName(key, ws), s, hand)
                Note("Ordered by what each press adds to the cursor sideways at its worst moment (sideways misses). Tap one for its graphs.")
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

/** The short-window measures (80 ms before, 150 ms after) behind Details. */
@Composable
private fun PressLines(s: PressShake, what: String, release: Boolean = true) {
    Line("Counted", "${s.presses} $what")
    Line("Off your line before", spread(s.before, ::deg))
    Line("   ↔ sideways", spread(s.beforeSide, ::deg))
    Line("   ↕ up/down", spread(s.beforeUp, ::deg))
    Line("Kick after", spread(s.after, ::deg))
    Line("   ↔ sideways", spread(s.afterSide, ::deg))
    Line("   ↕ up/down", spread(s.afterUp, ::deg))
    if (release) {
        Line("On letting go", spread(s.release, ::deg))
        Line("   ↔ sideways", spread(s.releaseSide, ::deg))
        Line("   ↕ up/down", spread(s.releaseUp, ::deg))
    }
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

/** Your hand alone: its shake level at moments with no press within 0.5 s, aiming and not. */
@Composable
private fun HandCard(hand: Hand) = StatCard("Your hand alone") {
    for ((name, c) in listOf("Aiming" to hand.aiming, "Not aiming" to hand.moving)) {
        if (c == null || c.n == 0) { Line(name, "no moments yet"); continue }
        Line(
            "$name (${c.n})",
            CHANNELS.joinToString("\n") { (ch, label, fmt) -> "$label ${fmt(c.level(ch))}" },
        )
    }
    Note("Typical shake at moments with no press within 0.5 s. A press shows only what it adds beyond this.")
}

/** What one moment of [c] is made of, per channel. */
@Composable
private fun Split(title: String, c: PressCurve, b: Int, hand: Hand) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    for ((ch, label, fmt) in CHANNELS) {
        val p = ShakeParts.of(c, ch, b, hand.natural(c, ch))
        Line(label, "${fmt(p.total)}: hand ${fmt(p.natural)} + press ${fmt(p.press)} (kick ${fmt(p.kick)}, varies ${fmt(p.jitter)})")
    }
}

/** How consistent the press's own part is: kick (same every time) against all it adds. */
private fun shape(c: PressCurve, hand: Hand, ch: Int, b: Int): String {
    val p = ShakeParts.of(c, ch, b, hand.natural(c, ch))
    if (p.press <= 0.0) return "adds nothing beyond your hand"
    val share = (100 * p.kick * p.kick / (p.press * p.press)).roundToInt()
    return when {
        share >= 60 -> "$share% the same every press: a fixed kick"
        share >= 30 -> "$share% the same every press: partly a fixed kick"
        else -> "$share% the same every press: mostly random"
    }
}

@Composable
private fun ShakeCard(title: String, s: PressShake?, hand: Hand, what: String) = StatCard(title) {
    val c = s?.curve
    if (s == null || c == null || c.n < MIN_CURVES) {
        Note("Needs $MIN_CURVES $what (${c?.n ?: 0} so far).")
        return@StatCard
    }
    ShakeBody(s, c, hand, what)
}

@Composable
private fun ShakeBody(s: PressShake, c: PressCurve, hand: Hand, what: String) {
    Line("Counted", "${c.n} $what")
    Split("At the press", c, PressCurve.CENTER, hand)
    val worst = ShakeParts.worst(c, PressCurve.X, hand.natural(c, PressCurve.X), PressCurve.Y, hand.natural(c, PressCurve.Y))
    Split("Worst, " + ms(worst), c, worst, hand)
    Line("Kick", shape(c, hand, PressCurve.X, worst))
    for ((ch, label, fmt) in CHANNELS) CurveGraph(label, c, ch, hand.natural(c, ch), fmt)
    var details by remember { mutableStateOf(false) }
    Text(
        if (details) "Hide details" else "Details",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clickable { details = !details }.padding(vertical = 4.dp),
    )
    if (details) PressLines(s, what)
}

@Composable
private fun RankRow(name: String, s: PressShake, hand: Hand) {
    val c = s.curve
    var open by remember { mutableStateOf(false) }
    val worst = ShakeParts.worst(c, PressCurve.X, hand.natural(c, PressCurve.X), PressCurve.Y, hand.natural(c, PressCurve.Y))
    val x = ShakeParts.of(c, PressCurve.X, worst, hand.natural(c, PressCurve.X))
    val y = ShakeParts.of(c, PressCurve.Y, worst, hand.natural(c, PressCurve.Y))
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp)) {
        Line("$name (${c.n})", "adds ↔ ${px(x.press)} · ↕ ${px(y.press)}, worst ${ms(worst)}\n${shape(c, hand, PressCurve.X, worst)}")
        if (open) ShakeBody(s, c, hand, "presses")
    }
}

/**
 * One channel of a press curve from 1 s before to 1 s after: the grey band is your hand alone (±),
 * the line the average kick, the shaded band the spread of single presses around it.
 */
@Composable
private fun CurveGraph(label: String, c: PressCurve, ch: Int, natural: Double, fmt: (Double?) -> String) {
    val col = MaterialTheme.colorScheme
    val n = PressCurve.BINS
    var top = natural
    for (b in 0 until n) top = maxOf(top, abs(c.mean(ch, b)) + c.sd(ch, b))
    if (top <= 0.0) top = 1.0
    Text("$label (±${fmt(top)})", style = MaterialTheme.typography.bodySmall, color = col.onSurfaceVariant)
    val line = col.primary
    val spread = col.primary.copy(alpha = 0.22f)
    val handBand = col.onSurface.copy(alpha = 0.10f)
    val axis = col.onSurface.copy(alpha = 0.35f)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val w = size.width; val h = size.height
        fun x(b: Int) = w * b / (n - 1)
        fun y(v: Double) = (h / 2 - (v / top) * (h / 2)).toFloat()
        drawRect(handBand, topLeft = Offset(0f, y(natural)), size = androidx.compose.ui.geometry.Size(w, y(-natural) - y(natural)))
        val band = Path()
        for (b in 0 until n) band.apply { val px = x(b); val py = y(c.mean(ch, b) + c.sd(ch, b)); if (b == 0) moveTo(px, py) else lineTo(px, py) }
        for (b in n - 1 downTo 0) band.lineTo(x(b), y(c.mean(ch, b) - c.sd(ch, b)))
        band.close()
        drawPath(band, spread)
        drawLine(axis, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        for (b in listOf(PressCurve.CENTER - 25, PressCurve.CENTER + 25)) drawLine(axis, Offset(x(b), 0f), Offset(x(b), h), 1f, pathEffect = dash)
        drawLine(axis, Offset(x(PressCurve.CENTER), 0f), Offset(x(PressCurve.CENTER), h), strokeWidth = 2f)
        val mean = Path()
        for (b in 0 until n) { val px = x(b); val py = y(c.mean(ch, b)); if (b == 0) mean.moveTo(px, py) else mean.lineTo(px, py) }
        drawPath(mean, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
    Row(Modifier.fillMaxWidth()) {
        Text("−1 s", style = MaterialTheme.typography.labelSmall, color = col.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("press", style = MaterialTheme.typography.labelSmall, color = col.onSurfaceVariant)
        Text("+1 s", style = MaterialTheme.typography.labelSmall, color = col.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Share of the time at each speed for each of [hists], one line each, on a log speed scale. */
@Composable
private fun SpeedGraph(hists: List<LogHist>) {
    val col = MaterialTheme.colorScheme
    val colors = listOf(col.primary, col.tertiary, col.error)
    val axis = col.onSurface.copy(alpha = 0.35f)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val w = size.width; val h = size.height
        var top = 0.0
        for (hist in hists) { val t = hist.total; if (t > 0) for (v in hist.counts) top = maxOf(top, v / t) }
        if (top <= 0.0) return@Canvas
        // Marks at 10, 100 and 1000 °/s.
        val span = ln(2000.0 / 1.0)
        for (m in listOf(10.0, 100.0, 1000.0)) {
            val x = (w * ln(m) / span).toFloat()
            drawLine(axis, Offset(x, 0f), Offset(x, h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        hists.forEachIndexed { i, hist ->
            val t = hist.total
            if (t <= 0) return@forEachIndexed
            val n = hist.counts.size
            val path = Path()
            for (b in 0 until n) {
                val x = w * (b + 0.5f) / n
                val y = (h - h * (hist.counts[b] / t) / top).toFloat()
                if (b == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[i % colors.size], style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("1", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(ln(10.0).toFloat()))
        Text("10", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(ln(10.0).toFloat()))
        Text("100", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(ln(10.0).toFloat()))
        Text("1000 °/s", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(ln(2.0).toFloat() + 0.6f))
    }
}
