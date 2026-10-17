package app.fusion.wallet.ui.proof

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.accountant.data.ArenaOutcome
import app.fusion.wallet.accountant.data.ArenaResultEntity
import app.fusion.wallet.accountant.data.ArenaScenario
import app.fusion.wallet.accountant.data.ReceiptRow
import app.fusion.wallet.accountant.domain.Raw
import app.fusion.wallet.ui.Fmt
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Evidence: measured results, the attack arena, what left the phone, and on-chain receipts. */
@Composable
fun ProofScreen(modifier: Modifier = Modifier, vm: AccountantViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var consentOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.pf_title), stringResource(R.string.pf_subtitle)) }
        s.message?.let { m -> item { Notice(m, onDismiss = vm::dismissMessage) } }
        item { ScorecardCard(s.arena) }
        item { ArenaCard(s.arena) }
        item { ReceiptsCard(s.receipts) }
        item { Text(stringResource(R.string.pf_privacy), style = MaterialTheme.typography.labelSmall, color = Muted) }
        item {
            PrivacyCard(
                enabled = s.settings.cloudEnabled, hasKey = s.hasApiKey, model = s.settings.model, dailyLimit = s.settings.dailyCallLimit,
                onToggle = { on -> if (on) consentOpen = true else vm.revokeConsent() },
                onSaveKey = vm::saveApiKey, onClearKey = vm::clearApiKey, onModel = vm::setModel, onLimit = vm::setDailyLimit,
            )
        }
        item { EgressCard(s.egress) }
    }
    if (consentOpen) ConsentDialog(onAccept = { consentOpen = false; vm.grantConsent() }, onDismiss = { consentOpen = false })
}

// ---- scorecard ----------------------------------------------------------------------------------

@Composable
private fun ScorecardCard(results: List<ArenaResultEntity>) {
    val total = results.size
    val gotThrough = results.count { it.outcome == ArenaOutcome.NOT_BLOCKED }
    val completed = results.count { it.outcome != ArenaOutcome.ERROR }
    val none = stringResource(R.string.pf_not_measured)
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.pf_scorecard))
            Metric(stringResource(R.string.pf_asr), if (total == 0) none else stringResource(R.string.pf_count_of, gotThrough.toString(), total.toString()), if (total > 0 && gotThrough == 0) Obsidian.StatusSuccess else if (total > 0) Obsidian.StatusError else Muted)
            // No benign-case evaluation exists yet, so this stays "not measured" rather than a flattering 0%.
            Metric(stringResource(R.string.pf_fbr), none, Muted)
            Metric(stringResource(R.string.pf_avail), if (total == 0) none else stringResource(R.string.pf_count_of, completed.toString(), total.toString()), if (total == 0) Muted else Obsidian.StatusSuccess)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, tone: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MonoStyle, color = tone)
    }
}

// ---- arena --------------------------------------------------------------------------------------

@Composable
private fun ArenaCard(results: List<ArenaResultEntity>) {
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.pf_arena))
            Text(stringResource(R.string.pf_arena_note), style = MaterialTheme.typography.bodySmall, color = Muted)
            ArenaScenario.entries.forEach { sc ->
                val last = results.firstOrNull { it.scenario == sc }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(scenarioRes(sc)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            last?.let { stringResource(outcomeRes(it.outcome)) + " · " + it.layer } ?: stringResource(R.string.pf_not_run),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (last?.outcome) {
                                ArenaOutcome.BLOCKED, ArenaOutcome.SAFE_CHOICE -> Obsidian.StatusSuccess
                                ArenaOutcome.NOT_BLOCKED, ArenaOutcome.ERROR -> Obsidian.StatusError
                                null -> Muted
                            },
                        )
                    }
                    OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.pf_run)) }
                }
            }
            Text(stringResource(R.string.pf_arena_unavailable), style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun scenarioRes(s: ArenaScenario) = when (s) {
    ArenaScenario.PROMPT_INJECTION -> R.string.pf_sc_PROMPT_INJECTION
    ArenaScenario.DEPEG -> R.string.pf_sc_DEPEG
    ArenaScenario.CAP_BREACH -> R.string.pf_sc_CAP_BREACH
    ArenaScenario.RESERVE_BREACH -> R.string.pf_sc_RESERVE_BREACH
    ArenaScenario.DRIP_DRAIN -> R.string.pf_sc_DRIP_DRAIN
    ArenaScenario.QUEUE_BYPASS -> R.string.pf_sc_QUEUE_BYPASS
    ArenaScenario.CRASH_RECOVERY -> R.string.pf_sc_CRASH_RECOVERY
}

private fun outcomeRes(o: ArenaOutcome) = when (o) {
    ArenaOutcome.BLOCKED -> R.string.pf_out_BLOCKED
    ArenaOutcome.SAFE_CHOICE -> R.string.pf_out_SAFE_CHOICE
    ArenaOutcome.NOT_BLOCKED -> R.string.pf_out_NOT_BLOCKED
    ArenaOutcome.ERROR -> R.string.pf_out_ERROR
}

// ---- receipts -----------------------------------------------------------------------------------

@Composable
private fun ReceiptsCard(rows: List<ReceiptRow>) {
    val onChain = rows.filter { it.txHash != null }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.pf_receipts))
            if (onChain.isEmpty()) Text(stringResource(R.string.pf_receipts_none), style = MaterialTheme.typography.bodySmall, color = Muted)
            onChain.take(8).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${Fmt.money(Raw.parse(r.amountRaw), r.decimals)} ${r.symbol}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(Fmt.short(r.txHash!!) + " · " + SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(r.occurredAt * 1000)), style = MonoStyle, color = Muted)
                    }
                    StatusPill(label = r.status.name, tone = Obsidian.StatusSuccess)
                }
            }
        }
    }
}
