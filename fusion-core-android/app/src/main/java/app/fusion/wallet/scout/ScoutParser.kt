package app.fusion.wallet.scout

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Strict parser for the proxy's yield feed. Anything unexpected drops that pool; a bad envelope drops the feed. */
object ScoutParser {
    private val PROJECT = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val ID = Regex("^[0-9a-fA-F-]{8,64}$")
    const val MAX_POOLS = 30
    const val MAX_SPARK = 60

    fun parse(raw: String, cached: Boolean = false): ScoutFeed? {
        val o = try { JSONObject(raw) } catch (e: JSONException) { return null }
        if (o.optString("source") != "DeFiLlama" || o.optString("chain") != "Base") return null
        val at = o.optLong("fetchedAt", -1L).takeIf { it > 1_600_000_000L } ?: return null
        val arr = o.optJSONArray("pools") ?: return null
        val pools = (0 until minOf(arr.length(), MAX_POOLS)).mapNotNull { pool(arr.optJSONObject(it)) }
        return if (pools.isEmpty()) null else ScoutFeed(at, pools, cached)
    }

    private fun num(o: JSONObject, k: String, lo: Double, hi: Double): Double? {
        if (!o.has(k) || o.isNull(k)) return null
        val v = (o.get(k) as? Number)?.toDouble() ?: return null
        return v.takeIf { it.isFinite() && it in lo..hi }
    }

    private fun pool(o: JSONObject?): ScoutPool? {
        o ?: return null
        val id = o.optString("id"); val project = o.optString("project")
        if (!ID.matches(id) || !PROJECT.matches(project)) return null
        val tvl = num(o, "tvlUsd", 0.0, 1e12) ?: return null
        val spark = (o.optJSONArray("spark") ?: JSONArray()).let { a ->
            (0 until minOf(a.length(), MAX_SPARK)).mapNotNull { i -> (a.opt(i) as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1000.0 } }
        }
        return ScoutPool(id, project, tvl, num(o, "apy", 0.0, 1000.0), num(o, "apyBase", 0.0, 1000.0), num(o, "apyReward", 0.0, 1000.0), num(o, "apyMean30d", 0.0, 1000.0), spark)
    }
}
