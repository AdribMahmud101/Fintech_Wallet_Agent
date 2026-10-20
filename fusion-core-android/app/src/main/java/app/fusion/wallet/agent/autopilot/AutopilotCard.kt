package app.fusion.wallet.agent.autopilot

import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.fusion.wallet.ui.common.formatEpochSeconds
import app.fusion.wallet.ui.common.readableCode
import app.fusion.wallet.ui.components.BentoTile
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
        Text(state.mode, style = FusionType.BodySmall, color = FusionColors.Amber, maxLines = 2, overflow = TextOverflow.Ellipsis)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 15).forEach { minutes ->
                FilterChip(selected = state.intervalMinutes == minutes, onClick = { controller.setInterval(minutes) },
                    label = { Text("${minutes}m") }, enabled = state.storageError == null)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.previewOnly || !state.liveAvailable) "Dry run" else "Live", style = FusionType.BodySmall, modifier = Modifier.weight(1f))
            Switch(checked = !state.previewOnly && state.liveAvailable, onCheckedChange = { controller.setPreviewOnly(!it) }, enabled = state.liveAvailable && !state.running && !runtimeBusy, modifier = Modifier.semantics { contentDescription = "Live execution mode" })
            Text("Auto-protect", style = FusionType.BodySmall)
            Switch(checked = state.autoProtect, onCheckedChange = controller::setAutoProtect, modifier = Modifier.semantics { contentDescription = "Auto-protect" })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = controller::runNow, enabled = state.foreground && !state.running && !runtimeBusy && state.storageError == null) { Text("Run now") }
            OutlinedButton(onClick = { controller.setEnabled(false) }, enabled = state.enabled || state.running) { Text("Stop", color = FusionColors.Red) }
        }
        if (state.running) Text("Stop cancels this tick and turns Autopilot off. A submitted transaction may still confirm.", style = FusionType.BodySmall)
        state.storageError?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Red) }
    }
}

@Composable
fun AutopilotTimelineTick(tick: AutopilotTick, onClick: () -> Unit = {}) {
    BentoTile(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Text("${formatEpochSeconds(tick.timeMillis / 1000)} · ${tick.wakePath}", style = FusionType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${tick.letter} · ${tick.title} · ${when (tick.outcome) {
            "EXECUTED" -> "Confirmed"; "PREVIEW" -> "Preview"; "PROTECTED" -> "Protected"; "RUNNING" -> "Running"; "UNCONFIRMED" -> "Unconfirmed"; else -> "Held"
        }}", style = FusionType.BodyMedium)
        Text(readableCode(tick.reason), style = FusionType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (tick.outcome != "RUNNING") WhyButton("tick:${tick.id}", facts = { tick.toFacts() })
    }
}
