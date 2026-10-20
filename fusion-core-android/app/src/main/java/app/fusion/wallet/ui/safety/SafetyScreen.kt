package app.fusion.wallet.ui.safety

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.agent.*
import app.fusion.wallet.ui.common.CustodySplitCard
import app.fusion.wallet.ui.common.allowanceLabels
import app.fusion.wallet.ui.advisor.CheckExternalTextCard
import app.fusion.wallet.ui.common.startActivitySafe
import app.fusion.wallet.ui.common.formatTimestamp
import app.fusion.wallet.ui.common.readableCode
import app.fusion.wallet.ui.owner.WhoCanDoWhatCard
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.shell.ShellViewModel
import app.fusion.wallet.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SafetyScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel(), evidenceVm: SafetyScreenModel = viewModel(), shell: ShellViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val evidence by evidenceVm.state.collectAsStateWithLifecycle()
    var detailsOpen by remember { mutableStateOf(false) }
    val arena = evidence.arena
    val safeUrl = rememberSafeUrl(state.treasury?.safeAddress)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback: (String, String?) -> Unit = { message, tx -> scope.launch {
        if (snackbar.showSnackbar(message, actionLabel = tx?.let { "View transaction" }, withDismissAction = true) == SnackbarResult.ActionPerformed && tx != null) {
            if (!context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://sepolia.basescan.org/tx/$tx")))) snackbar.showSnackbar(context.getString(R.string.k24_link_unavailable))
        }
    } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = FusionColors.Bg) { padding ->
        LazyColumn(modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(FusionSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(FusionSpacing.gutter)) {
            item { Text(stringResource(R.string.nav_safety), style = FusionType.HeadingLarge) }
            item {
                if (!evidence.loaded) SkeletonTile(height = 200.dp) else BentoTile(Modifier.fillMaxWidth()) {
                    Text(if (arena == null) "Attack score unavailable" else "${arena.stopped} / ${arena.scenarios.size} attacks stopped", style = FusionType.HeadingMedium)
                    val frozen = arena?.frozenAsr
                    val heldOut = evidence.heldOut
                    if (frozen != null && heldOut != null) {
                        Text("${frozen.numerator + heldOut.numerator} attacks succeeded (frozen ${frozen.numerator}/${frozen.denominator} · held-out ${heldOut.numerator}/${heldOut.denominator})", style = FusionType.BodyMedium)
                    } else Text("Attack success metrics unavailable", style = FusionType.BodyMedium)
                    Text("ASR measures off-chain proposal admission in the evaluated corpora; it does not measure realized on-chain loss.", style = FusionType.BodySmall)
                    Text(evidence.source, style = FusionType.BodySmall)
                    arena?.measuredAt?.let { Text("Measured ${formatTimestamp(it)}", style = FusionType.BodySmall) }
                    evidence.error?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Amber) }
                    TextButton(onClick = evidenceVm::refresh, enabled = !evidence.refreshing, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (evidence.refreshing) "Refreshing evidence…" else "Refresh evidence")
                    }
                    ExternalAction("Arena evidence", EVIDENCE_RAW + ARENA_PATH) { feedback(it, null) }
                    ExternalAction("Held-out evaluation", EVIDENCE_RAW + HELDOUT_PATH) { feedback(it, null) }
                }
            }
            item { CustodySplitCard(state) }
            item { WhoCanDoWhatCard(state) }
            item { BentoTile(Modifier.fillMaxWidth()) {
                Text("AI allowance", style = FusionType.HeadingSmall)
                state.module?.let { m -> allowanceLabels(m, state.blockTime).forEach { Text(it, style = FusionType.BodySmall) } }
                    ?: Text("Policy unavailable")
            } }
            item { TextButton(onClick = { detailsOpen = !detailsOpen }) { Text(if (detailsOpen) "Hide evidence details" else "Safety layers and evidence details") } }
            if (detailsOpen) item { Column { SafetyCharts(evidence) } }
            if (detailsOpen) item { CheckExternalTextCard() }
            if (detailsOpen) item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text("Five safety layers", style = FusionType.HeadingMedium)
                    if (arena == null) Text("Layer counts unavailable until evidence loads", style = FusionType.BodyMedium)
                    else {
                        ConnectedSteps(safetyDiagramLayers.map { (title, keys) -> title to "${keys.sumOf { arena.layerCounts[it] ?: 0 }} attacks stopped" })
                        Text("Counts credit the recorded stopping layer, rather than the expected layer. Contract probes deliberately bypass earlier layers.", style = FusionType.BodySmall)
                        val displayed = safetyDiagramLayers.flatMap { it.second }.toSet()
                        arena.layerCounts.filterKeys { it !in displayed }.forEach { (layer, count) ->
                            Text("${safetyLayerTitle(layer)} · $count stopped (additional control)", style = FusionType.BodyMedium)
                        }
                    }
                }
            }
            item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text("Emergency controls", style = FusionType.HeadingMedium)
                    SnapshotStatus(state, vm::refresh)
                    GuardianStopControl(state.module?.paused, vm::requestGuardianPause, feedback)
                    Text(stringResource(R.string.k24_unwind_explanation), style = FusionType.BodyMedium)
                    if (safeUrl != null) ExternalAction(stringResource(R.string.k24_emergency_unwind), safeUrl) { feedback(it, null) }
                    else Text(stringResource(R.string.k24_safe_unavailable), style = FusionType.BodySmall)
                }
            }
            item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text("Judge mode", style = FusionType.HeadingSmall)
                    Text("Inspect transaction evidence and verification commands.", style = FusionType.BodyMedium)
                    Button(onClick = { shell.navigate(NavRoute.Judge) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open judge mode") }
                }
            }
            if (detailsOpen) {
            item { Text("Known limits", style = FusionType.HeadingMedium) }
            if (arena == null) item { BentoTile(Modifier.fillMaxWidth()) { Text("Known limits unavailable; evidence has not loaded", style = FusionType.BodyMedium) } }
            else if (arena.knownLimits.isEmpty()) item { BentoTile(Modifier.fillMaxWidth()) { Text("No escaped or unmeasured scenarios in this evidence set", style = FusionType.BodyMedium) } }
            items(arena?.knownLimits.orEmpty(), key = { "limit:${it.id}" }) { AttackCard(it, true, feedback) }
            if (!arena?.limitations.isNullOrEmpty()) item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text("Evaluation boundaries", style = FusionType.HeadingSmall)
                    arena?.limitations?.forEach { Text(it, style = FusionType.BodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
            item { Text("Blocked attacks", style = FusionType.HeadingMedium) }
            if (arena == null) item {
                if (!evidence.loaded) SkeletonTile(height = 180.dp) else BentoTile(Modifier.fillMaxWidth()) {
                    Text("Blocked attacks unavailable; retry evidence refresh", style = FusionType.BodyMedium)
                }
            }
            items(arena?.scenarios.orEmpty().filter { it.stopped == true }, key = { it.id }) { AttackCard(it, false, feedback) }
            }
        }
    }
}

@Composable
private fun AttackCard(scenario: AttackScenario, knownLimit: Boolean, feedback: (String, String?) -> Unit) {
    BentoTile(Modifier.fillMaxWidth()) {
        Text("${scenario.id} · ${scenario.description}", style = FusionType.HeadingSmall)
        StatusChip(if (knownLimit) readableCode(scenario.status) else "Stopped · ${safetyLayerTitle(scenario.stoppingLayer.orEmpty())}", if (knownLimit) FusionColors.Red else FusionColors.Green)
        if (knownLimit || scenario.stoppingLayer == "off-chain impl pin" || scenario.stoppingLayer == "durable_journal") {
            scenario.explanation?.let { Text(it, style = FusionType.BodyMedium) }
        }
        scenario.evidence.forEach { link ->
            if (link.txHash != null) TxLink(link.txHash, explorerUrl = link.url)
            else ExternalAction(link.label, link.url) { feedback(it, null) }
        }
    }
}
