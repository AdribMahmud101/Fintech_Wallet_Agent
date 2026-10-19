package app.fusion.wallet.ui.proof

import app.fusion.wallet.ui.common.startActivitySafe
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.data.ArenaScenario
import app.fusion.wallet.data.BlockedOnChain
import app.fusion.wallet.data.DataSource
import app.fusion.wallet.data.LoadStatus
import app.fusion.wallet.data.SafetyData
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.common.BentoGap
import app.fusion.wallet.ui.common.BentoRow
import app.fusion.wallet.ui.common.BentoTile
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.common.StatTile
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.shorten
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.MonoStyle

/**
 * Safety screen — real and honest.
 *
 * Counts come from the frozen evidence files (arena [SafetyData] + Base Sepolia reverts), never
 * from live logs: a reverted attack emits no event, so it can never be reconstructed from history.
 * The three tiles read "21 / 24 attacks stopped", "0 / 30 attacks succeeded", and the live stop
 * state. Scenarios that escaped or are not implemented are listed honestly under "Known limits".
 * Emergency Unwind is explanatory text, not a dead button — it is a 2-of-2 Safe owner action.
 */
@Composable
fun ProofScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val safety by vm.safety.collectAsStateWithLifecycle()
    var userDismissedError by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BentoGap),
    ) {
        item {
            ScreenHeader(
                title = stringResource(R.string.safe_title),
                subtitle = stringResource(R.string.safe_subtitle),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (state.source == DataSource.DEMO) {
                            StatusPill(label = stringResource(R.string.badge_demo), tone = Tone.Earning)
                        }
                        IconButton(onClick = { vm.refresh() }, modifier = Modifier.size(48.dp)) {
                            if (state.refreshing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh_button), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                },
            )
        }

        if (state.status == LoadStatus.ERROR && !userDismissedError) {
            item { Notice(text = state.error ?: "Can't reach the network", onDismiss = { userDismissedError = true }) }
        }

        // Headline + honest scorecard tiles
        item { SafetyHeadlineTile(safety) }
        item { ScorecardRow(safety, paused = state.module?.paused) }

        // Emergency Unwind — explanatory, owner action (no dead button)
        item { EmergencyUnwindCard() }

        // Blocked on-chain (reverted) — from Base Sepolia receipts
        item {
            SectionTitle(
                stringResource(R.string.safe_onchain_title),
                trailing = safety.blockedOnChain.size.takeIf { it > 0 }?.toString(),
            )
        }
        if (!safety.loaded) {
            item { LoadingTile() }
        } else if (safety.blockedOnChain.isEmpty()) {
            item { EmptyTile(stringResource(R.string.safe_onchain_empty)) }
        } else {
            items(safety.blockedOnChain, key = { it.txHash }) { BlockedAttackCard(it) }
        }

        // Arena scenarios that were stopped
        val stoppedScenarios = safety.scenarios.filter { it.isStopped }
        if (stoppedScenarios.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.safe_arena_title), trailing = stoppedScenarios.size.toString()) }
            items(stoppedScenarios, key = { it.id }) { ScenarioRow(it) }
        }

        // Known limits — escaped / not implemented, listed honestly
        item { SectionTitle(stringResource(R.string.safe_known_limits_title), trailing = safety.knownLimits.size.takeIf { it > 0 }?.toString()) }
        if (safety.knownLimits.isEmpty() && safety.loaded) {
            item { EmptyTile(stringResource(R.string.safe_known_limits_empty)) }
        } else {
            items(safety.knownLimits, key = { it.id }) { ScenarioRow(it) }
        }
    }
}

// ---- Headline ---------------------------------------------------------------------------------

