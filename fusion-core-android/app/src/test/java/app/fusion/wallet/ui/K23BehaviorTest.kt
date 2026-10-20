package app.fusion.wallet.ui

import app.fusion.wallet.data.*
import app.fusion.wallet.ui.activity.*
import app.fusion.wallet.ui.charts.*
import app.fusion.wallet.ui.components.Provenance
import app.fusion.wallet.ui.components.formatBigIntegerAmount
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class K23BehaviorTest {
    private val tx = "0x" + "a".repeat(64)
    private val passport = "0x" + "b".repeat(64)

    @Test fun signedAndWholeUnitFormattingStaysExact() {
        assertEquals("-0.01", formatBigIntegerAmount(BigInteger("-5000")))
        assertEquals("-1.234567", formatBigIntegerAmount(BigInteger("-1234567"), exact = true))
        assertEquals("10", formatBigIntegerAmount(BigInteger.TEN, decimals = 0, exact = true))
        assertEquals("1,000.00", formatBigIntegerAmount(BigInteger("999999999")))
        assertEquals("123,456,789,012,345,678,901,234.567890",
            formatBigIntegerAmount(BigInteger("123456789012345678901234567890"), exact = true))
    }

    @Test fun summaryHandlesZeroBaselineAndLargeBalancesWithoutInventedStability() {
        val zeroStart = listOf(BalancePoint(1, BigInteger.ZERO), BalancePoint(2, BigInteger("1000000")))
        assertEquals("1.000000 USDC change · 7 days", balanceSummary(zeroStart, 7))
        val first = BigInteger.TEN.pow(300)
        assertEquals("+10.0% across recorded points · 30 days", balanceSummary(
            listOf(BalancePoint(1, first), BalancePoint(2, first.multiply(BigInteger.valueOf(11)).divide(BigInteger.TEN))), 30))
        assertEquals(.5f, chartFraction(first, first * BigInteger.TWO), 0f)
    }

    @Test fun sameTransactionEventsSurviveAndScriptDoesNotBecomeAgentOrOwner() {
        val action = AgentAction("a", ActionKind.DEPOSIT, Money(BigInteger("1000000")), null,
            tx, passport, 101, 1000, source = "SCRIPT")
        val events = listOf(
            EvidenceRow("log:1", "ActionExecuted", tx, 101, 1000, passport, explorerUrl = "https://example.test/$tx"),
            EvidenceRow("log:2", "Paused", tx, 101, 1000, null, explorerUrl = "https://example.test/$tx"))
        // SCRIPT must be recorded explicitly: module rows inherit a deployment default.
        val index = TxIndex.parse("""[{"txHash":"$tx","source":"SCRIPT","kind":"DEPOSIT","evidenceFile":"fixture.json"}]""")
        val rows = ActivityTimelineBuilder.build(WalletState(recentActions = listOf(action), evidence = events), SafetyData(), txIndex = index)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.provenance == Provenance.Script })
        assertEquals(BigInteger("1000000"), rows.first { it.title == "Deposit to vault" }.amountRaw)
        assertTrue(rows.all { it.gasUsed == null })
        assertTrue(rows.none { it.matches(ActivityFilter.Agent) })
        assertTrue(rows.all { it.matches(ActivityFilter.All) })
    }

    @Test fun frozenEvidencePreservesExplicitOriginAndUnknownTime() {
        val frozen = ActivityTimelineBuilder.parseFrozen("""{"rows":[
            {"id":"r","txHash":"$tx","block":102,"status":0,"kind":"INTENTIONAL_REVERT","scenario":"Limit exceeded","source":"APP","decisionHash":"$passport"}
        ]}""")
        val rows = ActivityTimelineBuilder.build(WalletState(), SafetyData(), frozen)
        assertEquals(1, rows.size)
        assertNull(rows.single().timestampSeconds)
        assertNull(rows.single().gasUsed)
        assertEquals(Provenance.App, rows.single().provenance)
        assertTrue(rows.single().matches(ActivityFilter.App))
        assertTrue(rows.single().matches(ActivityFilter.Blocked))
        assertFalse(rows.single().matches(ActivityFilter.Owner))
        assertEquals(passport, rows.single().passportHash)
    }

    @Test fun liveLogsTakePrecedenceOverDuplicatedFrozenTransaction() {
        val frozen = ActivityItem("frozen", "Bill paid", provenance = Provenance.App, txHash = tx, blockNumber = 101)
        val ev = EvidenceRow("log", "ObligationPaid", tx, 101, 1000, null, explorerUrl = "https://example.test")
        val rows = ActivityTimelineBuilder.build(WalletState(evidence = listOf(ev)), SafetyData(), listOf(frozen))
        assertEquals(1, rows.size)
        assertEquals(Provenance.App, rows.single().provenance)
        assertEquals(1000L, rows.single().timestampSeconds)
    }
}
