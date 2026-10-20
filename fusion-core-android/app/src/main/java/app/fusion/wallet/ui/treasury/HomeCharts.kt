package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.fusion.wallet.data.*
import app.fusion.wallet.ui.charts.*
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.theme.*
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.*

/** Two equal bento columns; large accessibility text stacks the same tiles. */
@Composable
private fun ChartPair(first: @Composable (Modifier)->Unit, second: @Composable (Modifier)->Unit) {
    if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        first(Modifier.weight(1f).fillMaxHeight()); second(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
fun HomeAllocationAndRunway(state: WalletState) {
    ChartPair(first={ modifier -> ChartTile(modifier) {
        val buckets = state.buckets
        ChartTileHeader("Where it sits", buildString {
            append("Cash is Safe idle cash after funded bills and the safety cushion. Bills includes the funded bill reserve and safety cushion. Earning is current vault assets. These sum to Safe cash plus vault assets.")
            buckets?.let {
                append("\n\nCash ${formatBigIntegerAmount(it.surplus.raw)} USDC; reserved ${formatBigIntegerAmount(it.reserve.raw + it.buffer.raw)} USDC; earning ${formatBigIntegerAmount(it.deployed.raw)} USDC.")
                if (it.shortfall.raw.signum() > 0) append("\n${formatBigIntegerAmount(it.shortfall.raw)} USDC bills unfunded.")
            }
        })
        if (buckets == null) Text("Allocation unavailable until balances and bills load", style = FusionType.BodySmall)
        else DonutChart(listOf(
            Segment("Cash", buckets.surplus.raw, FusionChart.Cash),
            Segment("Bills", buckets.reserve.raw + buckets.buffer.raw, FusionChart.Reserved),
            Segment("Earning", buckets.deployed.raw, FusionChart.Sky)),
            chartAmountLabel(buckets.total.raw), "USDC")
    } },second={ modifier -> ChartTile(modifier) {
        val evidence = state.chartEvidence
        val runway = evidence?.let { cashRunway(it.cashRaw, it.outflowRaw, it.historyStart, it.through) }
        ChartTileHeader("Cash runway", buildString {
            append("Safe cash divided by average daily outflow over the observed last 30 days. Bill payments and owner sends count once; vault deposits and self-transfers are excluded. At least 7 days of history is required. Arc scale: 90 days, capped visually; the center shows uncapped days. Healthy: 60+ days. Keep an eye on it: 21–59 days. Low: under 21 days.")
            if (evidence != null && runway != null) append("\n\n${formatBigIntegerAmount(evidence.cashRaw)} USDC cash; ${formatBigIntegerAmount(evidence.outflowRaw)} USDC out over ${runway.observedDays.setScale(1, java.math.RoundingMode.DOWN)} days.")
        })
        if (runway?.days == null) Box(Modifier.fillMaxWidth().heightIn(min = 166.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(runway?.status ?: "Runway history unavailable", style = FusionType.BodyMedium,
                color = FusionChart.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } else {
            val days = runway.days
            RadialGauge(days, 90.toBigDecimal(), days.setScale(0, java.math.RoundingMode.DOWN).toPlainString(), "days", runway.status,
                when { days >= 60.toBigDecimal() -> FusionChart.Cash; days >= 21.toBigDecimal() -> FusionChart.Reserved; else -> FusionChart.Coral },
                arcColor = FusionChart.Reserved)
        }
    } })
}

@Composable
fun HomeSpendingAndRates(state: WalletState, txIndex: TxIndex) {
    ChartPair(first={ modifier -> ChartTile(modifier) {
        val evidence = state.chartEvidence
        val date = SimpleDateFormat("MMM d", Locale.getDefault())
        ChartTileHeader("Spending by week", buildString {
            append("USDC bill payments from the shared event index. Local Monday weeks: 3w, 2w, 1w, Now. The current week is partial. Values in the legend sum the last four weeks. Up to three named vendors plus Other; vendor labels come from the transaction index, then registered payees, then an abbreviated address. Empty weeks are flat stubs.")
            evidence?.let { append("\n\nHistory begins ${date.format(Date(it.historyStart * 1000))}; earlier dates are unobserved.") }
        })
        if (evidence == null) Text("Bill payment history unavailable", style = FusionType.BodySmall)
        else {
            val weeks = weeklySpending(evidence.payments, evidence.through, TimeZone.getDefault()) { payment ->
                txIndex.find(payment.txHash)?.payeeLabel ?: state.payees.firstOrNull { it.address.equals(payment.recipient, true) }?.label
                    ?: "Vendor ${payment.recipient.take(8)}…"
            }
            val top = weeks.flatMap { it.totals.entries }.groupBy { it.key }
                .mapValues { (_, rows) -> rows.fold(BigInteger.ZERO) { sum, row -> sum + row.value } }
                .entries.sortedWith(compareByDescending<Map.Entry<String, BigInteger>> { it.value }.thenBy { it.key }).take(3).map { it.key }
            val palette = listOf(FusionChart.Teal, FusionChart.Coral, FusionChart.Sky)
            val labels = listOf("3w", "2w", "1w", "Now")
            WeeklyBars(weeks.mapIndexed { index, week -> WeekBucket(labels[index],
                top.mapIndexed { i, label -> Segment(label, week.totals[label] ?: BigInteger.ZERO, palette[i]) } +
                    listOf(Segment("Other", week.totals.filterKeys { it !in top }.values.fold(BigInteger.ZERO, BigInteger::add), FusionChart.Reserved))) })
        }
    } },second={ modifier -> ChartTile(modifier) {
        val apy = state.chartEvidence?.aaveApyPercent
        ChartTileHeader("Interest per year", buildString {
            append("Aave lending: live waUSDC supply APY from Aave V3 Pool getReserveData(USDC).currentLiquidityRate (ray APR converted to compounded annual supply APY). Pool 0x8bAB6d1b75f19e9eD9fCe8b9BD338844fF79aE27; USDC 0xba50Cd2A20f6DA35D788639E581bca8d0B5d4D5f. Bars scale to the largest available rate shown, rather than 100%.")
            state.chartEvidence?.blockNumber?.let { append("\n\nPool supply rate · block $it") }
            append("\n\nDemo vault: simulated — no live rate. Simulated yield is funded by sponsor donations; no configured annual rate getter. Empty track means no live rate, not zero yield.")
        })
        ProgressBars(listOf(
            ProgressRow("Aave lending", apy?.let { "${it.setScale(2, java.math.RoundingMode.HALF_UP)}%" } ?: "unavailable",
                apy?.let { gaugeFraction(it, it) }, FusionChart.Sky),
            ProgressRow("Demo vault", "simulated", null, FusionChart.Sky)
        ), Modifier.padding(top = 8.dp))
    } })
}

@Composable
fun WorthKnowing(state: WalletState) {
    ChartTile(Modifier.fillMaxWidth()) {
        Text("Worth knowing", style = FusionType.HeadingSmall)
        val buckets = state.buckets
        val evidence = state.chartEvidence
        if (buckets == null) Text("Insights need a verified balance and bill snapshot", style = FusionType.BodySmall)
        else {
            if (evidence != null) {
                val runway = cashRunway(evidence.cashRaw, evidence.outflowRaw, evidence.historyStart, evidence.through)
                runway.days?.let {
                    InsightRow("Your ${formatBigIntegerAmount(evidence.cashRaw)} USDC cash covers about ${it.setScale(0, java.math.RoundingMode.DOWN)} days at your current pace.", FusionChart.Reserved)
                }
            }
            val bill = state.obligations.filterNot { it.paid }.minByOrNull { it.dueAt }
            if (state.accountingMatches == true && bill != null) {
                val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(bill.dueAt * 1000))
                InsightRow("Your next bill is ${formatBigIntegerAmount(bill.amount.raw)} USDC, due $date; ${formatBigIntegerAmount(buckets.reserve.raw)} USDC is reserved.", FusionChart.Cash)
            } else InsightRow("${formatBigIntegerAmount(buckets.owed.raw)} USDC owed; ${formatBigIntegerAmount(buckets.shortfall.raw)} USDC is unfunded.", FusionChart.Reserved)
            InsightRow("${formatBigIntegerAmount(buckets.surplus.raw)} USDC spare cash is idle; ${formatBigIntegerAmount(buckets.deployed.raw)} USDC is in vaults.", FusionChart.Sky)
        }
    }
}

@Composable
private fun InsightRow(text: String, tone: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(8.dp).background(tone, CircleShape))
        Text(text, style = FusionType.BodyMedium)
    }
}
