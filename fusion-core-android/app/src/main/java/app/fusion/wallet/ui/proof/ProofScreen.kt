package app.fusion.wallet.ui.proof

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import app.fusion.wallet.ui.components.ExecutionSuccess
import app.fusion.wallet.ui.components.ReceiptVerificationMark
import app.fusion.wallet.ui.motion.rememberMotionEnabled
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.data.Deployment
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.activity.ActivityItem
import app.fusion.wallet.ui.activity.ActivityReceipt
import app.fusion.wallet.ui.activity.ActivityReceiptReader
import app.fusion.wallet.ui.activity.ActivityTimelineBuilder
import app.fusion.wallet.ui.activity.RecordedProvenanceChip
import app.fusion.wallet.ui.activity.rememberTxIndex
import app.fusion.wallet.ui.components.AmountText
import app.fusion.wallet.ui.components.BentoTile
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.components.TxLink
import app.fusion.wallet.ui.common.formatEpochSeconds
import app.fusion.wallet.ui.common.startActivitySafe
import app.fusion.wallet.ui.safety.SafetyScreenModel
import app.fusion.wallet.ui.safety.SafetyScreenState
import app.fusion.wallet.ui.settings.SettingsViewModel
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ARENA_URL =
    "https://github.com/AdribMahmud101/fusion_wallet/blob/main/evidence/results/ARENA.md"

/**
 * Proof screen (win plan wow factor 9): every indexed on-chain transaction grouped by Topic F
 * operation — asset management, transactions, risk control, owner/setup — each row with its
 * recorded source badge, time, amount, BaseScan link, and a live receipt check (status and block,
 * fetched read-only from the pinned Base Sepolia RPC) on tap. Header counts read the recorded
 * index only ("N AI-executed txs"); the attacks-stopped count links to ARENA.md on GitHub.
 * Every number is real recorded data; failures are shown plainly, never papered over.
 */
@Composable
fun ProofScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val safety by vm.safety.collectAsStateWithLifecycle()
    val arenaVm: SafetyScreenModel = viewModel()
    val arena by arenaVm.state.collectAsStateWithLifecycle()
    val settings: SettingsViewModel = viewModel()
    val txIndex = rememberTxIndex()
    val context = LocalContext.current
    var frozen by remember { mutableStateOf(emptyList<ActivityItem>()) }
    LaunchedEffect(Unit) {
        frozen = try {
            withContext(Dispatchers.IO) {
                ActivityTimelineBuilder.parseFrozen(
                    context.assets.open("evidence/base-sepolia.json").bufferedReader().use { it.readText() })
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { emptyList() }
    }
    val sections = remember(state.evidence, state.recentActions, state.payees, safety, frozen, txIndex) {
        ProofBuilder.build(state, safety, frozen, txIndex)
    }
    var verifiedHashes by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember { mutableStateOf<ActivityItem?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column {
                Text(stringResource(R.string.prf_title), style = FusionType.HeadingMedium)
                Text(stringResource(R.string.prf_subtitle), style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
        }
        item(key = "counts") { ProofCountsTile(sections, arena, arenaVm::refresh, arena.refreshing) }
        if (sections.indexedCount == 0 && !state.refreshing) {
            item(key = "index-missing") { IndexUnavailableTile(vm::refresh) }
        }
        state.error?.let { error ->
            item(key = "state-error") {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text(error, style = FusionType.BodySmall, color = FusionColors.Amber)
                    TextButton(onClick = vm::refresh) { Text(stringResource(R.string.refresh_button)) }
                }
            }
        }
        sections.sections.forEach { section ->
            item(key = "section:${section.operation.name}") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(proofOperationLabel(section.operation), style = FusionType.HeadingSmall)
                    Text(section.rows.size.toString(), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                }
            }
            items(section.rows, key = { "${section.operation.name}:${it.id}" }) { row ->
                ProofTransactionCard(row, row.txHash in verifiedHashes, onClick = { selected = row })
            }
        }
        item(key = "contracts-header") {
            Column {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.prf_contracts), style = FusionType.HeadingSmall)
                Text(stringResource(R.string.prf_contracts_hint), style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
        }
        items(settings.contracts, key = { "contract:${it.address}" }) { contract ->
            BentoTile(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(contract.label, style = FusionType.BodyMedium)
                        Text(contract.address, style = FusionType.NumberCaption, color = FusionColors.TextMuted, maxLines = 1)
                    }
                    ExplorerLink(contract.explorerUrl)
                }
            }
        }
        item(key = "receipt-note") {
            Text(stringResource(R.string.prf_receipt_note), style = FusionType.BodySmall, color = FusionColors.TextMuted)
        }
    }

    selected?.let { row -> ProofReceiptSheet(row, onVerified = { verifiedHashes = verifiedHashes + row.txHash }, onDismiss = { selected = null }) }
}

