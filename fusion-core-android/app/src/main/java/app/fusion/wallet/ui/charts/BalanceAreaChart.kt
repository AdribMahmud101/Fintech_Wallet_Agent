package app.fusion.wallet.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fusion.wallet.ui.components.AmountText
import app.fusion.wallet.ui.components.BentoTile
import app.fusion.wallet.ui.components.formatBigIntegerAmount
import app.fusion.wallet.ui.theme.FusionChart
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType
import java.math.BigInteger
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*

data class BalancePoint(val timestampSeconds: Long, val balanceRaw: BigInteger)

/** Convert only a dimensionless ratio to pixels. Monetary arithmetic stays decimal/integer. */
internal fun chartFraction(numerator: BigInteger, denominator: BigInteger): Float =
    if (denominator.signum() <= 0) 0f else numerator.toBigDecimal()
        .divide(denominator.toBigDecimal(), 8, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)

fun balanceSummary(points: List<BalancePoint>, days: Int): String {
    if (points.isEmpty()) return "No verified history in this window"
    val first = points.first().balanceRaw
    val delta = points.last().balanceRaw - first
    if (delta.signum() == 0) return "No change across recorded points · $days days"
    if (first.signum() == 0) return "${formatBigIntegerAmount(delta, exact = true)} USDC change · $days days"
    val percent = delta.toBigDecimal().multiply(BigDecimal(100))
        .divide(first.toBigDecimal(), 1, RoundingMode.HALF_UP)
    return "${if (percent.signum() > 0) "+" else ""}$percent% across recorded points · $days days"
}

@Composable
fun BalanceAreaChart(
    points: List<BalancePoint>, currentBalance: BigInteger?, modifier: Modifier = Modifier,
    loading: Boolean = false, note: String? = null,
) = AreaLineChart(points, currentBalance, modifier, loading, note)

@Composable
fun AreaLineChart(
    points: List<BalancePoint>, currentBalance: BigInteger?, modifier: Modifier = Modifier,
    loading: Boolean = false, note: String? = null,
) {
    val cutoff = System.currentTimeMillis() / 1000 - 30 * 86400L
    val filtered = points.filter { it.timestampSeconds >= cutoff }.sortedBy { it.timestampSeconds }.distinctBy { it.timestampSeconds }
    val summary = balanceSummary(filtered, 30)
    val progress = rememberChartProgress()
    val date = remember { SimpleDateFormat("MMM d, HH:mm z", Locale.getDefault()) }
    val explanation = buildString {
        append("Reconstructed Safe USDC cash from transfers and module checkpoints. Historical vault values are unavailable; vault assets are separate. Change compares the first and last verified samples in the last 30 days; the window can be shorter than 30 days. Points retain their actual timestamp spacing, including initial funding.")
        if (filtered.isNotEmpty()) append("\n\n${date.format(Date(filtered.first().timestampSeconds * 1000))} → ${date.format(Date(filtered.last().timestampSeconds * 1000))}\n${filtered.size} verified block samples. $summary")
        note?.let { append("\n\n$it") }
    }
    ChartTile(modifier.fillMaxWidth()) {
        ChartTileHeader("Total, last 30 days", explanation)
        currentBalance?.let { ChartAmount(formatBigIntegerAmount(it)) }
            ?: Text("Balance unavailable", style = FusionType.BodyMedium)
        if (filtered.size < 4) {
            Text(if (loading) "Reading historical balances…" else
                "${filtered.size} verified points · not enough history yet", style = FusionType.BodySmall,
                modifier = Modifier.padding(top = 8.dp))
        } else {
            val delta = filtered.last().balanceRaw - filtered.first().balanceRaw
            Text(balanceChangeCaption(filtered), style = FusionChart.Status,
                color = if (delta.signum() < 0) FusionChart.Coral else FusionChart.Cash)
            val low = filtered.minOf { it.balanceRaw }
            val high = filtered.maxOf { it.balanceRaw }
            // Pad the actual range, never force the baseline to zero. Funding cliffs stay real.
            val rangePadding = ((high - low) / BigInteger.TEN).coerceAtLeast(BigInteger.ONE)
            val floor = low - rangePadding
            val span = high + rangePadding - floor
            Canvas(Modifier.fillMaxWidth().height(120.dp).semantics {
                contentDescription = "$summary. Safe USDC cash excluding vault assets. Range ${formatBigIntegerAmount(low)} to ${formatBigIntegerAmount(high)} USDC. ${filtered.size} verified block samples."
            }) {
                val pad = 7.dp.toPx(); val w = size.width - pad * 2; val h = size.height - pad * 2
                val start = filtered.first().timestampSeconds
                val duration = (filtered.last().timestampSeconds - start).coerceAtLeast(1)
                val offsets = filtered.map { point -> PlotPoint(
                    pad + w * ((point.timestampSeconds - start).toBigDecimal().divide(duration.toBigDecimal(), 12, RoundingMode.HALF_UP).toFloat()),
                    pad + h * (1f - chartFraction(point.balanceRaw - floor, span))) }
                val line = Path().apply {
                    moveTo(offsets.first().x, offsets.first().y)
                    monotoneCubics(offsets).forEach { cubicTo(it.c1.x,it.c1.y,it.c2.x,it.c2.y,it.end.x,it.end.y) }
                }
                val area = Path().apply { addPath(line); lineTo(offsets.last().x,size.height); lineTo(offsets.first().x,size.height); close() }
                clipRect(right = size.width * progress) {
                    drawPath(area, Brush.verticalGradient(listOf(FusionChart.Sky.copy(alpha = .28f), Color.Transparent)))
                    drawPath(line, FusionChart.Sky, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                    if (progress >= .99f) {
                        val end = Offset(offsets.last().x, offsets.last().y)
                        drawCircle(Brush.radialGradient(listOf(FusionChart.Sky.copy(alpha = .5f), Color.Transparent), end, 7.dp.toPx()), 7.dp.toPx(), end)
                        drawCircle(FusionChart.Sky, 3.5.dp.toPx(), end)
                    }
                }
            }
        }
    }
}

/** Short face label; exact observed-window details remain in the info sheet. */
fun balanceChangeCaption(points: List<BalancePoint>): String {
    if (points.isEmpty()) return "No verified history"
    val first = points.first().balanceRaw
    val delta = points.last().balanceRaw - first
    if (first.signum() == 0) return if (delta.signum() == 0) "0% in 30 days"
        else "+${formatBigIntegerAmount(delta)} USDC in 30 days"
    val percent = delta.abs().toBigDecimal().multiply(BigDecimal(100))
        .divide(first.toBigDecimal(), 1, RoundingMode.HALF_UP)
    return "${if (delta.signum() < 0) "▼" else if (delta.signum() > 0) "▲" else ""} $percent% in 30 days".trim()
}
