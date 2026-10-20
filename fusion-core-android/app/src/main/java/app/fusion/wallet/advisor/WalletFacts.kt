package app.fusion.wallet.advisor

import app.fusion.wallet.data.ActionKind
import app.fusion.wallet.data.LoadStatus
import app.fusion.wallet.data.WalletState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Compact JSON of the live WalletState for "Ask the Accountant": balances, buckets, limits,
 * obligations, runway and recent activity. Public chain state only: no addresses, hashes, payee
 * labels, session key or anything the signer holds. Missing data is "not measured", never a guess.
 */
object WalletFacts {
    const val SCHEMA = "fw.advisor.wallet.v1"
    const val MAX_OBLIGATIONS = 10
    const val MAX_RECENT = 10
    private const val DAY = 86_400L

    /** Null when the snapshot is not ready: the advisor does not answer from partial data. */
    fun build(state: WalletState, nowSec: Long, nowMillis: Long = nowSec * 1000): JSONObject? {
        if (state.status != LoadStatus.READY) return null
        val module = state.module ?: return null
        val buckets = state.buckets ?: return null
        val treasury = state.treasury ?: return null
        val asOf = state.blockTime ?: nowSec
        val unpaid = state.obligations.filter { !it.paid }.sortedBy { it.dueAt }

        val facts = JSONObject()
            .put("asOfBlock", (state.blockNumber ?: 0L).toString())
            .put("stale", state.isStale(nowMillis).toString())
            .put("dataSource", state.source.name)
            .put("cash", usdc(treasury.balance.raw))
            .put("total", usdc(buckets.total.raw))
            .put("reserve", usdc(buckets.reserve.raw))
            .put("owed", usdc(buckets.owed.raw))
            .put("buffer", usdc(buckets.buffer.raw))
            .put("bufferTarget", usdc(buckets.bufferTarget.raw))
            .put("surplus", usdc(buckets.surplus.raw))
            .put("free", usdc(buckets.free.raw))
            .put("shortfall", usdc(buckets.shortfall.raw))
            .put("deployed", usdc(buckets.deployed.raw))
            .put("paused", module.paused.toString())
            .put("permissionTier", module.policy.permissionTier.name)
            .put("hardMaxPerMove", usdc(module.policy.hardMax.raw))
            .put("dailyLimit", usdc(module.policy.dailyLimit.raw))
            .put("dailyUsed", usdc(module.dailyUsed.raw))
            .put("dailyRemaining", usdc(module.dailyRemaining.raw))
            .put("operatingBuffer", usdc(module.policy.operatingBuffer.raw))
            .put("sessionActive", module.sessionActive.toString())
            .put("unpaidBillsCount", unpaid.size.toString())
            .put("unpaidBillsTotal", usdc(unpaid.fold(BigInteger.ZERO) { a, o -> a + o.amount.raw }))
            .put("runwayDaysAtRecentPayRate", runwayDays(state, asOf)?.toString() ?: NOT_MEASURED)

        return JSONObject()
            .put("schema", SCHEMA)
            .put("unit", "USDC")
            .put("network", "Base Sepolia testnet")
            .put("facts", facts)
            .put("vaults", JSONArray().apply {
                state.vaults.forEach { v ->
                    put(JSONObject().put("name", AdvisorEgress.redact(v.name).take(40)).put("assets", usdc(v.assets.raw))
                        .put("exposureLimit", usdc(v.exposureLimit.raw)))
                }
            })
            .put("unpaidBills", JSONArray().apply {
                unpaid.take(MAX_OBLIGATIONS).forEach { o ->
                    put(JSONObject().put("amount", usdc(o.amount.raw)).put("dueInDays", Math.floorDiv(o.dueAt - asOf, DAY).toString()))
                }
            })
            .put("recentActivity", JSONArray().apply {
                state.recentActions.sortedByDescending { it.blockTime }.take(MAX_RECENT).forEach { a ->
                    put(JSONObject().put("kind", a.kind.name).put("amount", usdc(a.amount.raw))
                        .put("daysAgo", Math.floorDiv(asOf - a.blockTime, DAY).coerceAtLeast(0).toString()).put("source", a.source.take(8)))
                }
            })
    }

