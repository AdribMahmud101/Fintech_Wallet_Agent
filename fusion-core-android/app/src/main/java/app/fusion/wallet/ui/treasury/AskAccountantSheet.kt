package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fusion.wallet.R
import app.fusion.wallet.accountant.agent.CloudOutcome
import app.fusion.wallet.accountant.cloud.BriefLimits
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.advisor.LiveAccountantSection
import app.fusion.wallet.ui.advisor.PolicyProposalSection
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.severityColor
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.proof.PreviewCard
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.Obsidian

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskAccountantSheet(vm: AccountantViewModel, wallet: WalletState, onDismiss: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var topic by rememberSaveable { mutableStateOf(InsightTopic.GENERAL.name) }
    var question by rememberSaveable { mutableStateOf("") }
    val t = InsightTopic.valueOf(topic)
    val cloudOn = s.settings.cloudEnabled

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FusionDims.gutter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.acc_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                StatusPill(
                    label = stringResource(if (cloudOn) R.string.pill_cloud_on else R.string.pill_local_only),
                    tone = if (cloudOn) app.fusion.wallet.ui.common.Tone.Earning else app.fusion.wallet.ui.common.Tone.Good,
                )
            }
            Text(stringResource(if (cloudOn) R.string.acc_mode_cloud else R.string.acc_mode_local), style = MaterialTheme.typography.bodySmall, color = Muted)

            // K40: live-wallet Q&A and goal → policy proposal through the cloud advisor (off → "Advisor off").
            LiveAccountantSection(wallet)
            PolicyProposalSection(wallet)
            Text(stringResource(R.string.acc_offline_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightTopic.entries.forEach { e ->
                    FilterChip(
                        selected = e == t,
                        onClick = { topic = e.name; vm.updatePreview(question, e) },
                        label = { Text(topicLabel(e)) },
                    )
                }
            }
            OutlinedTextField(
                value = question,
                onValueChange = { if (it.length <= BriefLimits.MAX_QUESTION_CHARS) { question = it; vm.updatePreview(it, t) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.acc_question_label)) },
                supportingText = { Text("${question.length}/${BriefLimits.MAX_QUESTION_CHARS}") },
                minLines = 2,
            )
            Button(
                onClick = { vm.ask(t, question) },
                enabled = !s.asking,
                colors = ButtonDefaults.buttonColors(containerColor = Accessible.ButtonFill),
            ) {
                Text(stringResource(if (s.asking) R.string.acc_asking else if (cloudOn) R.string.acc_ask_cloud else R.string.acc_ask_local))
            }

            s.reply?.let { ReplyCard(it.cloud) }
            s.preview?.let { PreviewCard(it.json, it.allowed, it.blockedRules) }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun topicLabel(t: InsightTopic): String = stringResource(
    when (t) {
        InsightTopic.LIQUIDITY -> R.string.acc_topic_LIQUIDITY
        InsightTopic.RESERVES -> R.string.acc_topic_RESERVES
        InsightTopic.CONCENTRATION -> R.string.acc_topic_CONCENTRATION
        InsightTopic.YIELD -> R.string.acc_topic_YIELD
        InsightTopic.CASHFLOW -> R.string.acc_topic_CASHFLOW
        InsightTopic.OBLIGATIONS -> R.string.acc_topic_OBLIGATIONS
        InsightTopic.GENERAL -> R.string.acc_topic_GENERAL
    },
)

@Composable
private fun ReplyCard(outcome: CloudOutcome) {
    ObsidianCard(level = 2) {
        Column(Modifier.padding(16.dp)) {
            when (outcome) {
                is CloudOutcome.Ok -> {
                    CardTitle(stringResource(R.string.acc_reply_title), stringResource(R.string.acc_reply_tag))
                    Text(outcome.advice.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    outcome.advice.findings.forEach { f ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(severityColor(f.severity)))
                            Text("${topicLabelPlain(f.topic)}: ${f.text}", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (outcome.advice.followUps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        outcome.advice.followUps.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = Muted) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.acc_reply_disclaimer), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                CloudOutcome.NotEnabled -> Text(stringResource(R.string.acc_reply_off), style = MaterialTheme.typography.bodySmall, color = Muted)
                CloudOutcome.NoData -> Text(stringResource(R.string.acc_reply_nodata), style = MaterialTheme.typography.bodySmall, color = Muted)
                CloudOutcome.LimitReached -> Text(stringResource(R.string.acc_reply_limit), style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusWarning)
                is CloudOutcome.Blocked -> Text(stringResource(R.string.acc_reply_blocked, outcome.rules.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusWarning)
                is CloudOutcome.Unavailable -> Text(stringResource(R.string.acc_reply_unavailable, outcome.reason), style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusWarning)
            }
        }
    }
}

private fun topicLabelPlain(t: InsightTopic): String = t.name.lowercase().replaceFirstChar { it.uppercase() }
