package app.fusion.wallet.treasury

import app.fusion.wallet.agent.AgentTickResult
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.treasury.earnPreviewSummary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EarnPreviewTest {
    private fun result(kind: Int, cash: String = "10000000", assets: String = "2000000", route: String = "INSTANT"): AgentTickResult {
        val snapshot = JSONObject().put("safeCash", cash).put("moduleState", JSONObject().put("reservedRaw", "7000000"))
            .put("policy", JSONObject().put("operatingBufferRaw", "1000000"))
        val action = JSONObject().put("assets", assets).put("destinationVault", "aave").put("recipient", "vendor")
        val report = JSONObject().put("snapshot", snapshot).put("chosenCandidate", JSONObject().put("kind", kind).put("action", action))
        return AgentTickResult("A", "SURPLUS_AVAILABLE", route, "hash", "{}", 123, true, 1.0, 0, true, report.toString())
    }
    @Test fun coverageUsesDecisionSnapshotAndActualActionAmount() {
        val state = WalletState() // UI may have different/absent buckets: use the decision's snapshot.
        assertTrue(earnPreviewSummary(result(0), state).contains("bills stay covered"))
        assertFalse(earnPreviewSummary(result(0, assets = "3000000"), state).contains("bills stay covered"))
        assertTrue(earnPreviewSummary(result(0), state).contains("2.00 USDC"))
    }
    @Test fun failedPolicyNeverClaimsInvestment() {
        val decision = result(0).copy(previewPassed = false)
        assertTrue(earnPreviewSummary(decision, WalletState()).startsWith("The AI would hold:"))
    }
    @Test fun holdAndOwnerRoutesAreExplicit() {
        assertTrue(earnPreviewSummary(result(0).copy(choiceLabel = "H"), WalletState()).startsWith("The AI would hold:"))
        assertTrue(earnPreviewSummary(result(0, route = "APPROVE"), WalletState()).contains("both Safe owners"))
        assertTrue(earnPreviewSummary(result(0, route = "QUEUE"), WalletState()).contains("queued"))
    }
}