@Composable
private fun ProofCountsTile(
    sections: ProofSections,
    arena: SafetyScreenState,
    onRefreshArena: () -> Unit,
    arenaRefreshing: Boolean,
) {
    val context = LocalContext.current
    BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Green) {
        Text(
            text = stringResource(R.string.prf_ai_count, sections.aiExecutedCount.toString()),
            style = FusionType.HeadingMedium,
        )
        Text(
            text = stringResource(R.string.prf_index_count, sections.indexedCount.toString()),
            style = FusionType.BodySmall, color = FusionColors.TextMuted,
        )
        Spacer(Modifier.height(10.dp))
        val evidence = arena.arena
        if (evidence != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        runCatching { context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(ARENA_URL))) }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Verified, contentDescription = null, tint = FusionColors.Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.prf_attacks_stopped, evidence.stopped.toString(), evidence.scenarios.size.toString()),
                    style = FusionType.BodyMedium, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.prf_arena_link), style = FusionType.BodySmall, color = FusionColors.Indigo)
                Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = FusionColors.Indigo, modifier = Modifier.size(12.dp))
            }
        } else {
            Column {
                Text(stringResource(R.string.prf_attacks_unavailable), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                TextButton(onClick = onRefreshArena, enabled = !arenaRefreshing) {
                    Text(if (arenaRefreshing) stringResource(R.string.prf_arena_refreshing) else stringResource(R.string.refresh_button))
                }
            }
        }
    }
}

@Composable
private fun IndexUnavailableTile(onRefresh: () -> Unit) {
    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.prf_index_unavailable), style = FusionType.BodyMedium, color = FusionColors.Amber)
        Text(stringResource(R.string.prf_index_hint), style = FusionType.BodySmall, color = FusionColors.TextMuted)
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh_button)) }
    }
}

@Composable
private fun proofOperationLabel(operation: ProofOperation): String = stringResource(when (operation) {
    ProofOperation.AssetManagement -> R.string.prf_op_asset
    ProofOperation.Transactions -> R.string.prf_op_transactions
    ProofOperation.RiskControl -> R.string.prf_op_risk
    ProofOperation.OwnerSetup -> R.string.prf_op_owner
})

