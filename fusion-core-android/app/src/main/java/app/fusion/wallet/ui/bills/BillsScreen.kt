package app.fusion.wallet.ui.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.data.*
import app.fusion.wallet.ui.charts.*
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.agent.*
import app.fusion.wallet.ui.common.vendorName
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.shorten
import app.fusion.wallet.ui.theme.*
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showVendors by rememberSaveable { mutableStateOf(false) }
    val now = screenNow()
    val context = LocalContext.current
    val reader = remember { BillEvidenceReader() }
    val links by produceState(BillEvidence(), state.blockHash, state.evidence, state.queue) {
        if (state.blockNumber != null && state.source == DataSource.LIVE) {
            value = withContext(Dispatchers.IO) {
                runCatching { reader.load(context, state) }.getOrElse { BillEvidence(error = it.message ?: "Receipt identifiers unavailable") }
            }
        }
    }
    val currentLinks = links.takeIf { it.blockHash == state.blockHash } ?: BillEvidence(error = links.error)
    val safeUrl = rememberSafeUrl(state.treasury?.safeAddress)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val feedback: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = FusionColors.Bg) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(FusionSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(FusionSpacing.gutter)) {
            item { Text(stringResource(R.string.bv_tab_bills), style = FusionType.HeadingLarge) }
            item { BentoTile(Modifier.fillMaxWidth()) { SnapshotStatus(state, vm::refresh) } }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !showVendors, onClick = { showVendors = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.bv_tab_bills))
                    }
                    SegmentedButton(selected = showVendors, onClick = { showVendors = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.bv_tab_payees))
                    }
                }
            }
            item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text("Paid per vendor",style=FusionType.HeadingSmall)
                    if (state.accountingMatches!=true) Text("Paid bills unavailable until metadata matches the chain commitment",style=FusionType.BodySmall)
                    else {
                        val paid=state.obligations.filter { it.paid }.groupBy { bill ->
                            state.payees.firstOrNull { it.id.equals(bill.payeeId,true) || it.address.equals(bill.payeeId,true) }?.label
                                ?: vendorName(bill.payeeId,null)
                        }.mapValues { (_,bills)->bills.fold(BigInteger.ZERO) { sum,bill->sum+bill.amount.raw } }
                        val max=paid.values.maxOrNull()?:BigInteger.ZERO
                        if (paid.isEmpty()) Text("No paid bills in the verified commitment",style=FusionType.BodySmall)
                        else ProgressBars(paid.entries.sortedByDescending { it.value }.map { row ->
                            ProgressRow(row.key,"${formatBigIntegerAmount(row.value)} USDC",gaugeFraction(row.value.toBigDecimal(),max.toBigDecimal()),FusionColors.Green)
                        },Modifier.padding(top=12.dp))
                        Text("Verified current bill commitment · bars relative to largest vendor total",style=FusionType.BodySmall)
                    }
                }
            }
            if (state.accountingError != null && !showVendors) item {
                BentoTile(Modifier.fillMaxWidth()) { Text(app.fusion.wallet.ui.common.accountingMessageForPeople(state.accountingError).orEmpty(), style = FusionType.BodyMedium, color = FusionColors.Amber) }
            }
            if (links.error != null) item {
                BentoTile(Modifier.fillMaxWidth()) { Text("Receipt / queue detail unavailable: ${links.error}", style = FusionType.BodySmall, color = FusionColors.Amber) }
            }
            if (showVendors) {
                if (state.payees.isEmpty()) item {
                    if (state.status == LoadStatus.LOADING) SkeletonTile(height = 180.dp) else BentoTile(Modifier.fillMaxWidth()) {
                        Text(if (state.status == LoadStatus.ERROR) "Vendor registry unavailable; refresh to retry" else "No vendors observed in the registry", style = FusionType.BodyMedium)
                    }
                }
                items(state.payees, key = { it.id }) { payee -> val registrationTx = currentLinks.registrationTxByPayee[payee.id.lowercase()]
                    val registeredAt = state.evidence.firstOrNull { it.kind == "PayeeAdded" && it.txHash == registrationTx }?.blockTime
                    VendorCard(payee, registeredAt, now, registrationTx, feedback) }
            } else {
                if (state.obligations.isEmpty()) item {
                    if (state.status == LoadStatus.LOADING) SkeletonTile(height = 180.dp) else BentoTile(Modifier.fillMaxWidth()) {
                        Text(when {
                            state.accountingMatches != true -> "Bills unavailable until metadata matches the on-chain commitment"
                            else -> "No committed bills"
                        }, style = FusionType.BodyMedium)
                    }
                }
                items(state.obligations, key = { it.id }) { bill -> BillCard(bill, state, currentLinks, now) }
            }
            item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_brand_safe), contentDescription = "Safe", modifier = Modifier.size(32.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                    Text(stringResource(R.string.k24_manage_bills), style = FusionType.HeadingSmall)
                    Text(stringResource(R.string.k24_owner_explanation), style = FusionType.BodyMedium)
                    if (safeUrl != null) ExternalAction(stringResource(R.string.k24_open_safe), safeUrl, feedback)
                    else Text(stringResource(R.string.k24_safe_unavailable), style = FusionType.BodySmall)
                }
            }
        }
    }
}

