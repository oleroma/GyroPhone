package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.engine.RatchetTiming
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Settings › Debugging › "Ratchet timing" on the pad: the last [RatchetTiming.KEPT] ratchet
 * presses, newest first, and the middle of each column. A plain box with no touch handling, so
 * the pad under it still gets every finger.
 */
@Composable
fun RatchetTimingPanel(runtime: GamepadRuntime, modifier: Modifier = Modifier) {
    val on by runtime.ratchetTimingOn.collectAsStateWithLifecycle()
    if (!on) return
    val timing = runtime.ratchetTiming
    var version by remember { mutableIntStateOf(timing.version) }
    LaunchedEffect(timing) {
        while (true) {
            delay(150)
            if (timing.version != version) version = timing.version
        }
    }
    val results = remember(version) { timing.results() }
    val text = remember(results) { describe(results) }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
    Box(
        modifier
            .background(Color(0xC0101418), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(text, color = Color.White, style = mono)
    }
}

private fun ms(v: Float?): String = when {
    v == null -> "   -"
    else -> "%+4d".format(v.roundToInt())
}

private fun back(r: RatchetTiming.Result): String = when {
    r.backDeg != null -> "%.1f°".format(r.backDeg)
    r.backCounts != null -> "${r.backCounts} ct"
    else -> "-"
}

private fun describe(rs: List<RatchetTiming.Result>): String = buildString {
    append("Ratchet timing   you  app  back\n")
    if (rs.isEmpty()) {
        append("Swing, press the ratchet at the turn, swing back.")
        return@buildString
    }
    val you = RatchetTiming.median(rs.mapNotNull { it.playerMs })
    val app = RatchetTiming.median(rs.mapNotNull { it.appMs })
    val deg = RatchetTiming.median(rs.mapNotNull { it.backDeg })
    val counts = RatchetTiming.median(rs.mapNotNull { it.backCounts?.toFloat() })
    val backMid = deg?.let { "%.1f°".format(it) } ?: counts?.let { "${it.roundToInt()} ct" } ?: "-"
    append("middle (ms)     ${ms(you)} ${ms(app)}  $backMid\n")
    for ((i, r) in rs.withIndex()) {
        append("%-15s ".format(if (i == 0) "last" else "${i + 1}"))
        if (!r.swing) append("   - ${ms(r.appMs)}  no swing") else append("${ms(r.playerMs)} ${ms(r.appMs)}  ${back(r)}")
        if (i < rs.lastIndex) append('\n')
    }
}
