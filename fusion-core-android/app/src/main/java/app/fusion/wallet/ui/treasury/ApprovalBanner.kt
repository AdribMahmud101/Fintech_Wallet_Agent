package app.fusion.wallet.ui.treasury

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fusion.wallet.data.*
import app.fusion.wallet.onboarding.KeyInventory
import app.fusion.wallet.ui.common.startActivitySafe
import app.fusion.wallet.ui.components.formatBigIntegerAmount
import app.fusion.wallet.ui.charts.ChartTile
import app.fusion.wallet.ui.theme.FusionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalBanner(state: WalletState, now: Long, txIndex: TxIndex) {
    if (!approvalBannerVisible(state.queue)) return
    val context = LocalContext.current
    var safeSheet by remember { mutableStateOf(false) }
    var importSheet by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    ChartTile(Modifier.fillMaxWidth()) {
        Text("Waiting for your approval", style = FusionType.HeadingSmall)
        state.queue.forEach { action ->
            val vendor = homeVendorLabel(state, txIndex, action.payee)
            val description = when (action.kind) {
                ActionKind.PAY_OBLIGATION -> "Pay $vendor"
                ActionKind.DEPOSIT -> "Move spare cash into a vault"
                ActionKind.WITHDRAW -> "Return vault funds to Safe cash"
                ActionKind.REBALANCE -> "Rebalance vaults"
            }
            Text("$description ${formatBigIntegerAmount(action.amount.raw)} USDC", style = FusionType.BodyMedium)
            val aboveLimit = state.module?.policy?.microMax?.raw?.let { action.amount.raw > it } == true
            val covered = billsStayCovered(state.buckets) && when (action.kind) {
                ActionKind.DEPOSIT -> state.buckets!!.surplus.raw >= action.amount.raw
                ActionKind.PAY_OBLIGATION -> action.amount.raw <= state.buckets!!.owed.raw
                else -> true
            }
            Text(listOfNotNull(if (aboveLimit) "Above the AI's auto-limit" else null,
                if (covered) "Your bills stay covered" else null).joinToString(" · "), style = FusionType.BodySmall)
            val remaining = (action.readyAt - now).coerceAtLeast(0)
            Text(when {
                now > action.expiresAt -> "Queue expired · cancel before requesting a new move"
                remaining == 0L -> "Timelock ready · owner approval is separate"
                else -> "Ready in ${remaining / 3600}h ${(remaining % 3600) / 60}m ${remaining % 60}s"
            }, style = FusionType.BodySmall)
        }
        // The currently published guardian API exposes pause only. Never pretend pause cancels a queue.
        Text("Queue cancellation requires the guardian cancellation API, which is not available in this build.", style = FusionType.BodySmall)
        if (!KeyInventory.guardianImported(context)) {
            TextButton(onClick = { importSheet = true }) { Text("Set up guardian to cancel") }
        }
        OutlinedButton(onClick = { safeSheet = true }, enabled = state.treasury != null) { Text("Approve in Safe (2 of 2)") }
    }
    if (importSheet) AlertDialog(onDismissRequest = { importSheet = false },
        title = { Text("Import the guardian key") },
        text = { Text("Open Settings → Keys → Guardian → Import. The guardian key is stored in Android Keystore and can stop the agent. Queue cancellation also requires the signer cancellation API. Safe owner keys stay outside this app.") },
        confirmButton = { TextButton(onClick = { importSheet = false }) { Text("Got it") } })
    if (safeSheet) ModalBottomSheet(onDismissRequest = { safeSheet = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Approve in Safe (2 of 2)", style = FusionType.HeadingSmall)
            Text("Both Safe owners must review and co-sign the required module approval in the Safe. This app does not hold owner keys. Opening the Safe does not submit an approval; the queue timelock and policy checks still apply.")
            state.queue.forEach { Text("Queued action digest: ${it.id}", style = FusionType.BodySmall) }
            Button(onClick = {
                val safe = state.treasury?.safeAddress ?: return@Button
                if (!context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://app.safe.global/home?safe=basesep:$safe")))) linkError = "No browser available to open Safe"
            }) { Text("Open Safe") }
            linkError?.let { Text(it) }
        }
    }
}
