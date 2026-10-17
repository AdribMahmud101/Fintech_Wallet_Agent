package app.fusion.wallet.ui.accountant

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.accountant.agent.CloudOutcome
import app.fusion.wallet.accountant.agent.LocalFinding
import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.cloud.BriefLimits
import app.fusion.wallet.accountant.data.EgressLogEntity
import app.fusion.wallet.accountant.data.EgressStatus
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.data.LendingProtocol
import app.fusion.wallet.accountant.data.Severity
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.accountant.domain.Raw
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Muted = Accessible.TextMuted

@Composable
fun AccountantScreen(modifier: Modifier = Modifier, vm: AccountantViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var consentOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(cloudOn = s.settings.cloudEnabled) }
        s.message?.let { m -> item { Notice(m, onDismiss = vm::dismissMessage) } }

        val snap = s.snapshot
        if (snap == null) {
            item { EmptyState(loading = s.loading) }
        } else {
            item { BucketsCard(snap) }
            item { LendingCard(snap) }
            item { ObligationsCard(snap) }
            item { FindingsCard(s.local) }
        }

        item {
            AskCard(
                asking = s.asking,
                cloudOn = s.settings.cloudEnabled,
                onAsk = vm::ask,
                onDraft = vm::updatePreview,
            )
        }
        s.reply?.let { r -> item { ReplyCard(r.cloud) } }

        item {
            PrivacyCard(
                enabled = s.settings.cloudEnabled,
                hasKey = s.hasApiKey,
                model = s.settings.model,
                dailyLimit = s.settings.dailyCallLimit,
                onToggle = { on -> if (on) consentOpen = true else vm.revokeConsent() },
                onSaveKey = vm::saveApiKey,
                onClearKey = vm::clearApiKey,
                onModel = vm::setModel,
                onLimit = vm::setDailyLimit,
            )
        }
        s.preview?.let { p -> item { PreviewCard(p.json, p.allowed, p.blockedRules) } }
        item { EgressCard(s.egress) }
    }

    if (consentOpen) {
        ConsentDialog(
            onAccept = { consentOpen = false; vm.grantConsent() },
            onDismiss = { consentOpen = false },
        )
    }
}

// ---- header & small pieces ---------------------------------------------------------------------

@Composable
private fun Header(cloudOn: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("Accountant", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("Read-only analysis. It cannot move funds.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        StatusPill(label = if (cloudOn) "CLOUD ON" else "LOCAL ONLY", tone = if (cloudOn) Obsidian.AccentAiViolet else Obsidian.StatusSuccess)
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    ObsidianCard(level = 2) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean) {
    ObsidianCard {
        Text(
            if (loading) "Loading accounts…" else "No accounts synced yet. Balances, aTokens and obligations appear here once the wallet has read the chain.",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Muted,
        )
    }
}

@Composable
private fun CardTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Muted)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

private val money = DecimalFormat("#,##0.00")
private fun fmt(raw: BigInteger, dec: Int): String = money.format(BigDecimal(raw).movePointLeft(dec).setScale(2, RoundingMode.HALF_UP))
private fun bps(b: Int): String = "${b / 100}.${(b % 100).toString().padStart(2, '0')}%"

// ---- buckets -----------------------------------------------------------------------------------

@Composable
private fun BucketsCard(snap: PortfolioSnapshot) {
    val b = snap.buckets
    val dec = snap.unit.decimals
    val u = snap.unit.symbol
    val total = b.totalAssets
    fun w(x: BigInteger): Float = if (total.signum() <= 0) 0f else (x.multiply(BigInteger.valueOf(10_000)).divide(total).toInt() / 10_000f)

    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle("Total assets", u)
            Text("${fmt(total, dec)} $u", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(FusionTheme.surfaces.level3)) {
                listOf(w(b.free) to Obsidian.StatusSuccess, w(b.reserved) to Obsidian.StatusWarning, w(b.deployed) to Obsidian.AccentAiViolet)
                    .filter { it.first > 0f }
                    .forEach { (weight, color) -> Box(Modifier.weight(weight).height(10.dp).background(color)) }
            }
            Spacer(Modifier.height(12.dp))
            BucketRow("Free cash", fmt(b.free, dec), Obsidian.StatusSuccess, "of which sweepable ${fmt(b.sweepable, dec)}")
            BucketRow("Reserved for obligations", fmt(b.reserved, dec), Obsidian.StatusWarning, "target ${fmt(b.obligationTarget, dec)}")
            BucketRow("Deployed (lending)", fmt(b.deployed, dec), Obsidian.AccentAiViolet, "aTokens + vault shares")
            if (b.shortfall.signum() > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Shortfall ${fmt(b.shortfall, dec)} $u — obligations exceed cash. Nothing is swept.", style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusError)
            }
        }
    }
}

