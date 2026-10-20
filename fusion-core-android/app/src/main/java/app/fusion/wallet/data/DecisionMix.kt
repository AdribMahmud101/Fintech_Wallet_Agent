package app.fusion.wallet.data

import org.json.JSONObject
import java.math.BigInteger

/** Only explicitly recorded APP/AGENT provenance can enter this chart. */
fun decisionMix(actions: List<AgentAction>, index: TxIndex, latestReport: String?): Map<String,BigInteger> {
    val counts=linkedMapOf("PAY" to BigInteger.ZERO,"DEPOSIT" to BigInteger.ZERO,"WITHDRAW" to BigInteger.ZERO,"HOLD" to BigInteger.ZERO)
    actions.distinctBy { it.txHash.lowercase() to it.id }.forEach { action ->
        if (index.find(action.txHash)?.source !in setOf("AGENT","APP")) return@forEach
        val kind=when(action.kind) { ActionKind.PAY_OBLIGATION->"PAY"; ActionKind.DEPOSIT->"DEPOSIT"; ActionKind.WITHDRAW->"WITHDRAW"; else->null }
        if (kind!=null) counts[kind]=counts.getValue(kind)+BigInteger.ONE
    }
    // There is one saved report, not a durable Hold history. Count at most that one live Hold.
    runCatching {
        val report=JSONObject(latestReport ?: return@runCatching)
        if (report.optString("source") !in setOf("APP","AGENT") || !report.has("dryRun") || report.getBoolean("dryRun")) return@runCatching
        val passport=JSONObject(report.getString("passportJson"))
        if (passport.getString("route")=="HOLD" && report.optString("txHash").isBlank() &&
            actions.none { it.decisionHash.equals(report.getString("decisionHash"),true) }) counts["HOLD"]=BigInteger.ONE
    }
    return counts
}
