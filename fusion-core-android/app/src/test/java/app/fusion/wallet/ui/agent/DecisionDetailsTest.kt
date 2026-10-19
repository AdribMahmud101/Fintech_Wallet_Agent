package app.fusion.wallet.ui.agent

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DecisionDetailsTest {
    private fun report() = requireNotNull(javaClass.getResourceAsStream("/k24/agent-report.json")).bufferedReader().use { it.readText() }

    @Test fun parsesTheRecordedAndroidHoldWithoutInventingCandidatesOrTransactions() {
        val data = DecisionDetails.parse(report())
        assertEquals("H", data.choice)
        assertEquals("mnn+ahem-g@android", data.planner)
        assertEquals(BigDecimal("853.769114"), data.loadMs)
        assertNull(data.decodeMs)
        assertEquals(BigDecimal("502.28843700000004"), data.inferenceMs)
        assertEquals(listOf("H"), data.candidates.map { it.label })
        assertEquals("Hold", data.candidates.single().title)
        assertEquals(BigInteger.ZERO, data.candidates.single().amount)
        assertEquals(7, data.checks.size)
        assertTrue(data.checks.all { it.status == "PASS" })
        assertTrue(data.dryRun == true)
        assertNull(data.txHash)
    }

    @Test fun missingTimingsAreUnknownAndPolicyFailureIsPreserved() {
        val report = JSONObject(report())
        report.remove("model")
        report.getJSONObject("preview").getJSONArray("checks").getJSONObject(0).put("status", "FAIL")
        val data = DecisionDetails.parse(report.toString())
        assertNull(data.loadMs)
        assertNull(data.decodeMs)
        assertEquals("FAIL", data.checks.first().status)
    }

    @Test fun uint256CandidateAssetsNeverBecomeFloatingPoint() {
        val report = JSONObject(report())
        val passport = JSONObject(report.getString("passportJson"))
        val raw = BigInteger.ONE.shiftLeft(255).toString()
        passport.getJSONArray("candidateSet").getJSONObject(0).put("label", "A").put("assets", raw).put("kind", "PAY_OBLIGATION")
        passport.put("choiceLabel", "A")
        report.put("passportJson", passport.toString())
        assertEquals(BigInteger(raw), DecisionDetails.parse(report.toString()).candidates.single().amount)
    }
}
