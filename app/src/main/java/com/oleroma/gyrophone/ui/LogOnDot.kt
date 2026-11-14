package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.runtime.GamepadRuntime

/**
 * A small red dot in the pad's top left corner while any Performance log is on: the phone's debug tools (with or
 * without their panel), Ratchet timing, Display timing log, or the companion's Record input log or Overlay timing
 * log. A still dot: it redraws only when a log is switched, so it costs the screen thread nothing while playing.
 */
@Composable
fun LogOnDot(runtime: GamepadRuntime, modifier: Modifier = Modifier) {
    val debug by runtime.debug.enabled.collectAsStateWithLifecycle()
    val ratchet by runtime.ratchetTimingOn.collectAsStateWithLifecycle()
    val draw by runtime.drawTimingLogOn.collectAsStateWithLifecycle()
    val pc by runtime.wifi.pcLogging.collectAsStateWithLifecycle()
    if (!(debug || ratchet || draw || pc)) return
    Box(modifier.padding(6.dp).size(8.dp).background(Color(0xFFE53935), CircleShape))
}
