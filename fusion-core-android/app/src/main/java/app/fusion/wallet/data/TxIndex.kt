package app.fusion.wallet.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL

/** Metadata recorded by evidence/tx_index.json; never inferred from a module log's default source. */
data class TxIndexEntry(
    val txHash: String,
    val source: String,
    val kind: String,
    val amountRaw: BigInteger?,
    val recipient: String?,
    val payeeLabel: String?,
    val planner: String?,
    val evidenceFile: String,
)

class TxIndex private constructor(private val entries: Map<String, TxIndexEntry>) {
    fun all(): List<TxIndexEntry> = entries.values.toList()
    fun find(txHash: String?): TxIndexEntry? = txHash?.let { entries[it.lowercase()] }

    companion object {
        val Empty = TxIndex(emptyMap())
        private val hash = Regex("0x[0-9a-fA-F]{64}")
        private val address = Regex("0x[0-9a-fA-F]{40}")

        fun parse(json: String): TxIndex {
            val rows = JSONArray(json)
            val entries = mutableMapOf<String, TxIndexEntry>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val tx = row.getString("txHash").also { require(hash.matches(it)) }.lowercase()
                val source = row.getString("source").also { require(it in setOf("SCRIPT", "OWNER", "AGENT", "APP")) }
                val raw = row.optionalString("amountRaw")?.also { require(Regex("[0-9]+").matches(it)) }?.toBigInteger()
                val recipient = row.optionalString("recipient")?.also { require(address.matches(it)) }
                val entry = TxIndexEntry(tx, source, row.getString("kind"), raw, recipient,
                    row.optionalString("payeeLabel"), row.optionalString("planner"), row.getString("evidenceFile"))
                require(entries.put(tx, entry) == null) { "Duplicate txHash in transaction index" }
            }
            return TxIndex(entries)
        }

        private fun JSONObject.optionalString(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key).takeIf { it.isNotBlank() }
    }
}

/** Disk cache survives process restarts; a failed/malformed download retains the last valid index. */
class TxIndexCache(
    private val cacheFile: File,
    private val fetch: () -> String = ::fetchTxIndex,
) {
    fun cached(): TxIndex = runCatching { TxIndex.parse(cacheFile.readText()) }.getOrDefault(TxIndex.Empty)

    fun refresh(): TxIndex {
        val json = runCatching { fetch() }.getOrNull() ?: return cached()
        val parsed = runCatching { TxIndex.parse(json) }.getOrNull() ?: return cached()
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File.createTempFile("tx_index.", ".tmp", cacheFile.parentFile)
            try {
                temp.writeText(json)
                check(temp.renameTo(cacheFile)) { "Cannot save transaction index cache" }
            } finally { temp.delete() }
        }
        return parsed
    }
}

private fun fetchTxIndex(): String {
    val url = "https://raw.githubusercontent.com/AdribMahmud101/fusion_wallet/main/evidence/tx_index.json"
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.setRequestProperty("User-Agent", "FusionWallet-Android")
        check(connection.responseCode in 200..299)
        connection.inputStream.bufferedReader().use { reader ->
            val json = reader.readText()
            require(json.length <= 2_000_000) { "Transaction index too large" }
            json
        }
    } finally { connection.disconnect() }
}
