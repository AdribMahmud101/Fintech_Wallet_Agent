package app.fusion.wallet.ui.common

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DecisionDisplayTest {
    @Test fun amountsUseUsdcUnitsAndMetadataUsesPlainLanguage() {
        assertEquals("Amount 0 USDC · max per action 2,500 USDC", readableCheckMessage("assets=0, hard_max=2500000000"))
        assertEquals("Used today 2,161 / 4,000 USDC · this session 8 / 7,998 USDC", readableCheckMessage("daily_used=2161000000/4000000000, session_used=8000000/7998000000"))
        assertEquals("Nonce, epoch, deadline and decision hash all valid", readableCheckMessage("nonce_ok=true, epoch_ok=true, deadline_ok=true, decision_hash_ok=true"))
        assertEquals("Invalid: nonce", readableCheckMessage("nonce_ok=false, epoch_ok=true, deadline_ok=true, decision_hash_ok=true"))
    }
    @Test fun absentTimingsAreOmittedAndSecondsAreFormatted() {
        assertEquals("Load 1.45 s · Inference 2.88 s", decisionTimings(BigDecimal("1450"), BigDecimal("2880"), null))
        assertEquals("", decisionTimings(null, null, null))
    }
    @Test fun historicalUsageDoesNotMasqueradeAsTodaysCounter() {
        val raw = "daily_used=2161000000/4000000000, session_used=8000000/7998000000"
        assertTrue(historicalCheckMessage(raw, 86400, 172800).startsWith("Daily counter has reset"))
        assertTrue(historicalCheckMessage(raw, 86400, 86401).startsWith("Used today 2,161"))
    }
    @Test fun emptyCandidateHoldShowsMeasuredGasAdjustedBreakEvenOnLegacyReports() {
        val quant = org.json.JSONObject().put("candidates", org.json.JSONArray().put(org.json.JSONObject()
            .put("status", "UNPROFITABLE_AFTER_GAS").put("sizeRaw", "10000000").put("breakEvenDaysHundredths", "5601")))
        val report = org.json.JSONObject().put("chosenCandidate", org.json.JSONObject().put("label", "H"))
            .put("candidateSet", org.json.JSONObject().put("candidates", org.json.JSONArray()))
            .put("quant", quant)
        assertEquals("10 USDC breaks even in 57 days; outside the 14-day window", decisionReason(report, "HOLD"))
    }
}
