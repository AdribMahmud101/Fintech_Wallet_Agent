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
) {
    var days by rememberSaveable { mutableStateOf(7) }
    val cutoff = System.currentTimeMillis() / 1000 - days * 86400L
    val filtered = points.filter { it.timestampSeconds >= cutoff }.sortedBy { it.timestampSeconds }
    val summary = balanceSummary(filtered, days)
    val progress = rememberChartProgress()
    BentoTile(modifier.fillMaxWidth()) {
        Text("Balance history", style = FusionType.HeadingSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30).forEach { range ->
                FilterChip(selected = days == range, onClick = { days = range },
                    label = { Text("$range d") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (filtered.size < 4) {
            currentBalance?.let { AmountText(it, style = FusionType.NumberSmall) }
                ?: Text("Balance unavailable", style = FusionType.BodyMedium)
            Text(if (loading) "Reading historical balances…" else note ?: 
                "${filtered.size} verified points in $days days. At least 4 are needed for a chart.",
                style = FusionType.BodySmall)
        } else {
            Text(summary, style = FusionType.BodySmall)
            val low = filtered.minOf { it.balanceRaw }
            val high = filtered.maxOf { it.balanceRaw }
            Text("Total treasury · solid line / circle markers", style = FusionType.BodySmall)
            Text("${formatBigIntegerAmount(low)} – ${formatBigIntegerAmount(high)} USDC", style = FusionType.NumberCaption)
            Canvas(Modifier.fillMaxWidth().height(150.dp).semantics {
                contentDescription = "$summary. Total treasury including vault assets. " +
                    "Range ${formatBigIntegerAmount(low)} to ${formatBigIntegerAmount(high)} USDC. ${filtered.size} verified block samples."
            }) {
                val pad = 8.dp.toPx()
                val w = size.width - pad * 2
                val h = size.height - pad * 2
                val span = high - low
                val start = filtered.first().timestampSeconds
                val duration = (filtered.last().timestampSeconds - start).coerceAtLeast(1)
                val offsets = filtered.map { point -> Offset(
                    pad + w * ((point.timestampSeconds - start).toBigDecimal().divide(duration.toBigDecimal(), 8, RoundingMode.HALF_UP).toFloat()),
                    pad + h * (1f - if (span.signum() == 0) 0.5f else chartFraction(point.balanceRaw - low, span))) }
                repeat(3) { i -> drawLine(FusionColors.Border, Offset(pad, pad + h * i / 2),
                    Offset(pad + w, pad + h * i / 2), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                val line = Path().apply { offsets.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                val area = Path().apply { addPath(line); lineTo(offsets.last().x, pad + h); lineTo(offsets.first().x, pad + h); close() }
                clipRect(right = size.width * progress) {
                    drawPath(area, Brush.verticalGradient(listOf(FusionColors.Indigo.copy(alpha = .25f), Color.Transparent)))
                    drawPath(line, FusionColors.Indigo, style = Stroke(2.dp.toPx()))
                    offsets.forEach { drawCircle(FusionColors.Text, 2.dp.toPx(), it) }
                }
            }
            val date = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
            Text("${date.format(Date(filtered.first().timestampSeconds * 1000))} → ${date.format(Date(filtered.last().timestampSeconds * 1000))}", style = FusionType.BodySmall)
            Text("Sampled at module-event blocks; transfers between samples are not shown.", style = FusionType.BodySmall)
        }
    }
}
