package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The latency test's tracking screen, shown while the PC's latency test window is open (TEST_MODE
 * LATENCY_SCREEN): four ArUco markers (4×4 dictionary, ids 0 to 3) in the top half, clear of the
 * hand holding the phone from below, so the PC's video analyser can tell how far the phone is
 * turned. "Top" is the phone's own top, the camera end, in every rotation: the screen is drawn
 * turned back to the phone's portrait. It draws over the pad but takes no touches (input keeps
 * working), except [onStop].
 */
@Composable
internal fun LatencyMarkers(onStop: () -> Unit) {
    // The display draws turned by its rotation; turning back by it puts the camera end on top.
    val turns = LocalView.current.display?.rotation ?: 0
    Box(Modifier.fillMaxSize().background(Color.Black).phonePortrait(turns)) { Markers(onStop) }
}

/** Lays the content out in the phone's own portrait and turns it there, for a display rotated by [turns] quarter turns. */
private fun Modifier.phonePortrait(turns: Int): Modifier {
    val swap = turns % 2 == 1
    return layout { measurable, c ->
        val inner = if (swap) Constraints.fixed(c.maxHeight, c.maxWidth) else Constraints.fixed(c.maxWidth, c.maxHeight)
        val p = measurable.measure(inner)
        layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - p.width) / 2, (c.maxHeight - p.height) / 2) }
    }.graphicsLayer { rotationZ = -90f * turns }
}

@Composable
private fun Markers(onStop: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        // Two rows of two in the top half, as big as fits.
        val side = minOf(w * 0.34f, h * 0.22f)
        val gapX = 16.dp
        val gapY = side * 0.15f
        val rows = listOf(12.dp, 12.dp + side + gapY)
        var id = 0
        for (top in rows) {
            for (left in listOf(gapX, w - gapX - side)) {
                Marker(MARKERS[id++], side, Modifier.offset(left, top))
            }
        }
        Column(
            Modifier.align(Alignment.Center).offset(y = h * 0.25f).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Twist your wrist left and right", color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
            Text(
                "Keep these markers and the green bar on the PC in the video. Input keeps working.",
                color = Color(0xFF8D96A8),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            FilledTonalButton(onClick = onStop, modifier = Modifier.padding(top = 16.dp)) { Text("Hide markers") }
        }
    }
}

/** One marker: a white quiet zone of one cell, then the 6×6 black-bordered code. */
@Composable
private fun Marker(bits: String, side: Dp, modifier: Modifier) {
    Canvas(modifier.size(side)) {
        val cell = size.width / 8f
        drawRect(Color.White)
        drawRect(Color.Black, Offset(cell, cell), Size(cell * 6, cell * 6))
        for (r in 0 until 4) for (c in 0 until 4) {
            if (bits[r * 4 + c] == '1') drawRect(Color.White, Offset(cell * (c + 2), cell * (r + 2)), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** ArUco DICT_4X4_50 ids 0..3, inner 4×4 bits row by row (1 = white): top left, top right, then the row below. */
private val MARKERS = listOf("1011010100110010", "0000111110011010", "0011001100101101", "1001100101000110")
