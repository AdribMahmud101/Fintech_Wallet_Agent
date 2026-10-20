package app.fusion.wallet.ui.advisor

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.BuildConfig
import app.fusion.wallet.advisor.AdvisorConfig
import app.fusion.wallet.advisor.AdvisorFixtures
import app.fusion.wallet.advisor.AdvisorResult
import app.fusion.wallet.advisor.DecisionFacts
import app.fusion.wallet.advisor.Effect
import app.fusion.wallet.advisor.PolicyProposal
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.common.startActivitySafe
import app.fusion.wallet.ui.onboarding.launchKeyImport
import app.fusion.wallet.ui.components.BentoTile
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType

/** "Why?" on any agent decision. Disabled-looking but harmless when the advisor is off. */
@Composable
fun WhyButton(id: String, facts: () -> DecisionFacts?, vm: AdvisorViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val call = s.explanations[id]
    Column {
        TextButton(onClick = { facts()?.let { vm.explain(id, it) } }, enabled = call?.running != true, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (call?.result is AdvisorResult.Ok) "Why? · ask again" else "Why?")
        }
        if (call != null) AdvisorOutcome(call) { e, model ->
            StatusChip(AdvisorConfig.label(model), FusionColors.Indigo)
            Text(e.text, style = FusionType.BodyMedium)
            e.points.forEach { Text("• $it", style = FusionType.BodySmall) }
            Text("Text only. The cloud model cannot sign or pick actions.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        }
    }
}

/** Running / off / blocked / failed / rejected / ok, each with plain wording. */
@Composable
fun <T> AdvisorOutcome(call: AdvisorCall<T>, content: @Composable (T, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (call.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Asking the cloud advisor…", style = FusionType.BodySmall)
            }
        } else when (val r = call.result) {
            null -> {}
            is AdvisorResult.Off -> Text(r.reason, style = FusionType.BodySmall, color = FusionColors.TextMuted)
            is AdvisorResult.Blocked -> Text("Not sent: ${r.reason}", style = FusionType.BodySmall, color = FusionColors.Amber)
            is AdvisorResult.Failed -> Text("Cloud advisor unavailable (${r.reason}) · ${r.model}", style = FusionType.BodySmall, color = FusionColors.Amber)
            is AdvisorResult.Rejected -> {
                Text("Reply rejected by the on-device validator · ${r.model}", style = FusionType.BodySmall, color = FusionColors.Amber)
                r.reasons.take(6).forEach { Text("• $it", style = FusionType.BodySmall, color = FusionColors.TextMuted) }
            }
            is AdvisorResult.Ok -> content(r.value, r.model)
        }
    }
}

/** Settings → "Cloud advisor (optional)". The key is pasted once, stored Keystore-encrypted, never shown again. */
@Composable
fun AdvisorSettingsSection(vm: AdvisorViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BentoTile(Modifier.fillMaxWidth(), tint = if (s.active) FusionColors.Indigo else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cloud advisor", style = FusionType.BodyMedium)
                Text(if (s.active) "On · ${s.settings.model}" else AdvisorConfig.OFF_LABEL, style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
            Switch(checked = s.settings.enabled, onCheckedChange = vm::setEnabled, enabled = s.hasKey)
        }
        Spacer(Modifier.height(8.dp))
        Text("Explains decisions, answers questions and drafts policy proposals. It never signs, never picks an action and " +
            "never receives keys. Proposals need both owners' signatures.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Spacer(Modifier.height(10.dp))
        if (s.hasKey) {
            StatusChip("DeepInfra key stored · Android Keystore", FusionColors.Green)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::clearKey) { Text("Remove key") }
        } else {
            OutlinedTextField(
                value = key, onValueChange = { key = it.take(300) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("DeepInfra API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
                if (BuildConfig.DEBUG) OutlinedButton(onClick = { launchKeyImport(context, ADVISOR_KEY_IMPORT_CLASS) }) { Text("Import (debug)") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Model", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdvisorConfig.MODELS.forEach { m ->
                FilterChip(selected = s.settings.model == m, onClick = { vm.setModel(m) },
                    label = { Text(if (m == AdvisorConfig.DEFAULT_MODEL) "$m (default)" else "$m (fallback)") })
            }
        }
        s.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = FusionType.BodySmall, color = FusionColors.Amber)
        }
    }
}

/** Ask the Accountant, grounded in the live wallet. Every figure must be cited from the data sent. */
@Composable
fun LiveAccountantSection(wallet: WalletState, vm: AdvisorViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var question by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ask about the live wallet", style = FusionType.HeadingSmall)
        Text(if (s.active) "Cloud advisor · ${s.settings.model} · sends balances, buckets, limits, bills and recent activity; no addresses or keys"
            else "${AdvisorConfig.OFF_LABEL}. Turn it on in Settings → Cloud advisor.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        OutlinedTextField(value = question, onValueChange = { question = it.take(AdvisorConfig.MAX_QUESTION_CHARS) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Question") }, supportingText = { Text("${question.length}/${AdvisorConfig.MAX_QUESTION_CHARS}") }, minLines = 2)
        Button(onClick = { vm.ask(wallet, question) }, enabled = !s.answer.running && question.isNotBlank()) { Text("Ask the Accountant") }
        AdvisorOutcome(s.answer) { a, model ->
            StatusChip("Cloud answer · $model", FusionColors.Indigo)
            Text(a.text, style = FusionType.BodyMedium)
            if (a.citations.isNotEmpty()) Text("Numbers used", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            a.citations.forEach { Text("${it.fact} = ${it.value}", style = FusionType.NumberCaption) }
            if (a.uncitedNumbers.isNotEmpty()) Text("Not found in the data sent: ${a.uncitedNumbers.joinToString(", ")}", style = FusionType.BodySmall, color = FusionColors.Amber)
            Text("Not investment advice. Testnet assets.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        }
    }
}

/** Goal → policy proposal → diff vs on-chain policy → the exact Safe transaction the owners sign. */
@Composable
fun PolicyProposalSection(wallet: WalletState, vm: AdvisorViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var goal by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Goal → policy proposal", style = FusionType.HeadingSmall)
        Text("Describe a goal. The advisor drafts a policy change; this phone validates it against the contract rules and " +
            "builds the Safe transaction. Only the two owners can apply it.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        OutlinedTextField(value = goal, onValueChange = { goal = it.take(AdvisorConfig.MAX_GOAL_CHARS) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Goal") }, placeholder = { Text("Keep 2,000 for rent, earn on the rest, slow down new payees") }, minLines = 2)
        Button(onClick = { vm.propose(wallet, goal) }, enabled = !s.proposal.running && goal.isNotBlank()) { Text("Draft proposal") }
        AdvisorOutcome(s.proposal) { p, model -> ProposalView(p, model) }
    }
}

