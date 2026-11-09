package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown over the pad while the PC runs a Link test (TEST_MODE LINK_TEST): the phone sends fake
 * moving input that the PC only counts, so the pad's own input is not sent. Takes no touches.
 */
@Composable
internal fun LinkTestNote() {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Link test running", color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
            Text(
                "The PC is measuring the Wi-Fi link with test data. The pad's input isn't sent until it ends. Leave the phone as it is.",
                color = Color(0xFF8D96A8),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
