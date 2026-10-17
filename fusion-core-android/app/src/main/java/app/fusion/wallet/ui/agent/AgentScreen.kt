package app.fusion.wallet.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
import app.fusion.wallet.ui.common.Expander
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The AI as an accountable participant. Default view answers two questions in seconds: "what is
 * it allowed to do?" and "what did it do?". The how (per-layer checks, rationale, receipt) is one
 * tap away on each card.
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
    val unit = s.snapshot?.unit?.symbol ?: "USDC"

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.ag_title), stringResource(R.string.ag_subtitle)) }
        s.mandate?.let { m -> item { AgentCard(m, dec, unit, now) } }
        if (s.demoData) item { Text(stringResource(R.string.ag_demo_note), style = MaterialTheme.typography.bodySmall, color = Tone.Earning) }

        item { Text(stringResource(R.string.ag_timeline), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) }
        if (s.decisions.isEmpty()) {
            item { ObsidianCard { Text(stringResource(R.string.ag_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = Muted) } }
        } else {
            items(s.decisions, key = { it.id }) { DecisionCard(it, now) }
        }
    }
}

// ---- the agent: identity + the two rules that matter, full rules on request -------------------

private fun statusTone(s: AgentStatus): Color = when (s) {
    AgentStatus.ARMED -> Tone.Good
    AgentStatus.PAUSED -> Tone.Warn
    AgentStatus.HALTED -> Tone.Bad
}

private fun statusRes(s: AgentStatus) = when (s) {
    AgentStatus.ARMED -> R.string.ag_status_ARMED
    AgentStatus.PAUSED -> R.string.ag_status_PAUSED
    AgentStatus.HALTED -> R.string.ag_status_HALTED
}

@Composable
private fun AgentCard(m: MandateEntity, dec: Int, unit: String, now: Long) {
    var open by rememberSaveable { mutableStateOf(false) }
    fun whole(raw: String) = "${Fmt.whole(Raw.parse(raw), dec)} $unit"
    val spent = Raw.parse(m.spentTodayRaw)
    val limit = Raw.parse(m.dailyLimitRaw)
    val frac = if (limit.signum() <= 0) 0f else (spent.multiply(BigInteger.valueOf(1000)).divide(limit).toInt() / 1000f).coerceIn(0f, 1f)

    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(FusionTheme.surfaces.level3), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SmartToy, contentDescription = null, tint = Tone.Earning, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(m.agentLabel, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.ag_summary_rules, whole(m.immediateMaxRaw), whole(m.delayedMaxRaw)),
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
                StatusPill(label = stringResource(statusRes(m.status)), tone = statusTone(m.status))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.ag_used_line, Fmt.whole(spent, dec), whole(m.dailyLimitRaw)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(FusionTheme.surfaces.level3)) {
                if (frac > 0f) Box(Modifier.weight(frac).height(8.dp).background(if (frac > 0.8f) Tone.Warn else Tone.Earning))
                if (frac < 1f) Box(Modifier.weight(1f - frac).height(8.dp))
            }

            Expander(
                label = stringResource(if (open) R.string.ag_rules_hide else R.string.ag_rules_show),
                expanded = open, onToggle = { open = !open },
            ) {
                Column(Modifier.padding(top = 4.dp)) {
                    Rule(Tone.Good, stringResource(R.string.ag_rule_auto, whole(m.immediateMaxRaw)))
                    Rule(Tone.Warn, stringResource(R.string.ag_rule_delay, whole(m.delayedMaxRaw), durationText(m.delaySeconds.toLong())))
                    Rule(Tone.Earning, stringResource(R.string.ag_rule_owner, whole(m.delayedMaxRaw)))
                    Rule(Tone.Bad, stringResource(R.string.ag_rule_hard, whole(m.hardMaxRaw), whole(m.dailyLimitRaw)))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ag_session, durationText(m.sessionExpiresAt - now)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.ag_fingerprint), style = MaterialTheme.typography.bodySmall, color = Muted)
                        Text(Fmt.short(m.policyHash), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ag_pause)) }
                    Text(stringResource(R.string.ag_controls_note), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
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
private fun Rule(color: Color, text: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(text, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- decisions: a plain sentence and an outcome; details on tap -------------------------------

private fun outcomeTone(d: AgentDecisionEntity): Color = when (d.status) {
    DecisionStatus.BLOCKED -> Tone.Bad
    DecisionStatus.QUEUED -> Tone.Warn
    DecisionStatus.EXECUTED -> Tone.Good
    else -> Muted
}

private fun outcomeIcon(d: AgentDecisionEntity): ImageVector = when (d.status) {
    DecisionStatus.BLOCKED -> Icons.Filled.Block
    DecisionStatus.QUEUED -> Icons.Filled.HourglassTop
    DecisionStatus.EXECUTED -> Icons.Filled.CheckCircle
    else -> Icons.Filled.Remove
}

@Composable
private fun DecisionCard(d: AgentDecisionEntity, now: Long) {
    var open by rememberSaveable(d.id) { mutableStateOf(false) }
    val attack = d.origin == DecisionOrigin.ARENA_ATTACK
    val tone = outcomeTone(d)
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(d.createdAt * 1000))

    ObsidianCard {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(tone))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .semantics { role = Role.Button }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (attack) stringResource(R.string.ag_attack_title) else time,
                        style = MaterialTheme.typography.bodySmall, color = if (attack) Tone.Bad else Muted,
                        fontWeight = if (attack) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Muted)
                }
                Text(decisionSentence(d), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(outcomeIcon(d), contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
                    Text(outcomeText(d, time, now), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = tone, fontWeight = FontWeight.Medium)
                }

                if (open) {
                    Spacer(Modifier.height(12.dp))
                    if (d.rationale.isNotBlank()) {
                        Text(d.rationale, style = MaterialTheme.typography.bodyMedium, color = Muted, fontStyle = FontStyle.Italic)
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(stringResource(tierRes(d.tier)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.ag_by, d.chosenBy), style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.ag_checks_label), style = MaterialTheme.typography.bodySmall, color = Muted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Check(R.string.ag_chk_model, d.modelCheck)
                        Check(R.string.ag_chk_rules, d.rustCheck)
                        Check(R.string.ag_chk_signer, d.signerCheck)
                        Check(R.string.ag_chk_contract, d.contractCheck)
                    }
                    if (d.status == DecisionStatus.QUEUED) {
                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.padding(top = 10.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.ag_cancel)) }
                        Text(stringResource(R.string.ag_controls_note), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                    d.txHash?.let { Text(stringResource(R.string.ag_receipt, Fmt.short(it)), style = MonoStyle, color = Muted, modifier = Modifier.padding(top = 10.dp)) }
                }
            }
        }
    }
}

