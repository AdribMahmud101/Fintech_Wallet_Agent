package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.data.BucketSnapshotEntity
import app.fusion.wallet.accountant.domain.Analytics
import app.fusion.wallet.accountant.domain.FlowView
import app.fusion.wallet.accountant.data.FlowCategory
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticsTest {
    private val week = 7 * DAY

    @Test fun weekly_spend_groups_outflows_oldest_first_and_ignores_income_and_old_data() {
        val s = referenceSnapshot()
        val w = Analytics.weeklySpend(s.flows30d, NOW, 6, weeks = 4)
        assertEquals(4, w.size)
        // payroll 3200 (5d ago -> this week), payroll 3100 (20d ago -> 3 weeks ago ... 20/7=2 -> index 1), supplier 800 (9d ago -> 1 -> index 2)
        assertEquals(usd(3_200), w[3].payroll)
        assertEquals(usd(800), w[2].suppliers)
        assertEquals(usd(3_100), w[1].payroll)
        assertEquals(BigInteger.ZERO, w[0].total)
        // the 41 USDC yield is income and must not count as spending
        assertEquals(usd(3_200 + 3_100 + 800), w.fold(BigInteger.ZERO) { a, x -> a + x.total })
    }

    @Test fun flows_outside_the_window_or_in_the_future_are_dropped() {
        val flows = listOf(
            FlowView(FlowCategory.PAYROLL, false, usd(10), 6, NOW - 40 * DAY),
            FlowView(FlowCategory.PAYROLL, false, usd(20), 6, NOW + DAY),
            FlowView(FlowCategory.TAX, false, usd(30), 6, NOW - 1),
        )
        val w = Analytics.weeklySpend(flows, NOW, 6)
        assertEquals(usd(30), w[3].other) // non-payroll, non-supplier categories collapse into "other"
        assertEquals(usd(30), w.fold(BigInteger.ZERO) { a, x -> a + x.total })
    }

    @Test fun a_flow_exactly_on_a_week_boundary_lands_in_the_older_week() {
        val w = Analytics.weeklySpend(listOf(FlowView(FlowCategory.PAYROLL, false, usd(5), 6, NOW - week)), NOW, 6)
        assertEquals(usd(5), w[2].payroll)
        assertEquals(BigInteger.ZERO, w[3].total)
    }

    @Test fun change_percent_rounds_to_whole_numbers_and_handles_no_base() {
        assertEquals(3, Analytics.changePct(usd(9_690), usd(10_000)))
        assertEquals(-10, Analytics.changePct(usd(1_000), usd(900)))
        assertEquals(0, Analytics.changePct(usd(1_000), usd(1_000)))
        assertNull(Analytics.changePct(BigInteger.ZERO, usd(5)))
    }

    @Test fun totals_sum_cash_reserved_and_deployed_in_time_order() {
        fun snap(t: Long, f: Long, r: Long, d: Long) = BucketSnapshotEntity(
            accountId = 1, freeRaw = usd(f).toString(), reservedRaw = usd(r).toString(), deployedRaw = usd(d).toString(),
            sweepableRaw = "0", shortfallRaw = "0", observedAt = t,
        )
        val out = Analytics.totals(listOf(snap(2, 3_500, 1_500, 5_000), snap(1, 3_000, 1_500, 4_000)))
        assertEquals(listOf(usd(8_500), usd(10_000)), out)
    }

    @Test fun bill_timing_splits_by_due_date() {
        val t = Analytics.billTiming(referenceSnapshot().obligations, NOW, 6)
        assertEquals(usd(1_000), t.thisWeek)   // due in 3 days
        assertEquals(usd(500), t.thisMonth)    // due in 20 days
        assertEquals(BigInteger.ZERO, t.overdue)
        assertEquals(usd(1_500), t.total)
    }
}
