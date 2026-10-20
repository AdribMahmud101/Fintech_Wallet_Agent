package app.fusion.wallet.advisor

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/** The public parts of one agent decision report; built by the UI from a passport, preview or tick. */
data class DecisionFacts(
    val choice: String,
    val chosenTitle: String,
    val reasonCode: String,
    /** DRY_RUN, LIVE or UNKNOWN. */
    val mode: String,
    /** HOLD, PREVIEW, EXECUTED, UNCONFIRMED or BLOCKED. */
    val outcome: String,
    val candidates: List<Candidate>,
    val checks: List<Check>,
) {
    data class Candidate(val label: String, val title: String, val reasonCode: String, val amountRaw: BigInteger?)
    data class Check(val name: String, val status: String, val message: String)

    /** Free text from the report is capped and redacted; amounts are USDC decimals. */
    fun toJson(): String {
        fun s(v: String, max: Int = 160) = AdvisorEgress.redact(v).take(max)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("choice", s(choice, 4))
            .put("chosenTitle", s(chosenTitle, 60))
            .put("reasonCode", s(reasonCode, 80))
            .put("mode", s(mode, 12))
            .put("outcome", s(outcome, 12))
            .put("candidates", JSONArray().apply {
                candidates.take(MAX_CANDIDATES).forEach { c ->
                    put(JSONObject().put("label", s(c.label, 4)).put("title", s(c.title, 60)).put("reasonCode", s(c.reasonCode, 80))
                        .put("amountUsdc", c.amountRaw?.let(::usdc) ?: "none"))
                }
            })
            .put("checks", JSONArray().apply {
                checks.take(MAX_CHECKS).forEach { c -> put(JSONObject().put("name", s(c.name, 60)).put("status", s(c.status, 12)).put("message", s(c.message))) }
            })
            .toString()
    }

    companion object {
        const val SCHEMA = "fw.advisor.decision.v1"
        const val MAX_CANDIDATES = 10
        const val MAX_CHECKS = 30
    }
}

/** Plain decimal USDC from 6-decimal raw units, no trailing zeros ("12.5", "0"). */
fun usdc(raw: BigInteger): String = BigDecimal(raw).movePointLeft(6).stripTrailingZeros().toPlainString()

data class Explanation(val text: String, val points: List<String>)

sealed interface Checked<out T> {
    data class Valid<T>(val value: T) : Checked<T>
    data class Rejected(val reasons: List<String>) : Checked<Nothing> {
        constructor(reason: String) : this(listOf(reason))
    }
}

/** `{"explanation": string, "points": [string]}` and nothing else. */
object ExplanationValidator {
    const val MAX_TEXT = 700
    const val MAX_POINT = 160
    const val MAX_POINTS = 4
    private val KEYS = setOf("explanation", "points")

    fun validate(raw: String): Checked<Explanation> {
        val o = try { JSONObject(raw) } catch (e: JSONException) { return Checked.Rejected("not json") }
        if (!o.keys().asSequence().all { it in KEYS }) return Checked.Rejected("unexpected keys")
        val text = o.opt("explanation") as? String ?: return Checked.Rejected("explanation missing")
        AdvisorText.problem(text, MAX_TEXT)?.let { return Checked.Rejected("explanation: $it") }
        val points = mutableListOf<String>()
        if (o.has("points")) {
            val a = o.opt("points") as? JSONArray ?: return Checked.Rejected("points must be a list")
            if (a.length() > MAX_POINTS) return Checked.Rejected("too many points")
            for (i in 0 until a.length()) {
                val p = a.opt(i) as? String ?: return Checked.Rejected("point must be text")
                AdvisorText.problem(p, MAX_POINT)?.let { return Checked.Rejected("point: $it") }
                points += AdvisorText.clean(p)
            }
        }
        return Checked.Valid(Explanation(AdvisorText.clean(text), points))
    }
}