@Composable
private fun BucketRow(label: String, value: String, color: Color, note: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(note, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Text(value, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- lending / aTokens -------------------------------------------------------------------------

@Composable
private fun LendingCard(snap: PortfolioSnapshot) {
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle("Lending positions", "${snap.lending.size}")
            if (snap.lending.isEmpty()) Text("Nothing deployed.", style = MaterialTheme.typography.bodySmall, color = Muted)
            snap.lending.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.positionSymbol, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (l.protocol == LendingProtocol.AAVE_V3) "Aave V3 supply" else "ERC-4626 demo vault") + " · ${l.underlyingSymbol}",
                            style = MaterialTheme.typography.labelSmall, color = Muted,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt(l.supplied, l.decimals), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
                        Text("${bps(l.supplyApyBps)} APY", style = MaterialTheme.typography.labelSmall, color = Obsidian.StatusSuccess)
                    }
                }
            }
        }
    }
}

// ---- obligations -------------------------------------------------------------------------------

@Composable
private fun ObligationsCard(snap: PortfolioSnapshot) {
    val dec = snap.unit.decimals
    val groups = snap.obligations.groupBy { BriefBuilder.dueWindow(it.dueAt - snap.asOf) }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle("Upcoming obligations", fmt(snap.reserved, dec) + " " + snap.unit.symbol)
            if (groups.isEmpty()) Text("No active obligations.", style = MaterialTheme.typography.bodySmall, color = Muted)
            BriefLimits.DUE_WINDOWS.forEach { w ->
                val list = groups[w] ?: return@forEach
                val sum = list.fold(BigInteger.ZERO) { a, o -> a + Raw.rescale(o.amount, o.decimals, dec) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        (if (w == "OVERDUE") "Overdue" else "Due in $w") + " · ${list.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (w == "OVERDUE") Obsidian.StatusError else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(fmt(sum, dec), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ---- findings ----------------------------------------------------------------------------------

private fun sevColor(s: Severity) = when (s) {
    Severity.INFO -> Obsidian.AccentPrimary
    Severity.NOTICE -> Obsidian.StatusSuccess
    Severity.WARNING -> Obsidian.StatusWarning
    Severity.CRITICAL -> Obsidian.StatusError
}

@Composable
private fun FindingsCard(findings: List<LocalFinding>) {
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle("On-device analysis", "exact figures · never leaves the phone")
            findings.forEach { f ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(sevColor(f.severity)))
                    Text(f.text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ---- ask ---------------------------------------------------------------------------------------

@Composable
private fun AskCard(asking: Boolean, cloudOn: Boolean, onAsk: (InsightTopic, String) -> Unit, onDraft: (String?, InsightTopic) -> Unit) {
    var topic by rememberSaveable { mutableStateOf(InsightTopic.GENERAL.name) }
    var question by rememberSaveable { mutableStateOf("") }
    val t = InsightTopic.valueOf(topic)
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle("Ask the Accountant", if (cloudOn) "DeepInfra · coarse brief only" else "cloud off")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightTopic.entries.forEach { e ->
                    FilterChip(selected = e == t, onClick = { topic = e.name; onDraft(question, e) }, label = { Text(e.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = question,
                onValueChange = { if (it.length <= BriefLimits.MAX_QUESTION_CHARS) { question = it; onDraft(it, t) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Question (no names, addresses or account numbers)") },
                supportingText = { Text("${question.length}/${BriefLimits.MAX_QUESTION_CHARS}") },
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onAsk(t, question) },
                enabled = !asking,
                colors = ButtonDefaults.buttonColors(containerColor = Accessible.ButtonFill),
            ) { Text(if (asking) "Analysing…" else if (cloudOn) "Ask (cloud + on-device)" else "Analyse on device") }
        }
    }
}

@Composable
private fun ReplyCard(outcome: CloudOutcome) {
    ObsidianCard(level = 2) {
        Column(Modifier.padding(16.dp)) {
            when (outcome) {
                is CloudOutcome.Ok -> {
                    CardTitle("Cloud Accountant", "AI-generated · advisory only")
                    Text(outcome.advice.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    outcome.advice.findings.forEach { f ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(sevColor(f.severity)))
                            Text("${f.topic.name.lowercase().replaceFirstChar { it.uppercase() }}: ${f.text}", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (outcome.advice.followUps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        outcome.advice.followUps.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = Muted) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Suggestions are not instructions. Nothing here can send or approve funds.", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                CloudOutcome.NotEnabled -> Text("Cloud analysis is off. Showing on-device analysis only.", style = MaterialTheme.typography.bodySmall, color = Muted)
                CloudOutcome.NoData -> Text("No data to analyse yet.", style = MaterialTheme.typography.bodySmall, color = Muted)
                CloudOutcome.LimitReached -> Text("Daily cloud limit reached. On-device analysis is still available.", style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusWarning)
                is CloudOutcome.Blocked -> Text(
                    "Blocked before sending: your question or data matched a privacy rule (${outcome.rules.joinToString(", ")}). Remove names, addresses, links or long numbers and try again. Nothing left the device.",
                    style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusWarning,
                )
                is CloudOutcome.Unavailable -> Text("Cloud analysis unavailable (${outcome.reason}). On-device analysis above is unaffected.", style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusWarning)
            }
        }
    }
}

// ---- privacy -----------------------------------------------------------------------------------

@Composable
private fun PrivacyCard(
    enabled: Boolean, hasKey: Boolean, model: String, dailyLimit: Int,
    onToggle: (Boolean) -> Unit, onSaveKey: (String) -> Unit, onClearKey: () -> Unit, onModel: (String) -> Unit, onLimit: (Int) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var modelText by rememberSaveable(model) { mutableStateOf(model) }
    var limitText by rememberSaveable(dailyLimit) { mutableStateOf(dailyLimit.toString()) }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle("Cloud analysis · DeepInfra")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share a coarse summary with the cloud Accountant", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("Off by default. Rounded totals and percentages only — no addresses, names, invoices or exact balances.", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (hasKey) "API key stored (enter to replace)" else "DeepInfra API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSaveKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
                TextButton(onClick = onClearKey, enabled = hasKey) { Text("Remove key") }
            }
            OutlinedTextField(
                value = modelText, onValueChange = { modelText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Model") },
                supportingText = { Text("google/ and anthropic/ models are refused: those providers retain prompts.") },
            )
            TextButton(onClick = { onModel(modelText) }, enabled = modelText.trim() != model) { Text("Apply model") }
            OutlinedTextField(
                value = limitText, onValueChange = { limitText = it.filter(Char::isDigit).take(3) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Max cloud calls per 24 h") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(onClick = { limitText.toIntOrNull()?.let(onLimit) }, enabled = limitText.toIntOrNull() != null && limitText.toIntOrNull() != dailyLimit) { Text("Apply limit") }
        }
    }
}

@Composable
private fun PreviewCard(json: String, allowed: Boolean, rules: List<String>) {
    var open by rememberSaveable { mutableStateOf(false) }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CardTitle("What the cloud would see")
                StatusPill(label = if (allowed) "GUARD OK" else "WOULD BLOCK", tone = if (allowed) Obsidian.StatusSuccess else Obsidian.StatusError)
            }
            Text("This exact JSON is the only thing that can leave the phone. ${json.length} bytes.", style = MaterialTheme.typography.labelSmall, color = Muted)
            if (!allowed) Text("Rules: ${rules.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = Obsidian.StatusError)
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide payload" else "Show payload") }
            if (open) Text(json, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun EgressCard(rows: List<EgressLogEntity>) {
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle("Cloud activity log", "${rows.size} recent")
            if (rows.isEmpty()) Text("Nothing has ever left this device.", style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusSuccess)
            rows.take(10).forEach { r -> EgressRow(r) }
        }
    }
}

@Composable
private fun EgressRow(r: EgressLogEntity) {
    var open by remember { mutableStateOf(false) }
    val tone = when (r.status) {
        EgressStatus.SENT_OK -> Obsidian.StatusSuccess
        EgressStatus.BLOCKED_BY_GUARD, EgressStatus.REJECTED_REPLY -> Obsidian.StatusWarning
        EgressStatus.FAILED -> Obsidian.StatusError
        else -> Muted
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(r.createdAt * 1000)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text("${r.model.substringAfterLast('/')} · ${r.payloadBytes} B" + (r.promptTokens?.let { " · $it→${r.completionTokens ?: 0} tok" } ?: ""), style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            StatusPill(label = r.status.name, tone = tone)
        }
        if (r.detail.isNotEmpty()) Text(r.detail, style = MaterialTheme.typography.labelSmall, color = Muted)
        if (r.payloadJson.isNotEmpty()) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide sent payload" else "Show sent payload") }
            if (open) Text(r.payloadJson, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ---- consent -----------------------------------------------------------------------------------

@Composable
private fun ConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share a coarse summary with the cloud?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WHAT IS SENT (to DeepInfra, only when you tap Ask):", style = MaterialTheme.typography.labelSmall, color = Obsidian.StatusSuccess)
                Text("• Total assets rounded to 2 significant figures\n• Free / reserved / deployed as whole percentages\n• Lending lines: protocol, asset symbol, share %, APY\n• Obligations grouped by due window: count + rounded total\n• Last 30 days of cash flow by fixed category: rounded totals\n• Days of runway, and the question you type", style = MaterialTheme.typography.bodySmall)
                Text("WHAT IS NEVER SENT:", style = MaterialTheme.typography.labelSmall, color = Obsidian.StatusError)
                Text("• Private keys, seed phrases, your API key\n• Wallet or contract addresses, transaction hashes\n• Invoice IDs, counterparty names, memos, category labels\n• Exact balances or amounts, exact dates\n• Anything the privacy filter does not recognise", style = MaterialTheme.typography.bodySmall)
                Text("The cloud model is read-only: it returns advice text only and cannot move or approve funds. You can inspect every payload in the activity log, and turn this off at any time. DeepInfra says it does not store prompts or outputs; Google and Anthropic models are blocked because those providers do.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("I understand — enable") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep local only") } },
    )
}
