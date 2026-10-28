package com.oleroma.gyrophone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Slider as M3Slider

/**
 * The app's slider: a thin track and a round handle, in a short row, in place of Material's tall
 * bar handle and thick track. Same parameters as the Material slider the screens used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val active = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)
    val inactive = colors.surfaceVariant
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        M3Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.height(28.dp),
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            steps = steps,
            valueRange = valueRange,
            thumb = { Box(Modifier.size(18.dp).background(active, CircleShape)) },
            track = { state ->
                val span = state.valueRange.endInclusive - state.valueRange.start
                val f = if (span <= 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val y = size.height / 2f
                    val w = size.height
                    drawLine(inactive, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
                    drawLine(active, Offset(0f, y), Offset(size.width * f, y), w, StrokeCap.Round)
                }
            },
        )
    }
}
