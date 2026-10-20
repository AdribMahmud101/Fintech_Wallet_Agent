package app.fusion.wallet.ui.charts

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Exact cumulative boundaries; only the final dimensionless coordinates become canvas floats. */
data class BucketSegment(val bucketIndex: Int, val start: BigDecimal, val end: BigDecimal)

fun bucketSegments(values: List<BigInteger>): List<BucketSegment> {
    require(values.all { it.signum() >= 0 })
    val total = values.fold(BigInteger.ZERO, BigInteger::add)
    if (total.signum() == 0) return emptyList()
    var used = BigInteger.ZERO
    return values.mapIndexedNotNull { index, value ->
        if (value.signum() == 0) null else {
            val start = used.toBigDecimal().divide(total.toBigDecimal(), 24, RoundingMode.HALF_UP)
            used += value
            val end = used.toBigDecimal().divide(total.toBigDecimal(), 24, RoundingMode.HALF_UP)
            BucketSegment(index, start, end)
        }
    }
}
