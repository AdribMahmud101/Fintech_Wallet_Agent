package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fusion.wallet.agent.AgentRunState
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.data.TxIndex
import app.fusion.wallet.ui.agent.DecisionDetails
import app.fusion.wallet.ui.common.readableCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarnPreviewSheet(agent: AgentRunState, state: WalletState, txIndex: TxIndex, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Preview next move", style = MaterialTheme.typography.headlineSmall)
            Text("Dry run · no transaction is broadcast")
            when {
                agent.running -> { CircularProgressIndicator(); Text("Reading live policy and asking the on-device AI…") }
                agent.error != null -> Text("Preview unavailable: ${readableCode(agent.error)}")
                agent.result?.dryRun == true -> {
                    val result = agent.result
                    Text(runCatching { earnPreviewSummary(result, state, txIndex) }.getOrDefault("Decision details unavailable"))
                    Text("Policy checks · snapshot block ${result.snapshotBlock}", style = MaterialTheme.typography.titleSmall)
                    val details = runCatching { DecisionDetails.parse(result.reportJson, result.passportJson) }.getOrNull()
                    if (details?.checks.isNullOrEmpty()) Text("No individual policy checks recorded")
                    details?.checks?.forEach { check ->
                        Text("${readableCode(check.status)} · ${readableCode(check.name)}")
                        if (check.message.isNotBlank()) Text(readableCode(check.message), style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> Text("No dry-run decision available")
            }
        }
    }
}
