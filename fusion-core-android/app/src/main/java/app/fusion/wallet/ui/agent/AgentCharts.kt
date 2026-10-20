package app.fusion.wallet.ui.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fusion.wallet.data.*
import app.fusion.wallet.ui.charts.*
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigInteger

@Composable
fun DecisionMixTile(state: WalletState, txIndex: TxIndex, activeReport: String?) {
    val context=LocalContext.current
    val saved by produceState<String?>(null,activeReport) {
        value=activeReport ?: withContext(Dispatchers.IO) { runCatching { File(context.filesDir,"agent/last_tick.json").readText() }.getOrNull() }
    }
    val actions = state.chartEvidence?.actions
    val counts = actions?.let { decisionMix(it, txIndex, saved) }
    ChartTile(Modifier.fillMaxWidth()) {
        ChartTileHeader("Decision mix", buildString {
            append("AGENT + APP indexed executions and the latest saved live Hold only. Dry runs and rebalances excluded; a complete Hold history is unavailable.")
            counts?.let { append("\n\n" + it.entries.joinToString(" · ") { row -> "${row.key} ${row.value}" }) }
        })
        if (counts == null) Text("Decision history unavailable until the event index loads", style = FusionType.BodySmall)
        else {
            val colors = listOf(FusionChart.Reserved, FusionChart.Cash, FusionChart.Sky, FusionChart.Muted)
            val total = counts.values.fold(BigInteger.ZERO, BigInteger::add)
            DonutChart(counts.entries.mapIndexed { i, row -> Segment(row.key, row.value, colors[i]) },
                total.toString(), "decisions", valueLabel = { "$it decisions" })
        }
    }
}

@Composable
fun DailyLimitRing(module: ModuleState) {
    if (module.policy.dailyLimit.raw.signum()<=0) return
    val fraction=gaugeFraction(module.dailyUsed.raw.toBigDecimal(),module.policy.dailyLimit.raw.toBigDecimal())
    RingGauge(fraction.toFloat(),FusionColors.Indigo,148.dp,12.dp,
        "Daily limit used: ${module.dailyUsed.display} / ${module.policy.dailyLimit.display} USDC. ${module.dailyRemaining.display} USDC remaining") {
        Text("${fraction.multiply(100.toBigDecimal()).setScale(0,java.math.RoundingMode.HALF_UP)} %",style=FusionType.HeadingMedium)
    }
}
