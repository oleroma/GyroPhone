package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The Swipe card's "Any" direction: eight arrows pointing out from a centre dot. */
@Composable
internal fun AnyWayIcon(size: Dp = 18.dp, color: Color = LocalContentColor.current) {
    Canvas(Modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2
        val w = r * 0.13f
        drawCircle(color, r * 0.14f, c)
        for (k in 0 until 8) {
            val a = k * PI / 4
            val ux = cos(a).toFloat()
            val uy = sin(a).toFloat()
            val from = Offset(c.x + ux * r * 0.38f, c.y + uy * r * 0.38f)
            val tip = Offset(c.x + ux * r * 0.95f, c.y + uy * r * 0.95f)
            drawLine(color, from, tip, w, StrokeCap.Round)
            // The arrowhead: two short strokes back from the tip.
            for (s in listOf(1f, -1f)) {
                val b = a + PI + s * PI / 5
                val end = Offset(tip.x + cos(b).toFloat() * r * 0.3f, tip.y + sin(b).toFloat() * r * 0.3f)
                drawLine(color, tip, end, w, StrokeCap.Round)
            }
        }
    }
}

/** A swipe direction's name ([com.oleroma.gyrophone.core.model.Bindings.slotLabel]): an arrow, or for "Any" its icon. */
@Composable
internal fun DirectionLabel(label: String, color: Color = LocalContentColor.current, style: TextStyle? = null, bold: Boolean = false) {
    if (label == "Any") AnyWayIcon(color = color)
    else if (style != null) Text(label, style = style, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    else Text(label, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}
