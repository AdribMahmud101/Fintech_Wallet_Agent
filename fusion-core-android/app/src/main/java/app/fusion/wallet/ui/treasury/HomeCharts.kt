package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.fusion.wallet.data.*
import app.fusion.wallet.ui.charts.*
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.theme.*
import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.*

/** Two equal bento columns; large accessibility text stacks the same tiles. */
@Composable
private fun ChartPair(first: @Composable (Modifier)->Unit, second: @Composable (Modifier)->Unit) {
    if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
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
    },second={ modifier -> BentoTile(modifier,padding=12.dp) {
        Text("Cash runway",style=FusionType.HeadingSmall)
        val evidence=state.chartEvidence
        if (evidence==null) Text("Runway history unavailable",style=FusionType.BodySmall)
        else {
            val runway=cashRunway(evidence.cashRaw,evidence.outflowRaw,evidence.historyStart,evidence.through)
            if (runway.days==null) Text(runway.status,style=FusionType.BodySmall,modifier=Modifier.padding(vertical=24.dp))
            else {
                val days=runway.days
                RadialGauge(days,90.toBigDecimal(),days.setScale(0,java.math.RoundingMode.DOWN).toPlainString(),"days",runway.status,
                    when { days>=60.toBigDecimal()->FusionColors.Green; days>=21.toBigDecimal()->FusionColors.Amber; else->FusionColors.Red })
            }
            Text("${formatBigIntegerAmount(evidence.outflowRaw)} USDC out over ${runway.observedDays.setScale(1,java.math.RoundingMode.DOWN)} days",style=FusionType.BodySmall)
            Text("Bill payments + owner sends. Vault deposits excluded. Arc scale: 90 days.",style=FusionType.BodySmall)
        }
    } })
}

@Composable
fun HomeSpendingAndRates(state: WalletState, txIndex: TxIndex) {
    ChartPair(first={ modifier -> BentoTile(modifier,padding=12.dp) {
        Text("Spending by week",style=FusionType.HeadingSmall)
        val evidence=state.chartEvidence
        if (evidence==null) Text("Bill payment history unavailable",style=FusionType.BodySmall)
        else {
            val weeks=weeklySpending(evidence.payments,evidence.through,TimeZone.getDefault()) { payment ->
                txIndex.find(payment.txHash)?.payeeLabel ?: state.payees.firstOrNull { it.address.equals(payment.recipient,true) }?.label
                    ?: "Vendor ${payment.recipient.take(8)}…"
            }
            val top=weeks.flatMap { it.totals.entries }.groupBy { it.key }.mapValues { (_,rows)->rows.fold(BigInteger.ZERO) { sum,row->sum+row.value } }
                .entries.sortedWith(compareByDescending<Map.Entry<String,BigInteger>> { it.value }.thenBy { it.key }).take(3).map { it.key }
            val palette=listOf(FusionColors.Indigo,FusionColors.Green,FusionColors.Amber)
            val date=SimpleDateFormat("MMM d",Locale.getDefault())
            WeeklyBars(weeks.map { week -> WeekBucket(date.format(Date(week.startSeconds*1000)),
                top.mapIndexed { i,label-> Segment(label,week.totals[label]?:BigInteger.ZERO,palette[i]) }+
                    listOf(Segment("Other",week.totals.filterKeys { it !in top }.values.fold(BigInteger.ZERO,BigInteger::add),FusionColors.TextMuted))) })
            Text("USDC · local Monday weeks · current week is partial",style=FusionType.BodySmall,modifier=Modifier.padding(top=8.dp))
            if (evidence.historyStart>weeks.first().startSeconds) Text("History begins ${date.format(Date(evidence.historyStart*1000))}; earlier dates are unobserved.",style=FusionType.BodySmall)
        }
    } },second={ modifier -> BentoTile(modifier,padding=12.dp) {
        Text("Interest per year",style=FusionType.HeadingSmall)
        val apy=state.chartEvidence?.aaveApyPercent
        val fraction=apy?.let { gaugeFraction(it,100.toBigDecimal()) }
        ProgressBars(listOf(
            ProgressRow("Aave waUSDC",apy?.let { "${it.setScale(2,java.math.RoundingMode.HALF_UP)} % APY" }?:"Live rate unavailable",fraction,FusionColors.Indigo,
                state.chartEvidence?.blockNumber?.let { "Pool supply rate · block $it · 100% scale" }),
            ProgressRow("Demo vault · simulated yield","simulated — no live rate",null,FusionColors.Amber,"Sponsor donations; no configured annual rate")
        ),Modifier.padding(top=16.dp))
    } })
}

@Composable
fun WorthKnowing(state: WalletState) {
    BentoTile(Modifier.fillMaxWidth()) {
        Text("Worth knowing",style=FusionType.HeadingSmall)
        val buckets=state.buckets; val evidence=state.chartEvidence
        if (buckets==null) Text("Insights need a verified balance and bill snapshot",style=FusionType.BodySmall)
        else {
            Text("• ${formatBigIntegerAmount(buckets.surplus.raw)} USDC spare cash sits idle; ${formatBigIntegerAmount(buckets.deployed.raw)} USDC is in vaults.",style=FusionType.BodyMedium,modifier=Modifier.padding(top=12.dp))
            val bill=state.obligations.filterNot { it.paid }.minByOrNull { it.dueAt }
            if (state.accountingMatches==true && bill!=null) {
                val date=SimpleDateFormat("MMM d, HH:mm z",Locale.getDefault()).format(Date(bill.dueAt*1000))
                Text("• Your next bill is ${formatBigIntegerAmount(bill.amount.raw)} USDC, due $date; ${formatBigIntegerAmount(buckets.reserve.raw)} USDC is reserved for bills.",style=FusionType.BodyMedium,modifier=Modifier.padding(top=12.dp))
            } else Text("• ${formatBigIntegerAmount(buckets.owed.raw)} USDC owed; ${formatBigIntegerAmount(buckets.shortfall.raw)} USDC is unfunded.",style=FusionType.BodyMedium,modifier=Modifier.padding(top=12.dp))
            if (evidence!=null) {
                val runway=cashRunway(evidence.cashRaw,evidence.outflowRaw,evidence.historyStart,evidence.through)
                runway.days?.let { Text("• ${formatBigIntegerAmount(evidence.cashRaw)} USDC cash covers ${it.setScale(1,java.math.RoundingMode.DOWN)} days at the observed pace of ${formatBigIntegerAmount(evidence.outflowRaw)} USDC out over ${runway.observedDays.setScale(1,java.math.RoundingMode.DOWN)} days.",style=FusionType.BodyMedium,modifier=Modifier.padding(top=12.dp)) }
            }
        }
    }
}
