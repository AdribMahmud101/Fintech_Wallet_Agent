package app.fusion.wallet.ui

import app.fusion.wallet.ui.charts.bucketSegments
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class BucketSegmentsTest {
    @Test fun segmentsAreSolidContiguousAndProportional() {
        val segments = bucketSegments(listOf(20, 30, 50).map { it.toBigInteger() })
        assertEquals(listOf(0, 1, 2), segments.map { it.bucketIndex })
        assertEquals(listOf("0.200000000000000000000000", "0.300000000000000000000000", "0.500000000000000000000000"),
            segments.map { (it.end - it.start).toPlainString() })
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
        assertEquals(0, segments.first().start.signum())
        assertEquals(0, segments.last().end.compareTo(java.math.BigDecimal.ONE))
    }
    @Test fun zerosProduceNoSegmentAndLargeMoneyDoesNotOverflow() {
        assertTrue(bucketSegments(List(3) { BigInteger.ZERO }).isEmpty())
        val segments = bucketSegments(listOf(BigInteger.ZERO, BigInteger.TEN.pow(300), BigInteger.ZERO))
        assertEquals(1, segments.size)
        assertEquals(1, segments.single().bucketIndex)
        assertEquals(0, segments.single().end.compareTo(java.math.BigDecimal.ONE))
    }
}
