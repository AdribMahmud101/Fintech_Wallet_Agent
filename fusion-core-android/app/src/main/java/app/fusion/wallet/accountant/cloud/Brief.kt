package app.fusion.wallet.accountant.cloud

import app.fusion.wallet.accountant.data.FlowCategory
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.data.LendingProtocol
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.accountant.domain.Raw
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * DATA CLASSIFICATION — what may leave the phone for the cloud Accountant.
 *
 * | Class   | Examples                                                        | Cloud?                          |
 * |---------|-----------------------------------------------------------------|---------------------------------|
 * | SECRET  | private keys, seeds, API keys, signed raw transactions          | Never (not even stored in Room) |
 * | PRIVATE | addresses, tx/block/invoice hashes, counterparties, memos,      | Never                           |
 * |         | category labels, exact balances/amounts, absolute dates         |                                 |
 * | COARSE  | totals to 2 significant figures, integer percentages, counts,   | Yes, only via [AccountantBrief] |
 * |         | APY in bps, relative due windows, fixed-enum categories         |                                 |
 * | PUBLIC  | topic enum, protocol names, asset symbols                       | Yes                             |
 *
 * The cloud model is READ-ONLY: it gets a lossy snapshot and can only return advisory text. It has
 * no tools, no access to Room, and its text never feeds the candidate generator, policy, signer or
 * contract. Every outbound payload must be an [AccountantBrief] JSON that passes [EgressGuard].
 */
enum class DataClass { SECRET, PRIVATE, COARSE, PUBLIC }

object BriefLimits {
    const val SCHEMA = "fw.accountant.brief.v1"
    const val MAX_QUESTION_CHARS = 200
    const val MAX_LENDING = 8
    const val MAX_OBLIGATION_BUCKETS = 5
    const val MAX_FLOWS = 12
    val DUE_WINDOWS = listOf("OVERDUE", "0-7d", "8-14d", "15-30d", "31d+")
}

data class BriefTotals(val assets: String, val freePct: Int, val reservedPct: Int, val deployedPct: Int, val shortfall: Boolean)
data class BriefLending(val protocol: LendingProtocol, val asset: String, val sharePct: Int, val aprBps: Int)
data class BriefDue(val window: String, val count: Int, val total: String)
data class BriefFlow(val category: FlowCategory, val income: Boolean, val total: String, val count: Int)

/** The ONLY shape that may be sent to the cloud. Contains COARSE/PUBLIC fields exclusively. */
data class AccountantBrief(
    val unit: String,
    val topic: InsightTopic,
    val question: String?,
    val totals: BriefTotals,
    val lending: List<BriefLending>,
    val obligations: List<BriefDue>,
    val flows30d: List<BriefFlow>,
    val runwayDays: Int?,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("schema", BriefLimits.SCHEMA)
        o.put("unit", unit)
        o.put("topic", topic.name)
        if (question != null) o.put("question", question)
        o.put(
            "totals",
            JSONObject()
                .put("assets", totals.assets)
                .put("freePct", totals.freePct)
                .put("reservedPct", totals.reservedPct)
                .put("deployedPct", totals.deployedPct)
                .put("shortfall", totals.shortfall),
        )
        o.put(
            "lending",
            JSONArray().also { a ->
                lending.forEach {
                    a.put(JSONObject().put("protocol", it.protocol.name).put("asset", it.asset).put("sharePct", it.sharePct).put("aprBps", it.aprBps))
                }
            },
        )
        o.put(
            "obligations",
            JSONArray().also { a -> obligations.forEach { a.put(JSONObject().put("window", it.window).put("count", it.count).put("total", it.total)) } },
        )
        o.put(
            "flows30d",
            JSONArray().also { a ->
                flows30d.forEach {
                    a.put(JSONObject().put("category", it.category.name).put("dir", if (it.income) "IN" else "OUT").put("total", it.total).put("count", it.count))
                }
            },
        )
        if (runwayDays != null) o.put("runwayDays", runwayDays)
        return o.toString()
    }
}

