package com.oleroma.gyrophone.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.hid.HidDeviceManager
import com.oleroma.gyrophone.hid.ReportPump
import kotlinx.coroutines.delay
import com.oleroma.gyrophone.runtime.GamepadRuntime

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(runtime: GamepadRuntime, onRetry: () -> Unit) {
    val context = LocalContext.current
    val state by runtime.hid.state.collectAsStateWithLifecycle()
    val gyro by runtime.gyroInfo.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val hosts = remember(state) { runCatching { runtime.hid.bondedHosts() }.getOrDefault(emptyList()) }
    val rate by runtime.reportRateHz.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf(runtime.linkStats()) }
    LaunchedEffect(Unit) {
        while (true) { stats = runtime.linkStats(); delay(1000) }
    }

    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PC connection", style = MaterialTheme.typography.titleMedium)
            Text(
                when (val s = state) {
                    HidDeviceManager.State.Off -> "Bluetooth HID is off"
                    HidDeviceManager.State.Registering -> "Registering as a gamepad…"
                    HidDeviceManager.State.Ready -> "Ready. On Windows: Settings › Bluetooth › Add device, pick this phone. Or connect below."
                    is HidDeviceManager.State.Connecting -> "Connecting to ${s.name}…"
                    is HidDeviceManager.State.Connected -> "Connected to ${s.name}"
                    is HidDeviceManager.State.Failed -> "Problem: ${s.reason}"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
                    )
                }) { Text("Make discoverable") }
                if (state is HidDeviceManager.State.Connected) {
                    OutlinedButton(onClick = { runtime.hid.disconnect() }) { Text("Disconnect") }
                }
                if (state is HidDeviceManager.State.Failed || state == HidDeviceManager.State.Off) {
                    OutlinedButton(onClick = { onRetry(); runtime.hid.start() }) { Text("Retry") }
                }
            }
            Text("Paired devices", style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(hosts, key = { it.address }) { device ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name ?: device.address, Modifier.weight(1f))
                        OutlinedButton(onClick = { runtime.hid.connect(device) }) { Text("Connect") }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Profile: ${profile.name}", style = MaterialTheme.typography.titleMedium)
            Text("Gyro: ${gyro.source}")
            Text("Gyro rate: ${gyro.rateHz} Hz" + if (gyro.active) " · moving mouse" else "")
            Text("Report rate", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReportPump.RATES_HZ.forEach { hz ->
                    FilterChip(selected = rate == hz, onClick = { runtime.setReportRate(hz) }, label = { Text("$hz Hz") })
                }
            }
            Text(
                "Sent ${stats.sentPerSecond}/s" +
                    if (stats.failedPerSecond > 0) " · refused ${stats.failedPerSecond}/s (lower the rate)" else "",
            )
            Text("If the mouse lags behind your hand, pick a lower rate. Bluetooth queues anything faster than the link can carry.",
                style = MaterialTheme.typography.bodySmall)
            Text("Controllers on phone", style = MaterialTheme.typography.titleSmall)
            if (controllers.isEmpty()) Text("None. Attach a GameSir X5 or any gamepad.")
            controllers.forEach { c ->
                Text("${c.name} (${c.known?.name ?: "standard layout"}) " + "%04X:%04X".format(c.vendorId, c.productId))
            }
        }
    }
}
