package app.fusion.wallet.accountant.cloud

import app.fusion.wallet.accountant.data.FlowCategory
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.data.LendingProtocol
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

sealed interface GuardResult {
    data object Ok : GuardResult

    /** [rules] are rule IDs only. They never echo the offending content. */
    data class Blocked(val rules: List<String>) : GuardResult
}

data class GuardPolicy(
    val maxPayloadBytes: Int,
    /** Literal private strings that must not appear anywhere in the payload (case-insensitive). */
    val denyStrings: Set<String> = emptySet(),
)

/**
 * Fail-closed egress filter. A payload leaves the device only if ALL hold:
 *  1. it is valid JSON no larger than the policy cap;
 *  2. every key is on the fixed [AccountantBrief] schema and every value matches its type/enum/range
 *     (amounts must be exactly two significant figures, so an exact balance cannot slip through);
 *  3. no string contains an address, hash, key material, URL, e-mail or seed-phrase shape;
 *  4. the free-text question has no long digit runs (account numbers, exact amounts);
 *  5. none of the device's known private strings (counterparties, memos, addresses…) appear in it.
 * Unknown anything => blocked. The guard is the last line of defence; [BriefBuilder] is the first.
 */
object EgressGuard {
    private val ROOT_REQUIRED = setOf("schema", "unit", "topic", "totals", "lending", "obligations", "flows30d")
    private val ROOT_OPTIONAL = setOf("question", "runwayDays")
    private val TOTALS_KEYS = setOf("assets", "freePct", "reservedPct", "deployedPct", "shortfall")
    private val LENDING_KEYS = setOf("protocol", "asset", "sharePct", "aprBps")
    private val DUE_KEYS = setOf("window", "count", "total")
    private val FLOW_KEYS = setOf("category", "dir", "total", "count")

    private val SYMBOL = Regex("^[A-Za-z0-9]{2,10}$")
    private val TOPICS = InsightTopic.entries.map { it.name }.toSet()
    private val PROTOCOLS = LendingProtocol.entries.map { it.name }.toSet()
    private val CATEGORIES = FlowCategory.entries.map { it.name }.toSet()

    // Shapes that must never appear in any string, anywhere.
    private val FORBIDDEN: List<Pair<String, Regex>> = listOf(
        "pattern:eth_address" to Regex("0x[0-9a-fA-F]{6,}"),
        "pattern:long_hex" to Regex("\\b[0-9a-fA-F]{24,}\\b"),
        "pattern:email" to Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
        "pattern:url" to Regex("(?i)(https?://|www\\.|ftp://)"),
        "pattern:api_key" to Regex("(?i)(sk-[A-Za-z0-9]{10,}|bearer\\s+[A-Za-z0-9._-]{10,}|api[_-]?key\\s*[:=]|-----BEGIN)"),
    )
    private val LONG_DIGITS = Regex("\\d{7,}")
    private val WORD = Regex("^[a-z]{3,8}$")
    private val STOPWORDS = setOf(
        "the", "and", "for", "how", "what", "why", "when", "can", "could", "should", "would", "our", "this", "that",
        "with", "have", "will", "much", "does", "are", "you", "any", "next", "month", "week", "please",
    )

    /**
     * Heuristic for a BIP-39-style recovery phrase: 12+ consecutive lower-case word-like tokens with
     * almost no ordinary English function words. Ordinary questions are full of them and pass.
     */
    fun looksLikeSeedPhrase(s: String): Boolean {
        val tokens = s.trim().split(Regex("\\s+"))
        if (tokens.size < 12) return false
        for (start in 0..tokens.size - 12) {
            val w = tokens.subList(start, start + 12)
            if (w.all { it.matches(WORD) } && w.count { it in STOPWORDS } <= 1) return true
        }
        return false
    }

    fun check(json: String, policy: GuardPolicy): GuardResult {
        val rules = linkedSetOf<String>()

        if (json.toByteArray(Charsets.UTF_8).size > policy.maxPayloadBytes) rules += "size:over_limit"

        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return GuardResult.Blocked(listOf("json:invalid"))
        }

        checkStructure(root, rules)
        scanStrings(root, rules)

        val lower = json.lowercase()
        if (policy.denyStrings.any { it.isNotBlank() && lower.contains(it.lowercase()) }) rules += "deny:private_string"

