package com.oleroma.gyrophone.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.wifi.WifiLink

/**
 * Connect tab, Wi-Fi: key file pairing. Shows this phone's key fingerprint, shares phone.pub,
 * imports a PC's host.pub, and lists the trusted PCs. QR code pairing (WifiPanel) and update.cmd
 * fill the same folders.
 */
@Composable
fun KeyPairingSection(wifi: WifiLink) {
    val context = LocalContext.current
    val fingerprint by wifi.keys.fingerprint.collectAsStateWithLifecycle()
    val trusted by wifi.keys.trusted.collectAsStateWithLifecycle()
    val autoConnect by wifi.autoConnect.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_KEY_FILE).toByteArray().toString(Charsets.UTF_8) }
        }.getOrNull()
        message = if (text == null) "Could not read that file" else wifi.importHostKey(text) ?: "PC key added"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Key pairing", style = MaterialTheme.typography.titleSmall)
        Text(
            "This phone: " + (fingerprint ?: "no key yet"),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Scanning a PC's QR code (or running update.cmd, which swaps keys over adb) lets the phone connect without a code.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Connect automatically")
                Text("Only while GyroPhone is on screen", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = autoConnect, onCheckedChange = { wifi.setAutoConnect(it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    wifi.keys.publicLine()?.let { line ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "phone.pub")
                            putExtra(Intent.EXTRA_TEXT, line)
                        }
                        context.startActivity(Intent.createChooser(send, "Share my key"))
                    }
                },
                enabled = fingerprint != null,
            ) { Text("Share my key") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import PC key") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (trusted.isEmpty()) {
            Text("No trusted PCs yet.", style = MaterialTheme.typography.bodySmall)
        }
        trusted.forEach { host ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(host.name)
                    Text(host.key.fingerprint, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { wifi.removeHostKey(host) }) { Text("Remove") }
            }
        }
    }
}

private const val MAX_KEY_FILE = 4096
