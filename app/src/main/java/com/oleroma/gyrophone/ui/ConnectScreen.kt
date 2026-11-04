package com.oleroma.gyrophone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.net.Wire
import com.oleroma.gyrophone.hid.HidDeviceManager
import com.oleroma.gyrophone.hid.ReportPump
import com.oleroma.gyrophone.link.TransportKind
import com.oleroma.gyrophone.service.GamepadService
import kotlinx.coroutines.delay
import com.oleroma.gyrophone.runtime.GamepadRuntime
import com.oleroma.gyrophone.sensor.DirectChannelGyroSource

/**
 * The Connection page (from the pad's Connection capsule), as categories: the PC link (Wi-Fi or
 * Bluetooth), key pairing and link quality over Wi-Fi, the report rate, the gyro poll rate and
 * when to stop while idle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(runtime: GamepadRuntime, onRetry: () -> Unit) {
    val context = LocalContext.current
    val transport by runtime.transport.collectAsStateWithLifecycle()
    val rate by runtime.reportRateHz.collectAsStateWithLifecycle()
    val wifiRate by runtime.wifiRateHz.collectAsStateWithLifecycle()
    val idleStop by runtime.idleStopMinutes.collectAsStateWithLifecycle()
    val gyroPoll by runtime.gyroPollHz.collectAsStateWithLifecycle()
    val gyroPollUsed by runtime.gyroPollUsed.collectAsStateWithLifecycle()
    val pcControllerGyro by runtime.pcControllerGyro.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf(runtime.linkStats()) }
    LaunchedEffect(transport) {
        while (true) { stats = runtime.linkStats(); delay(1000) }
    }
    val retry by rememberUpdatedState(onRetry)
    val wifi = transport == TransportKind.WIFI

    val items = listOfNotNull(
        category(runtime, "pc", "PC connection", if (wifi) "Wi-Fi: pairing, paired and found PCs" else "Bluetooth: pairing and paired PCs", defaultOpen = true) {
            Searchable("PC connection", "Wi-Fi Bluetooth transport link") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TransportKind.entries.forEachIndexed { i, kind ->
                        SegmentedButton(
                            selected = transport == kind,
                            onClick = {
                                runtime.setTransport(kind)
                                if (kind == TransportKind.WIFI) {
                                    // Wi-Fi needs no runtime permission: make sure the service runs.
                                    GamepadService.start(context)
                                } else if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    retry() // starts the service once granted
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = TransportKind.entries.size),
                            label = { Text(if (kind == TransportKind.WIFI) "Wi-Fi" else "Bluetooth") },
                        )
                    }
                }
            }
            when (transport) {
                TransportKind.BLUETOOTH -> BluetoothPanel(runtime, retry)
                TransportKind.WIFI -> WifiPanel(runtime, stats)
            }
        },
        if (wifi) category(runtime, "keyPairing", "Key pairing", "Connect without a code: keys, trusted PCs, connect automatically") {
            KeyPairingSection(runtime.wifi)
        } else null,
        if (wifi) category(runtime, "link", "Link quality", "Rates, loss, latency, USB") { WifiLinkSection(stats) } else null,
        category(runtime, "reportRate", "Report rate", "How often input is sent to the PC") {
            Searchable("Report rate", "Hz sent refused lag loss") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (wifi) {
                            Wire.RATES_HZ.forEach { hz ->
                                FilterChip(selected = wifiRate == hz, onClick = { runtime.setWifiRate(hz) }, label = { Text("$hz Hz") })
                            }
                        } else {
                            ReportPump.RATES_HZ.forEach { hz ->
                                FilterChip(selected = rate == hz, onClick = { runtime.setReportRate(hz) }, label = { Text("$hz Hz") })
                            }
                        }
                    }
                    if (wifi) {
                        Text("Wi-Fi sends on every change, up to this rate. Pick a lower rate if the PC reports loss.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Sent ${stats.sentPerSecond}/s" +
                                if (stats.failedPerSecond > 0) " · refused ${stats.failedPerSecond}/s (lower the rate)" else "",
                        )
                        Text("If the mouse lags behind your hand, pick a lower rate. Bluetooth queues anything faster than the link can carry.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        category(runtime, "gyroPoll", "Gyro poll rate", "How often the app checks the gyro") {
            Searchable("Gyro poll rate", "kHz sensor samples delay jitter battery CPU") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DirectChannelGyroSource.POLL_RATES_HZ.forEach { hz ->
                            FilterChip(selected = gyroPoll == hz, enabled = gyroPollUsed, onClick = { runtime.setGyroPollRate(hz) },
                                label = { Text("${hz / 1000} kHz") })
                        }
                    }
                    Text(
                        if (gyroPollUsed) {
                            "How often the app checks the gyro for new samples. Higher means less delay and jitter " +
                                "(at most 0.25 ms at 4 kHz, 1 ms at 1 kHz) but more CPU and battery."
                        } else {
                            "This phone reads the gyro through the standard sensor listener, so the poll rate has no effect."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        if (wifi) category(runtime, "pcControllerGyro", "PC controller for gyro ratchet", "Ratchet the gyro from a controller plugged into the PC") {
            Searchable("Use PC controller for gyro ratchet", "controller gamepad plugged PC ratchet toggle gyro Steam Input XInput") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use PC controller for gyro ratchet", Modifier.weight(1f))
                        Switch(checked = pcControllerGyro, onCheckedChange = { runtime.setPcControllerGyro(it) })
                    }
                    Text(
                        "Ratchet and Toggle gyro buttons from your controller preset work on a controller plugged into the PC. " +
                            "Gyro inertia, gyro sensitivity buttons and the phone's ratchet displays don't react to it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else null,
        category(runtime, "idleStop", "Stop when idle", "End the connection while the app is off screen") {
            Searchable("Stop when idle", "background notification screen off minutes battery") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GamepadRuntime.IDLE_STOP_CHOICES.forEach { m ->
                            FilterChip(selected = idleStop == m, onClick = { runtime.setIdleStopMinutes(m) },
                                label = { Text(if (m == 0) "Off" else "$m min") })
                        }
                    }
                    Text(
                        if (idleStop == 0) "The connection stays up while the app is in the background. Stop it from the notification."
                        else "The connection stops after the app has been off screen for $idleStop min, screen off included. " +
                            "Opening the app connects again. The notification's Stop ends it right away.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
    SearchableSettings(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Hint("Long press a category's name to move it.")
        ReorderableSections(runtime, "connection", items)
    }
}

/** Connect tab, Bluetooth: the HID registration, discoverability and bonded PCs. */
@SuppressLint("MissingPermission")
@Composable
private fun BluetoothPanel(runtime: GamepadRuntime, onRetry: () -> Unit) {
    val context = LocalContext.current
    val state by runtime.hid.state.collectAsStateWithLifecycle()
    val hosts = remember(state) { runCatching { runtime.hid.bondedHosts() }.getOrDefault(emptyList()) }

    Searchable(
        "Bluetooth", "PC connection make discoverable paired devices connect disconnect retry HID",
        *hosts.map { it.name ?: it.address }.toTypedArray(),
    ) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                OutlinedButton(onClick = {
                    onRetry()
                    // Without the permission HID would register before the dialog is answered and
                    // crash; the permission callback starts it once granted.
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        runtime.hid.start()
                    }
                }) { Text("Retry") }
            }
        }
        Text("Paired devices", style = MaterialTheme.typography.titleSmall)
        // A plain column: the page scrolls, and a lazy list inside a scrolling one has no height.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            hosts.forEach { device ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name ?: device.address, Modifier.weight(1f))
                    OutlinedButton(onClick = { runtime.hid.connect(device) }) { Text("Connect") }
                }
            }
        }
    } }
}
