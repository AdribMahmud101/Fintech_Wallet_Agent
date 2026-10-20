package app.fusion.wallet.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.agent.AgentRuntime
import app.fusion.wallet.data.Deployment
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val safety by vm.safety.collectAsStateWithLifecycle()
    val agent by AgentRuntime.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var frozen by remember { mutableStateOf(emptyList<ActivityItem>()) }
    var evidenceError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            frozen = withContext(Dispatchers.IO) {
                ActivityTimelineBuilder.parseFrozen(context.assets.open("evidence/base-sepolia.json").bufferedReader().use { it.readText() })
            }
        } catch (_: Exception) { evidenceError = true }
    }
    var filterName by rememberSaveable { mutableStateOf(ActivityFilter.All.name) }
    val filter = ActivityFilter.valueOf(filterName)
    var selected by remember { mutableStateOf<ActivityItem?>(null) }
    val rows = remember(state.evidence, state.recentActions, state.payees, safety, frozen, agent.result?.txHash) {
        ActivityTimelineBuilder.build(state, safety, frozen).map { row ->
            if (row.txHash.equals(agent.result?.txHash, true)) row.copy(provenance = Provenance.App) else row
        }
    }
    val visible = rows.filter { it.matches(filter) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(R.string.act_title), style = FusionType.HeadingMedium)
            Text(stringResource(R.string.act_subtitle), style = FusionType.BodySmall)
            Text("Module history and bundled evidence · provenance follows recorded source", style = FusionType.BodySmall)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ActivityFilter.entries) { f ->
                    FilterChip(selected = filter == f, onClick = { filterName = f.name },
                        label = { Text(stringResource(when (f) {
                            ActivityFilter.All -> R.string.filter_all
                            ActivityFilter.Agent -> R.string.filter_agent
                            ActivityFilter.Owner -> R.string.filter_owner
                            ActivityFilter.App -> R.string.filter_app
                            ActivityFilter.Blocked -> R.string.filter_blocked
                        })) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        if (state.error != null || evidenceError) item {
            BentoTile(Modifier.fillMaxWidth()) {
                Text(state.error ?: "Bundled transaction evidence unavailable", style = FusionType.BodySmall)
                TextButton(onClick = vm::refresh) { Text("Refresh chain history") }
            }
        }
        if (visible.isEmpty()) item {
            BentoTile(Modifier.fillMaxWidth()) {
                Text(if (state.refreshing) "Loading on-chain history…" else stringResource(R.string.act_empty_title), style = FusionType.HeadingSmall)
                Text(stringResource(R.string.act_empty_desc), style = FusionType.BodySmall)
            }
        }
        items(visible, key = { it.id }) { row ->
            BentoTile(Modifier.fillMaxWidth(), onClick = { selected = row }) {
                Text(row.title, style = FusionType.HeadingSmall)
                row.amountRaw?.let { AmountText(it, style = FusionType.NumberSmall) }
                    ?: Text("Amount not recorded", style = FusionType.BodySmall)
                Text(row.counterparty ?: "Counterparty not recorded", style = FusionType.BodySmall)
                Text(row.timestampSeconds?.let { app.fusion.wallet.ui.common.formatEpochSeconds(it) } ?: "Time unavailable · Block ${row.blockNumber}", style = FusionType.BodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProvenanceChip(row.provenance)
                    if (row.isBlocked) StatusChip("Blocked · reverted", FusionColors.Red)
                }
                TxLink(row.txHash, explorerUrl = row.explorerUrl)
            }
        }
    }
    selected?.let { row ->
        var receipt by remember(row.txHash) { mutableStateOf<ActivityReceipt?>(null) }
        var receiptLoading by remember(row.txHash) { mutableStateOf(true) }
        var attempt by remember(row.txHash) { mutableStateOf(0) }
        LaunchedEffect(row.txHash, attempt) {
            receiptLoading = true
            try {
                receipt = withContext(Dispatchers.IO) {
                    val deployment = Deployment.parse(context.assets.open("deployments/84532.json").bufferedReader().use { it.readText() })
                    ActivityReceiptReader.read(deployment, row)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { receipt = null
            } finally { receiptLoading = false }
        }
        val clipboard = LocalClipboardManager.current
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = FusionColors.SurfaceRaised) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.title, style = FusionType.HeadingMedium)
                ProvenanceChip(row.provenance)
                row.amountRaw?.let { AmountText(it, exact = true, style = FusionType.NumberSmall) }
                Text("Block ${row.blockNumber}", style = FusionType.NumberBody)
                Text("Gas: ${receipt?.gasUsed?.let { "$it units" } ?: row.gasUsed ?: if (receiptLoading) "reading receipt…" else "receipt unavailable"}", style = FusionType.BodyMedium)
                receipt?.let { r ->
                    Text(app.fusion.wallet.ui.common.formatEpochSeconds(r.timestampSeconds), style = FusionType.BodySmall)
                    StatusChip(if (r.reverted) "Reverted" else "Confirmed", if (r.reverted) FusionColors.Red else FusionColors.Green)
                }
                if (!receiptLoading && receipt == null) TextButton(onClick = { attempt++ }) { Text("Retry receipt") }
                Text("Counterparty: ${row.counterparty ?: "not recorded"}", style = FusionType.BodySmall)
                Text("Passport hash", style = FusionType.BodySmall)
                SelectionContainer { Text(row.passportHash ?: "Not recorded for this action", style = FusionType.NumberCaption) }
                row.passportHash?.let { hash -> TextButton(onClick = { clipboard.setText(AnnotatedString(hash)) }) { Text("Copy passport hash") } }
                row.details?.let { Text(it, style = FusionType.BodySmall) }
                TxLink(row.txHash, explorerUrl = row.explorerUrl)
                TextButton(onClick = { selected = null }) { Text("Close") }
            }
        }
    }
}
