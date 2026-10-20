package app.fusion.wallet.agent.autopilot

import app.fusion.wallet.data.*
import org.junit.Assert.*
import org.junit.Test

class EventRouterTest {
    private val now = 1_800_000_000_000L
    private fun wallet(): WalletState {
        val zero = Money.ZERO
        val policy = Policy(PermissionTier.AUTONOMOUS, zero, zero, zero, zero, zero, 0, 0, "guardian", 0, 259200)
        val module = ModuleState("module", true, false, true, policy, 1.toBigInteger(), "p", "m", "r", zero,
            0.toBigInteger(), 0.toBigInteger(), zero, zero, "s", "k", now / 1000 + 86400, zero, zero)
        return WalletState(status = LoadStatus.READY, blockNumber = 10, capturedAtEpochMillis = now, module = module,
            accountingMatches = true, buckets = Buckets(zero, zero, zero, Money.fromRaw("101000000"), zero, zero, zero, Money.fromRaw("1000000000"), zero))
    }
    @Test fun idleCashRequiresMeasuredActionableBreakEvenAndSuppressesRepeatedWakes() {
        val router = EventRouter()
        val w = wallet()
        assertNull(router.evaluate(w, now))
        assertNull(router.evaluate(w, now, CognitiveMetrics(false)))
        assertEquals("Idle spare cash with actionable break-even", router.evaluate(w, now, CognitiveMetrics(true)))
        assertNull(router.evaluate(w, now, CognitiveMetrics(true)))
        router.evaluate(w.copy(buckets = w.buckets!!.copy(surplus = Money.ZERO)), now, CognitiveMetrics(true))
        assertNotNull(router.evaluate(w, now, CognitiveMetrics(true)))
    }
    @Test fun bucketDriftUsesStrictFivePercentagePointBoundaryAndUnavailableInputHolds() {
        val w = wallet()
        val router = EventRouter()
        val exact = w.copy(buckets = w.buckets!!.copy(bufferTarget = Money.fromRaw("50000000")))
        assertNull(router.evaluate(exact, now))
        assertTrue(router.evaluate(exact.copy(buckets = exact.buckets!!.copy(bufferTarget = Money.fromRaw("50000001"))), now)!!.contains("Bucket drift"))
        assertTrue(router.evaluate(w.copy(accountingMatches = null), now)!!.startsWith("Input unreadable"))
        assertTrue(router.evaluate(w.copy(refreshing = true), now)!!.startsWith("Input unreadable"))
        assertTrue(router.evaluate(w, now + 30000)!!.startsWith("Input unreadable"))
    }
    @Test fun gasSpikeDefersOnlyAboveThresholdAndOnlyDueNowBillsAreExempt() {
        val t = GasSpikePolicy.THRESHOLD_WEI
        assertNull(GasSpikePolicy.holdReason(t, 0, null, 100))
        assertEquals("Gas spike, deferred", GasSpikePolicy.holdReason(t + 1.toBigInteger(), 0, null, 100))
        assertEquals("Gas spike, deferred", GasSpikePolicy.holdReason(t + 1.toBigInteger(), 3, 101, 100))
        assertNull(GasSpikePolicy.holdReason(t + 1.toBigInteger(), 3, 100, 100))
        assertNull(GasSpikePolicy.holdReason(t + 1.toBigInteger(), 3, 99, 100))
        assertTrue(GasSpikePolicy.holdReason(null, 3, 99, 100)!!.contains("unreadable"))
    }
}
