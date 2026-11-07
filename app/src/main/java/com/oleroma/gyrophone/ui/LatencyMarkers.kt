package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

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
        val h = maxHeight
        // Two rows of two, as big as fits in the top half: 16 cells square (see [MarkerGrid]).
        Canvas(Modifier.fillMaxWidth().height(h / 2).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            val cell = floor(minOf(size.width, size.height) / MarkerGrid.CELLS)
            if (cell < 1f) return@Canvas
            val x0 = floor((size.width - cell * MarkerGrid.CELLS) / 2)
            drawMarkerGrid(x0, 0f, cell)
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

/**
 * The marker grid, in whole cells so every edge falls on a pixel: 16 × 16 cells, each marker a
 * one-cell grey quiet zone around a 6 × 6 black square (border and 4 × 4 code), the markers' black
 * squares 8 cells apart (ids 0, 1 on top, 2, 3 below). The PC's analyser knows this layout
 * (`VideoTracker` MarkerPitch). Light grey, not white: a white screen glows on camera into the
 * markers' thin black border.
 */
private object MarkerGrid {
    const val CELLS = 16
    val LIGHT = Color(0xFFA8A8A8)
}

private fun DrawScope.drawMarkerGrid(x0: Float, y0: Float, cell: Float) {
    for (id in 0 until 4) {
        val mx = x0 + cell * (8 * (id % 2))
        val my = y0 + cell * (8 * (id / 2))
        drawRect(MarkerGrid.LIGHT, Offset(mx, my), Size(cell * 8, cell * 8))
        drawRect(Color.Black, Offset(mx + cell, my + cell), Size(cell * 6, cell * 6))
        val bits = MARKERS[id]
        for (r in 0 until 4) for (c in 0 until 4) {
            if (bits[r * 4 + c] == '1') drawRect(MarkerGrid.LIGHT, Offset(mx + cell * (c + 2), my + cell * (r + 2)), Size(cell, cell))
        }
    }
}

/** ArUco DICT_4X4_50 ids 0..3, inner 4×4 bits row by row (1 = white): top left, top right, then the row below. */
private val MARKERS = listOf("1011010100110010", "0000111110011010", "0011001100101101", "1001100101000110")
