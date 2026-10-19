package app.fusion.wallet.ui.safety

import org.json.JSONObject

const val EVIDENCE_RAW = "https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/"
const val ARENA_PATH = "evidence/results/latest.json"
const val HELDOUT_PATH = "evidence/planner/TUNING_K20.md"

data class AttackEvidence(val label: String, val url: String, val txHash: String? = null)
data class AttackScenario(
    val id: String, val description: String, val stopped: Boolean?, val status: String,
    val stoppingLayer: String?, val evidence: List<AttackEvidence>, val explanation: String?,
)
data class EvidenceRatio(val numerator: Int, val denominator: Int)
data class ArenaEvidence(
    val scenarios: List<AttackScenario>, val frozenAsr: EvidenceRatio?, val measuredAt: String?, val limitations: List<String>,
) {
    val stopped: Int get() = scenarios.count { it.stopped == true }
    val layerCounts: Map<String, Int> get() = scenarios.filter { it.stopped == true }.groupingBy { requireNotNull(it.stoppingLayer) }.eachCount()
    val knownLimits: List<AttackScenario> get() = scenarios.filter { it.stopped != true }
}

object ArenaEvidenceParser {
    fun parse(raw: String): ArenaEvidence {
        val root = JSONObject(raw)
        val rows = root.getJSONArray("scenarios")
        require(rows.length() > 0) { "Arena evidence has no scenarios" }
        val scenarios = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val refs = row.optJSONArray("evidence")
            val links = if (refs == null) emptyList() else (0 until refs.length()).mapNotNull { refIndex ->
                val ref = refs.getJSONObject(refIndex)
                val path = ref.optString("path")
                val url = ref.optString("url")
                val tx = ref.optString("tx_hash")
                when {
                    path == "evidence/base-sepolia.json" && Regex("0x[0-9a-fA-F]{64}").matches(tx) ->
                        AttackEvidence("Base Sepolia revert", "https://sepolia.basescan.org/tx/$tx", tx)
                    path.startsWith("evidence/") && !path.contains("..") ->
                        AttackEvidence(ref.optString("test").takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'), EVIDENCE_RAW + path)
                    url.startsWith("https://") -> AttackEvidence("Evidence", url)
                    else -> null
                }
            }
            val layers = row.optJSONArray("layers")
            val notes = if (layers == null) emptyList() else (0 until layers.length()).mapNotNull {
                layers.getJSONObject(it).optString("note").takeIf(String::isNotBlank)
            }
            val stopped = if (row.has("stopped") && !row.isNull("stopped")) row.getBoolean("stopped") else null
            val layer = row.optString("stopping_layer").takeIf { it.isNotBlank() && it != "null" }
            require(stopped != true || layer != null) { "Stopped scenario missing its measured layer" }
            AttackScenario(row.getString("id"), row.getString("description"), stopped, row.getString("status"), layer,
                links.ifEmpty { listOf(AttackEvidence("Arena scenario ${row.getString("id")}", EVIDENCE_RAW + ARENA_PATH)) },
                row.optString("coverage_note").takeIf(String::isNotBlank) ?: notes.joinToString("\n").takeIf(String::isNotBlank))
        }
        require(scenarios.map { it.id }.distinct().size == scenarios.size) { "Duplicate arena scenario" }
        root.optJSONObject("summary")?.let { summary ->
            if (summary.has("scenario_count")) require(summary.getInt("scenario_count") == scenarios.size) { "Arena scenario count mismatch" }
            if (summary.has("stopped")) require(summary.getInt("stopped") == scenarios.count { it.stopped == true }) { "Arena stopped count mismatch" }
        }
        val limits = root.optJSONArray("limitations")
        return ArenaEvidence(scenarios, ratio(root.optJSONObject("asr")?.optJSONObject("ahem-g")),
            root.optString("generated_at").takeIf(String::isNotBlank),
            if (limits == null) emptyList() else (0 until limits.length()).map { limits.getString(it) })
    }

    fun ratio(value: JSONObject?): EvidenceRatio? {
        if (value == null || !value.has("numerator") || !value.has("denominator")) return null
        val numerator = value.getInt("numerator")
        val denominator = value.getInt("denominator")
        require(numerator >= 0 && denominator > 0 && numerator <= denominator) { "Invalid ASR evidence" }
        return EvidenceRatio(numerator, denominator)
    }

    /** The committed held-out evaluation is published as a Markdown results table. */
    fun heldOutAsr(markdown: String): EvidenceRatio {
        val row = markdown.lineSequence().singleOrNull { it.startsWith('|') && it.contains("Held-Out Set", true) && it.contains("AHEM-G", true) }
            ?: error("Held-out AHEM-G evidence row missing or ambiguous")
        val cell = row.split('|').getOrNull(3) ?: error("Held-out ASR column missing")
        val match = Regex("(\\d+)\\s*/\\s*(\\d+)").find(cell) ?: error("Held-out ASR ratio missing")
        return requireNotNull(ratio(JSONObject().put("numerator", match.groupValues[1].toInt()).put("denominator", match.groupValues[2].toInt())))
    }
}

/** Only the observed stopping layer earns credit; expected layers and veto totals do not. */
val safetyDiagramLayers: List<Pair<String, Set<String>>> = listOf(
    "AHEM-G" to setOf("ahem_g", "ahem-g"),
    "Validator" to setOf("output_validator"),
    "Policy" to setOf("rust_candidate_policy", "rust_policy_preview", "policy"),
    "Signer" to setOf("signer"),
    "Contract" to setOf("contract"),
)

fun safetyLayerTitle(layer: String): String = when (layer) {
    "ahem_g", "ahem-g" -> "AHEM-G"
    "output_validator" -> "Validator"
    "rust_candidate_policy" -> "Candidate policy"
    "rust_policy_preview" -> "Policy preview"
    "durable_journal" -> "Durable journal"
    "off-chain impl pin" -> "Implementation pin"
    "contract" -> "Contract"
    "signer" -> "Signer"
    else -> layer
}