@Composable
private fun BillCard(bill: Obligation, state: WalletState, links: BillEvidence, now: Long) {
    val payee = state.payees.firstOrNull { it.id.equals(bill.payeeId, true) || it.address.equals(bill.payeeId, true) }
    val name = vendorName(bill.payeeId, payee?.address)
    val date = remember(bill.dueAt) { SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.getDefault()).format(Date(bill.dueAt * 1_000)) }
    val queue = state.queue.singleOrNull { it.kind == ActionKind.PAY_OBLIGATION && it.expiresAt >= now && it.status != QueueStatus.EXPIRED }
        ?.takeIf { links.queuedBillId.equals(bill.id, true) }
    BentoTile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(FusionColors.SurfaceRaised), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), style = FusionType.HeadingSmall)
            }
            Text(name, style = FusionType.HeadingSmall, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_crypto_usdc), contentDescription = null, modifier = Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
            AmountText(bill.amount.raw)
        }
        Text("Due $date", style = FusionType.BodySmall)
        StatusChip(when {
            bill.paid -> "Paid"
            queue != null -> queueStatusText(queue, now)
            else -> "Due"
        }, if (bill.paid) FusionColors.Green else FusionColors.Amber)
        if (bill.paid) {
            links.paymentTxByBill[bill.id.lowercase()]?.let { TxLink(it) }
                ?: Text("Paid on-chain · receipt not in loaded history", style = FusionType.BodySmall)
        }
        if (!bill.paid && queue == null && state.queue.any { it.kind == ActionKind.PAY_OBLIGATION } && links.queuedBillId == null) {
            Text("A payment is queued; its bill identifier is unavailable", style = FusionType.BodySmall)
        }
    }
}

@Composable
private fun VendorCard(payee: Payee, registeredAt: Long?, now: Long, registrationTx: String?, onError: (String) -> Unit) {
    val name = vendorName(payee.id, payee.address)
    val cooling = payee.status != PayeeStatus.CANCELLED && payee.activeAt > now
    BentoTile(Modifier.fillMaxWidth()) {
        Text(name, style = FusionType.HeadingSmall)
        Text(shorten(payee.address), style = FusionType.NumberBody)
        StatusChip(when { payee.status == PayeeStatus.CANCELLED -> "CANCELLED"; cooling -> "COOLING · ${countdownText(payee.activeAt, now)}"; else -> "ACTIVE" },
            if (payee.status == PayeeStatus.CANCELLED) FusionColors.Red else if (cooling) FusionColors.Amber else FusionColors.Green)
        if (cooling) {
            val remaining = remainingSeconds(payee.activeAt, now)
            val duration = registeredAt?.let { remainingSeconds(payee.activeAt, it) }
            val bps = duration?.takeIf { it > 0 }?.let { duration ->
                gaugeBasisPoints(BigInteger.valueOf(remaining), BigInteger.valueOf(duration))
            }
            EvidenceRing(bps, "Vendor cooling time remaining: ${countdownText(payee.activeAt, now)}")
            Text("${countdownText(payee.activeAt, now)} remaining", style = FusionType.BodyMedium)
            Text(if (duration == null) "Cooling duration unavailable until registration receipt loads" else "Remaining fraction of the registered cooling period", style = FusionType.BodySmall)
        }
        StatusChip(if (payee.sanctioned) "Sanctions check · BLOCKED" else "Sanctions check · clear", if (payee.sanctioned) FusionColors.Red else FusionColors.Green)
        registrationTx?.let { TxLink(it) } ?: Text("Registration receipt not in loaded history", style = FusionType.BodySmall)
        ExternalAction("View vendor on BaseScan", "https://sepolia.basescan.org/address/${payee.address}", onError)
    }
}
