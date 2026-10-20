package app.fusion.wallet.agent.autopilot

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.fusion.wallet.ui.common.formatEpochSeconds
import app.fusion.wallet.ui.common.readableCheckMessage
import app.fusion.wallet.ui.common.readableCode
import app.fusion.wallet.ui.components.BentoTile
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.components.TxLink
import app.fusion.wallet.ui.advisor.WhyButton
import app.fusion.wallet.ui.advisor.toFacts
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType

@Composable
fun AutopilotCard(state: AutopilotState, controller: AutopilotController, nowMillis: Long, runtimeBusy: Boolean) {
    BentoTile(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Autopilot", style = FusionType.HeadingMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = controller::setEnabled, enabled = state.storageError == null,
                modifier = Modifier.semantics { contentDescription = "Autopilot" })
        }
        Text(autopilotStatus(state, nowMillis), style = FusionType.BodyMedium)
        Text(state.mode, style = FusionType.BodySmall, color = FusionColors.Amber)
        Text("Runs while this app is in the foreground. Each tick checks your rules before acting.", style = FusionType.BodySmall)
        Text("Tick interval", style = FusionType.BodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 15).forEach { minutes ->
                FilterChip(selected = state.intervalMinutes == minutes, onClick = { controller.setInterval(minutes) },
                    label = { Text("${minutes}m") }, enabled = state.storageError == null)
            }
        }
        state.timeline.firstOrNull { it.outcome != "RUNNING" }?.let { last ->
            Text("Last decision: ${last.letter} · ${last.title}", style = FusionType.BodyMedium)
            Text(readableCode(last.reason), style = FusionType.BodySmall)
        } ?: Text("No Autopilot ticks yet", style = FusionType.BodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = controller::runNow, enabled = state.foreground && !state.running && !runtimeBusy && state.storageError == null) { Text("Run now") }
            OutlinedButton(onClick = { controller.setEnabled(false) }, enabled = state.enabled || state.running) { Text("Stop", color = FusionColors.Red) }
        }
        if (state.running) Text("Stop cancels this tick and turns Autopilot off. A submitted transaction may still confirm.", style = FusionType.BodySmall)
        state.storageError?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Red) }
    }
}

@Composable
fun AutopilotTimelineTick(tick: AutopilotTick) {
    var expanded by rememberSaveable(tick.id) { mutableStateOf(false) }
    BentoTile(Modifier.fillMaxWidth()) {
        Text(formatEpochSeconds(tick.timeMillis / 1000), style = FusionType.BodySmall)
        Text("${tick.letter} · ${tick.title}", style = FusionType.HeadingSmall)
        StatusChip(when (tick.outcome) {
            "EXECUTED" -> "Executed · receipt verified"
            "PREVIEW" -> "Preview only · no transaction"
            "UNCONFIRMED" -> "Execution not confirmed"
            "RUNNING" -> "Running"
            else -> "Hold · no transaction requested"
        }, if (tick.outcome == "EXECUTED") FusionColors.Green else FusionColors.Amber)
        Text(readableCode(tick.reason), style = FusionType.BodyMedium)
        Text(tick.mode, style = FusionType.BodySmall)
        if (tick.checks.isNotEmpty()) {
            Text("Checks: ${tick.checks.count { it.status == "PASS" }} passed · ${tick.checks.count { it.status == "FAIL" }} failed", style = FusionType.BodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide checks" else "Show checks") }
        }
        if (expanded) tick.checks.forEach { check ->
            Text("${when (check.status) { "PASS" -> "✓ Passed"; "FAIL" -> "✕ Failed"; else -> "? Not measured" }} · ${readableCode(check.name)}",
                style = FusionType.BodySmall, color = if (check.status == "PASS") FusionColors.Green else FusionColors.Amber)
            if (check.message.isNotBlank()) Text(readableCheckMessage(check.message), style = FusionType.BodySmall)
        }
        tick.txHash?.takeIf { tick.outcome == "EXECUTED" }?.let { TxLink(it) }
        if (tick.outcome != "RUNNING") WhyButton("tick:${tick.id}", facts = { tick.toFacts() })
    }
}
