package app.fusion.wallet.data

import android.content.Context
import org.json.JSONObject

/** One attack that was rejected on-chain: the transaction reverted, so there is no success event. */
data class BlockedOnChain(
    val id: String,
    val scenario: String,
    val txHash: String,
    val blockNumber: Long,
    val explorerUrl: String,
    val decisionHash: String?,
)

/** One arena scenario and the layer that stopped it (or why it escaped). */
data class ArenaScenario(
    val id: String,
    val description: String,
    val status: String,       // "measured" | "escaped" | "not_implemented"
    val stopped: Boolean?,     // null = not implemented / not run
    val stoppingLayer: String?,
) {
    val isStopped: Boolean get() = stopped == true
}

/**
 * Safety-tab evidence, parsed from the frozen evidence files (bundled as assets and public on
 * GitHub raw). Every number here comes straight from those files — nothing is computed from live
 * chain logs, because a reverted attack emits no event and so can never appear in the log history.
 */
data class SafetyData(
    val blockedOnChain: List<BlockedOnChain> = emptyList(),
    val scenarios: List<ArenaScenario> = emptyList(),
    val scenarioCount: Int = 0,
    val stopped: Int = 0,
    val escaped: Int = 0,
    val notImplemented: Int = 0,
    val asrNumerator: Int = 0,
    val asrDenominator: Int = 0,
    val measuredAt: String? = null,
    val loaded: Boolean = false,
) {
    val knownLimits: List<ArenaScenario>
        get() = scenarios.filter { it.status == "escaped" || it.status == "not_implemented" }
}

object SafetyEvidence {
    /** Base Sepolia receipts: attacks whose transaction reverted (status 0). */
    private fun parseBlocked(baseSepoliaJson: String, explorerBase: String): List<BlockedOnChain> {
        val rows = JSONObject(baseSepoliaJson).optJSONArray("rows") ?: return emptyList()
        val blocked = mutableListOf<BlockedOnChain>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val reverted = row.optInt("status", 1) == 0 ||
                row.optString("kind").equals("INTENTIONAL_REVERT", ignoreCase = true)
            if (!reverted) continue
            val txHash = row.optString("txHash")
            if (txHash.isBlank()) continue
            blocked += BlockedOnChain(
                id = row.optString("id"),
                scenario = row.optString("scenario"),
                txHash = txHash,
                blockNumber = row.optLong("block"),
                explorerUrl = "$explorerBase/tx/$txHash",
                decisionHash = row.optString("decisionHash").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return blocked
    }

    fun parse(
        baseSepoliaJson: String,
        arenaJson: String,
        explorerBase: String = "https://sepolia.basescan.org",
    ): SafetyData {
        val blocked = parseBlocked(baseSepoliaJson, explorerBase)
        val arena = JSONObject(arenaJson)
        val summary = arena.optJSONObject("summary") ?: JSONObject()
        val asr = arena.optJSONObject("asr")?.optJSONObject("ahem-g") ?: JSONObject()
        val scenariosArray = arena.optJSONArray("scenarios")
        val scenarios = mutableListOf<ArenaScenario>()
        if (scenariosArray != null) {
            for (i in 0 until scenariosArray.length()) {
                val s = scenariosArray.getJSONObject(i)
                scenarios += ArenaScenario(
                    id = s.optString("id"),
                    description = s.optString("description"),
                    status = s.optString("status"),
                    stopped = if (s.isNull("stopped")) null else s.optBoolean("stopped"),
                    stoppingLayer = s.optString("stopping_layer").takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }
        return SafetyData(
            blockedOnChain = blocked,
            scenarios = scenarios,
            scenarioCount = summary.optInt("scenario_count", scenarios.size),
            stopped = summary.optInt("stopped", scenarios.count { it.isStopped }),
            escaped = summary.optInt("escaped", scenarios.count { it.status == "escaped" }),
            notImplemented = summary.optInt("not_implemented", scenarios.count { it.status == "not_implemented" }),
            asrNumerator = asr.optInt("numerator", 0),
            asrDenominator = asr.optInt("denominator", 0),
            measuredAt = arena.optString("generated_at").takeIf { it.isNotBlank() },
            loaded = true,
        )
    }

    /** Reads the bundled evidence assets. Returns an empty, unloaded [SafetyData] on any failure. */
    fun fromAssets(context: Context): SafetyData = try {
        fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
        parse(asset("evidence/base-sepolia.json"), asset("evidence/arena-latest.json"))
    } catch (_: Exception) {
        SafetyData()
    }
}
