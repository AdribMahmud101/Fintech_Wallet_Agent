package app.fusion.wallet.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.ui.common.formatEpochSeconds
import app.fusion.wallet.ui.common.readableCode
import app.fusion.wallet.R
import app.fusion.wallet.agent.autopilot.AutopilotCard
import app.fusion.wallet.agent.autopilot.AutopilotTimelineTick
import app.fusion.wallet.agent.AgentRuntime
import app.fusion.wallet.data.*
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.activity.activityProvenance
import app.fusion.wallet.ui.activity.RecordedProvenanceChip
import app.fusion.wallet.ui.activity.rememberTxIndex
import app.fusion.wallet.ui.common.startActivitySafe
import app.fusion.wallet.ui.common.vendorName
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.theme.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AgentScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val run by AgentRuntime.state.collectAsStateWithLifecycle()
    val autopilot by vm.autopilot.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val txIndex = rememberTxIndex()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now = screenNow()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dryRun by rememberSaveable { mutableStateOf(true) }
    var confirmLive by remember { mutableStateOf(false) }
    var decisionError by remember { mutableStateOf<String?>(null) }
    val decision by produceState<DecisionDetails?>(null, run.result) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                run.result?.let { DecisionDetails.parse(it.reportJson, it.passportJson) } ?: run {
                    val file = File(context.filesDir, "agent/last_tick.json")
                    if (file.isFile) DecisionDetails.parse(file.readText()) else null
                }
            }.onFailure { decisionError = "Decision report unavailable: ${it.message}" }.getOrNull()
        }
    }
    val viewTx = stringResource(R.string.k24_view_tx)
    val linkError = stringResource(R.string.k24_link_unavailable)
    val feedback: (String, String?) -> Unit = { message, hash ->
        scope.launch {
            val result = snackbar.showSnackbar(message, actionLabel = hash?.let { viewTx }, withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed && hash != null) {
                if (!context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://sepolia.basescan.org/tx/$hash")))) {
                    snackbar.showSnackbar(linkError)
                }
            }
        }
    }
    val runAgent: (Boolean) -> Unit = { previewOnly ->
        scope.launch {
            vm.runAgentTick(previewOnly).fold(onSuccess = { result ->
                val message = when {
                    result.choiceLabel == "H" -> "Agent chose Hold · no transaction"
                    !result.previewPassed -> "Policy preview blocked execution"
                    result.dryRun -> "Dry run complete · no transaction sent"
                    else -> "Agent action confirmed"
                }
                feedback(message, result.txHash)
            }, onFailure = { feedback(readableAgentError(it.message), null) })
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = FusionColors.Bg) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding).background(FusionColors.Bg),
            contentPadding = PaddingValues(FusionSpacing.gutter), verticalArrangement = Arrangement.spacedBy(FusionSpacing.gutter)) {
            item { Text(stringResource(R.string.ag_title), style = FusionType.HeadingLarge) }
            item { AutopilotCard(autopilot, vm.autopilot, now * 1000, run.running) }
            item { Text("Autopilot timeline · last 50 ticks", style = FusionType.HeadingSmall) }
            if (autopilot.timeline.isEmpty()) item { Text("Turn on Autopilot or tap Run now to record a tick.", style = FusionType.BodySmall) }
            items(autopilot.timeline, key = { "autopilot:${it.id}" }) { tick -> AutopilotTimelineTick(tick) }
            item { BentoTile(Modifier.fillMaxWidth()) { SnapshotStatus(state, vm::refresh) } }
            item {
                BentoTile(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (expanded) R.string.k24_hide_flow else R.string.k24_show_flow))
                    }
                    if (expanded) ConnectedSteps(listOf(
                        "Facts" to "Fresh chain snapshot, bills, payees and available rates",
                        "Candidates A–G + H (Hold)" to "Only eligible candidates are generated; unavailable letters are omitted",
                        "On-device model · AHEM-G" to "Picks a letter from that candidate set",
                        "Policy preview" to "Checks lifecycle, queue, metadata, limits and action rules",
                        "Signer" to "Session authority and locked execution",
                        "Contract" to "Enforces treasury policy on-chain",
                    ))
                }
            }
            item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.k24_agent_controls), style = FusionType.HeadingSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = dryRun, onCheckedChange = { dryRun = it }, enabled = !run.running && !autopilot.running, modifier = Modifier.size(48.dp))
                        Text(stringResource(R.string.k24_dry_run), modifier = Modifier.padding(top = 12.dp), style = FusionType.BodyMedium)
                    }
                    Button(onClick = { if (dryRun) runAgent(true) else confirmLive = true }, enabled = !run.running && !autopilot.running,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(if (run.running) R.string.k24_deciding else R.string.k24_run_agent))
                    }
                    GuardianStopControl(state.module?.paused, vm::requestGuardianPause, feedback, onStop = { vm.autopilot.setEnabled(false) })
                    Text(stringResource(R.string.k24_dry_explanation), style = FusionType.BodySmall)
                    run.error?.let { Text(readableAgentError(it), style = FusionType.BodyMedium, color = FusionColors.Red) }
                }
            }
            item {
                val latest = decision
                when {
                    latest != null -> LatestDecision(latest, txIndex, onCopy = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Passport hash", latest.hash))
                        feedback("Passport hash copied", null)
                    })
                    run.running -> SkeletonTile(height = 240.dp)
                    else -> BentoTile(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.k24_latest_decision), style = FusionType.HeadingSmall)
                        Text(decisionError ?: stringResource(R.string.k24_no_decision), style = FusionType.BodyMedium)
                    }
                }
            }
            item {
                val module = state.module
                if (module == null && state.status == LoadStatus.LOADING) SkeletonTile(height = 180.dp)
                else BentoTile(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.k24_daily_limit), style = FusionType.HeadingSmall)
                    if (module == null) Text(stringResource(R.string.k24_policy_unavailable), style = FusionType.BodyMedium)
                    else {
                        val bps = gaugeBasisPoints(module.dailyUsed.raw, module.policy.dailyLimit.raw)
                        DailyLimitRing(module)
                        AmountText(module.dailyUsed.raw)
                        Text("of daily limit", style = FusionType.BodySmall)
                        AmountText(module.policy.dailyLimit.raw, style = FusionType.NumberSmall)
                        Text(stringResource(R.string.k24_remaining), style = FusionType.BodySmall)
                        AmountText(module.dailyRemaining.raw, style = FusionType.NumberSmall)
                        if (bps == null) Text("Daily limit is zero; gauge unavailable", style = FusionType.BodySmall)
                    }
                }
            }
            item { DecisionMixTile(state, txIndex, run.result?.reportJson) }
            item { Text(stringResource(R.string.ag_queue_title), style = FusionType.HeadingMedium) }
            if (state.queue.isEmpty()) item {
                if (state.status == LoadStatus.LOADING) SkeletonTile() else BentoTile(Modifier.fillMaxWidth()) {
                    Text(if (state.module == null) "Queue unavailable until a chain snapshot loads" else stringResource(R.string.ag_queue_empty), style = FusionType.BodyMedium)
                }
            }
            items(state.queue, key = { it.id }) { queued ->
                BentoTile(Modifier.fillMaxWidth()) {
                    Text(decisionTitle(queued.kind.name), style = FusionType.HeadingSmall)
                    if (queued.kind == ActionKind.PAY_OBLIGATION) {
                        val payee = state.payees.firstOrNull { it.address.equals(queued.payee, true) || it.id == queued.payee }
                        Text(vendorName(payee?.id, payee?.address ?: queued.payee), style = FusionType.BodyMedium)
                    }
                    AmountText(queued.amount.raw)
                    StatusChip(queueStatusText(queued, now), if (queued.status == QueueStatus.EXPIRED || queued.expiresAt in 1..now) FusionColors.Red else FusionColors.Amber)
                    Text("Expiry: ${if (queued.expiresAt > 0) countdownText(queued.expiresAt, now) else "not supplied"}", style = FusionType.BodySmall)
                    Text("Passport ${queued.decisionHash}", style = FusionType.NumberCaption)
                }
            }
            item { Text(stringResource(R.string.k24_decision_history), style = FusionType.HeadingMedium) }
            if (state.recentActions.isEmpty()) item {
                if (state.status == LoadStatus.LOADING) SkeletonTile() else BentoTile(Modifier.fillMaxWidth()) {
                    Text(if (state.status == LoadStatus.ERROR) "History unavailable: refresh the chain snapshot" else stringResource(R.string.ag_recent_empty), style = FusionType.BodyMedium)
                }
            }
            items(state.recentActions, key = { it.id }) { action ->
                BentoTile(Modifier.fillMaxWidth()) {
                    Text(decisionTitle(action.kind.name), style = FusionType.HeadingSmall)
                    action.payee?.let { address -> Text(vendorName(state.payees.firstOrNull { it.address.equals(address, true) }?.id, address), style = FusionType.BodySmall) }
                    AmountText(action.amount.raw)
                    RecordedProvenanceChip(activityProvenance(txIndex.find(action.txHash)?.source))
                    txIndex.find(action.txHash)?.planner?.let { StatusChip(it, FusionColors.Indigo) }
                    Text("Block ${action.blockNumber} · ${formatEpochSeconds(action.blockTime)}", style = FusionType.BodySmall)
                    TxLink(action.txHash)
                }
            }
        }
    }
    if (confirmLive) AlertDialog(onDismissRequest = { confirmLive = false },
        title = { Text(stringResource(R.string.k24_live_title)) }, text = { Text(stringResource(R.string.k24_live_explanation)) },
        confirmButton = { TextButton(onClick = { confirmLive = false; runAgent(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.k24_live_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmLive = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.k24_cancel)) } })
}

