package com.oleroma.gyrophone.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.link.PhonePower
import com.oleroma.gyrophone.link.SetupTip
import com.oleroma.gyrophone.link.SetupTips
import com.oleroma.gyrophone.link.TipFix
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlinx.coroutines.delay

/** The Setup tips now, read every 2 s while shown (the link's stats and the phone's power). */
@Composable
internal fun rememberSetupTips(runtime: GamepadRuntime): List<SetupTip> {
    val context = LocalContext.current
    var tips by remember { mutableStateOf(emptyList<SetupTip>()) }
    LaunchedEffect(runtime) {
        while (true) {
            tips = SetupTips.of(runtime.linkStats(), PhonePower.read(context))
            delay(2000)
        }
    }
    return tips
}

/** The Connection page's "Setup tips" card: what could be better, each with its fix; nothing when all is well. */
@Composable
internal fun SetupTipsCard(tips: List<SetupTip>) {
    if (tips.isEmpty()) return
    val context = LocalContext.current
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = c.secondaryContainer, contentColor = c.onSecondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setup tips", style = MaterialTheme.typography.titleSmall)
            tips.forEach { tip ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tip.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    tip.fix?.let { fix -> OutlinedButton(onClick = { open(context, fix) }) { Text(fix.label) } }
                }
            }
        }
    }
}

private fun open(context: Context, fix: TipFix) {
    val intent = when (fix) {
        TipFix.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        TipFix.BATTERY_SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        TipFix.APP_BATTERY -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
