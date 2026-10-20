package app.fusion.wallet.ui.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fusion.wallet.ui.common.startActivitySafe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveSheet(safeAddress: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val payload = remember(safeAddress) { receivePayload(safeAddress) }
    val qr = remember(payload) { receiveQr(payload) }
    var feedback by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Receive USDC", style = MaterialTheme.typography.headlineSmall)
            Text("Send USDC on Base Sepolia (test network) only")
            Canvas(Modifier.align(Alignment.CenterHorizontally).size(240.dp).background(Color.White).semantics {
                contentDescription = "QR code of Safe address $payload"
            }) {
                val cell = size.minDimension / qr.width
                for (y in 0 until qr.height) for (x in 0 until qr.width) if (qr[x, y]) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
                }
            }
            SelectionContainer { Text(payload) }
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Safe address", payload))
                feedback = "Safe address copied"
            }) { Text("Copy Safe address") }
            TextButton(onClick = {
                if (!context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://sepolia.basescan.org/address/$payload")))) feedback = "No browser available to open BaseScan"
            }) { Text("View Safe on BaseScan") }
            feedback?.let { Text(it) }
        }
    }
}