    /** Cash divided by the average daily bill payments over the last 30 days; null when no payments were made. */
    fun runwayDays(state: WalletState, asOf: Long): Long? {
        val cash = state.treasury?.balance?.raw ?: return null
        val paid30 = state.recentActions.filter { it.kind == ActionKind.PAY_OBLIGATION && it.blockTime in (asOf - 30 * DAY)..asOf }
            .fold(BigInteger.ZERO) { a, x -> a + x.amount.raw }
        if (paid30.signum() == 0) return null
        return BigDecimal(cash).multiply(BigDecimal(30)).divide(BigDecimal(paid30), 0, RoundingMode.FLOOR).toLong()
    }

    /** Every leaf of the facts JSON as "facts.cash", "vaults[0].assets", … → string value. */
    fun flatten(o: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun walk(prefix: String, v: Any?) {
            when (v) {
                is JSONObject -> v.keys().forEach { k -> walk(if (prefix.isEmpty()) k else "$prefix.$k", v.opt(k)) }
                is JSONArray -> for (i in 0 until v.length()) walk("$prefix[$i]", v.opt(i))
                null -> {}
                else -> out[prefix] = v.toString()
            }
        }
        walk("", o)
        return out
    }

    const val NOT_MEASURED = "not measured"
}

data class Citation(val fact: String, val value: String)

/** [uncitedNumbers] are figures in the answer that appear nowhere in the data sent; the UI flags them. */
data class AccountantAnswer(val text: String, val citations: List<Citation>, val uncitedNumbers: List<String>)

/**
 * `{"answer": string, "citations": [{"fact": key, "value": string}]}`. Each citation must name a
 * fact that was sent and quote its exact value, or the whole answer is rejected. Numbers in the
 * text that match no sent value are surfaced, not hidden.
 */
object AnswerValidator {
    const val MAX_ANSWER = 900
    const val MAX_CITATIONS = 8
    private val KEYS = setOf("answer", "citations")
    private val CITATION_KEYS = setOf("fact", "value")
    private val NUMBER = Regex("(?<![A-Za-z0-9.])\\d[\\d,]*(?:\\.\\d+)?")

    fun validate(raw: String, sent: Map<String, String>): Checked<AccountantAnswer> {
        val o = try { JSONObject(raw) } catch (e: JSONException) { return Checked.Rejected("not json") }
        if (!o.keys().asSequence().all { it in KEYS }) return Checked.Rejected("unexpected keys")
        val text = o.opt("answer") as? String ?: return Checked.Rejected("answer missing")
        AdvisorText.problem(text, MAX_ANSWER)?.let { return Checked.Rejected("answer: $it") }
        val a = o.opt("citations") as? JSONArray ?: return Checked.Rejected("citations missing")
        if (a.length() > MAX_CITATIONS) return Checked.Rejected("too many citations")
        val citations = mutableListOf<Citation>()
        for (i in 0 until a.length()) {
            val c = a.opt(i) as? JSONObject ?: return Checked.Rejected("citation must be an object")
            if (c.keys().asSequence().toSet() != CITATION_KEYS) return Checked.Rejected("citation keys")
            val fact = c.opt("fact") as? String ?: return Checked.Rejected("citation fact must be text")
            val value = c.opt("value")?.takeIf { it is String || it is Number || it is Boolean }?.toString()
                ?: return Checked.Rejected("citation value must be a scalar")
            val sentValue = sent[fact] ?: return Checked.Rejected("cites a fact that was not sent: ${fact.take(40)}")
            if (!sameValue(sentValue, value)) return Checked.Rejected("citation for $fact does not match the data")
            citations += Citation(fact, sentValue)
        }
        val numbersInText = NUMBER.findAll(text).map { it.value.replace(",", "") }.toList()
        if (numbersInText.isNotEmpty() && citations.isEmpty()) return Checked.Rejected("numbers without citations")
        val known = sent.values.mapNotNull(::decimal)
        val uncited = numbersInText.filter { n -> val d = decimal(n); d == null || known.none { it.compareTo(d) == 0 } }.distinct()
        return Checked.Valid(AccountantAnswer(AdvisorText.clean(text), citations, uncited))
    }

    private fun decimal(s: String): BigDecimal? = s.toBigDecimalOrNull()

    private fun sameValue(sent: String, claimed: String): Boolean {
        val a = decimal(sent)
        val b = decimal(claimed.replace(",", ""))
        return if (a != null && b != null) a.compareTo(b) == 0 else sent.equals(claimed.trim(), ignoreCase = true)
    }
}