@Composable
private fun outcomeText(d: AgentDecisionEntity, time: String, now: Long): String = when (d.status) {
    DecisionStatus.EXECUTED -> stringResource(R.string.ag_out_EXECUTED, time)
    DecisionStatus.QUEUED -> {
        val left = (d.readyAt ?: now) - now
        if (left <= 0) stringResource(R.string.ag_out_READY) else stringResource(R.string.ag_out_QUEUED, Fmt.duration(left))
    }
    DecisionStatus.BLOCKED -> stringResource(R.string.ag_out_BLOCKED, layerName(d.blockedBy ?: "CONTRACT"))
    DecisionStatus.CANCELLED -> stringResource(R.string.ag_out_CANCELLED)
    DecisionStatus.EXPIRED -> stringResource(R.string.ag_out_EXPIRED)
    DecisionStatus.HELD -> stringResource(R.string.ag_out_HELD)
    DecisionStatus.PROPOSED -> stringResource(R.string.ag_out_PROPOSED)
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
private fun labelText(key: String): String = when (key) {
    "CASH" -> stringResource(R.string.lab_CASH)
    "AAVE" -> stringResource(R.string.lab_AAVE)
    "VAULT" -> stringResource(R.string.lab_VAULT)
    "UNKNOWN" -> stringResource(R.string.lab_UNKNOWN)
    else -> key
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

private fun tierRes(t: Tier) = when (t) {
    Tier.IMMEDIATE -> R.string.ag_tier_IMMEDIATE
    Tier.DELAYED -> R.string.ag_tier_DELAYED
    Tier.OWNER_REQUIRED -> R.string.ag_tier_OWNER_REQUIRED
}

/** One safety layer's verdict. State is never colour-only: a symbol and a spoken description too. */
@Composable
private fun Check(labelRes: Int, state: CheckState) {
    val (symbol, tone, descRes) = when (state) {
        CheckState.PASS -> Triple("✓", Tone.Good, R.string.ag_chk_pass)
        CheckState.FAIL -> Triple("✗", Tone.Bad, R.string.ag_chk_fail)
        CheckState.SKIPPED -> Triple("–", Muted, R.string.ag_chk_skipped)
    }
    val label = stringResource(labelRes)
    val desc = "$label: ${stringResource(descRes)}"
    Row(
        Modifier
            .clip(RoundedCornerShape(FusionDims.radiusSmall))
            .background(FusionTheme.surfaces.level2)
            .border(FusionDims.hairline, FusionTheme.surfaces.border, RoundedCornerShape(FusionDims.radiusSmall))
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, color = tone, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
