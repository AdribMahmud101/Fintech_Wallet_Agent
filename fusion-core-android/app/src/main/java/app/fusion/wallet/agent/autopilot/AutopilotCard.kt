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
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.agent.DecisionPipeline
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import app.fusion.wallet.ui.motion.rememberMotionEnabled
import app.fusion.wallet.ui.advisor.WhyButton
import app.fusion.wallet.ui.advisor.toFacts
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType

@Composable
fun AutopilotCard(state: AutopilotState, controller: AutopilotController, nowMillis: Long, runtimeBusy: Boolean, pipeline: DecisionPipeline = DecisionPipeline()) {
    BentoTile(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Autopilot", style = FusionType.HeadingMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = controller::setEnabled, enabled = state.storageError == null,
                modifier = Modifier.semantics { contentDescription = "Autopilot" })
        }
        Text(autopilotStatus(state, nowMillis), style = FusionType.BodyMedium)
        AutopilotCountdown(state.nextTickAtMillis, state.intervalMinutes, nowMillis, state.enabled && !state.running)
        DecisionPipelineStrip(pipeline)
        Text(state.mode, style = FusionType.BodySmall, color = FusionColors.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 15).forEach { minutes ->
                FilterChip(selected = state.intervalMinutes == minutes, onClick = { controller.setInterval(minutes) },
                    label = { Text("${minutes}m") }, enabled = state.storageError == null)
            }
        }
        // One setting per row: label and explanation on the left, its switch on the right.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (state.previewOnly || !state.liveAvailable) "Dry run" else "Live", style = FusionType.BodyMedium)
                Text(if (state.previewOnly || !state.liveAvailable) "Preview only, nothing is broadcast" else "The agent may execute within its limits",
                    style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
            Switch(checked = !state.previewOnly && state.liveAvailable, onCheckedChange = { controller.setPreviewOnly(!it) }, enabled = state.liveAvailable && !state.running && !runtimeBusy, modifier = Modifier.semantics { contentDescription = "Live execution" })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-protect", style = FusionType.BodyMedium)
                Text("Pause on a critical risk signal", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
            Switch(checked = state.autoProtect, onCheckedChange = controller::setAutoProtect, modifier = Modifier.semantics { contentDescription = "Auto-protect" })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = controller::runNow, enabled = state.foreground && !state.running && !runtimeBusy && state.storageError == null,
                modifier = Modifier.weight(1f).height(44.dp)) { Text("Run now", maxLines = 1) }
            OutlinedButton(onClick = { controller.setEnabled(false) }, enabled = state.enabled || state.running,
                modifier = Modifier.weight(1f).height(44.dp)) { Text("Stop", color = FusionColors.Red, maxLines = 1) }
        }
        if (state.running) Text("Stop cancels this tick and turns Autopilot off. A submitted transaction may still confirm.", style = FusionType.BodySmall)
        state.storageError?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Red) }
    }
}

@Composable
fun AutopilotTimelineTick(tick: AutopilotTick, showExecution: Boolean = false, onClick: () -> Unit = {}) {
    val motion = rememberMotionEnabled()
    BentoTile(Modifier.fillMaxWidth().animateContentSize(tween(if (motion) 220 else 0)), onClick = onClick, padding = 12.dp) {
        Text("${formatEpochSeconds(tick.timeMillis / 1000)} · ${tick.wakePath}", style = FusionType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${tick.letter} · ${tick.title} · ${when (tick.outcome) {
            "EXECUTED" -> "Confirmed"; "PREVIEW" -> "Preview"; "PROTECTED" -> "Protected"; "RUNNING" -> "Running"; "UNCONFIRMED" -> "Unconfirmed"; else -> "Held"
        }}", style = FusionType.BodyMedium)
        Text(readableCode(tick.reason), style = FusionType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (showExecution && tick.outcome == "EXECUTED" && tick.txHash != null) ExecutionSuccess(tick.txHash)
        if (tick.outcome != "RUNNING") WhyButton("tick:${tick.id}", facts = { tick.toFacts() })
    }
}
