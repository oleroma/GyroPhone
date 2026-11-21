package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Calibrate mapping's popup ([com.oleroma.gyrophone.runtime.GamepadRuntime.calibratePopup]):
 * the countdown, "Calibrating", then the result. A second line (the countdown's number) shows
 * large. It takes no touches, so the mapping pressed again can cancel.
 */
@Composable
internal fun CalibratePopup(text: String) {
    val lines = text.split('\n')
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .background(Color(0xE6000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(lines[0], color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
            if (lines.size > 1) {
                Text(lines[1], color = Color.White, fontSize = 48.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
