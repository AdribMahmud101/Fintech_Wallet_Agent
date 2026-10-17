package app.fusion.wallet.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.accountant.data.AgentDecisionEntity
import app.fusion.wallet.accountant.data.AgentStatus
import app.fusion.wallet.accountant.data.CheckState
import app.fusion.wallet.accountant.data.DecisionKind
import app.fusion.wallet.accountant.data.DecisionOrigin
import app.fusion.wallet.accountant.data.DecisionStatus
import app.fusion.wallet.accountant.data.MandateEntity
import app.fusion.wallet.accountant.data.Tier
import app.fusion.wallet.accountant.domain.Raw
import app.fusion.wallet.ui.Fmt
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The AI as an accountable participant: who it is, what it may do (its mandate), and a visible
 * record of every decision with the verdict of each safety layer.
 */
@Composable
fun AgentScreen(modifier: Modifier = Modifier, vm: AccountantViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val dec = s.snapshot?.unit?.decimals ?: 6

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.ag_title), stringResource(R.string.ag_subtitle)) }
        s.mandate?.let { m -> item { PassportCard(m, dec, now) } }
        if (s.demoData) item { Text(stringResource(R.string.ag_demo_note), style = MaterialTheme.typography.labelSmall, color = Obsidian.AccentAiViolet) }

        item { Text(stringResource(R.string.ag_timeline), style = MaterialTheme.typography.labelSmall, color = Muted) }
        if (s.decisions.isEmpty()) {
            item {
                ObsidianCard {
                    Text(stringResource(R.string.ag_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Muted)
                }
            }
        } else {
            items(s.decisions, key = { it.id }) { DecisionCard(it, now) }
        }
    }
}

// ---- passport -----------------------------------------------------------------------------------

private fun statusTone(s: AgentStatus): Color = when (s) {
    AgentStatus.ARMED -> Obsidian.StatusSuccess
    AgentStatus.PAUSED -> Obsidian.StatusWarning
    AgentStatus.HALTED -> Obsidian.StatusError
}

@Composable
private fun PassportCard(m: MandateEntity, dec: Int, now: Long) {
    fun whole(raw: String) = Fmt.whole(Raw.parse(raw), dec)
    val spent = Raw.parse(m.spentTodayRaw)
    val limit = Raw.parse(m.dailyLimitRaw)
    val frac = if (limit.signum() <= 0) 0f else (spent.multiply(BigInteger.valueOf(1000)).divide(limit).toInt() / 1000f).coerceIn(0f, 1f)

    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(stringResource(R.string.ag_passport), style = MaterialTheme.typography.labelSmall, color = Muted)
                    Text(m.agentLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                }
                StatusPill(label = stringResource(statusRes(m.status)), tone = statusTone(m.status))
            }
            Spacer(Modifier.height(12.dp))
            Rule(Obsidian.StatusSuccess, stringResource(R.string.ag_rule_auto, "${whole(m.immediateMaxRaw)} USDC"))
            Rule(Obsidian.StatusWarning, stringResource(R.string.ag_rule_delay, "${whole(m.delayedMaxRaw)} USDC", durationText(m.delaySeconds.toLong())))
            Rule(Obsidian.AccentAiViolet, stringResource(R.string.ag_rule_owner, "${whole(m.delayedMaxRaw)} USDC"))
            Rule(Obsidian.StatusError, stringResource(R.string.ag_rule_hard, "${whole(m.hardMaxRaw)} USDC", "${whole(m.dailyLimitRaw)} USDC"))

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ag_spent_today), style = MaterialTheme.typography.labelSmall, color = Muted)
                Text("${Fmt.whole(spent, dec)} / ${Fmt.whole(limit, dec)} USDC", style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(FusionTheme.surfaces.level3)) {
                if (frac > 0f) Box(Modifier.weight(frac).height(6.dp).background(if (frac > 0.8f) Obsidian.StatusWarning else Accessible.AccentText))
                if (frac < 1f) Box(Modifier.weight(1f - frac).height(6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.ag_session, durationText(m.sessionExpiresAt - now)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ag_fingerprint), style = MaterialTheme.typography.labelSmall, color = Muted)
                Text(Fmt.short(m.policyHash), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.ag_pause)) }
            Text(stringResource(R.string.ag_controls_note), style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

/** "3 min", "22h 12m" in the current language (Fmt.duration's m:ss is only for live countdowns). */
@Composable
private fun durationText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s >= 3600 -> stringResource(R.string.dur_hours_minutes, (s / 3600).toString(), ((s % 3600) / 60).toString())
        s >= 60 -> stringResource(R.string.dur_minutes, (s / 60).toString())
        else -> stringResource(R.string.dur_seconds, s.toString())
    }
}

@Composable
private fun labelText(key: String): String = when (key) {
    "CASH" -> stringResource(R.string.lab_CASH)
    "AAVE" -> stringResource(R.string.lab_AAVE)
    "VAULT" -> stringResource(R.string.lab_VAULT)
    "UNKNOWN" -> stringResource(R.string.lab_UNKNOWN)
    else -> key
}

private fun statusRes(s: AgentStatus) = when (s) {
    AgentStatus.ARMED -> R.string.ag_status_ARMED
    AgentStatus.PAUSED -> R.string.ag_status_PAUSED
    AgentStatus.HALTED -> R.string.ag_status_HALTED
}

