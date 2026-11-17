package com.oleroma.gyrophone.ui

import android.view.RoundedCorner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt

/**
 * A small red dot in the pad's top left corner while Diagnostics is on, on the phone or in the companion. It sits just inside the screen's rounded corner (the display's own corner radius), so the curve never hides
 * it. A still dot: it redraws only when a log is switched, so it costs the screen thread nothing while playing.
 */
@Composable
fun LogOnDot(runtime: GamepadRuntime, modifier: Modifier = Modifier) {
    val on by runtime.debug.enabled.collectAsStateWithLifecycle()
    val pc by runtime.wifi.pcLogging.collectAsStateWithLifecycle()
    if (!(on || pc)) return
    val view = LocalView.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val size = 8.dp
    val margin = with(density) { 6.dp.toPx() }
    // The dot's top left: the corner curve's point at 45°, moved [margin] further in; without a rounded corner, [margin] from both edges.
    val at = remember(config.orientation, config.screenWidthDp, config.screenHeightDp, view.rootWindowInsets) {
        val c = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        val inset = if (c == null || c.radius <= 0) 0f
        else maxOf(c.center.x - loc[0], c.center.y - loc[1]).toFloat() - c.radius * 0.7071f
        val p = (maxOf(inset, 0f) + margin).roundToInt()
        IntOffset(p, p)
    }
    Box(modifier.offset { at }.size(size).background(Color(0xFFE53935), CircleShape))
}
