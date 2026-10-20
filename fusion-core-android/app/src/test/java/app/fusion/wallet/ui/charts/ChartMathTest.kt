package app.fusion.wallet.ui.charts

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class ChartMathTest {
    @Test fun segmentPercentagesRoundAndSumTo100() {
        assertEquals(listOf(34,33,33),segmentPercentages(List(3) { BigInteger.ONE }))
        assertEquals(listOf(0,0),segmentPercentages(List(2) { BigInteger.ZERO }))
        assertEquals(listOf(0,100,0),segmentPercentages(listOf(BigInteger.ONE,BigInteger.TEN.pow(40),BigInteger.ZERO)))
        for (n in 1..101) {
            val values=List(n) { BigInteger.valueOf((it+1).toLong()) }
            val result=segmentPercentages(values)
            assertEquals(100,result.sum())
            assertTrue(result.all { it in 0..100 })
        }
    }
    @Test(expected=IllegalArgumentException::class) fun negativeSegmentsRejected() {
        segmentPercentages(listOf(BigInteger.valueOf(-1)))
    }
    @Test fun compactBarLabelsPreserveScaleAndSmallNonzeroAmounts() {
        assertEquals("0",chartAmountLabel(BigInteger.ZERO))
        assertEquals("<0.01",chartAmountLabel(BigInteger.ONE))
        assertEquals("15",chartAmountLabel(BigInteger("15000000")))
        assertEquals("500",chartAmountLabel(BigInteger("500000000")))
        assertEquals("6.3K",chartAmountLabel(BigInteger("6300000000")))
        assertEquals("9.4K",chartAmountLabel(BigInteger("9399000000")))
        assertEquals("1.2K",chartAmountLabel(BigInteger("1200000000")))
        assertEquals("2.5M",chartAmountLabel(BigInteger("2500000000000")))
    }
    @Test fun gaugeClampHandlesZeroAndOverLimit() {
        assertEquals(0,gaugeFraction((-1).toBigDecimal(),100.toBigDecimal()).signum())
        assertEquals(0,gaugeFraction(10.toBigDecimal(),0.toBigDecimal()).signum())
        assertEquals(0,gaugeFraction(150.toBigDecimal(),100.toBigDecimal()).compareTo(1.toBigDecimal()))
        assertEquals(0,gaugeFraction(25.toBigDecimal(),100.toBigDecimal()).compareTo("0.25".toBigDecimal()))
    }
    @Test fun monotoneCubicNeverOvershootsIncludingFlatAndReversals() {
        val series=listOf(listOf(0f,1f,2f,3f),listOf(3f,2f,2f,0f),listOf(0f,100f,0f,100f),listOf(2f,2f,2f,2f))
        series.forEach { ys ->
            val spans=monotoneCubics(ys.mapIndexed { i,y->PlotPoint(listOf(0f,.1f,8f,100f)[i],y) })
            assertEquals(3,spans.size)
            spans.forEach { span ->
                for (step in 0..1000) {
                    val y=span.y(step/1000f)
                    assertTrue("$span at $step = $y", y>=minOf(span.start.y,span.end.y)-.0001f && y<=maxOf(span.start.y,span.end.y)+.0001f)
                }
                assertEquals(span.start.y,span.y(0f),.0001f)
                assertEquals(span.end.y,span.y(1f),.0001f)
            }
        }
    }
    @Test(expected=IllegalArgumentException::class) fun duplicatePlotTimesRejected() {
        monotoneCubics(listOf(PlotPoint(0f,1f),PlotPoint(0f,2f)))
    }
}
