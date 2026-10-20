package app.fusion.wallet.ui.scout

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.agent.AgentRuntime
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.scout.PoolScore
import app.fusion.wallet.scout.RuleResult
import app.fusion.wallet.scout.RuleStatus
import app.fusion.wallet.scout.ScoutContext
import app.fusion.wallet.scout.ScoutRules
import app.fusion.wallet.scout.ScoutViewModel
import app.fusion.wallet.scout.ScoutWhy
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.activity.rememberTxIndex
import app.fusion.wallet.ui.common.startActivitySafe
import app.fusion.wallet.ui.components.BentoTile
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.theme.FusionChart
import app.fusion.wallet.ui.theme.FusionChartMotion
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType
import app.fusion.wallet.ui.treasury.EarnPreviewSheet
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale

/** What the app itself measured: spare cash from the buckets, gas from the K44 report. */
internal fun scoutContext(state: WalletState): ScoutContext {
    val spare = state.buckets?.surplus?.raw?.let { BigDecimal(it).movePointLeft(6).toDouble() }
    val gas = state.quantReport?.let { runCatching { JSONObject(it).optString("gasUsdRaw").toBigDecimalOrNull()?.movePointLeft(6)?.toDouble() }.getOrNull() }
    return ScoutContext(spare, gas)
}

@Composable
private fun rankedScores(vm: ScoutViewModel, ctx: ScoutContext): List<PoolScore> {
    val ui by vm.state.collectAsStateWithLifecycle()
    return remember(ui.feed, ctx) { ui.feed?.let { f -> ScoutRules.rank(f.pools.map { ScoutRules.score(it, ctx) }) } ?: emptyList() }
}

private fun ageText(fetchedAtSec: Long, nowMs: Long): String {
    val m = ((nowMs / 1000 - fetchedAtSec) / 60).coerceAtLeast(0)
    return if (m < 1) "just now" else if (m < 60) "$m min ago" else "${m / 60} h ago"
}

