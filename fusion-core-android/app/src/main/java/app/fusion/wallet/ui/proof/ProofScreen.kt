package app.fusion.wallet.ui.proof

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import app.fusion.wallet.ui.common.Expander
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.MonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Safety": one headline you can read in a second; the evidence underneath, one tap at a time. */
@Composable
fun ProofScreen(modifier: Modifier = Modifier, vm: AccountantViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var consentOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.pf_title), stringResource(R.string.pf_subtitle)) }
        s.message?.let { m -> item { Notice(m, onDismiss = vm::dismissMessage) } }
        item { HeadlineCard(s.arena) }
        item { ArenaCard(s.arena) }
        item { CloudCard(vm, s.settings.cloudEnabled, s.hasApiKey, s.settings.model, s.settings.dailyCallLimit, s.egress.size, s.egress, onToggleOn = { consentOpen = true }) }
        item { ReceiptsCard(s.receipts) }
    }
    if (consentOpen) ConsentDialog(onAccept = { consentOpen = false; vm.grantConsent() }, onDismiss = { consentOpen = false })
}

// ---- headline ---------------------------------------------------------------------------------

@Composable
private fun HeadlineCard(results: List<ArenaResultEntity>) {
    val total = results.size
    val gotThrough = results.count { it.outcome == ArenaOutcome.NOT_BLOCKED }
    val completed = results.count { it.outcome != ArenaOutcome.ERROR }
    val none = stringResource(R.string.pf_not_measured)
    val good = total > 0 && gotThrough == 0
    ObsidianCard {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (total == 0) Muted.copy(alpha = 0.2f) else if (good) Tone.Good.copy(alpha = 0.2f) else Tone.Bad.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = if (total == 0) Muted else if (good) Tone.Good else Tone.Bad, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(
                        if (total == 0) stringResource(R.string.pf_headline_none) else stringResource(R.string.pf_headline_some, (total - gotThrough).toString(), total.toString()),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold,
                    )
                    if (total == 0) Text(stringResource(R.string.pf_headline_hint), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Metric(stringResource(R.string.pf_asr), if (total == 0) none else stringResource(R.string.pf_count_of, gotThrough.toString(), total.toString()), if (total == 0) Muted else if (good) Tone.Good else Tone.Bad)
            // No benign-case evaluation exists yet, so this stays "not measured" rather than a flattering 0%.
            Metric(stringResource(R.string.pf_fbr), none, Muted)
            Metric(stringResource(R.string.pf_avail), if (total == 0) none else stringResource(R.string.pf_count_of, completed.toString(), total.toString()), if (total == 0) Muted else Tone.Good)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, tone: Color) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MonoStyle, color = tone)
    }
}

// ---- arena ------------------------------------------------------------------------------------

@Composable
private fun ArenaCard(results: List<ArenaResultEntity>) {
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.pf_arena))
            Text(stringResource(R.string.pf_arena_note), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(bottom = 6.dp))
            ArenaScenario.entries.forEach { sc ->
                val last = results.firstOrNull { it.scenario == sc }
                val tone = when (last?.outcome) {
                    ArenaOutcome.BLOCKED, ArenaOutcome.SAFE_CHOICE -> Tone.Good
                    ArenaOutcome.NOT_BLOCKED, ArenaOutcome.ERROR -> Tone.Bad
                    null -> Muted
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tone))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(stringResource(scenarioRes(sc)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            last?.let { stringResource(outcomeRes(it.outcome)) + " · " + it.layer } ?: stringResource(R.string.pf_not_run),
                            style = MaterialTheme.typography.bodySmall, color = tone,
                        )
                    }
                }
            }
            Text(stringResource(R.string.pf_arena_unavailable), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
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

// ---- cloud help: a switch, with the detail behind taps -----------------------------------------

@Composable
private fun CloudCard(
    vm: AccountantViewModel, enabled: Boolean, hasKey: Boolean, model: String, dailyLimit: Int, eventCount: Int,
    egress: List<app.fusion.wallet.accountant.data.EgressLogEntity>, onToggleOn: () -> Unit,
) {
    var shared by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var activity by rememberSaveable { mutableStateOf(false) }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pf_cloud_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.pf_cloud_desc), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                StatusPill(label = stringResource(if (enabled) R.string.pill_cloud_on else R.string.pill_local_only), tone = if (enabled) Tone.Earning else Tone.Good, modifier = Modifier.padding(horizontal = 8.dp))
                Switch(checked = enabled, onCheckedChange = { on -> if (on) onToggleOn() else vm.revokeConsent() })
            }
            Expander(stringResource(R.string.pf_what_shared), shared, { shared = !shared }) { SharedLists() }
            Expander(stringResource(R.string.pf_activity) + " ($eventCount)", activity, { activity = !activity }) { EgressList(egress) }
            Expander(stringResource(R.string.pf_advanced), advanced, { advanced = !advanced }) {
                PrivacyAdvanced(
                    hasKey = hasKey, model = model, dailyLimit = dailyLimit,
                    onSaveKey = vm::saveApiKey, onClearKey = vm::clearApiKey, onModel = vm::setModel, onLimit = vm::setDailyLimit,
                )
            }
        }
    }
}

// ---- receipts ---------------------------------------------------------------------------------

@Composable
private fun ReceiptsCard(rows: List<ReceiptRow>) {
    val onChain = rows.filter { it.txHash != null }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.pf_receipts))
            if (onChain.isEmpty()) Text(stringResource(R.string.pf_receipts_none), style = MaterialTheme.typography.bodyLarge, color = Muted)
            onChain.take(8).forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${Fmt.money(Raw.parse(r.amountRaw), r.decimals)} ${r.symbol}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(Fmt.short(r.txHash!!) + " · " + SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(r.occurredAt * 1000)), style = MonoStyle, color = Muted)
                    }
                    StatusPill(label = r.status.name, tone = Tone.Good)
                }
            }
        }
    }
}
