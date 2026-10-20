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
    BentoTile(modifier.fillMaxWidth(), tint = FusionColors.Indigo) {
        Text("Total, last 30 days", style = FusionType.HeadingSmall)
        Text("Safe cash · USDC", style = FusionType.BodySmall)
        currentBalance?.let { AmountText(it, style = FusionType.NumberMedium) }
            ?: Text("Balance unavailable", style = FusionType.BodyMedium)
        if (filtered.size < 4) {
            Text(if (loading) "Reading historical balances…" else note ?:
                "${filtered.size} verified points in 30 days. At least 4 are needed for a chart.", style = FusionType.BodySmall)
        } else {
            val delta = filtered.last().balanceRaw - filtered.first().balanceRaw
            Text("${if (delta.signum() > 0) "▲" else if (delta.signum() < 0) "▼" else "="} $summary", style = FusionType.BodySmall,
                color = if (delta.signum() < 0) FusionColors.Amber else FusionColors.Green)
            val low = filtered.minOf { it.balanceRaw }
            val high = filtered.maxOf { it.balanceRaw }
            Canvas(Modifier.fillMaxWidth().height(160.dp).semantics {
                contentDescription = "$summary. Safe USDC cash excluding vault assets. Range ${formatBigIntegerAmount(low)} to ${formatBigIntegerAmount(high)} USDC. ${filtered.size} verified block samples."
            }) {
                val pad = 10.dp.toPx(); val w = size.width - pad * 2; val h = size.height - pad * 2
                val span = high - low; val start = filtered.first().timestampSeconds
                val duration = (filtered.last().timestampSeconds - start).coerceAtLeast(1)
                val offsets = filtered.map { point -> PlotPoint(
                    pad + w * ((point.timestampSeconds - start).toBigDecimal().divide(duration.toBigDecimal(), 12, RoundingMode.HALF_UP).toFloat()),
                    pad + h * (1f - if (span.signum() == 0) 0.5f else chartFraction(point.balanceRaw - low, span))) }
                repeat(3) { i -> drawLine(FusionColors.Border.copy(alpha=.6f), Offset(pad, pad + h * i / 2), Offset(pad+w,pad+h*i/2)) }
                val line = Path().apply {
                    moveTo(offsets.first().x, offsets.first().y)
                    monotoneCubics(offsets).forEach { cubicTo(it.c1.x,it.c1.y,it.c2.x,it.c2.y,it.end.x,it.end.y) }
                }
                val area = Path().apply { addPath(line); lineTo(offsets.last().x,pad+h); lineTo(offsets.first().x,pad+h); close() }
                clipRect(right = size.width * progress) {
                    drawPath(area,Brush.verticalGradient(listOf(FusionColors.Indigo.copy(alpha=.3f),Color.Transparent)))
                    drawPath(line,FusionColors.Indigo,style=Stroke(2.5.dp.toPx(),cap=StrokeCap.Round))
                    offsets.forEach { drawCircle(FusionColors.Text,2.dp.toPx(),Offset(it.x,it.y)) }
                    if (progress >= .99f) {
                        val end=Offset(offsets.last().x,offsets.last().y)
                        drawCircle(Brush.radialGradient(listOf(FusionColors.Indigo.copy(alpha=.55f),Color.Transparent),end,12.dp.toPx()),12.dp.toPx(),end)
                        drawCircle(FusionColors.Indigo,4.dp.toPx(),end)
                        drawCircle(FusionColors.Text,2.dp.toPx(),end)
                    }
                }
            }
            val date = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
            Text("${date.format(Date(filtered.first().timestampSeconds * 1000))} → ${date.format(Date(filtered.last().timestampSeconds * 1000))}",style=FusionType.BodySmall)
        }
        Text("Reconstructed from Safe USDC transfers and module checkpoints. Historical vault values are unavailable; vault assets are separate.",style=FusionType.BodySmall)
    }
}
