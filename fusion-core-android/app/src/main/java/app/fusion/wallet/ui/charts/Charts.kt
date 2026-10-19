package app.fusion.wallet.ui.charts

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.theme.FusionTheme

/**
 * Small hand-drawn charts (no chart library). Rules every chart follows:
 *  - a plain-language `description` read by screen readers (the picture is never the only carrier of meaning);
 *  - the numbers are also shown as text next to the chart, never colour-only;
 *  - draw-in animation is skipped when the system animation scale is 0 (reduced motion).
 */
@Composable
fun rememberChartProgress(): Float {
    val context = LocalContext.current
    val reduced = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) a.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
    return a.value
}

/** Proportional ring. Zero total draws only the empty track. */
@Composable
fun DonutChart(
    values: List<Float>, colors: List<Color>, size: Dp, stroke: Dp, description: String,
    modifier: Modifier = Modifier, center: @Composable BoxScope.() -> Unit = {},
) {
    val p = rememberChartProgress()
    val track = FusionTheme.surfaces.level3
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val sw = stroke.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            val total = values.filter { it > 0f }.sum()
            if (total > 0f) {
                var start = -90f
                val gap = if (values.count { it > 0f } > 1) 3f else 0f
                values.forEachIndexed { i, v ->
                    if (v <= 0f) return@forEachIndexed
                    val full = 360f * v / total
                    drawArc(colors[i % colors.size], start + gap / 2, ((full - gap).coerceAtLeast(0.5f)) * p, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Butt))
                    start += full
                }
            }
        }
        center()
    }
}

/** One value as an arc of a full ring (0..1). */
@Composable
fun RingGauge(progress: Float, color: Color, size: Dp, stroke: Dp, description: String, modifier: Modifier = Modifier, center: @Composable BoxScope.() -> Unit = {}) {
    val p = rememberChartProgress()
    val track = FusionTheme.surfaces.level3
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val sw = stroke.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            val sweep = 360f * progress.coerceIn(0f, 1f) * p
            if (sweep > 0f) drawArc(color, -90f, sweep.coerceAtLeast(2f), false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        center()
    }
}

/** Line with a soft fill and an end dot, revealed left to right. Flat data draws a flat line. */
@Composable
fun Sparkline(values: List<Float>, color: Color, description: String, modifier: Modifier = Modifier) {
    val p = rememberChartProgress()
    val dotBackground = FusionTheme.surfaces.level1
    Canvas(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        if (values.size < 2) return@Canvas
        val lo = values.min(); val hi = values.max()
        val span = (hi - lo).takeIf { it > 0f }
        val padY = 8.dp.toPx(); val padX = 6.dp.toPx()
        val w = size.width - padX * 2; val h = size.height - padY * 2
        fun x(i: Int) = padX + w * i / (values.size - 1)
        fun y(v: Float) = padY + h * (1f - (if (span == null) 0.5f else (v - lo) / span))
        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) {
                val cx = (x(i - 1) + x(i)) / 2f
                cubicTo(cx, y(values[i - 1]), cx, y(values[i]), x(i), y(values[i]))
            }
        }
        val fill = Path().apply { addPath(line); lineTo(x(values.size - 1), size.height); lineTo(x(0), size.height); close() }
        clipRect(right = size.width * p) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (p >= 0.98f) {
            drawCircle(dotBackground, 6.dp.toPx(), Offset(x(values.size - 1), y(values.last())))
            drawCircle(color, 4.dp.toPx(), Offset(x(values.size - 1), y(values.last())))
        }
    }
}

/**
 * Vertical stacked bars, one per group (oldest first), segments bottom-up in [colors] order.
 * [labels] are drawn as real text under each bar.
 */
@Composable
fun StackedBars(groups: List<List<Float>>, colors: List<Color>, labels: List<String>, description: String, height: Dp, modifier: Modifier = Modifier) {
    val p = rememberChartProgress()
    val maxTotal = groups.maxOfOrNull { it.sum() }?.takeIf { it > 0f } ?: 1f
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height).clearAndSetSemantics { }) {
            val n = groups.size
            val slot = size.width / n
            val barW = slot * 0.56f
            val radius = CornerRadius(6.dp.toPx())
            groups.forEachIndexed { i, segs ->
                val total = segs.sum()
                val barH = (size.height * (total / maxTotal)) * p
                val left = slot * i + (slot - barW) / 2
                if (total <= 0f) {
                    drawRoundRect(color = Color.White.copy(alpha = 0.06f), topLeft = Offset(left, size.height - 3.dp.toPx()), size = Size(barW, 3.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                    return@forEachIndexed
                }
                val clip = Path().apply { addRoundRect(RoundRect(Rect(left, size.height - barH, left + barW, size.height), radius)) }
                clipPath(clip) {
                    var bottom = size.height
                    segs.forEachIndexed { s, v ->
                        if (v <= 0f) return@forEachIndexed
                        val segH = barH * (v / total)
                        drawRect(colors[s % colors.size], Offset(left, bottom - segH), Size(barW, segH))
                        bottom -= segH
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

/** Horizontal bar with a label above and the value at the right. [fraction] is 0..1 of the track. */
@Composable
fun LabeledBar(label: String, valueText: String, fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val p = rememberChartProgress()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(FusionTheme.surfaces.level3)) {
            Box(Modifier.fillMaxWidth((fraction.coerceIn(0.02f, 1f)) * p).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/** A single horizontal stacked bar (e.g. bills by due date). Segments of zero width are skipped. */
@Composable
fun SegmentBar(values: List<Float>, colors: List<Color>, description: String, modifier: Modifier = Modifier) {
    val p = rememberChartProgress()
    val total = values.sum()
    Row(
        modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(FusionTheme.surfaces.level3).semantics { contentDescription = description },
    ) {
        if (total > 0f) values.forEachIndexed { i, v ->
            if (v > 0f) Box(Modifier.weight(v / total * p.coerceAtLeast(0.001f)).height(12.dp).background(colors[i % colors.size]))
        }
    }
}
