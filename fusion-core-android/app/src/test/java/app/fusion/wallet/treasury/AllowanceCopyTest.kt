package app.fusion.wallet.treasury

import app.fusion.wallet.data.Money
import app.fusion.wallet.data.PermissionTier
import app.fusion.wallet.data.Policy
import app.fusion.wallet.data.allowanceCopy
import app.fusion.wallet.ui.common.custodyRatio
import app.fusion.wallet.ui.components.formatBigIntegerAmount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowanceCopyTest {
    private fun raw(usdc: Long) = Money(BigInteger.valueOf(usdc).multiply(Money.SCALE))
    private fun policy(tier: PermissionTier = PermissionTier.AUTONOMOUS) = Policy(
        permissionTier = tier,
        operatingBuffer = raw(500),
        microMax = raw(10),
        delayedMax = raw(100),
        hardMax = raw(2_500),
        dailyLimit = raw(4_000),
        delaySeconds = 180,
        maxShareSlippageBps = 50,
        guardian = "0x" + "1".repeat(40),
        payeeDelaySeconds = 3_600,
        payEarlyWindowSeconds = 259_200,
    )
    private fun format(money: Money) = formatBigIntegerAmount(money.raw)

    @Test
    fun labelsNameTheInstantCeilingAndTheDailyCapSeparately() {
        val copy = allowanceCopy(policy(), raw(2_093), ::format)
        assertEquals("Per move: up to 10.00 USDC (auto)", copy.perMove)
        assertEquals("Daily cap: 4,000.00 USDC", copy.dailyCap)
        assertEquals("1,907.00 of 4,000.00 USDC left", copy.remaining)
    }

    @Test
    fun explanationStatesTheContractsActualTiersAndQueueDuration() {
        val explanation = allowanceCopy(policy(), raw(0), ::format).explanation
        assertTrue(explanation.contains("at or below this amount can execute immediately"))
        assertTrue(explanation.contains("Above 10.00 USDC and up to 100.00 USDC, the agent must queue the move for 180 seconds"))
        assertTrue(explanation.contains("this tier is not owner-approved"))
        assertTrue(explanation.contains("Above 100.00 USDC and up to 2,500.00 USDC"))
        assertTrue(explanation.contains("both Safe owners must approve"))
        assertTrue(explanation.contains("Above 2,500.00 USDC the contract rejects the move"))
        assertTrue(explanation.contains("UTC-day gross"))
    }

    @Test
    fun remainingClampsAtZeroWhenTheDayIsOverspent() {
        val copy = allowanceCopy(policy(), raw(4_500), ::format)
        assertEquals("0.00 of 4,000.00 USDC left", copy.remaining)
    }

    @Test
    fun ownerApprovedPolicyDoesNotClaimAnAutoTier() {
        val copy = allowanceCopy(policy(PermissionTier.OWNER_APPROVED), raw(0), ::format)
        assertTrue(copy.perMove.contains("both Safe owners must approve"))
        assertFalse(copy.perMove.contains("(auto)"))
        assertFalse(copy.explanation.contains("queue"))
    }

    @Test
    fun custodyRatioOnlyLabelsKnownThresholdAndOwnerCounts() {
        assertEquals("2-of-2", custodyRatio(2, 2))
        assertNull(custodyRatio(null, 2))
        assertNull(custodyRatio(2, null))
    }

    @Test
    fun readOnlyPolicyDoesNotClaimAgentExecution() {
        val copy = allowanceCopy(policy(PermissionTier.READ_ONLY), raw(0), ::format)
        assertTrue(copy.perMove.contains("read-only"))
        assertFalse(copy.explanation.contains("execute immediately"))
    }
}
