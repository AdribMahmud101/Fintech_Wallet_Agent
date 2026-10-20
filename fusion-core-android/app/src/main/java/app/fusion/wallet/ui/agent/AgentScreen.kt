package app.fusion.wallet.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import app.fusion.wallet.agent.autopilot.AutopilotTick
import app.fusion.wallet.ui.common.usdc
import app.fusion.wallet.ui.common.allowanceLabels
import app.fusion.wallet.ui.common.historicalCheckMessage
import app.fusion.wallet.ui.common.decisionTimings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.fusion.wallet.ui.advisor.WhyButton
import app.fusion.wallet.ui.advisor.toFacts
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val run by AgentRuntime.state.collectAsStateWithLifecycle()
    val autopilot by vm.autopilot.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val txIndex = rememberTxIndex()
    val now = screenNow()
    LaunchedEffect(Unit) { vm.autopilot.refreshExecutionMode() }
    var selected by remember { mutableStateOf<AutopilotTick?>(null) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize().background(FusionColors.Bg), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.ag_title), style = FusionType.HeadingMedium) }
        item { AutopilotCard(autopilot, vm.autopilot, now * 1000, run.running) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BentoTile(Modifier.weight(1f).height(148.dp), onClick = { sheet = "limits" }, padding = 12.dp) {
                    Text("Daily limit", style = FusionType.HeadingSmall)
                    state.module?.let { module ->
                        val bps = gaugeBasisPoints(module.dailyUsed.raw, module.policy.dailyLimit.raw)
                        val fraction = (bps ?: 0) / 10000f
                        if (bps != null)
                        app.fusion.wallet.ui.charts.RingGauge(fraction, FusionColors.Indigo, 64.dp, 6.dp, "Daily limit used") {
                            Text("${(fraction * 100).toInt()}%", style = FusionType.BodyMedium)
                        } else Text("No daily allowance", style = FusionType.BodySmall)
                        Text("${usdc(module.dailyRemaining.rawUnits)} USDC left", style = FusionType.BodySmall)
                    } ?: Text("Unavailable", style = FusionType.BodySmall)
                }
                BentoTile(Modifier.weight(1f).height(148.dp), padding = 12.dp) {
                    Text("Decision mix", style = FusionType.HeadingSmall)
                    val completed = autopilot.timeline.filter { it.outcome != "RUNNING" }
                    val holds = completed.count { it.outcome == "HOLD" }
                    Text("${completed.size} ticks", style = FusionType.HeadingMedium)
                    LinearProgressIndicator(progress = { if (completed.isEmpty()) 0f else holds.toFloat() / completed.size }, modifier = Modifier.fillMaxWidth())
                    Text("$holds held · ${completed.count { it.outcome == "EXECUTED" }} executed", style = FusionType.BodySmall)
                    Text("Recorded timeline", style = FusionType.BodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BentoTile(Modifier.weight(1f).height(94.dp), onClick = { sheet = "queue" }, padding = 12.dp) {
                    Text("Queued actions", style = FusionType.HeadingSmall)
                    Text(if (state.module == null) "Unavailable" else "${state.queue.size} waiting", style = FusionType.BodyMedium)
                }
                BentoTile(Modifier.weight(1f).height(94.dp), onClick = { sheet = "how" }, padding = 12.dp) {
                    Text("How it decides", style = FusionType.HeadingSmall)
                    Text("Facts → AI → rules → signer", style = FusionType.BodySmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Timeline", style = FusionType.HeadingSmall)
                TextButton(onClick = { sheet = "ticks" }) { Text("See all ticks") }
            }
        }
        if (autopilot.timeline.isEmpty()) item { Text("Turn on Autopilot or tap Run now to record a tick.", style = FusionType.BodySmall) }
        items(autopilot.timeline.take(5), key = { it.id }) { tick -> AutopilotTimelineTick(tick, onClick = { selected = tick }) }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (sheet) {
                "ticks" -> {
                    item { Text("All recorded ticks · latest 50", style = FusionType.HeadingMedium) }
                    items(autopilot.timeline, key = { it.id }) { tick -> AutopilotTimelineTick(tick, onClick = { sheet = null; selected = tick }) }
                }
                "how" -> item {
                    ConnectedSteps(listOf(
                        "Heartbeat" to "Every 1, 5 or 15 minutes in the foreground",
                        "Trigger" to "Home refresh: new bill due soon, bucket drift > 5%, or spare cash > 100 USDC with an actionable break-even",
                        "Reflex" to "Validated risk signals interrupt planning and request guardian pause when Auto-protect is on. No LLM.",
                        "Facts and candidates" to "Fresh complete snapshot → eligible candidates A–G + H (Hold)",
                        "On-device model" to "AHEM-G restricts the choice to one legal letter",
                        "Policy and gas" to "Independent checks. Base fee above 1 gwei defers non-urgent moves; bills due now continue.",
                        "Signer and contract" to "Scoped session authority and on-chain policy enforce every execution",
                    ))
                    Text("Automatic risk detection and gas-adjusted break-even data are unavailable in this build. Due-bill and bucket-drift triggers remain active; Auto-protect needs a validated risk signal.", style = FusionType.BodySmall)
                }
                "limits" -> item { BentoTile {
                    Text("AI allowance", style = FusionType.HeadingMedium)
                    SnapshotStatus(state, vm::refresh)
                    state.module?.let { module -> allowanceLabels(module, state.blockTime).forEach { Text(it, style = FusionType.BodyMedium) } }
                        ?: Text("Policy unavailable")
                } }
                "queue" -> {
                    item { Text("Queued actions", style = FusionType.HeadingMedium) }
                    if (state.queue.isEmpty()) item { Text(if (state.module == null) "Queue unavailable" else "No queued actions") }
                    items(state.queue, key = { it.id }) { queued -> BentoTile {
                        Text(decisionTitle(queued.kind.name), style = FusionType.HeadingSmall)
                        AmountText(queued.amount.raw)
                        Text(queueStatusText(queued, now))
                        Text("Passport ${queued.decisionHash}", style = FusionType.NumberCaption)
                    } }
                }
            }
        }
    }
    selected?.let { tick ->
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(tick.wakePath, style = FusionType.HeadingMedium)
                Text(tick.reason, style = FusionType.BodyMedium)
                val details = remember(tick.id) { runCatching { tick.reportJson?.let { DecisionDetails.parse(it, tick.passportJson) } }.getOrNull() }
                if (details != null) LatestDecision(details, txIndex, state.blockTime, tick.timeMillis / 1000, tick.checks.map { DecisionCheck(it.name, it.status, it.message) }, onCopy = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Passport hash", details.hash))
                }) else {
                    WhyButton("tick:${tick.id}", facts = { tick.toFacts() })
                    var checksOpen by remember { mutableStateOf(false) }
                    TextButton(onClick = { checksOpen = !checksOpen }) { Text("${tick.checks.count { it.status == "PASS" }} passed · ${tick.checks.count { it.status == "FAIL" }} failed") }
                    if (checksOpen) tick.checks.forEach { Text("${readableCode(it.name)} · ${historicalCheckMessage(it.message, tick.timeMillis / 1000, state.blockTime)}") }
                    Text("Planner details were not recorded for this tick.", style = FusionType.BodySmall)
                    tick.txHash?.let { TxLink(it) }
                }
            }
        }
    }
}