private fun pretty(project: String) = project.split('-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** Home tile: the scout's current top pick, or an honest "not eligible" / loading state. */
@Composable
fun ScoutTile(state: WalletState, onOpen: () -> Unit, modifier: Modifier = Modifier, vm: ScoutViewModel = viewModel()) {
    val ctx = vm.stable(scoutContext(state))
    val scores = rankedScores(vm, ctx)
    val ui by vm.state.collectAsStateWithLifecycle()
    BentoTile(modifier.fillMaxWidth(), onClick = onOpen, tint = FusionColors.Indigo) {
        Text("Yield Scout", style = FusionType.HeadingSmall)
        val top = scores.firstOrNull()
        when {
            ui.loading && top == null -> Text("Screening open yield data…", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            top == null -> Text(ui.error ?: "No data yet", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            else -> {
                val eligible = scores.count { it.eligible }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (top.eligible) "Top pick: ${pretty(top.pool.project)}" else "No pool passes every rule",
                        style = FusionType.BodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    top.conservativeApy?.let { Text(ScoutRules.pct(it), style = FusionType.HeadingSmall, color = FusionColors.Green, maxLines = 1) }
                }
                Text("$eligible of ${scores.size} pools eligible · rule-checked, not advice", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoutScreen(vm: MainViewModel, onBack: () -> Unit, onOpenAgent: () -> Unit = {}, scout: ScoutViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ui by scout.state.collectAsStateWithLifecycle()
    val why by scout.why.collectAsStateWithLifecycle()
    val agent by AgentRuntime.state.collectAsStateWithLifecycle()
    val txIndex = rememberTxIndex()
    val ctx = scout.stable(scoutContext(state))
    val scores = rankedScores(scout, ctx)
    var onlyEligible by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = scores.firstOrNull { it.pool.id == selectedId }
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shown = remember(scores, onlyEligible) { if (onlyEligible) scores.filter { it.eligible } else scores }
    val top = scores.firstOrNull()?.takeIf { it.eligible }

    Box(Modifier.fillMaxSize().background(Color(0xFF080A0D))) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Yield Scout", style = FusionType.HeadingMedium)
                        Text("Where your spare cash could earn", style = FusionType.BodySmall, color = FusionColors.TextMuted)
                    }
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ui.feed?.let { f ->
                        StatusChip("DeFiLlama · ${ageText(f.fetchedAtSec, System.currentTimeMillis())}${if (f.fromCache) " · cached" else ""}",
                            if (f.fromCache) FusionColors.Amber else FusionColors.Green)
                    }
                    ctx.spareCashUsd?.let { StatusChip("Spare cash ${ScoutRules.usd(it)}", FusionChart.Muted) }
                    TextButton(onClick = scout::load) { Text(if (ui.loading) "Loading…" else "Refresh") }
                }
            }
            item { TestnetVenueCard(state, onPreview = { previewOpen = true; scope.launch { vm.runAgentTick(dryRun = true) } }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlyEligible, onClick = { onlyEligible = false }, label = { Text("All ${scores.size}") })
                    FilterChip(selected = onlyEligible, onClick = { onlyEligible = true }, label = { Text("Eligible ${scores.count { it.eligible }}") })
                }
            }
            if (ui.loading && scores.isEmpty()) item {
                BentoTile(Modifier.fillMaxWidth()) { Text("Screening open yield data…", style = FusionType.BodyMedium, color = FusionColors.TextMuted) }
            }
            if (!ui.loading && scores.isEmpty()) item {
                BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Amber) {
                    Text(ui.error ?: "No yield data available", style = FusionType.BodyMedium)
                    TextButton(onClick = scout::load) { Text("Try again") }
                }
            }
            items(shown, key = { it.pool.id }) { PoolCard(it, isTop = it.pool.id == top?.pool?.id) { selectedId = it.pool.id } }
            item {
                Text("Screening, not financial advice. Pool data is Base mainnet from DeFiLlama; this app runs on a test network and can only preview Aave on Base Sepolia.",
                    style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
        }
    }

    selected?.let { s ->
        PoolSheet(s, ctx, isTop = s.pool.id == top?.pool?.id, why = why, onWhy = { scout.explain(s, scores.filter { it.pool.id != s.pool.id }, ctx) },
            onPreview = { selectedId = null; scout.resetWhy(); previewOpen = true; scope.launch { vm.runAgentTick(dryRun = true) } },
            onInitiate = { selectedId = null; scout.resetWhy(); vm.autopilot.runNow(); onOpenAgent() },
            onDismiss = { selectedId = null; scout.resetWhy() })
    }
    if (previewOpen) EarnPreviewSheet(agent, state, txIndex, onDismiss = { previewOpen = false })
}

@Composable
private fun TestnetVenueCard(state: WalletState, onPreview: () -> Unit) {
    val q = state.quantReport?.let { runCatching { JSONObject(it) }.getOrNull() }
    BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Green) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Executable here: Aave v3 on Base Sepolia", style = FusionType.BodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            StatusChip("Test network", FusionColors.Green)
        }
        if (q == null) Text("Live Aave numbers not measured yet", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        else {
            val util = q.optInt("utilizationBps", -1).takeIf { it >= 0 }?.let { String.format(Locale.US, "%.1f%%", it / 100.0) } ?: "not measured"
            val apy = q.optLong("conservativeApyPpb", -1).takeIf { it >= 0 }?.let { ScoutRules.pct(it / 1e7) } ?: "not measured"
            val cand = q.optJSONArray("candidates")?.optJSONObject(0)
            val days = cand?.optString("breakEvenDaysHundredths")?.toDoubleOrNull()?.let { String.format(Locale.US, "%.1f", it / 100.0) }
            val size = cand?.optString("sizeRaw")?.toBigDecimalOrNull()?.movePointLeft(6)?.toInt()
            val need = cand?.optString("actionableSizeRaw")?.toBigDecimalOrNull()?.movePointLeft(6)?.setScale(0, java.math.RoundingMode.CEILING)?.toInt()
            Text("Utilization $util · conservative APY $apy", style = FusionType.BodySmall)
            if (days != null && size != null) Text("A $size USDC move breaks even in $days days" + (need?.let { " · pays off from about $it USDC" } ?: ""),
                style = FusionType.BodySmall, color = FusionColors.TextMuted)
        }
        TextButton(onClick = onPreview) { Text("Preview a move") }
    }
}