/** One-way, lossy projection of the private snapshot into a [AccountantBrief]. */
object BriefBuilder {
    private const val DAY = 86_400L

    fun build(s: PortfolioSnapshot, topic: InsightTopic, question: String?): AccountantBrief {
        val b = s.buckets
        val dec = s.unit.decimals
        val total = b.totalAssets

        // Percentages of total assets. Rounded individually; may sum to 99..101, which is fine for advice.
        val totals = BriefTotals(
            assets = Raw.coarse(total, dec),
            freePct = Raw.pct(b.free, total),
            reservedPct = Raw.pct(b.reserved, total),
            deployedPct = Raw.pct(b.deployed, total),
            shortfall = b.shortfall.signum() > 0,
        )

        val deployedTotal = b.deployed
        val lending = s.lending
            .filter { it.isStable && it.supplied.signum() > 0 }
            .sortedByDescending { it.supplied }
            .take(BriefLimits.MAX_LENDING)
            .map {
                BriefLending(
                    protocol = it.protocol,
                    asset = it.underlyingSymbol.sanitizeSymbol(),
                    sharePct = Raw.pct(Raw.rescale(it.supplied, it.decimals, dec), deployedTotal),
                    aprBps = it.supplyAprBps.coerceIn(0, 100_000),
                )
            }

        // Obligations only as aggregates per due window: no per-invoice rows, no names, no dates.
        val byWindow = linkedMapOf<String, Pair<Int, BigInteger>>()
        for (o in s.obligations) {
            val w = dueWindow(o.dueAt - s.asOf)
            val prev = byWindow[w] ?: (0 to BigInteger.ZERO)
            byWindow[w] = (prev.first + 1) to (prev.second + Raw.rescale(o.amount, o.decimals, dec))
        }
        val obligations = BriefLimits.DUE_WINDOWS.mapNotNull { w ->
            byWindow[w]?.let { (n, sum) -> BriefDue(w, n.coerceAtMost(1000), Raw.coarse(sum, dec)) }
        }.take(BriefLimits.MAX_OBLIGATION_BUCKETS)

        // Flows grouped by (fixed-enum category, direction). Sorted for deterministic output.
        val flowGroups = s.flows30d.groupBy { it.category to it.isIncome }
        val flows = flowGroups.entries
            .map { (k, v) ->
                val sum = v.fold(BigInteger.ZERO) { a, f -> a + Raw.rescale(f.amount, f.decimals, dec) }
                BriefFlow(k.first, k.second, Raw.coarse(sum, dec), v.size.coerceAtMost(1000))
            }
            .sortedWith(compareBy({ it.category.ordinal }, { !it.income }))
            .take(BriefLimits.MAX_FLOWS)

        return AccountantBrief(
            unit = s.unit.symbol.sanitizeSymbol(),
            topic = topic,
            question = question?.trim()?.takeIf { it.isNotEmpty() },
            totals = totals,
            lending = lending,
            obligations = obligations,
            flows30d = flows,
            runwayDays = runwayDays(s),
        )
    }

    fun dueWindow(deltaSec: Long): String = when {
        deltaSec < 0 -> "OVERDUE"
        deltaSec <= 7 * DAY -> "0-7d"
        deltaSec <= 14 * DAY -> "8-14d"
        deltaSec <= 30 * DAY -> "15-30d"
        else -> "31d+"
    }

    /** Days of cash at the 30-day average outflow, capped at 999; null when there were no outflows. */
    fun runwayDays(s: PortfolioSnapshot): Int? {
        val dec = s.unit.decimals
        val out = s.flows30d.filter { !it.isIncome }.fold(BigInteger.ZERO) { a, f -> a + Raw.rescale(f.amount, f.decimals, dec) }
        if (out.signum() <= 0) return null
        val perDay = out.divide(BigInteger.valueOf(30)).max(BigInteger.ONE)
        return s.cash.divide(perDay).min(BigInteger.valueOf(999)).toInt()
    }

    private fun String.sanitizeSymbol(): String =
        filter { it.isLetterOrDigit() && it.code < 128 }.take(10).ifEmpty { "ASSET" }
}
