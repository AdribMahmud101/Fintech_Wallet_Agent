package app.fusion.wallet.data

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

interface ObligationsCandidateSource {
    fun fetchCandidate(url: String): String?
    fun listAgentEvidenceUrls(): List<String>
}

object DefaultObligationsCandidateSource : ObligationsCandidateSource {
    private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/evidence"
    private const val GITHUB_API_AGENT = "https://api.github.com/repos/AdribMahmud101/fusion_wallet/contents/evidence/agent"

    override fun fetchCandidate(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "FusionWallet-Android")
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    override fun listAgentEvidenceUrls(): List<String> {
        val urls = mutableListOf(
            "$GITHUB_RAW_BASE/agent/obligations-84532.json",
            "$GITHUB_RAW_BASE/agent/obligations.json",
            "$GITHUB_RAW_BASE/demo/obligations.json",
        )
        try {
            val text = fetchCandidate(GITHUB_API_AGENT)
            if (text != null) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val name = item.optString("name")
                    val dlUrl = item.optString("download_url")
                    if (name.startsWith("obligations") && name.endsWith(".json") && dlUrl.isNotEmpty()) {
                        if (dlUrl !in urls) urls.add(dlUrl)
                    }
                }
            }
        } catch (_: Exception) {}
        return urls
    }
}

class ObligationsResolver(
    private val candidateSource: ObligationsCandidateSource = DefaultObligationsCandidateSource,
) {
    private val cache = mutableMapOf<String, String>()

    fun resolve(onChainRoot: String, bundledJson: String?): String? =
        resolve(onChainRoot, listOf(bundledJson))

    /**
     * Resolve the current on-chain commitment root against every bundled candidate (K2 + K9 agent
     * demo files), then the cache, then GitHub raw. The on-chain root moves when the agent commits a
     * newer bill set, so a single bundled file is not enough — we try them all before giving up.
     */
    fun resolve(onChainRoot: String, bundledJsons: List<String?>): String? {
        // 1. Try every bundled file
        for (bundledJson in bundledJsons) {
            if (bundledJson == null) continue
            try {
                val obj = JSONObject(bundledJson)
                if (obj.optString("root").equals(onChainRoot, ignoreCase = true)) {
                    return bundledJson
                }
            } catch (_: Exception) {}
        }

        // 2. Try cache
        for ((_, json) in cache) {
            try {
                val obj = JSONObject(json)
                if (obj.optString("root").equals(onChainRoot, ignoreCase = true)) {
                    return json
                }
            } catch (_: Exception) {}
        }

        // 3. Try primary raw GitHub URL
        val primaryUrl = "https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/evidence/obligations-84532.json"
        fetchAndCheck(primaryUrl, onChainRoot)?.let { return it }

        // 4. Try candidate paths under evidence/agent/
        for (url in candidateSource.listAgentEvidenceUrls()) {
            fetchAndCheck(url, onChainRoot)?.let { return it }
        }

        return null
    }

    private fun fetchAndCheck(url: String, onChainRoot: String): String? {
        val json = candidateSource.fetchCandidate(url) ?: return null
        try {
            val obj = JSONObject(json)
            val root = obj.optString("root")
            if (root.isNotEmpty()) {
                cache[url] = json
                if (root.equals(onChainRoot, ignoreCase = true)) {
                    return json
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
