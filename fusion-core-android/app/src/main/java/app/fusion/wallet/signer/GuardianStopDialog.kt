package app.fusion.wallet.signer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** K11 can mount this at its existing Stop confirmation seam without duplicating signer UI state. */
@Composable
fun GuardianStopDialog(requestPause: suspend () -> Result<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var txHash by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val publicHash = txHash ?: error?.let { Regex("0x[0-9a-f]{64}").find(it)?.value }
    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(if (txHash != null) "Paused on-chain" else "Stop the agent?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Anything already sent may still complete. Restarting needs both Safe owners.")
                when {
                    sending -> { CircularProgressIndicator(); Text("Pausing on-chain… Waiting for a confirmed receipt.") }
                    txHash != null -> Text("Guardian pause confirmed.")
                    error != null -> Text(error!!)
                    else -> Text("Send the guardian pause transaction on Base Sepolia.")
                }
                publicHash?.let { hash ->
                    Text(hash)
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sepolia.basescan.org/tx/$hash")))
                    }) { Text("View on BaseScan") }
                }
            }
        },
        confirmButton = {
            if (txHash == null) Button(enabled = !sending, onClick = {
                sending = true
                error = null
                scope.launch {
                    try {
                        requestPause().fold(onSuccess = { txHash = it }, onFailure = {
                            error = it.message ?: "Guardian pause failed"
                        })
                    } finally { sending = false }
                }
            }) { Text(if (sending) "Pausing…" else if (error == null) "Pause on-chain" else "Retry") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text(if (txHash != null) "Done" else "Close") } },
    )
}