@Composable
private fun LatestDecision(decision: DecisionDetails, txIndex: TxIndex, onCopy: () -> Unit) {
    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.k24_latest_decision), style = FusionType.HeadingMedium)
        StatusChip(when (decision.dryRun) { true -> "Dry run"; false -> "Live run"; null -> "Run mode unknown" }, FusionColors.Indigo)
        Text("${decision.choice} · ${readableCode(decision.reason)}", style = FusionType.HeadingSmall)
        decision.txHash?.let { RecordedProvenanceChip(activityProvenance(txIndex.find(it)?.source)) }
        Text("Planner: ${readableCode(txIndex.find(decision.txHash)?.planner ?: decision.planner)}", style = FusionType.NumberCaption)
        Text("Load: ${decision.loadMs?.toPlainString() ?: "not recorded"} ms", style = FusionType.BodySmall)
        Text("Decode: ${decision.decodeMs?.toPlainString() ?: "not recorded"} ms", style = FusionType.BodySmall)
        Text("Inference total: ${decision.inferenceMs?.toPlainString() ?: "not recorded"} ms", style = FusionType.BodySmall)
        decision.block?.let { Text("Snapshot block $it", style = FusionType.BodySmall) }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.k24_candidates), style = FusionType.HeadingSmall)
        decision.candidates.forEach { candidate ->
            Column(Modifier.fillMaxWidth().background(if (candidate.label == decision.choice) FusionColors.SurfaceRaised else FusionColors.Surface).padding(8.dp)) {
                if (candidate.label == decision.choice) StatusChip("Chosen · ${candidate.label}", FusionColors.Green)
                Text("${candidate.label} · ${candidate.title}", style = FusionType.BodyMedium)
                candidate.amount?.let { AmountText(it, style = FusionType.NumberBody) }
                Text(readableCode(candidate.reason), style = FusionType.BodySmall)
            }
        }
        if (decision.candidates.isEmpty()) Text("Candidate list not recorded", style = FusionType.BodySmall)
        Text(stringResource(R.string.k24_policy_checks), style = FusionType.HeadingSmall)
        decision.checks.forEach { check ->
            Column(Modifier.padding(vertical = 6.dp)) {
                StatusChip(readableCode(check.status), when (check.status) { "PASS" -> FusionColors.Green; "FAIL" -> FusionColors.Red; else -> FusionColors.Amber })
                Text(readableCode(check.name), style = FusionType.BodyMedium)
                Text(check.message, style = FusionType.BodySmall)
            }
        }
        if (decision.checks.isEmpty()) Text("Policy checks not recorded", style = FusionType.BodySmall)
        Text("Passport ${decision.hash}", style = FusionType.NumberCaption)
        TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.k24_copy_hash)) }
        decision.txHash?.let { TxLink(it) } ?: Text(stringResource(R.string.k24_no_transaction), style = FusionType.BodySmall)
    }
}

internal fun queueStatusText(action: QueuedAction, now: Long): String = when {
    action.status == QueueStatus.EXPIRED || action.expiresAt in 1 until now -> "Expired"
    action.readyAt <= now -> "Ready · awaiting execution"
    else -> "Queued · ${countdownText(action.readyAt, now)}"
}
