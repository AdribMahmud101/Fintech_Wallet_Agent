package app.fusion.wallet.ui.agent

import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

data class DecisionCandidate(val label: String, val title: String, val reason: String, val amount: BigInteger?)
data class DecisionCheck(val name: String, val status: String, val message: String)
data class DecisionDetails(
    val choice: String, val reason: String, val planner: String?, val loadMs: BigDecimal?,
    val decodeMs: BigDecimal?, val inferenceMs: BigDecimal?, val candidates: List<DecisionCandidate>, val checks: List<DecisionCheck>,
    val hash: String, val txHash: String?, val dryRun: Boolean?, val block: Long?,
) {
    companion object {
        fun parse(reportJson: String, passportJson: String? = null): DecisionDetails {
            val report = JSONObject(reportJson)
            val passport = JSONObject(passportJson ?: report.getString("passportJson"))
            val candidates = passport.getJSONArray("candidateSet")
            val checks = report.optJSONObject("preview")?.optJSONArray("checks")
            val model = report.optJSONObject("model")
            val timings = model?.optJSONObject("timings")
            fun decimal(obj: JSONObject?, key: String) = obj?.optString(key)?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
            return DecisionDetails(
                choice = passport.getString("choiceLabel"), reason = passport.optString("reasonCode"),
                planner = passport.optJSONObject("planner")?.optString("kind")?.takeIf { it.isNotBlank() },
                loadMs = decimal(model?.optJSONObject("load"), "coldLoadMs"),
                decodeMs = decimal(timings, "decodeMs"),
                inferenceMs = decimal(timings, "totalMs"),
                candidates = (0 until candidates.length()).map { index ->
                    val c = candidates.getJSONObject(index)
                    DecisionCandidate(c.getString("label"), if (c.optString("label") == "H") "Hold" else decisionTitle(c.optString("kind")),
                        c.optString("reason"), c.optString("assets").toBigIntegerOrNull()?.takeIf { it.signum() >= 0 })
                },
                checks = if (checks == null) emptyList() else (0 until checks.length()).map { index ->
                    val c = checks.getJSONObject(index)
                    DecisionCheck(c.optString("name", c.optString("ruleId")), c.optString("status", "UNKNOWN"), c.optString("message"))
                },
                hash = report.getString("decisionHash"),
                txHash = (report.optString("txHash").takeIf { it.isNotBlank() && it != "null" }
                    ?: report.optJSONObject("execution")?.optString("txHash"))?.takeIf { Regex("0x[0-9a-fA-F]{64}").matches(it) },
                dryRun = if (report.has("dryRun")) report.getBoolean("dryRun") else null,
                block = passport.optJSONObject("snapshot")?.optLong("block")?.takeIf { it > 0 },
            )
        }
    }
}

fun decisionTitle(kind: String): String = when (kind.uppercase()) {
    "DEPOSIT" -> "Invest surplus"
    "WITHDRAW" -> "Return funds to treasury"
    "REBALANCE" -> "Rebalance vaults"
    "PAY_OBLIGATION" -> "Pay bill"
    "HOLD" -> "Hold"
    else -> kind.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
}

internal fun readableAgentError(message: String?): String = when {
    message.isNullOrBlank() -> "Agent run failed. Refresh and try again."
    message.contains("SESSION_LOCK_REQUIRED") || message.contains("edge_rpc.json") ->
        "Live execution requires an authorized session lock. Establish a session before running live."
    message.contains("ACCOUNTING_MISMATCH") || message.contains("Commitment proof root mismatch") ->
        "Bill metadata does not match the on-chain commitment. Refresh the treasury and review committed bills."
    message.contains("UnsatisfiedLinkError") || message.contains("dlopen failed") ->
        "The on-device planner is unavailable. Check the model setup before running again."
    else -> message
}
