package com.oleroma.gyrophone.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.oleroma.gyrophone.core.net.KeyStatus
import com.oleroma.gyrophone.link.LinkState
import com.oleroma.gyrophone.link.LinkStats
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** Connect tab, Wi-Fi: the PC companion's status, paired and found PCs, and link quality. */
@Composable
fun WifiPanel(runtime: GamepadRuntime, stats: LinkStats, modifier: Modifier = Modifier) {
    val wifi = runtime.wifi
    val state by runtime.linkState.collectAsStateWithLifecycle()
    val paired by wifi.paired.collectAsStateWithLifecycle()
    val found by wifi.found.collectAsStateWithLifecycle()
    val nothingFound by wifi.nothingFound.collectAsStateWithLifecycle()
    val pairing by wifi.pairing.collectAsStateWithLifecycle()
    val notice by wifi.notice.collectAsStateWithLifecycle()
    val current by wifi.currentHostId.collectAsStateWithLifecycle()
    val pairedByKey by wifi.pairedByKey.collectAsStateWithLifecycle()
    val qr by wifi.qrPairing.collectAsStateWithLifecycle()
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanError = wifi.scanQr(it) } // null contents: the user backed out
    }
    var address by rememberSaveable { mutableStateOf("") }
    val connected = state is LinkState.Connected
    val sessionActive = state is LinkState.Connected || state is LinkState.Connecting || state is LinkState.HostSilent

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(statusLine(state))
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (sessionActive && pairedByKey) {
            // A key session has no row under Paired PCs, so it gets its own Disconnect.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paired by key", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { wifi.disconnect() }) { Text("Disconnect") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "New PC? On the PC choose Pair new phone and scan its QR code.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                scanError = null
                wifi.dismissQr()
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the QR code in GyroPhone Companion")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            }) { Text("Scan QR code") }
        }
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        qr?.let { q ->
            Text(
                q.message,
                color = if (q.finished && !q.ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (connected) {
            warnings(stats).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        Text("Paired PCs", style = MaterialTheme.typography.titleSmall)
        if (paired.isEmpty()) Text("None yet. Start GyroPhone Companion on the PC; it appears below.")
        paired.forEach { host ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(host.name, Modifier.weight(1f))
                if (sessionActive && current == host.hostIdHex) {
                    OutlinedButton(onClick = { wifi.disconnect() }) { Text("Disconnect") }
                } else {
                    OutlinedButton(onClick = { wifi.connect(host.hostIdHex) }) { Text("Connect") }
                }
                OutlinedButton(onClick = { wifi.forget(host.hostIdHex) }) { Text("Forget") }
            }
        }

        Text("Found on this network", style = MaterialTheme.typography.titleSmall)
        if (found.isEmpty()) {
            Text(
                if (nothingFound) {
                    "No PC found: check that the PC app is running, the network is Private, and the router has no client isolation"
                } else if (connected) {
                    "Not looking while connected."
                } else {
                    "Looking…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        found.forEach { host ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(host.name)
                    Text(
                        host.address + (if (host.busy) " · in use by another phone" else "") +
                            (if (!host.vigemOk) " · no gamepad driver" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    keyHint(host.name, host.keyStatus)?.takeIf { !host.paired }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                when {
                    host.keyStatus == KeyStatus.READY && !host.paired -> if (!(sessionActive && current == host.hostIdHex)) {
                        OutlinedButton(onClick = { wifi.connectByKey(host.hostIdHex) }) { Text("Connect") }
                    } else Unit
                    host.paired -> if (!(sessionActive && current == host.hostIdHex)) {
                        OutlinedButton(onClick = { wifi.connect(host.hostIdHex) }) { Text("Connect") }
                    } else Unit
                    host.pairingOpen -> Button(onClick = { wifi.pair(host) }) { Text("Connect") }
                    else -> Text("On the PC choose Pair new phone", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("Enter PC address", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                label = { Text("e.g. 192.168.1.20") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedButton(onClick = { wifi.connectByIp(address) }, enabled = address.isNotBlank()) { Text("Find") }
        }

        KeyPairingSection(wifi)

        Text("Link", style = MaterialTheme.typography.titleSmall)
        Text(
            "Sent ${stats.sentPerSecond}/s" +
                (stats.hostRxPerSecond?.let { " · PC got $it/s" } ?: "") +
                (stats.lossPercent?.let { " · loss " + "%.1f".format(it) + " %" } ?: ""),
        )
        if (stats.latP50Ms != null || stats.rttMs != null) {
            Text(
                listOfNotNull(
                    stats.latP50Ms?.let { "latency p50 " + "%.1f".format(it) + " ms" },
                    stats.latP99Ms?.let { "p99 " + "%.1f".format(it) + " ms" },
                    stats.rttMs?.let { "RTT " + "%.1f".format(it) + " ms" },
                ).joinToString(" · ").replaceFirstChar { it.uppercase() },
            )
        }
        Text(
            if (stats.usb) "Over USB tethering (Wi-Fi takes over when the cable comes out)"
            else "Low-latency Wi-Fi: " + (if (stats.lowLatencyLock == true) "on" else "off") + " (keep GyroPhone on screen)",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!stats.usb) {
            Text(
                "Faster by cable: plug the phone into the PC and turn on USB tethering. Developer options → " +
                    "Default USB configuration → USB tethering turns it on by itself each time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pairing?.let { view ->
        PairDialog(view, onConfirm = { wifi.confirmPair() }, onCancel = { wifi.cancelPair() }, onDismiss = { wifi.dismissPair() })
    }
}

/** Why key pairing with a PC does not work yet, or null. */
private fun keyHint(name: String, status: KeyStatus): String? = when (status) {
    KeyStatus.PC_LACKS_PHONE -> "$name doesn't know this phone yet: scan its QR code (Pair new phone on the PC)"
    KeyStatus.PHONE_LACKS_PC -> "This phone doesn't know $name yet: scan its QR code (Pair new phone on the PC)"
    KeyStatus.READY, KeyStatus.NONE -> null
}

private fun statusLine(state: LinkState): String = when (state) {
    LinkState.Off -> "Wi-Fi link is off"
    LinkState.Searching -> "Looking for PCs running GyroPhone Companion…"
    is LinkState.Pairing -> "Pairing with ${state.host}…"
    is LinkState.Connecting -> "Connecting to ${state.name}…"
    is LinkState.Connected -> "Connected to ${state.name}" + if (state.detail.isNotBlank()) " · ${state.detail}" else ""
    is LinkState.HostSilent -> "${state.name} is not answering, reconnecting…"
    is LinkState.Failed -> "Problem: ${state.reason}"
}

private fun warnings(stats: LinkStats): List<String> = listOfNotNull(
    if (stats.band == 2 && !stats.usb) "2.4 GHz Wi-Fi adds lag: use the router's 5 GHz network if it has one" else null,
    if ((stats.lossPercent ?: 0f) > 2f) "Loss above 2 %: move closer to the router or pick a lower rate" else null,
    if (stats.vigemOk == false) {
        "The PC has no gamepad driver (ViGEmBus): pad input is dropped, keyboard and mouse still work"
    } else {
        null
    },
)
