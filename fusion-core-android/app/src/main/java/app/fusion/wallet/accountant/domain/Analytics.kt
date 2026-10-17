package app.fusion.wallet.accountant.domain

import app.fusion.wallet.accountant.data.BucketSnapshotEntity
import app.fusion.wallet.accountant.data.FlowCategory
import java.math.BigInteger

/** Spending in one 7-day window, split by the three categories the charts distinguish. */
data class WeekSpend(val payroll: BigInteger, val suppliers: BigInteger, val other: BigInteger) {
    val total: BigInteger get() = payroll + suppliers + other
}

/** A bill-timing summary in three plain buckets. */
data class BillTiming(val thisWeek: BigInteger, val thisMonth: BigInteger, val later: BigInteger, val overdue: BigInteger) {
    val total: BigInteger get() = thisWeek + thisMonth + later + overdue
}

object Analytics {
    private const val WEEK = 7L * 24 * 3600

    /**
     * Outflows grouped into [weeks] consecutive 7-day windows ending at [asOf], oldest first, so the last
     * item is "this week". Incoming money and anything older than the window are ignored.
     */
    fun weeklySpend(flows: List<FlowView>, asOf: Long, unitDecimals: Int, weeks: Int = 4): List<WeekSpend> {
        val payroll = Array(weeks) { BigInteger.ZERO }
        val suppliers = Array(weeks) { BigInteger.ZERO }
        val other = Array(weeks) { BigInteger.ZERO }
        for (f in flows) {
            if (f.isIncome) continue
            val ago = ((asOf - f.at) / WEEK).toInt()
            if (f.at > asOf || ago !in 0 until weeks) continue
            val i = weeks - 1 - ago
            val amount = Raw.rescale(f.amount, f.decimals, unitDecimals)
            when (f.category) {
                FlowCategory.PAYROLL -> payroll[i] += amount
                FlowCategory.SUPPLIERS -> suppliers[i] += amount
                else -> other[i] += amount
            }
        }
        return List(weeks) { WeekSpend(payroll[it], suppliers[it], other[it]) }
    }

    /** Total assets (cash + deployed) per stored snapshot, oldest first. Reserved money is part of cash. */
    fun totals(history: List<BucketSnapshotEntity>): List<BigInteger> =
        history.sortedBy { it.observedAt }.map { Raw.parse(it.freeRaw) + Raw.parse(it.reservedRaw) + Raw.parse(it.deployedRaw) }

    /** Whole-percent change from [first] to [last]; null when there is no meaningful base. */
    fun changePct(first: BigInteger, last: BigInteger): Int? {
        if (first.signum() <= 0) return null
        val diff = (last - first).multiply(BigInteger.valueOf(100))
        val half = first.shiftRight(1)
        return (if (diff.signum() >= 0) diff + half else diff - half).divide(first).toInt()
    }

    fun billTiming(obligations: List<ObligationView>, asOf: Long, unitDecimals: Int): BillTiming {
        var overdue = BigInteger.ZERO; var week = BigInteger.ZERO; var month = BigInteger.ZERO; var later = BigInteger.ZERO
        for (o in obligations) {
            val a = Raw.rescale(o.amount, o.decimals, unitDecimals)
            val left = o.dueAt - asOf
            when {
                left < 0 -> overdue += a
                left <= WEEK -> week += a
                left <= 30L * 24 * 3600 -> month += a
                else -> later += a
            }
        }
        return BillTiming(week, month, later, overdue)
    }
}