        return if (rules.isEmpty()) GuardResult.Ok else GuardResult.Blocked(rules.toList())
    }

    // ---- structure ----------------------------------------------------------------------------

    private fun checkStructure(root: JSONObject, rules: MutableSet<String>) {
        keysExact(root, ROOT_REQUIRED, ROOT_OPTIONAL, "root", rules)
        if (root.optString("schema") != BriefLimits.SCHEMA) rules += "schema:version"
        if (!root.optString("unit").matches(SYMBOL)) rules += "field:unit"
        if (root.optString("topic") !in TOPICS) rules += "field:topic"

        if (root.has("question")) {
            val q = root.opt("question")
            if (q !is String || q.length > BriefLimits.MAX_QUESTION_CHARS) rules += "field:question_len"
            else if (LONG_DIGITS.containsMatchIn(q)) rules += "question:long_digits"
        }
        if (root.has("runwayDays")) intIn(root, "runwayDays", 0, 999, rules)

        root.optJSONObject("totals")?.let { t ->
            keysExact(t, TOTALS_KEYS, emptySet(), "totals", rules)
            if (!isTwoSigFig(t.opt("assets"))) rules += "amount:not_coarse(totals.assets)"
            for (k in listOf("freePct", "reservedPct", "deployedPct")) intIn(t, k, 0, 100, rules)
            if (t.opt("shortfall") !is Boolean) rules += "field:shortfall"
        } ?: run { rules += "field:totals" }

        arrayField(root, "lending", BriefLimits.MAX_LENDING, rules)?.forEachObject { o ->
            keysExact(o, LENDING_KEYS, emptySet(), "lending", rules)
            if (o.optString("protocol") !in PROTOCOLS) rules += "field:lending.protocol"
            if (!o.optString("asset").matches(SYMBOL)) rules += "field:lending.asset"
            intIn(o, "sharePct", 0, 100, rules)
            intIn(o, "aprBps", 0, 100_000, rules)
        }
        arrayField(root, "obligations", BriefLimits.MAX_OBLIGATION_BUCKETS, rules)?.forEachObject { o ->
            keysExact(o, DUE_KEYS, emptySet(), "obligations", rules)
            if (o.optString("window") !in BriefLimits.DUE_WINDOWS) rules += "field:obligations.window"
            intIn(o, "count", 0, 1000, rules)
            if (!isTwoSigFig(o.opt("total"))) rules += "amount:not_coarse(obligations.total)"
        }
        arrayField(root, "flows30d", BriefLimits.MAX_FLOWS, rules)?.forEachObject { o ->
            keysExact(o, FLOW_KEYS, emptySet(), "flows30d", rules)
            if (o.optString("category") !in CATEGORIES) rules += "field:flows30d.category"
            if (o.optString("dir") !in setOf("IN", "OUT")) rules += "field:flows30d.dir"
            intIn(o, "count", 0, 1000, rules)
            if (!isTwoSigFig(o.opt("total"))) rules += "amount:not_coarse(flows30d.total)"
        }
    }

    private fun keysExact(o: JSONObject, required: Set<String>, optional: Set<String>, where: String, rules: MutableSet<String>) {
        val keys = o.keys().asSequence().toSet()
        if (!keys.containsAll(required)) rules += "keys:missing($where)"
        if (!(required + optional).containsAll(keys)) rules += "keys:unknown($where)"
    }

    private fun intIn(o: JSONObject, k: String, lo: Int, hi: Int, rules: MutableSet<String>) {
        val v = o.opt(k)
        if (v !is Int || v < lo || v > hi) rules += "field:$k"
    }

    private fun arrayField(o: JSONObject, k: String, max: Int, rules: MutableSet<String>): JSONArray? {
        val a = o.optJSONArray(k)
        if (a == null) {
            rules += "field:$k"
            return null
        }
        if (a.length() > max) {
            rules += "field:$k.length"
            return null
        }
        return a
    }

    private fun JSONArray.forEachObject(f: (JSONObject) -> Unit) {
        for (i in 0 until length()) {
            val v = opt(i)
            if (v is JSONObject) f(v)
        }
    }

    /** Exactly the shape [Raw.coarse] produces: a plain decimal with at most 2 significant digits. */
    fun isTwoSigFig(v: Any?): Boolean {
        val s = v as? String ?: return false
        if (!Regex("^(0|[0-9]+(\\.[0-9]+)?)$").matches(s)) return false
        if (s.length > 20) return false
        val digits = s.replace(".", "").trimStart('0').trimEnd('0')
        return digits.length <= 2
    }

    // ---- content scan -------------------------------------------------------------------------

    private fun scanStrings(v: Any?, rules: MutableSet<String>) {
        when (v) {
            is String -> {
                for ((id, rx) in FORBIDDEN) if (rx.containsMatchIn(v)) rules += id
                if (looksLikeSeedPhrase(v)) rules += "pattern:seed_phrase"
            }
            is JSONObject -> v.keys().forEach { k ->
                scanStrings(k, rules)
                scanStrings(v.opt(k), rules)
            }
            is JSONArray -> for (i in 0 until v.length()) scanStrings(v.opt(i), rules)
        }
    }
}