@Composable
private fun ProposalView(p: PolicyProposal, model: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val payload = remember(p) { p.safeTx.toJson() }
    StatusChip(PolicyProposal.OWNER_LABEL, FusionColors.Red)
    StatusChip("Drafted by $model · validated on this device", FusionColors.Indigo)
    if (p.rationale.isNotBlank()) Text(p.rationale, style = FusionType.BodyMedium)
    Text("Change vs current on-chain policy", style = FusionType.BodySmall, color = FusionColors.TextMuted)
    p.diff.forEach { row ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${row.label}: ${row.current} → ${row.proposed}", style = FusionType.BodySmall, modifier = Modifier.weight(1f))
            StatusChip(when (row.effect) { Effect.LOOSER -> "Looser"; Effect.TIGHTER -> "Tighter"; Effect.NEUTRAL -> "Changed" },
                if (row.effect == Effect.LOOSER) FusionColors.Amber else FusionColors.Green)
        }
    }
    p.warnings.forEach { Text("⚠ $it", style = FusionType.BodySmall, color = FusionColors.Amber) }
    Text("Safe transaction (Safe 2-of-2 must sign)", style = FusionType.BodySmall, color = FusionColors.TextMuted)
    Text("to ${p.safeTx.to}", style = FusionType.NumberCaption)
    Text("value ${p.safeTx.value} · operation ${p.safeTx.operation} (CALL) · chain ${p.safeTx.chainId}", style = FusionType.NumberCaption)
    Text("data ${p.safeTx.data.take(74)}…", style = FusionType.NumberCaption)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(payload)) }) { Text("Copy payload") }
        OutlinedButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, payload)
            context.startActivitySafe(Intent.createChooser(send, "Share Safe transaction"))
        }) { Text("Share") }
    }
}

/** Safety → "Check external text": a zero-privilege classifier whose only output is bounded enums. */
@Composable
fun CheckExternalTextCard(vm: AdvisorViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    BentoTile(Modifier.fillMaxWidth()) {
        Text("Check external text", style = FusionType.HeadingMedium)
        Text("Paste a governance post, invoice memo, news item or payee message. The cloud model may only return a fixed " +
            "label set. The text never reaches the on-device planner or any signer; nothing executes.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = text, onValueChange = { text = it.take(AdvisorConfig.MAX_EXTERNAL_TEXT_CHARS) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("External text (untrusted)") }, minLines = 3, maxLines = 8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { text = AdvisorFixtures.INJECTION }) { Text("Load injection demo") }
            Button(onClick = { vm.classify(text) }, enabled = !s.risk.running && text.isNotBlank()) { Text("Classify") }
        }
        AdvisorOutcome(s.risk) { signal, model ->
            StatusChip("Validated label · $model", FusionColors.Green)
            Text(signal.toJson(), style = FusionType.NumberCaption)
            Text("Only these enum values reach the risk monitor. No transaction was requested.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        }
    }
}

/** Debug source set only; absent in release, where the launch falls through harmlessly. */
const val ADVISOR_KEY_IMPORT_CLASS = "app.fusion.wallet.advisor.AdvisorKeyImportActivity"
