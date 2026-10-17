package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.agent.LocalAccountant
import app.fusion.wallet.accountant.data.Severity
import app.fusion.wallet.accountant.domain.Aave
import app.fusion.wallet.accountant.domain.GasView
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {
    @Test fun aave_rate_ray_converts_to_basis_points_using_the_real_base_sepolia_value() {
        // currentLiquidityRate of the Aave V3 USDC reserve on Base Sepolia, read on-chain 2026-10-17:
        // 2074789914389810119812431 ray = 0.2075% APR
        assertEquals(21, Aave.aprBps(BigInteger("2074789914389810119812431")))
        assertEquals("0.21%", Aave.pct(21))
        assertEquals(0, Aave.aprBps(BigInteger.ZERO))
        assertEquals(10_000, Aave.aprBps(BigInteger.TEN.pow(27))) // 100% APR
        assertEquals(500, Aave.aprBps(BigInteger("50000000000000000000000000"))) // 5%
        assertEquals("5.00%", Aave.pct(500))
        assertEquals("0.05%", Aave.pct(5))
    }

    @Test fun gas_is_native_not_part_of_treasury_value_and_has_a_runway() {
        val s = referenceSnapshot()
        assertEquals(usd(5_000) + usd(5_000), s.buckets.totalAssets) // ETH never counted
        val g = s.gas!!
        // 0.012345678901234567 ETH / 0.00003 ETH per action = 411 actions
        assertEquals(411, g.runwayActions)
        assertEquals(0, GasView(BigInteger.ZERO, BigInteger.TEN).runwayActions)
        assertEquals(0, GasView(BigInteger.TEN, BigInteger.ZERO).runwayActions)
        assertEquals(9_999, GasView(BigInteger.TEN.pow(30), BigInteger.ONE).runwayActions)
    }

    @Test fun low_gas_raises_a_warning_and_healthy_gas_does_not() {
        val low = referenceSnapshot().copy(gas = GasView(BigInteger("100000000000000"), BigInteger("30000000000000")))
        assertTrue(LocalAccountant.analyze(low).any { it.severity == Severity.WARNING && it.text.contains("Gas fuel") })
        assertFalse(LocalAccountant.analyze(referenceSnapshot()).any { it.text.contains("Gas fuel") })
    }

    @Test fun yield_text_says_apr_not_apy() {
        val text = LocalAccountant.analyze(referenceSnapshot()).first { it.text.contains("supply rate") }.text
        assertTrue(text, text.contains("APR"))
        assertFalse(text, text.contains("APY"))
    }
}