@Composable
private fun PoolCard(score: PoolScore, isTop: Boolean, onClick: () -> Unit) {
    val tone = if (score.eligible) FusionColors.Green else if (score.fails > 0) FusionColors.Red else FusionColors.Amber
    BentoTile(Modifier.fillMaxWidth(), onClick = onClick, tint = if (isTop) FusionColors.Green else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(FusionColors.SurfaceRaised), contentAlignment = Alignment.Center) {
                Text(score.pool.project.first().uppercase(), style = FusionType.BodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(pretty(score.pool.project), style = FusionType.BodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                Text("TVL ${ScoutRules.usdShort(score.pool.tvlUsd)}", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
            ScoreRing(score.passes, score.rules.size, tone)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(score.conservativeApy?.let(ScoutRules::pct) ?: "n/a", style = FusionType.HeadingMedium, color = tone)
                Text(buildString {
                    append("conservative APY")
                    score.pool.apy?.let { append(" · now ${ScoutRules.pct(it)}") }
                    score.pool.apyMean30d?.let { append(" · 30d ${ScoutRules.pct(it)}") }
                }, style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
            Spark(score.pool.spark, tone, Modifier.width(96.dp).height(36.dp))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(if (score.eligible) "Eligible" else if (score.fails > 0) "Not eligible" else "Not enough measured", tone)
            if (score.executableOnTestnet) StatusChip("Executable on testnet", FusionColors.Green)
            if (isTop) StatusChip("Top pick", FusionColors.Indigo)
        }
    }
}

@Composable
private fun ScoreRing(passes: Int, total: Int, color: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(passes, total) { progress.animateTo(if (total == 0) 0f else passes.toFloat() / total, tween(FusionChartMotion.EnterMillis)) }
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            val inset = 3.dp.toPx(); val sz = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(FusionChart.Track, 0f, 360f, false, topLeft = Offset(inset, inset), size = sz, style = stroke)
            drawArc(color, -90f, 360f * progress.value, false, topLeft = Offset(inset, inset), size = sz, style = stroke)
        }
        Text("$passes/$total", style = FusionType.BodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Spark(values: List<Double>, color: Color, modifier: Modifier) {
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { progress.snapTo(0f); progress.animateTo(1f, tween(FusionChartMotion.EnterMillis)) }
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min(); val hi = values.max(); val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height * (1f - ((v - lo) / span).toFloat()).coerceIn(0.05f, 0.95f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val measure = PathMeasure().also { it.setPath(path, false) }
        val part = Path().also { measure.getSegment(0f, measure.length * progress.value, it, true) }
        drawPath(part, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PoolSheet(
    score: PoolScore, ctx: ScoutContext, isTop: Boolean, why: ScoutWhy,
    onWhy: () -> Unit, onPreview: () -> Unit, onInitiate: () -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF111418)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(pretty(score.pool.project), style = FusionType.HeadingMedium)
            Text("${score.passes} of ${score.rules.size} rules passed · ${score.measured} measured", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            score.rules.forEach { RuleRow(it) }
            score.breakEvenDays?.let {
                Text("Pays for its gas in ${ScoutRules.daysText(it)} on ${ScoutRules.usd(ctx.spareCashUsd ?: 0.0)} spare cash (measured agent gas cost).",
                    style = FusionType.BodySmall)
            }
            if (isTop) {
                when (why) {
                    ScoutWhy.Idle -> TextButton(onClick = onWhy) { Text("Why this pick?") }
                    ScoutWhy.Loading -> Text("Asking the cloud advisor…", style = FusionType.BodySmall, color = FusionColors.TextMuted)
                    is ScoutWhy.Done -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(why.text, style = FusionType.BodySmall)
                        Text("Cloud explanation · ${why.model} · the ranking above is fixed code", style = FusionType.BodySmall, color = FusionColors.TextMuted)
                    }
                    is ScoutWhy.Unavailable -> Text("Explanation unavailable: ${why.reason}", style = FusionType.BodySmall, color = FusionColors.Amber)
                }
            }
            if (score.executableOnTestnet) {
                Button(onClick = onInitiate, modifier = Modifier.fillMaxWidth()) { Text("Ask the AI agent to act on this") }
                Text("The agent re-checks every rule and the on-chain limits first. It may hold, and the AI Agent timeline shows why. " +
                    "Without a session key on this phone it only previews.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
                TextButton(onClick = onPreview) { Text("Preview a move on testnet") }
            } else {
                Button(onClick = {
                    val text = "Yield proposal for the Safe owners (2-of-2)\n" +
                        "${pretty(score.pool.project)} on Base · conservative APY ${score.conservativeApy?.let(ScoutRules::pct) ?: "n/a"} · TVL ${ScoutRules.usdShort(score.pool.tvlUsd)}\n" +
                        "${score.passes} of ${score.rules.size} screening rules passed (${score.measured} measured). Data: DeFiLlama. Screening, not financial advice.\n" +
                        "Needs owner signatures; the AI agent cannot add a venue."
                    context.startActivitySafe(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share with owners"))
                }, modifier = Modifier.fillMaxWidth()) { Text("Share proposal with owners") }
                Text("Mainnet data · not available on the test network. Adding a venue needs owner signatures (Safe 2-of-2).",
                    style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
            TextButton(onClick = { context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://defillama.com/protocol/${score.pool.project}"))) }) {
                Text("Audits and details on DeFiLlama")
            }
            Text("Screening, not financial advice. Test network.", style = FusionType.BodySmall, color = FusionColors.TextMuted)
        }
    }
}

@Composable
private fun RuleRow(r: RuleResult) {
    val tone = when (r.status) { RuleStatus.PASS -> FusionColors.Green; RuleStatus.FAIL -> FusionColors.Red; RuleStatus.NOT_MEASURED -> FusionChart.Muted }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(tone))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = FusionType.BodyMedium, fontWeight = FontWeight.Medium)
            Text("${r.value} · needs ${r.threshold}", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            Text("Source: ${r.source}", style = FusionType.BodySmall, color = FusionChart.Muted)
        }
        Text(when (r.status) { RuleStatus.PASS -> "PASS"; RuleStatus.FAIL -> "FAIL"; RuleStatus.NOT_MEASURED -> "NOT MEASURED" }, style = FusionType.BodySmall, color = tone)
    }
}
