package app.fusion.wallet.treasury

import app.fusion.wallet.data.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class HomeMetricsTest {
    private fun n(value: Long) = BigInteger.valueOf(value)
    @Test fun allowanceClampsOverspentAndZeroLimitsWithoutInventingCapacity() {
        assertEquals(n(75), aiAllowance(n(25), n(100), n(20)).remaining)
        assertEquals(0.75f, aiAllowance(n(25), n(100), n(20)).fraction)
        assertEquals(n(20), aiAllowance(n(25), n(100), n(20)).perMove)
        assertEquals(BigInteger.ZERO, aiAllowance(n(110), n(100), n(20)).remaining)
        assertEquals(0f, aiAllowance(n(0), n(0), n(0)).fraction)
    }
    @Test fun gasUsesMedianAndFloorsMovesWithExactWei() {
        assertEquals(n(5), gasRunway(n(101), listOf(n(1000), n(10), n(20))).estimatedMoves)
        assertEquals(n(4), gasRunway(n(10), listOf(n(2), n(3))).estimatedMoves)
        assertEquals(n(0), gasRunway(n(0), listOf(n(2))).estimatedMoves)
        assertNull(gasRunway(n(100), emptyList()).estimatedMoves)
        assertNull(gasRunway(n(100), listOf(n(0))).estimatedMoves)
        val huge = BigInteger.TEN.pow(40)
        assertEquals(huge / n(3), gasRunway(huge, listOf(n(3))).estimatedMoves)
    }
    @Test fun emptyQueueHidesBannerEvenWhenBillsExist() {
        assertFalse(approvalBannerVisible(emptyList()))
        val q = QueuedAction("digest", ActionKind.PAY_OBLIGATION, Money(n(2500)), 100, 200,
            QueueStatus.WAITING, "vendor", "decision", BigInteger.ZERO)
        assertTrue(approvalBannerVisible(listOf(q)))
        assertTrue(approvalBannerVisible(listOf(q.copy(status = QueueStatus.EXPIRED))))
    }
    @Test fun billCoverageIncludesSafetyCushion() {
        assertFalse(billsStayCovered(null))
        assertFalse(billsStayCovered(BucketMath.compute(n(90), n(100), n(10), n(0))))
        assertFalse(billsStayCovered(BucketMath.compute(n(105), n(100), n(10), n(0))))
        assertTrue(billsStayCovered(BucketMath.compute(n(110), n(100), n(10), n(0))))
    }
}
