package com.oleroma.gyrophone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.runtime.ScreenOrientation

/**
 * The Settings page (from the pad's Settings capsule): options of the whole app. Everything about
 * one workspace is in that workspace's settings (edit mode, tap the selected workspace).
 */
@Composable
fun AppSettingsScreen(runtime: GamepadRuntime, modifier: Modifier) {
    val context = LocalContext.current
    val gyro by runtime.gyroInfo.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val orientation by runtime.screenOrientation.collectAsStateWithLifecycle()
    val padOnly by runtime.padOnlyInput.collectAsStateWithLifecycle()
    val startFullscreen by runtime.startFullscreen.collectAsStateWithLifecycle()
    val autoHide by runtime.cornerAutoHide.collectAsStateWithLifecycle()
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow("Start in fullscreen", startFullscreen) { runtime.setStartFullscreen(it) }
        Hint("The pad opens in fullscreen when the app starts. Press ← and → together (or Back) to leave it.")
        SwitchRow("Hide the ← / → corners after a few seconds", autoHide) { runtime.setCornerAutoHide(it) }
        Hint(
            "Tap the screen 4 times quickly to show them again. Hidden corners still work. " +
                "Off: they are always shown.",
        )

        SwitchRow("Send input only while the pad is open", padOnly) { runtime.setPadOnlyInput(it) }
        Hint(
            if (padOnly) "Controller, keyboard and gyro reach the PC only on the pad, outside its pages and edit mode. The connection stays up."
            else "Controller, keyboard and gyro reach the PC from anywhere, also with the app in the background.",
        )

        Text("Screen rotation", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScreenOrientation.entries.forEach { o ->
                FilterChip(selected = orientation == o, onClick = { runtime.setScreenOrientation(o) }, label = { Text(o.label) })
            }
        }

        Text("Controllers on phone", style = MaterialTheme.typography.titleSmall)
        if (controllers.isEmpty()) Text("None. Attach a GameSir X5 or any gamepad.")
        controllers.forEach { c ->
            Text("${c.name} (${c.known?.name ?: "standard layout"}) " + "%04X:%04X".format(c.vendorId, c.productId))
        }

        Text("About", style = MaterialTheme.typography.titleSmall)
        Text("GyroPhone $version", style = MaterialTheme.typography.bodySmall)
        Text("Gyro: ${gyro.source}", style = MaterialTheme.typography.bodySmall)
        Text("Gyro rate: ${gyro.rateHz} Hz" + if (gyro.active) " · moving mouse" else "", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
