package app.fusion.wallet.ui.safety

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.data.BenchmarkMetrics
import app.fusion.wallet.ui.charts.*
import app.fusion.wallet.ui.components.BentoTile
import app.fusion.wallet.ui.theme.*

@Composable
fun SafetyCharts(evidence: SafetyScreenState) {
    val context=LocalContext.current
    val metrics=remember { runCatching { context.resources.openRawResource(R.raw.k28_benchmarks).bufferedReader().use { BenchmarkMetrics.parse(it.readText()) } }.getOrNull() }
    ChartTile(Modifier.fillMaxWidth()) {
        ChartTileHeader("Stopped at each layer",
            "Actual recorded stopping layers, divided by all recorded scenarios. No expected-layer credit. Recorded stopping layer · ${evidence.source} · evidence/results/latest.json")
        val arena=evidence.arena
        if (arena==null) Text("Layer counts unavailable until evidence loads",style=FusionType.BodySmall)
        else {
            val layers=safetyDiagramLayers.map { (label,keys)->label to keys.sumOf { arena.layerCounts[it]?:0 } }+
                arena.layerCounts.filterKeys { key->safetyDiagramLayers.none { key in it.second } }.map { safetyLayerTitle(it.key) to it.value }
            ProgressBars(layers.map { (label,count)->ProgressRow(label,"$count / ${arena.scenarios.size} scenarios",
                gaugeFraction(count.toBigDecimal(),arena.scenarios.size.toBigDecimal()),FusionColors.Green) },Modifier.padding(top=12.dp))
        }
    }
    Spacer(Modifier.height(16.dp))
    BentoTile(Modifier.fillMaxWidth()) {
        Text("ASR / FBR benchmarks",style=FusionType.HeadingSmall)
        if (metrics==null) Text("Benchmark evidence unavailable",style=FusionType.BodySmall)
        else ProgressBars(metrics.map { ProgressRow(it.label,"${it.numerator}/${it.denominator}",
            gaugeFraction(it.numerator.toBigDecimal(),it.denominator.toBigDecimal()),if (it.label.endsWith("ASR")) FusionColors.Green else FusionColors.Amber) },Modifier.padding(top=12.dp))
        Text("ASR: admitted attacks. FBR: blocked benign cases. Lower is better. Zero shows an empty track.",style=FusionType.BodySmall)
        Text("Frozen evaluation snapshot · evidence/planner/TUNING_K20.md · AHEM-G · all benign FBR",style=FusionType.BodySmall)
    }
}
