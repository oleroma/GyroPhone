package com.oleroma.gyrophone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import com.oleroma.gyrophone.runtime.ScreenOrientation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(runtime: GamepadRuntime, onRetry: () -> Unit) {
    val context = LocalContext.current
    val transport by runtime.transport.collectAsStateWithLifecycle()
    val gyro by runtime.gyroInfo.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val workspaceIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val rate by runtime.reportRateHz.collectAsStateWithLifecycle()
    val wifiRate by runtime.wifiRateHz.collectAsStateWithLifecycle()
    val orientation by runtime.screenOrientation.collectAsStateWithLifecycle()
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var stats by remember { mutableStateOf(runtime.linkStats()) }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }
    LaunchedEffect(transport) {
        while (true) { stats = runtime.linkStats(); delay(1000) }
    }

    val retry by rememberUpdatedState(onRetry)
    // Movable, so the link panel's state (e.g. a pairing in progress) survives a rotation that
    // moves it between the portrait Column and the landscape Row. It reads only State objects
    // (transport, stats) and stable values, so remembering it once does not freeze anything.
    val linkContent = remember(runtime) { movableContentOf {
        Text("PC connection", style = MaterialTheme.typography.titleMedium)
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
        when (transport) {
            TransportKind.BLUETOOTH -> BluetoothPanel(runtime, retry)
            TransportKind.WIFI -> WifiPanel(runtime, stats)
        }
    } }
    val statusContent: @Composable () -> Unit = {
        Text("Workspaces", style = MaterialTheme.typography.titleMedium)
        if (profile.workspaces.size > 1) {
            val w = profile.workspace(workspaceIndex)
            Text("Workspace ${workspaceIndex.coerceIn(0, profile.workspaces.lastIndex) + 1}/${profile.workspaces.size}: ${w.name}")
        }
        Text("GyroPhone $version", style = MaterialTheme.typography.bodySmall)
        Text("Gyro: ${gyro.source}")
        Text("Gyro rate: ${gyro.rateHz} Hz" + if (gyro.active) " · moving mouse" else "")
        Text("Report rate", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (transport == TransportKind.WIFI) {
                Wire.RATES_HZ.forEach { hz ->
                    FilterChip(selected = wifiRate == hz, onClick = { runtime.setWifiRate(hz) }, label = { Text("$hz Hz") })
                }
            } else {
                ReportPump.RATES_HZ.forEach { hz ->
                    FilterChip(selected = rate == hz, onClick = { runtime.setReportRate(hz) }, label = { Text("$hz Hz") })
                }
            }
        }
        if (transport == TransportKind.WIFI) {
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
    }

    if (portrait) {
        // Portrait: the link panel above, status below. The panels scroll on their own, so the
        // outer column must not (nested vertical scrolling would get an unbounded height).
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { linkContent() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                statusContent()
            }
        }
    } else {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { linkContent() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                statusContent()
            }
        }
    }
}

/** Connect tab, Bluetooth: the HID registration, discoverability and bonded PCs. */
@SuppressLint("MissingPermission")
@Composable
private fun BluetoothPanel(runtime: GamepadRuntime, onRetry: () -> Unit) {
    val context = LocalContext.current
    val state by runtime.hid.state.collectAsStateWithLifecycle()
    val hosts = remember(state) { runCatching { runtime.hid.bondedHosts() }.getOrDefault(emptyList()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}
