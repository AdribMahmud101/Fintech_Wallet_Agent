package app.fusion.wallet.data

import app.fusion.wallet.chain.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.Calendar
import java.util.TimeZone

/** Exact amounts from the full shared index, never the 50-row recent-actions preview. */
data class ChartPayment(val timestamp: Long, val amountRaw: BigInteger, val txHash: String, val recipient: String)
data class ChartEvidence(
    val historyStart: Long, val through: Long, val outflowRaw: BigInteger,
    val payments: List<ChartPayment>, val actions: List<AgentAction>,
    val cashRaw: BigInteger, val blockNumber: Long,
    val aaveApyPercent: BigDecimal? = null,
)
data class Runway(val days: BigDecimal?, val status: String, val observedDays: BigDecimal)
fun cashRunway(cash: BigInteger, outflow: BigInteger, historyStart: Long, now: Long): Runway {
    require(cash.signum() >= 0 && outflow.signum() >= 0)
    val seconds = (now-historyStart).coerceIn(0,30*86400L)
    val observed = seconds.toBigDecimal().divide(86400.toBigDecimal(), 8, RoundingMode.HALF_UP)
    if (seconds < 12*3600) return Runway(null,"Not enough history yet",observed)
    if (outflow.signum() == 0) return Runway(null,"No outflow recorded",observed)
    val days = cash.toBigDecimal().multiply(seconds.toBigDecimal())
        .divide(outflow.toBigDecimal().multiply(86400.toBigDecimal()), 8, RoundingMode.DOWN)
    // Under a week of data the number is real but young: say so instead of hiding it.
    if (seconds < 7*86400) {
        val n = observed.setScale(0, RoundingMode.UP).toInt().coerceAtLeast(1)
        return Runway(days, "Early estimate · $n day${if (n == 1) "" else "s"} of data", observed)
    }
    return Runway(days, when { days >= 60.toBigDecimal() -> "Healthy"; days >= 21.toBigDecimal() -> "Keep an eye on it"; else -> "Low" },observed)
}

data class SpendWeek(val startSeconds: Long, val totals: Map<String,BigInteger>)
/** Monday local midnight, including DST; the current partial week is included. */
fun localWeekStart(timestamp: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
    timeInMillis = timestamp*1000
    set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0)
    add(Calendar.DAY_OF_MONTH,-((get(Calendar.DAY_OF_WEEK)+5)%7))
}.timeInMillis/1000
fun weeklySpending(payments: List<ChartPayment>, now: Long, zone: TimeZone, label: (ChartPayment)->String): List<SpendWeek> {
    val calendar = Calendar.getInstance(zone).apply { timeInMillis=localWeekStart(now,zone)*1000; add(Calendar.WEEK_OF_YEAR,-3) }
    val starts = List(4) { (calendar.timeInMillis/1000).also { calendar.add(Calendar.WEEK_OF_YEAR,1) } }
    return starts.map { start ->
        SpendWeek(start,payments.filter { it.timestamp <= now && localWeekStart(it.timestamp,zone)==start }
            .groupBy(label).mapValues { (_,rows)-> rows.fold(BigInteger.ZERO) { sum,row->sum+row.amountRaw } })
    }
}

object ChartEvidenceReader {
    const val AAVE_POOL = "0x8bAB6d1b75f19e9eD9fCe8b9BD338844fF79aE27"
    const val AAVE_USDC = "0xba50Cd2A20f6DA35D788639E581bca8d0B5d4D5f"
    // getReserveData(address): Pool ReserveData word 2 = currentLiquidityRate (ray APR).
    const val RESERVE_DATA = "0x35ea6a75"
    fun supplyApyPercent(ray: BigInteger): BigDecimal {
        require(ray.signum() >= 0)
        val mc=MathContext(34)
        val perSecond=ray.toBigDecimal().divide(BigInteger.TEN.pow(27).toBigDecimal(),mc).divide(31536000.toBigDecimal(),mc)
        var base=BigDecimal.ONE.add(perSecond,mc); var exponent=31536000; var result=BigDecimal.ONE
        while (exponent>0) { if (exponent and 1 == 1) result=result.multiply(base,mc); base=base.multiply(base,mc); exponent=exponent shr 1 }
        return result.subtract(BigDecimal.ONE).multiply(100.toBigDecimal())
    }
    fun read(index: EventIndex.Snapshot, deployment: Deployment, head: BlockHeader, reader: ChainReader, cashRaw: BigInteger): ChartEvidence {
        val logs=index.logs.filter { !it.removed }.distinctBy { it.blockHash to it.logIndex }
        val module=logs.filter { it.address.equals(deployment.module,true) }
        val paid=module.filter { it.topics.firstOrNull()==ModuleEvents.PAID }
        val payments=paid.map { ChartPayment(index.headers.getValue(it.blockNumber).timestamp,
            Abi(it.data).requireSize(3).uint(0),it.txHash,ModuleEvents.paidRecipient(it)) }
        val actions=module.filter { it.topics.firstOrNull()==ModuleEvents.EXECUTED }.map { log ->
            ModuleEvents.action(log,index.headers.getValue(log.blockNumber).timestamp,
                paid.lastOrNull { it.txHash==log.txHash && it.logIndex<log.logIndex }?.let(ModuleEvents::paidRecipient))
        }
        val cutoff=head.timestamp-30*86400
        // Count each actual cash transfer once. Payment events supply vendor grouping, not
        // another debit. This also preserves extra owner sends in a bill's Safe multisend tx.
        val outflow=logs.filter { it.address.equals(deployment.asset,true) && it.topics.firstOrNull()==BalanceReconstruction.TRANSFER }
            .filter { index.headers.getValue(it.blockNumber).timestamp in cutoff..head.timestamp }
            .filter { Abi(it.topics[1]).address(0).equals(deployment.safe,true) &&
                listOf(deployment.safe,deployment.vaultA,deployment.vaultB).none { address -> Abi(it.topics[2]).address(0).equals(address,true) } }
            .fold(BigInteger.ZERO) { sum,log -> sum+Abi(log.data).requireSize(1).uint(0) }
        val start=reader.block(ChainReader.tag(deployment.deploymentBlock)).timestamp
        require(start <= head.timestamp && cashRaw.signum() >= 0)
        val apy=runCatching {
            val reserve=reader.call(AAVE_POOL,RESERVE_DATA+Abi.addressWord(AAVE_USDC),head.number)
            require(reserve.size>=15)
            supplyApyPercent(reserve.uint(2))
        }.getOrNull()
        return ChartEvidence(start,head.timestamp,outflow,payments,actions,cashRaw,head.number,apy)
    }
}
