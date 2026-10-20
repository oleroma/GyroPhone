package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oleroma.gyrophone.core.net.PairFailure
import com.oleroma.gyrophone.core.net.PairState
import com.oleroma.gyrophone.core.net.PairingMath
import com.oleroma.gyrophone.wifi.PairingView

/**
 * Wi-Fi pairing: shows the 6-digit code the PC shows too. The user compares them, taps Confirm
 * here and Allow on the PC; the pairing is kept only after the PC proves it holds the same key.
 */
@Composable
fun PairDialog(view: PairingView, onConfirm: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val s = view.state
    val finished = s is PairState.Done || s is PairState.Failed
    var confirmed by remember(view.hostName) { mutableStateOf(false) }
    val sas = when (s) {
        is PairState.GotHost -> s.sas
        is PairState.AwaitUser -> s.sas
        is PairState.Confirming -> s.sas
        else -> null
    }
    val cancelButton: @Composable () -> Unit = { TextButton(onClick = onCancel) { Text("Cancel") } }

    AlertDialog(
        // A stray tap outside must not cancel a pairing in progress.
        onDismissRequest = { if (finished) onDismiss() },
        title = { Text(if (s is PairState.Done) "Paired" else "Pair with ${view.hostName}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sas != null) {
                    Text(
                        PairingMath.formatSas(sas),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 44.sp,
                    )
                    Text("Check that your PC shows this code, then tap Confirm here and Allow on the PC")
                }
                when (s) {
                    PairState.Idle, PairState.Started -> Waiting("Contacting ${view.hostName}…")
                    is PairState.GotHost -> if (confirmed) Waiting("Waiting for the PC to check the code…") else Unit
                    is PairState.AwaitUser -> Unit
                    is PairState.Confirming ->
                        Waiting(if (s.hostPending) "Waiting for Allow on the PC…" else "Sending confirmation…")
                    is PairState.Done -> Text("Paired with ${s.host.name}. Connecting…")
                    is PairState.Failed -> Text(failureText(s.reason), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (s) {
                is PairState.Done, is PairState.Failed -> TextButton(onClick = onDismiss) { Text("OK") }
                is PairState.GotHost, is PairState.AwaitUser -> if (!confirmed) {
                    Button(onClick = { confirmed = true; onConfirm() }) { Text("Confirm") }
                } else Unit
                else -> Unit
            }
        },
        dismissButton = if (finished) null else cancelButton,
    )
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

private fun failureText(reason: PairFailure): String = when (reason) {
    PairFailure.CLOSED -> "The PC is not accepting new phones. On the PC choose Pair new phone, then try again."
    PairFailure.BUSY -> "The PC is pairing another phone. Try again in a moment."
    PairFailure.COOLDOWN -> "The PC is pausing after a failed attempt. Try again in a few seconds."
    PairFailure.BAD_COMMIT -> "The PC rejected this attempt. Try again."
    PairFailure.UNKNOWN_ATTEMPT -> "The PC lost track of this attempt. Try again."
    PairFailure.DENIED -> "Denied on the PC."
    PairFailure.TIMEOUT -> "Nobody answered on the PC in time."
    PairFailure.BAD_CONFIRM -> "The PC could not verify this phone. Did the codes match?"
    PairFailure.HOST_CONFIRM_MISMATCH ->
        "The PC's answer did not verify. Someone may be interfering: do not pair on this network."
    PairFailure.CANCELLED -> "Cancelled."
    PairFailure.GAVE_UP -> "No answer from the PC. Check its firewall and that both are on the same network."
}