@Composable
private fun Rule(color: Color, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(16.dp).background(color))
        Text(text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- decision timeline --------------------------------------------------------------------------

private fun stripeColor(d: AgentDecisionEntity): Color = when {
    d.status == DecisionStatus.BLOCKED -> Obsidian.StatusError
    d.status == DecisionStatus.QUEUED -> Obsidian.StatusWarning
    d.status == DecisionStatus.EXECUTED -> Obsidian.StatusSuccess
    else -> Muted
}

@Composable
private fun DecisionCard(d: AgentDecisionEntity, now: Long) {
    val attack = d.origin == DecisionOrigin.ARENA_ATTACK
    ObsidianCard {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(stripeColor(d)))
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (attack) stringResource(R.string.ag_attack_title) else SimpleDateFormat("HH:mm", Locale.US).format(Date(d.createdAt * 1000)),
                        style = MaterialTheme.typography.labelSmall, color = if (attack) Obsidian.StatusError else Muted,
                    )
                    StatusPill(label = stringResource(decisionStatusRes(d.status)), tone = stripeColor(d))
                }
                Spacer(Modifier.height(6.dp))
                Text(decisionSentence(d), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (d.rationale.isNotBlank()) {
                    Text(d.rationale, style = MaterialTheme.typography.bodySmall, color = Muted, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(tierRes(d.tier)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("·", color = Muted)
                    Text(stringResource(R.string.ag_by, d.chosenBy), style = MaterialTheme.typography.labelSmall, color = Muted)
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Check(R.string.ag_chk_model, d.modelCheck)
                    Check(R.string.ag_chk_rules, d.rustCheck)
                    Check(R.string.ag_chk_signer, d.signerCheck)
                    Check(R.string.ag_chk_contract, d.contractCheck)
                }

                if (d.status == DecisionStatus.QUEUED && d.readyAt != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.ag_countdown, Fmt.duration(d.readyAt - now)), style = MonoStyle, color = Obsidian.StatusWarning)
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.ag_cancel)) }
                }
                d.blockedBy?.let { layer ->
                    Text(stringResource(R.string.ag_blocked_at, layerName(layer)), style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusError, modifier = Modifier.padding(top = 8.dp))
                }
                d.txHash?.let { Text(stringResource(R.string.ag_receipt, Fmt.short(it)), style = MonoStyle, color = Muted, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

@Composable
private fun decisionSentence(d: AgentDecisionEntity): String {
    val amount = Fmt.money(Raw.parse(d.assetsRaw), d.decimals)
    return when (d.kind) {
        DecisionKind.HOLD -> stringResource(R.string.ag_kind_HOLD)
        DecisionKind.SWEEP_TO_VAULT -> stringResource(R.string.ag_kind_SWEEP_TO_VAULT, amount, d.symbol, labelText(d.fromLabel), labelText(d.toLabel))
        DecisionKind.WITHDRAW_TO_CASH -> stringResource(R.string.ag_kind_WITHDRAW_TO_CASH, amount, d.symbol, labelText(d.fromLabel), labelText(d.toLabel))
        DecisionKind.REBALANCE -> stringResource(R.string.ag_kind_REBALANCE, amount, d.symbol, labelText(d.fromLabel), labelText(d.toLabel))
    }
}

@Composable
private fun layerName(blockedBy: String): String {
    val base = blockedBy.substringBefore(':')
    val reason = blockedBy.substringAfter(':', "")
    val name = stringResource(
        when (base) {
            "MODEL" -> R.string.layer_MODEL
            "RUST" -> R.string.layer_RUST
            "SIGNER" -> R.string.layer_SIGNER
            else -> R.string.layer_CONTRACT
        },
    )
    return if (reason.isEmpty()) name else "$name ($reason)"
}

private fun decisionStatusRes(s: DecisionStatus) = when (s) {
    DecisionStatus.PROPOSED -> R.string.ag_st_PROPOSED
    DecisionStatus.QUEUED -> R.string.ag_st_QUEUED
    DecisionStatus.EXECUTED -> R.string.ag_st_EXECUTED
    DecisionStatus.CANCELLED -> R.string.ag_st_CANCELLED
    DecisionStatus.EXPIRED -> R.string.ag_st_EXPIRED
    DecisionStatus.BLOCKED -> R.string.ag_st_BLOCKED
    DecisionStatus.HELD -> R.string.ag_st_HELD
}

private fun tierRes(t: Tier) = when (t) {
    Tier.IMMEDIATE -> R.string.ag_tier_IMMEDIATE
    Tier.DELAYED -> R.string.ag_tier_DELAYED
    Tier.OWNER_REQUIRED -> R.string.ag_tier_OWNER_REQUIRED
}

/** One safety layer's verdict. State is never colour-only: a symbol and a spoken description too. */
@Composable
private fun Check(labelRes: Int, state: CheckState) {
    val (symbol, tone, descRes) = when (state) {
        CheckState.PASS -> Triple("✓", Obsidian.StatusSuccess, R.string.ag_chk_pass)
        CheckState.FAIL -> Triple("✗", Obsidian.StatusError, R.string.ag_chk_fail)
        CheckState.SKIPPED -> Triple("–", Muted, R.string.ag_chk_skipped)
    }
    val label = stringResource(labelRes)
    val desc = "$label: ${stringResource(descRes)}"
    Row(
        Modifier
            .clip(RoundedCornerShape(FusionDims.radiusSmall))
            .background(FusionTheme.surfaces.level2)
            .border(FusionDims.hairline, FusionTheme.surfaces.border, RoundedCornerShape(FusionDims.radiusSmall))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, color = tone, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