@Composable
private fun LatestDecision(decision: DecisionDetails, txIndex: TxIndex, currentBlockTime: Long?, recordedTime: Long?, checks: List<DecisionCheck>, onCopy: () -> Unit) {
    BentoTile(Modifier.fillMaxWidth()) {
        Text("Decision detail", style = FusionType.HeadingMedium)
        StatusChip(when (decision.dryRun) { true -> "Dry run"; false -> "Live run"; null -> "Run mode unknown" }, FusionColors.Indigo)
        Text("${decision.choice} · ${readableCode(decision.reason)}", style = FusionType.HeadingSmall)
        WhyButton("decision:${decision.hash}", facts = { decision.toFacts() })
        decision.txHash?.let { RecordedProvenanceChip(activityProvenance(txIndex.find(it)?.source)) }
        Text("Planner: ${readableCode(txIndex.find(decision.txHash)?.planner ?: decision.planner)}", style = FusionType.NumberCaption)
        decisionTimings(decision.loadMs, decision.inferenceMs, decision.decodeMs).takeIf { it.isNotBlank() }?.let { Text(it, style = FusionType.BodySmall) }
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
        var checksOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { checksOpen = !checksOpen }) { Text("${checks.count { it.status == "PASS" }} passed · ${checks.count { it.status == "FAIL" }} failed") }
        if (checksOpen) checks.forEach { check ->
            Column(Modifier.padding(vertical = 6.dp)) {
                StatusChip(readableCode(check.status), when (check.status) { "PASS" -> FusionColors.Green; "FAIL" -> FusionColors.Red; else -> FusionColors.Amber })
                Text(readableCode(check.name), style = FusionType.BodyMedium)
                Text(historicalCheckMessage(check.message, decision.snapshotTime ?: recordedTime, currentBlockTime), style = FusionType.BodySmall)
            }
        }
        if (checks.isEmpty()) Text("Policy checks not recorded", style = FusionType.BodySmall)
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
