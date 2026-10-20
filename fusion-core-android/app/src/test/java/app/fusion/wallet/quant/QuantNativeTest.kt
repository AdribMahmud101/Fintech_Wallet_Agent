package app.fusion.wallet.quant

import app.fusion.wallet.inference.core.evaluateEconomics
import app.fusion.wallet.inference.core.quantReport
import app.fusion.wallet.data.QuantReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Calls the production Rust code through the generated Kotlin boundary: no copied formulas. */
class QuantNativeTest {
    private fun input() = JSONObject("""{"blockNumber":50000,"vault":"0x1111111111111111111111111111111111111111",
        "medianGasWei":"1000000000000","quotedSizeRaw":"10000000","depositSlippageRaw":"1",
        "oracle":{"feed":"${QuantReader.ETH_USD_FEED}","decimals":8,"answer":"200000000000","roundId":"1","answeredInRound":"1","updatedAt":100000},
        "reserve":{"availableRaw":"150","variableDebtRaw":"850","stableDebtRaw":"0","liquidityRateRay":"2000000000000000000000000","liquidityIndex":"1000000000000000000000000000"}}""")
    private fun report(q: JSONObject = input(), now: ULong = 100000u) = JSONObject(quantReport(q.toString(), now, "10000000")).getJSONObject("quant")
    private fun economics(size: String="365000000", new: ULong=10000000u, current: ULong=0u, gas: String?="100000", slip: String="40000") =
        JSONObject(evaluateEconomics(size,new,current,gas,slip)).getJSONObject("economics")
    @Test fun fourteenDaysIncludesGasAndSlippageAndUsesExactBoundary() {
        val e=economics();assertEquals("ACTIONABLE",e.getString("status"));assertEquals("1400",e.getString("breakEvenDaysHundredths"))
        assertEquals("365000000",e.getString("actionableSizeRaw"))
        assertEquals("UNPROFITABLE_AFTER_GAS",economics(gas="100001").getString("status"))
    }
    @Test fun zeroAndNegativeDeltaAndUnknownGasCannotAuthorize() {
        for ((new,current) in listOf(0uL to 0uL, 1uL to 1uL, 0uL to 1uL)) {
            val e=economics(new=new,current=current);assertEquals("UNPROFITABLE_AFTER_GAS",e.getString("status"));assertTrue(e.isNull("breakEvenDaysHundredths"))
        }
        assertEquals("UNPROFITABLE_AFTER_GAS",economics(size="0").getString("status"))
        assertEquals("UNMEASURED",economics(gas=null).getString("status"))
    }
    @Test fun utilizationAndWithdrawableAreMeasuredAndThresholdIsStrict() {
        val q=input();assertEquals(8500,report(q).getInt("utilizationBps"));assertTrue(report(q).getBoolean("depositAllowed"))
        assertEquals("150",report(q).getString("withdrawableNowRaw"))
        q.getJSONObject("reserve").put("stableDebtRaw","1");assertFalse(report(q).getBoolean("depositAllowed"))
        q.getJSONObject("reserve").put("availableRaw","0");assertEquals(10000,report(q).getInt("utilizationBps"))
    }
    @Test fun staleFutureAndInvalidOracleAreUnmeasured() {
        assertEquals("2000",report().getString("gasUsdRaw"))
        assertTrue(report(now=101201u).isNull("gasUsdRaw"));assertTrue(report(now=99999u).isNull("gasUsdRaw"))
        for (answer in listOf("0","-1")) { val q=input();q.getJSONObject("oracle").put("answer",answer);assertTrue(report(q).isNull("gasUsdRaw")) }
        val q=input();q.remove("oracle");assertTrue(report(q).isNull("gasUsdRaw"))
    }
    @Test fun realizedApyCapsSpotAndArchiveAbsenceIsLabelled() {
        val q=input();assertEquals("spot only",report(q).getString("apyLabel"));assertTrue(report(q).getLong("conservativeApyPpb")>2000000)
        q.getJSONObject("reserve").put("pastIndex","1000000000000000000000000000").put("pastTimestamp",13600).put("pastBlock",6800)
        assertEquals(0L,report(q).getLong("conservativeApyPpb"));assertEquals("min(spot, realized ~24h)",report(q).getString("apyLabel"))
    }
}
