package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.cloud.AccountantBrief
import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.cloud.EgressGuard
import app.fusion.wallet.accountant.cloud.GuardPolicy
import app.fusion.wallet.accountant.cloud.GuardResult
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.domain.BucketMath
import app.fusion.wallet.accountant.domain.Raw
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketAndBriefTest {
    @Test fun reference_state_matches_the_spec_buckets() {
        val b = referenceSnapshot().buckets
        assertEquals(usd(3_500), b.free)
        assertEquals(usd(1_500), b.reserved)
        assertEquals(usd(5_000), b.deployed)
        assertEquals(usd(3_000), b.sweepable)
        assertEquals(BigInteger.ZERO, b.shortfall)
        assertEquals(usd(10_000), b.totalAssets)
        assertEquals(usd(2_000), b.reserved + b.buffer) // protected cash threshold R + B
    }

    @Test fun shortfall_is_shown_and_the_liability_is_never_reduced() {
        val b = BucketMath.compute(usd(1_000), usd(1_500), usd(500), usd(0))
        assertEquals(usd(1_000), b.reserved) // backed reserve = min(C, R)
        assertEquals(usd(1_500), b.obligationTarget)
        assertEquals(BigInteger.ZERO, b.free)
        assertEquals(usd(500), b.shortfall)
        assertEquals(BigInteger.ZERO, b.sweepable)
    }

    @Test fun non_stable_assets_never_count_toward_cash() {
        assertEquals(usd(5_000), referenceSnapshot().cash)
    }

    @Test fun coarse_rounding_is_two_significant_figures() {
        assertEquals("1.2", Raw.coarse(BigInteger("1234567"), 6))
        assertEquals("10000", Raw.coarse(usd(10_000), 6))
        assertEquals("3500", Raw.coarse(usd(3_500), 6))
        assertEquals("0", Raw.coarse(BigInteger.ZERO, 6))
        assertEquals("3200", Raw.coarse(usd(3_199), 6))
        assertEquals("1500", Raw.format(usd(1_500), 6))
        assertEquals(35, Raw.pct(usd(3_500), usd(10_000)))
    }

    @Test fun brief_is_coarse_aggregated_and_passes_the_guard_with_canaries_denied() {
        val brief = BriefBuilder.build(referenceSnapshot(), InsightTopic.LIQUIDITY, "Is my reserve safe?")
        val json = brief.toJson()
        assertEquals("10000", brief.totals.assets)
        assertEquals(listOf(35, 15, 50), listOf(brief.totals.freePct, brief.totals.reservedPct, brief.totals.deployedPct))
        assertEquals(listOf(60, 40), brief.lending.map { it.sharePct })
        // obligations only as aggregated due windows
        assertEquals(listOf("0-7d", "15-30d"), brief.obligations.map { it.window })
        assertEquals(listOf("1000", "500"), brief.obligations.map { it.total })
        // nothing private, no exact figures, no ETH balance
        PRIVATE_CANARIES.forEach { assertFalse(json.contains(it, ignoreCase = true)) }
        assertFalse(json.contains("12345678901234567"))
        assertFalse(json.contains("ETH"))
        assertEquals(GuardResult.Ok, EgressGuard.check(json, GuardPolicy(4096, PRIVATE_CANARIES)))
    }

    @Test fun runway_is_days_of_cash_at_the_30_day_average_outflow() {
        // outflows 3200+3100+800 = 7100 over 30d => 236.67/day => 5000/236.67 = 21 days
        assertEquals(21, BriefBuilder.runwayDays(referenceSnapshot()))
        assertNull(BriefBuilder.runwayDays(referenceSnapshot().copy(flows30d = emptyList())))
    }

    @Test fun due_windows() {
        assertEquals("OVERDUE", BriefBuilder.dueWindow(-1))
        assertEquals("0-7d", BriefBuilder.dueWindow(7 * DAY))
        assertEquals("8-14d", BriefBuilder.dueWindow(7 * DAY + 1))
        assertEquals("31d+", BriefBuilder.dueWindow(31 * DAY))
    }

    @Test fun a_brief_never_carries_more_than_the_schema_allows() {
        val b: AccountantBrief = BriefBuilder.build(referenceSnapshot(), InsightTopic.GENERAL, null)
        val keys = org.json.JSONObject(b.toJson()).keys().asSequence().toSet()
        assertEquals(setOf("schema", "unit", "topic", "totals", "lending", "obligations", "flows30d", "runwayDays"), keys)
        assertTrue(b.flows30d.all { it.category.name in setOf("PAYROLL", "SUPPLIERS", "INFRASTRUCTURE", "TAX", "YIELD", "OTHER") })
    }
}
