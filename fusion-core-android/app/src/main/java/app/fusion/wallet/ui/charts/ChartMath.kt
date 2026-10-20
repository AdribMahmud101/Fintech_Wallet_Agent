package app.fusion.wallet.ui.charts

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.min

/** Largest remainder allocation: deterministic ties and a total of exactly 100 for nonzero data. */
fun segmentPercentages(values: List<BigInteger>): List<Int> {
    require(values.all { it.signum() >= 0 })
    val total = values.fold(BigInteger.ZERO, BigInteger::add)
    if (total.signum() == 0) return values.map { 0 }
    val divided = values.map { (it * BigInteger.valueOf(100)).divideAndRemainder(total) }
    val result = divided.map { it[0].toInt() }.toMutableList()
    val order = values.indices.sortedWith(compareByDescending<Int> { divided[it][1] }.thenBy { it })
    repeat(100 - result.sum()) { result[order[it]]++ }
    return result
}

fun gaugeFraction(value: BigDecimal, max: BigDecimal): BigDecimal =
    if (max.signum() <= 0) BigDecimal.ZERO else value.divide(max, 12, RoundingMode.HALF_UP).coerceIn(BigDecimal.ZERO, BigDecimal.ONE)

/** Compact amount labels for narrow bar columns; full amounts remain in the legend and semantics. */
fun chartAmountLabel(raw: BigInteger): String {
    require(raw.signum() >= 0)
    val units=BigDecimal(raw,6)
    val (divisor,suffix)=when {
        units >= BigDecimal("1000000") -> BigDecimal("1000000") to "M"
        units >= BigDecimal("1000") -> BigDecimal("1000") to "K"
        else -> BigDecimal.ONE to ""
    }
    if (raw.signum()>0 && units < BigDecimal("0.01")) return "<0.01"
    return units.divide(divisor,if (suffix.isEmpty()) 2 else 1,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+suffix
}

/** Padded actual extrema, including funding cliffs; never add a synthetic zero baseline. */
fun balancePlotRange(values: List<BigInteger>): Pair<BigInteger, BigInteger> {
    require(values.isNotEmpty())
    val low = values.min()
    val high = values.max()
    val padding = ((high - low) / BigInteger.TEN).coerceAtLeast(BigInteger.ONE)
    return (low - padding) to (high + padding)
}

/** Drawing coordinates only. Each Bezier's controls stay inside its endpoint interval.
 * Weighted harmonic slopes are zero at a direction change; the limiter preserves monotonicity.
 */
data class PlotPoint(val x: Float, val y: Float)
data class CubicSpan(val start: PlotPoint, val c1: PlotPoint, val c2: PlotPoint, val end: PlotPoint) {
    fun y(t: Float): Float {
        val u = 1f - t
        return u*u*u*start.y + 3*u*u*t*c1.y + 3*u*t*t*c2.y + t*t*t*end.y
    }
}
fun monotoneCubics(points: List<PlotPoint>): List<CubicSpan> {
    if (points.size < 2) return emptyList()
    require(points.all { it.x.isFinite() && it.y.isFinite() })
    require(points.zipWithNext().all { (a,b) -> b.x > a.x })
    val widths = points.zipWithNext().map { (a,b) -> b.x - a.x }
    val slopes = points.zipWithNext().mapIndexed { i, (a,b) -> (b.y-a.y)/widths[i] }
    val tangents = MutableList(points.size) { 0f }
    tangents[0] = slopes.first(); tangents[tangents.lastIndex] = slopes.last()
    for (i in 1 until points.lastIndex) {
        val a = slopes[i-1]; val b = slopes[i]
        if (a*b > 0f) {
            val w1 = 2*widths[i] + widths[i-1]; val w2 = widths[i] + 2*widths[i-1]
            tangents[i] = (w1+w2)/(w1/a+w2/b)
        }
    }
    slopes.forEachIndexed { i, slope ->
        if (slope == 0f) { tangents[i] = 0f; tangents[i+1] = 0f }
        else {
            tangents[i] = min(abs(tangents[i]), 3*abs(slope)) * if (slope > 0) 1 else -1
            tangents[i+1] = min(abs(tangents[i+1]), 3*abs(slope)) * if (slope > 0) 1 else -1
        }
    }
    return slopes.indices.map { i ->
        val a=points[i]; val b=points[i+1]; val dx=widths[i]/3
        CubicSpan(a, PlotPoint(a.x+dx,a.y+tangents[i]*dx), PlotPoint(b.x-dx,b.y-tangents[i+1]*dx), b)
    }
}
