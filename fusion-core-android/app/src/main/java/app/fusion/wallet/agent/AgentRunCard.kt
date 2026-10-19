package app.fusion.wallet.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fusion.wallet.ui.common.BentoTile
import kotlinx.coroutines.launch

@Composable
fun AgentRunCard(runTick: suspend (Boolean) -> Result<AgentTickResult>) {
    val state by AgentRuntime.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BentoTile(Modifier.fillMaxWidth(), padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI in this wallet", style = MaterialTheme.typography.titleMedium)
            Text("LFM2.5 · local Android inference · AHEM-G", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { scope.launch { runTick(true) } }, enabled = !state.running) {
                if (state.running) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (state.running) "Deciding…" else "Run agent now")
            }
            Text(if (state.result?.dryRun == false) "Agent tick under the session lock" else "Dry run · previews live state without broadcasting", style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.result?.let { r ->
                Text("${r.choiceLabel} · ${r.reason} · ${r.route}", style = MaterialTheme.typography.titleSmall)
                Text("Block ${r.snapshotBlock} · ${r.decodeMs.toLong()} ms · ${r.vetoes} vetoes", style = MaterialTheme.typography.bodySmall)
                Text(if (r.choiceLabel == "H") "HOLD · no transaction" else if (r.previewPassed) "Policy preview passed" else "Policy preview blocked execution", style = MaterialTheme.typography.bodySmall)
                Text("Passport ${r.decisionHash.take(18)}…", style = MaterialTheme.typography.bodySmall)
                r.txHash?.let { Text("Confirmed $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
