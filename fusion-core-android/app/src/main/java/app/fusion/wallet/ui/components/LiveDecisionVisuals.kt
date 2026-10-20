package app.fusion.wallet.ui.components

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fusion.wallet.agent.*
import app.fusion.wallet.ui.agent.DecisionCheck
import app.fusion.wallet.ui.common.readableCode
import app.fusion.wallet.ui.common.readableCheckMessage
import app.fusion.wallet.ui.motion.*
import app.fusion.wallet.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun DecisionPipelineStrip(pipeline: DecisionPipeline) {
    val motion = rememberMotionEnabled()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            DecisionStage.entries.forEach { stage ->
                val step = pipeline.steps.firstOrNull { it.stage == stage }
                val active = pipeline.active == stage
                val targetTone = when { pipeline.error != null && step != null && step == pipeline.steps.lastOrNull() -> FusionColors.Red; active -> FusionColors.Indigo; step != null -> FusionColors.Green; else -> FusionColors.TextMuted }
                val tone by animateColorAsState(targetTone, tween(if (motion) 180 else 0), label = "Reached pipeline stage")
                Column(Modifier.weight(1f).background(FusionColors.SurfaceRaised, RoundedCornerShape(8.dp)).padding(horizontal = 2.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (active) "●" else if (step != null) "✓" else "${stage.ordinal + 1}", color = tone, fontSize = 12.sp)
                    // Short one-word labels so nothing breaks mid-word; the full name is the accessibility label.
                    val short = when (stage) { DecisionStage.SNAPSHOT -> "Read"; DecisionStage.CANDIDATES -> "Options"; DecisionStage.AI -> "AI"
                        DecisionStage.POLICY -> "Rules"; DecisionStage.SIGNER -> "Sign"; DecisionStage.CHAIN -> "Chain" }
                    Text(short, color = tone, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.semantics { contentDescription = stage.title })
                    Text(step?.durationMs?.let { "${it}ms" } ?: if (active) "Running" else "—", color = tone, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
        val timings = listOfNotNull(pipeline.loadMs?.let { "Load ${it.toLong()}ms" }, pipeline.inferenceMs?.let { "Inference ${it.toLong()}ms" })
        if (timings.isNotEmpty()) Text(timings.joinToString(" · "), style = FusionType.NumberCaption)
        if (pipeline.dryRun == true && pipeline.active == null && pipeline.steps.any { it.stage == DecisionStage.POLICY })
            StatusChip("Preview · no broadcast", FusionColors.Indigo)
        pipeline.error?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Red, maxLines = 2) }
        if (pipeline.steps.isEmpty()) Text("Run a tick to see its measured stages", style = FusionType.BodySmall)
    }
}

/** A presentation reveal of already recorded results; never pretends checks execute serially. */
@Composable
fun PolicyChecksReveal(checks: List<DecisionCheck>, message: (DecisionCheck) -> String = { readableCheckMessage(it.message) }) {
    val motion = rememberMotionEnabled()
    var revealed by remember(checks) { mutableIntStateOf(if (motion) 0 else checks.size) }
    LaunchedEffect(checks, motion) {
        if (!motion) revealed = checks.size else {
            revealed = 0
            checks.indices.forEach { index ->
                if (index > 0) delay(MotionPolicy.revealDelayMillis(1, true))
                revealed = index + 1
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Recorded policy results", style = FusionType.BodySmall)
        checks.forEachIndexed { index, check ->
            val visible = !motion || index < revealed
            val flip = remember { Animatable(if (visible) 1f else 0f) }
            LaunchedEffect(visible, motion) { if (motion) flip.animateTo(if (visible) 1f else 0f, tween(150)) else flip.snapTo(1f) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (visible) check.status else "…", color = when { !visible -> FusionColors.TextMuted; check.status == "PASS" -> FusionColors.Green; check.status == "FAIL" -> FusionColors.Red; else -> FusionColors.Amber },
                    style = FusionType.NumberCaption, modifier = Modifier.width(52.dp).graphicsLayer { rotationX = if (motion) (1f - flip.value) * 75f else 0f })
                Column(Modifier.weight(1f)) {
                    Text(readableCode(check.name), style = FusionType.BodySmall)
                    if (visible && check.message.isNotBlank()) Text(message(check), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                }
            }
        }
        if ((!motion || revealed == checks.size) && checks.isNotEmpty()) {
            val passed = checks.count { it.status == "PASS" }
            StatusChip("$passed/${checks.size} passed", if (passed == checks.size) FusionColors.Green else FusionColors.Amber)
        }
    }
}

@Composable
fun ExecutionSuccess(txHash: String, explorerUrl: String = "https://sepolia.basescan.org/tx/$txHash") {
    val pulse = eventPulse(txHash)
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - pulse * 0.15f }.background(FusionColors.SurfaceRaised, RoundedCornerShape(12.dp)).padding(10.dp)) {
        StatusChip("✓ Execution confirmed", FusionColors.Green)
        Text(txHash, style = FusionType.NumberCaption, maxLines = 2)
        TxLink(txHash, label = "Verified on BaseScan", explorerUrl = explorerUrl)
    }
}

