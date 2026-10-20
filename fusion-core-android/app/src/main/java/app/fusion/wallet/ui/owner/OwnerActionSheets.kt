package app.fusion.wallet.ui.owner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.common.startActivitySafe
import app.fusion.wallet.ui.common.allowanceLabels
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.math.BigInteger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerSendSheet(state: WalletState, onSwap: () -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var understand by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val checkedAddress = remember(recipient) { OwnerTransactionEncoder.checksummedAddress(recipient) }
    val amountRaw = remember(amount) { parseUsdcAmount(amount) }
    val liveSnapshotReady = state.source == app.fusion.wallet.data.DataSource.LIVE &&
        !state.isDisplayStale() && state.accountingMatches == true
    val spare = state.buckets?.surplus?.raw?.takeIf { liveSnapshotReady }
    val excess = if (amountRaw != null && spare != null) amountRaw.subtract(spare).max(BigInteger.ZERO) else BigInteger.ZERO
    val overSpare = excess.signum() > 0
    val knownPayee = checkedAddress != null && state.payees.any { it.address.equals(checkedAddress, true) }
    val tx = remember(state.treasury?.assetAddress, checkedAddress, amountRaw) {
        if (!liveSnapshotReady || state.treasury == null || checkedAddress == null || amountRaw == null)
            null
        else runCatching { OwnerTransactionEncoder.transfer(state.treasury.assetAddress, checkedAddress, amountRaw) }.getOrNull()
    }
    val canShow = tx != null && spare != null && (!overSpare || understand)
    val json = remember(tx) { tx?.json() }
    val qr = remember(json, canShow) { if (json != null && canShow) runCatching { QRCodeWriter().encode(json, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull() else null }
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(min = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Send USDC · owner path", style = MaterialTheme.typography.headlineSmall)
            Text("Owner-signed 2-of-2 · this app holds no owner keys · the AI cannot send")
            OutlinedTextField(recipient, { recipient = it; understand = false }, Modifier.fillMaxWidth(), label = { Text("Recipient address") }, singleLine = true)
            when {
                recipient.isBlank() -> Unit
                checkedAddress == null -> Text("Enter a valid EIP-55 checksummed 0x address.", color = MaterialTheme.colorScheme.error)
                !knownPayee -> Text("This address is not a known payee. Check it carefully before sharing with the Safe owners.", color = Color(0xFFFFC857))
            }
            OutlinedTextField(amount, { amount = it; understand = false }, Modifier.fillMaxWidth(), label = { Text("Amount (USDC)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            when {
                spare == null -> Text("Reserve check unavailable until a fresh live snapshot with verified bill accounting loads.", color = Color(0xFFFFC857))
                amountRaw == null && amount.isNotBlank() -> Text("Enter a positive amount with up to 6 decimal places.", color = MaterialTheme.colorScheme.error)
                amountRaw != null && !overSpare -> Text("Bills and safety cushion stay covered", color = Color(0xFF67D391))
                amountRaw != null -> {
                    Text("This would dip into reserved bills/cushion by ${formatRawUsdc(excess)} USDC", color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(understand, { understand = it })
                        Text("I understand")
                    }
                }
            }
            if (canShow && json != null) {
                Text("Exact Safe transaction · unsigned", style = MaterialTheme.typography.titleMedium)
                TransactionField("to (USDC token)", tx.to, context) { feedback = it }
                TransactionField("value", tx.value, context) { feedback = it }
                TransactionField("data (ERC-20 transfer)", tx.data, context) { feedback = it }
                TransactionField("operation", tx.operation, context) { feedback = it }
                qr?.let { matrix ->
                    Canvas(Modifier.align(Alignment.CenterHorizontally).size(220.dp).background(Color.White).semantics {
                        contentDescription = "QR code of the unsigned Safe transaction JSON"
                    }) {
                        val cell = size.minDimension / matrix.width
                        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
                            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
                        }
                    }
                }
                Text("QR contains: $json", style = MaterialTheme.typography.bodySmall)
            }
            state.treasury?.safeAddress?.takeIf { Regex("0x[0-9a-fA-F]{40}").matches(it) }?.let { safe ->
                Button(onClick = {
                    val url = "https://app.safe.global/home?safe=basesep:$safe"
                    if (!context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) feedback = "No browser available to open Safe{Wallet}"
                }, modifier = Modifier.fillMaxWidth()) { Text("Open in Safe{Wallet}") }
            }
            TextButton(onClick = onSwap) { Text("Swap tokens (owner-signed)") }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerSwapSheet(safeAddress: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var feedback by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Swap · owner path", style = MaterialTheme.typography.headlineSmall)
            Text("Swaps are owner-signed in Safe{Wallet}. The AI never swaps.")
            Text("Swaps are not available on the Base Sepolia test network.")
            safeAddress?.takeIf { Regex("0x[0-9a-fA-F]{40}").matches(it) }?.let { safe ->
                Button(onClick = {
                    val url = "https://app.safe.global/home?safe=basesep:$safe"
                    if (!context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) feedback = "No browser available to open Safe{Wallet}"
                }, modifier = Modifier.fillMaxWidth()) { Text("Open in Safe{Wallet}") }
            }
            feedback?.let { Text(it) }
        }
    }
}

@Composable
fun WhoCanDoWhatCard(state: WalletState) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().background(Color(0xFF111418)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Who can do what", style = MaterialTheme.typography.titleMedium)
        Text("Owner (2-of-2): Send · Swap · policy · resume.", style = MaterialTheme.typography.bodyMedium)
        Text("Guardian: pause · queued cancellation unavailable in this build.", style = MaterialTheme.typography.bodyMedium)
        Text("AI agent: Deposit · Withdraw · Rebalance · Pay verified bills · Hold — within on-chain limits.", style = MaterialTheme.typography.bodyMedium)
        state.module?.let { module -> allowanceLabels(module, state.blockTime).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB0B7C3))
        } } ?: Text("AI limits unavailable until policy loads.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TransactionField(label: String, value: String, context: Context, onCopied: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(label, value))
            onCopied("$label copied")
        }) { Text("Copy $label") }
    }
}

private fun formatRawUsdc(raw: BigInteger): String = raw.divide(BigInteger.TEN.pow(6)).toString() + "." +
    raw.mod(BigInteger.TEN.pow(6)).toString().padStart(6, '0')