@Composable
private fun ProofTransactionCard(row: ActivityItem, verified: Boolean, onClick: () -> Unit) {
    val motion = rememberMotionEnabled()
    var expanded by remember(row.txHash) { mutableStateOf(false) }
    BentoTile(Modifier.fillMaxWidth().animateContentSize(tween(if (motion) 220 else 0)), onClick = { expanded = true; onClick() }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, style = FusionType.HeadingSmall, modifier = Modifier.weight(1f), maxLines = 2)
            Spacer(Modifier.width(8.dp))
            RecordedProvenanceChip(row.provenance)
        }
        row.amountRaw?.let { AmountText(it, style = FusionType.NumberSmall) }
        row.counterparty?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.TextMuted, maxLines = 1) }
        row.note?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Amber) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.timestampSeconds?.let { formatEpochSeconds(it) }
                    ?: (if (row.blockNumber > 0) "Block ${row.blockNumber}" else stringResource(R.string.prf_time_unavailable)),
                style = FusionType.BodySmall, color = FusionColors.TextMuted,
            )
            row.planner?.let { StatusChip(it, FusionColors.Indigo) }
            row.note?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Amber) }
        }
        if (expanded && verified && row.provenance == app.fusion.wallet.ui.components.Provenance.Agent) ExecutionSuccess(row.txHash, row.explorerUrl ?: "https://sepolia.basescan.org/tx/${row.txHash}")
        else TxLink(row.txHash, explorerUrl = row.explorerUrl)
    }
}

@Composable
private fun ExplorerLink(url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .clickable(role = Role.Button) { runCatching { context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.view_on_basescan), style = FusionType.BodySmall.copy(fontWeight = FontWeight.Medium), color = FusionColors.Indigo)
        Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = FusionColors.Indigo, modifier = Modifier.size(12.dp))
    }
}

/**
 * Live receipt check on tap: the pinned Base Sepolia RPC (read-only) fetches the actual receipt,
 * verifies it against the recorded block and its canonical header hash, and shows status, gas and
 * timestamp. Nothing is signed or broadcast here; failures say so and offer a retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProofReceiptSheet(row: ActivityItem, onVerified: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var receipt by remember(row.txHash) { mutableStateOf<ActivityReceipt?>(null) }
    var loading by remember(row.txHash) { mutableStateOf(true) }
    var attempt by remember(row.txHash) { mutableIntStateOf(0) }
    LaunchedEffect(row.txHash, attempt) {
        loading = true
        try {
            receipt = withContext(Dispatchers.IO) {
                val deployment = Deployment.parse(context.assets.open("deployments/84532.json").bufferedReader().use { it.readText() })
                ActivityReceiptReader.read(deployment, row)
            }
            if (receipt?.reverted == false) onVerified()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (_: Exception) { receipt = null
        } finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = FusionColors.SurfaceRaised) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(row.title, style = FusionType.HeadingMedium)
            RecordedProvenanceChip(row.provenance)
            row.planner?.let { StatusChip(it, FusionColors.Indigo) }
            row.amountRaw?.let { AmountText(it, exact = true, style = FusionType.NumberSmall) }
            Text(
                text = row.timestampSeconds?.let { formatEpochSeconds(it) }
                    ?: (if (row.blockNumber > 0) "Block ${row.blockNumber}" else stringResource(R.string.prf_time_unavailable)),
                style = FusionType.BodySmall, color = FusionColors.TextMuted,
            )
            ReceiptVerificationMark(loading, receipt?.reverted == false)
            receipt?.takeUnless { loading }?.let { r ->
                Text("Receipt block ${r.blockNumber}", style = FusionType.NumberBody)
                StatusChip(
                    text = if (r.reverted) stringResource(R.string.prf_receipt_reverted) else stringResource(R.string.prf_receipt_confirmed),
                    tone = if (r.reverted) FusionColors.Red else FusionColors.Green,
                )
                Text(stringResource(R.string.prf_receipt_block, r.gasUsed.toString()), style = FusionType.BodySmall)
                Text(formatEpochSeconds(r.timestampSeconds), style = FusionType.BodySmall)
            }
            if (loading) Text(stringResource(R.string.prf_receipt_loading), style = FusionType.BodySmall, color = FusionColors.TextMuted)
            if (!loading && receipt == null) {
                Text(stringResource(R.string.prf_receipt_unavailable), style = FusionType.BodySmall, color = FusionColors.Amber)
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.prf_receipt_retry)) }
            }
            TxLink(row.txHash, explorerUrl = row.explorerUrl)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.prf_sheet_close)) }
        }
    }
}
