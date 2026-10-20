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
    BentoTile(Modifier.fillMaxWidth()) {
        Text("Decision mix",style=FusionType.HeadingSmall)
        val actions=state.chartEvidence?.actions
        if (actions==null) Text("Decision history unavailable until the event index loads",style=FusionType.BodySmall)
        else {
            val counts=decisionMix(actions,txIndex,saved)
            val colors=listOf(FusionColors.Amber,FusionColors.Green,FusionColors.Indigo,FusionColors.TextMuted)
            val total=counts.values.fold(BigInteger.ZERO,BigInteger::add)
            DonutChart(counts.entries.mapIndexed { i,row->Segment(row.key,row.value,colors[i]) },total.toString(),"recorded decisions")
            Text(counts.entries.joinToString(" · ") { "${it.key} ${it.value}" },style=FusionType.BodySmall)
            Text("AGENT + APP indexed executions and the latest saved live Hold only. Dry runs and rebalances excluded; a complete Hold history is unavailable.",style=FusionType.BodySmall)
        }
    }
}

@Composable
fun DailyLimitRing(module: ModuleState) {
    if (module.policy.dailyLimit.raw.signum()<=0) return
    val fraction=gaugeFraction(module.dailyUsed.raw.toBigDecimal(),module.policy.dailyLimit.raw.toBigDecimal())
    RingGauge(fraction.toFloat(),FusionColors.Indigo,148.dp,12.dp,
        "Daily limit used: ${module.dailyUsed.display} / ${module.policy.dailyLimit.display} USDC. ${module.dailyRemaining.display} USDC remaining") {
        Text("${fraction.multiply(100.toBigDecimal()).setScale(0,java.math.RoundingMode.HALF_UP)} %",style=FusionType.NumberMedium)
    }
}