/** Countdown is a fraction of the controller's actual scheduled interval, never an estimate. */
@Composable
fun AutopilotCountdown(nextAt: Long?, intervalMinutes: Int, now: Long, enabled: Boolean) {
    if (!enabled || nextAt == null) return
    val left = (nextAt - now).coerceAtLeast(0)
    val fraction = (left.toDouble() / (intervalMinutes.coerceAtLeast(1) * 60_000L)).coerceIn(0.0, 1.0).toFloat()
    val color = FusionColors.Indigo
    val track = FusionColors.Border
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(30.dp)) {
            drawCircle(track, style = Stroke(3.dp.toPx()))
            drawArc(color, -90f, fraction * 360f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("Next tick · ${(left + 999) / 1000}s", style = FusionType.BodySmall)
    }
}

/** Arc resolves into a check only when the canonical receipt lookup actually succeeds. */
@Composable
fun ReceiptVerificationMark(loading: Boolean, confirmed: Boolean) {
    val motion = rememberMotionEnabled()
    val turn = remember { Animatable(0f) }
    val morph = remember { Animatable(0f) }
    LaunchedEffect(loading, motion) {
        turn.snapTo(0f)
        if (loading && motion) while (true) { turn.animateTo(360f, tween(800)); turn.snapTo(0f) }
    }
    LaunchedEffect(loading, confirmed, motion) {
        if (!motion) morph.snapTo(if (!loading && confirmed) 1f else 0f)
        else morph.animateTo(if (!loading && confirmed) 1f else 0f, tween(220))
    }
    val progress = if (motion) morph.value else if (!loading && confirmed) 1f else 0f
    val tone = if (confirmed && !loading) FusionColors.Green else FusionColors.Indigo
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(36.dp)) {
            val stroke = 3.dp.toPx()
            if (progress < 1f) drawArc(tone, turn.value - 90f, (if (loading) 260f else 360f) * (1f - progress), false, style = Stroke(stroke, cap = StrokeCap.Round))
            if (progress > 0f) {
                val a = androidx.compose.ui.geometry.Offset(size.width * .2f, size.height * .52f)
                val b = androidx.compose.ui.geometry.Offset(size.width * .42f, size.height * .73f)
                val c = androidx.compose.ui.geometry.Offset(size.width * .82f, size.height * .28f)
                val first = (progress * 2f).coerceAtMost(1f)
                drawLine(tone, a, a + (b - a) * first, stroke, StrokeCap.Round)
                if (progress > .5f) drawLine(tone, b, b + (c - b) * ((progress - .5f) * 2f), stroke, StrokeCap.Round)
            }
        }
        Text(if (loading) "Checking real receipt…" else if (confirmed) "Receipt verified" else "Receipt not confirmed", style = FusionType.BodyMedium)
    }
}