@Composable
private fun SafetyHeadlineTile(safety: SafetyData) {
    val hasData = safety.loaded && safety.scenarioCount > 0
    BentoTile(modifier = Modifier.fillMaxWidth(), tint = Tone.Good, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Tone.Good.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = Tone.Good, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = if (hasData) stringResource(R.string.pf_headline_some, safety.stopped.toString(), safety.scenarioCount.toString())
                    else stringResource(R.string.pf_headline_none),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (hasData && safety.measuredAt != null)
                        stringResource(R.string.safe_measured_on, safety.measuredAt!!.take(10), safety.scenarioCount.toString())
                    else stringResource(R.string.pf_headline_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun ScorecardRow(safety: SafetyData, paused: Boolean?) {
    BentoRow {
        StatTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = stringResource(R.string.safe_tile_stopped),
            value = stringResource(R.string.safe_count_pair, safety.stopped.toString(), safety.scenarioCount.toString()),
            color = Tone.Good,
        )
        StatTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = stringResource(R.string.safe_tile_succeeded),
            value = stringResource(R.string.safe_count_pair, safety.asrNumerator.toString(), safety.asrDenominator.toString()),
            color = if (safety.asrNumerator == 0) Tone.Good else Tone.Bad,
        )
        StatTile(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = stringResource(R.string.safe_tile_stop),
            value = if (paused == true) stringResource(R.string.safe_stop_paused) else stringResource(R.string.safe_stop_armed),
            color = if (paused == true) Tone.Warn else Tone.Good,
        )
    }
}

// ---- Section helpers --------------------------------------------------------------------------

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun LoadingTile() {
    BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.tr_loading), style = MaterialTheme.typography.bodyLarge, color = Muted)
        }
    }
}

@Composable
private fun EmptyTile(text: String) {
    BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

// ---- Blocked attack (on-chain revert) ---------------------------------------------------------

@Composable
private fun BlockedAttackCard(row: BlockedOnChain) {
    val context = LocalContext.current
    ObsidianCard(modifier = Modifier.fillMaxWidth(), level = 1) {
        Column(Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Tone.Good.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tone.Good, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.safe_tried, row.scenario),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(label = stringResource(R.string.safe_sc_stopped), tone = Tone.Good)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Block #${row.blockNumber}" + (row.decisionHash?.let { " · ${shorten(it)}" } ?: ""),
                style = MonoStyle.copy(fontSize = 12.sp),
                color = Muted,
            )
            Spacer(Modifier.height(6.dp))
            BaseScanLink(url = row.explorerUrl, context = context)
        }
    }
}

// ---- Arena scenario row -----------------------------------------------------------------------

@Composable
private fun ScenarioRow(s: ArenaScenario) {
    val tone = when {
        s.isStopped -> Tone.Good
        s.status == "escaped" -> Tone.Bad
        else -> Tone.Warn
    }
    val statusLabel = when (s.status) {
        "escaped" -> stringResource(R.string.safe_sc_escaped)
        "not_implemented" -> stringResource(R.string.safe_sc_not_impl)
        else -> stringResource(R.string.safe_sc_stopped)
    }
    ObsidianCard(modifier = Modifier.fillMaxWidth(), level = 2) {
        Column(Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                StatusPill(label = statusLabel, tone = tone)
            }
            if (s.isStopped && s.stoppingLayer != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.safe_stopped_at, layerLabel(s.stoppingLayer)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun layerLabel(layer: String): String = when (layer.lowercase()) {
    "contract" -> stringResource(R.string.safe_layer_contract)
    "rust_policy_preview", "rust_candidate_policy" -> stringResource(R.string.safe_layer_rules)
    "ahem_g" -> stringResource(R.string.safe_layer_grammar)
    "output_validator" -> stringResource(R.string.safe_layer_validator)
    "signer" -> stringResource(R.string.safe_layer_signer)
    "reflex_latch_then_contract" -> stringResource(R.string.safe_layer_reflex)
    "durable_journal" -> stringResource(R.string.safe_layer_journal)
    else -> layer.replace('_', ' ')
}

// ---- Emergency Unwind (owner action, no dead button) ------------------------------------------

@Composable
private fun EmergencyUnwindCard() {
    ObsidianCard(modifier = Modifier.fillMaxWidth(), level = 1) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = Tone.Earning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.safe_unwind_owner_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(label = stringResource(R.string.safe_unwind_owner_tag), tone = Tone.Warn)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.safe_unwind_owner_body),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun BaseScanLink(url: String, context: android.content.Context) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.view_on_basescan),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = Accessible.AccentText,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(3.dp))
        Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.view_on_basescan), tint = Accessible.AccentText, modifier = Modifier.size(12.dp))
    }
}
