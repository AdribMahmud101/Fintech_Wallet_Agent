package app.fusion.wallet.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fusion.wallet.ui.components.formatBigIntegerAmount
import app.fusion.wallet.ui.theme.*
import java.math.BigDecimal
import java.math.BigInteger

data class Segment(val label: String, val valueRaw: BigInteger, val color: Color)
data class WeekBucket(val label: String, val segments: List<Segment>)
data class ProgressRow(val label: String, val valueText: String, val fraction: BigDecimal?, val tone: Color, val note: String? = null)

@Composable
fun DonutChart(segments: List<Segment>, centerTop: String, centerBottom: String, modifier: Modifier = Modifier) {
    val percentages=segmentPercentages(segments.map { it.valueRaw })
    val total=segments.fold(BigInteger.ZERO) { sum,s->sum+s.valueRaw }
    val progress=rememberChartProgress()
    Column(modifier.fillMaxWidth(), horizontalAlignment=Alignment.CenterHorizontally) {
        if (total.signum()==0) {
            Text(centerTop,style=FusionType.NumberSmall)
            Text("No recorded allocation yet",style=FusionType.BodySmall)
        } else {
            Box(Modifier.fillMaxWidth().height(152.dp).semantics {
                contentDescription="$centerTop $centerBottom. "+segments.mapIndexed { i,s->"${s.label}: ${s.valueRaw} raw units, ${percentages[i]} percent" }.joinToString(". ")
            },contentAlignment=Alignment.Center) {
                Canvas(Modifier.size(148.dp)) {
                    val sw=13.dp.toPx(); val diameter=size.minDimension-sw; val radius=diameter/2
                    val top=Offset((size.width-diameter)/2,(size.height-diameter)/2)
                    var angle=-90f
                    bucketSegments(segments.map { it.valueRaw }).forEach { s ->
                        val full=(s.end-s.start).toFloat()*360f
                        val width=sw.coerceAtMost(full*radius*0.017453293f/3)
                        val gap=(width+2.dp.toPx())/radius*57.29578f
                        if (full>gap) drawArc(segments[s.bucketIndex].color,angle+gap/2,(full-gap)*progress,false,
                            top,Size(diameter,diameter),style=Stroke(width,cap=StrokeCap.Round))
                        angle+=full
                    }
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally, modifier=Modifier.padding(horizontal=18.dp)) {
                    Text(centerTop,style=FusionType.NumberSmall,textAlign=TextAlign.Center)
                    Text(centerBottom,style=FusionType.BodySmall,textAlign=TextAlign.Center)
                }
            }
        }
        segments.forEachIndexed { i,s ->
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("●",color=s.color,style=FusionType.BodySmall)
                Text(s.label,Modifier.weight(1f),style=FusionType.BodySmall)
                Text("${percentages[i]} %",style=FusionType.NumberCaption)
            }
        }
    }
}

@Composable
fun RadialGauge(value: BigDecimal, max: BigDecimal, centerValue: String, centerUnit: String, status: String, tone: Color, modifier: Modifier = Modifier) {
    val fraction=gaugeFraction(value,max).toFloat()
    val progress=rememberChartProgress()
    Column(modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(152.dp).semantics { contentDescription="$centerValue $centerUnit. $status. Value $value of $max." },contentAlignment=Alignment.Center) {
            Canvas(Modifier.size(148.dp)) {
                val sw=12.dp.toPx(); val d=size.minDimension-sw; val top=Offset(sw/2,sw/2)
                drawArc(FusionColors.Border,135f,270f,false,top,Size(d,d),style=Stroke(sw,cap=StrokeCap.Round))
                if (fraction>0f) drawArc(tone,135f,270f*fraction*progress,false,top,Size(d,d),style=Stroke(sw,cap=StrokeCap.Round))
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(centerValue,style=FusionType.NumberMedium)
                Text(centerUnit,style=FusionType.BodySmall)
            }
        }
        Text(status,style=FusionType.BodySmall,color=tone,textAlign=TextAlign.Center)
    }
}

@Composable
fun WeeklyBars(weeks: List<WeekBucket>, modifier: Modifier = Modifier) {
    if (weeks.isEmpty()) { Text("Spending history unavailable",style=FusionType.BodySmall); return }
    val max=weeks.maxOf { w->w.segments.fold(BigInteger.ZERO) { sum,s->sum+s.valueRaw } }
    val labels=weeks.flatMap { it.segments }.map { it.label }.distinct()
    val progress=rememberChartProgress()
    Column(modifier.fillMaxWidth().semantics {
        contentDescription=weeks.joinToString(". ") { w->w.label+": "+w.segments.joinToString { "${it.label} ${formatBigIntegerAmount(it.valueRaw)} USDC" } }
    }) {
        if (max.signum()==0) Text("No bill payments recorded in these weeks",style=FusionType.BodySmall)
        Canvas(Modifier.fillMaxWidth().height(112.dp).padding(top=12.dp)) {
            val slot=size.width/weeks.size; val width=slot*.56f
            weeks.forEachIndexed { i,w ->
                val total=w.segments.fold(BigInteger.ZERO) { sum,s->sum+s.valueRaw }
                val h=size.height*chartFraction(total,max)*progress
                val left=slot*i+(slot-width)/2
                if (total.signum()==0) drawRoundRect(FusionColors.Border,Offset(left,size.height-2.dp.toPx()),Size(width,2.dp.toPx()),CornerRadius(2.dp.toPx()))
                else {
                    val path=Path().apply { addRoundRect(RoundRect(Rect(left,size.height-h,left+width,size.height),CornerRadius(6.dp.toPx()))) }
                    clipPath(path) {
                        var bottom=size.height
                        w.segments.forEach { segment ->
                            val height=h*chartFraction(segment.valueRaw,total)
                            drawRect(segment.color,Offset(left,bottom-height),Size(width,height)); bottom-=height
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=8.dp)) {
            weeks.forEach { w -> Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(w.label,style=FusionType.BodySmall,textAlign=TextAlign.Center)
                Text(chartAmountLabel(w.segments.fold(BigInteger.ZERO) { sum,s->sum+s.valueRaw }),style=FusionType.NumberCaption,textAlign=TextAlign.Center)
            } }
        }
        labels.forEach { label ->
            val all=weeks.flatMap { it.segments }.filter { it.label==label }
            Text("● $label · ${formatBigIntegerAmount(all.fold(BigInteger.ZERO) { sum,s->sum+s.valueRaw })} USDC",
                color=all.first().color,style=FusionType.BodySmall,modifier=Modifier.padding(top=8.dp))
        }
    }
}

@Composable
fun ProgressBars(rows: List<ProgressRow>, modifier: Modifier = Modifier) {
    val progress=rememberChartProgress()
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if (rows.isEmpty()) Text("No verified data yet",style=FusionType.BodySmall)
        rows.forEach { row ->
            Column(Modifier.fillMaxWidth().semantics { contentDescription="${row.label}: ${row.valueText}. ${row.note.orEmpty()}" }) {
                Text(row.label,style=FusionType.BodySmall)
                Text(row.valueText,style=FusionType.NumberBody)
                if (row.fraction!=null) {
                    Box(Modifier.padding(top=6.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(FusionColors.Border)) {
                        val width=row.fraction.coerceIn(BigDecimal.ZERO,BigDecimal.ONE).toFloat()*progress
                        if (width>0f) Box(Modifier.fillMaxWidth(width).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(row.tone))
                    }
                }
                row.note?.let { Text(it,style=FusionType.BodySmall,modifier=Modifier.padding(top=4.dp)) }
            }
        }
    }
}
