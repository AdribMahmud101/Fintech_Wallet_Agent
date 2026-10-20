package app.fusion.wallet.chain

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Injectable read-only transport. No signing, key handling, or transaction RPC exists here. */
fun interface RpcTransport {
    fun request(method: String, params: JSONArray): Any
    fun batch(calls: List<Pair<String, JSONArray>>): List<Any> {
        return calls.map { (method, params) -> request(method, params) }
    }
}

class RpcException(message: String, val httpStatus: Int? = null, val rpcCode: Int? = null) : IOException(message)

class JsonRpcClient(val rpcUrl: String = "https://sepolia.base.org") : RpcTransport {
    private val nextId = AtomicLong()
    init { require(URL(rpcUrl).protocol == "https") { "RPC requires HTTPS" } }

    override fun request(method: String, params: JSONArray): Any = retryRateLimited { requestOnce(method, params) }

    private fun requestOnce(method: String, params: JSONArray): Any {
        require(method in READ_METHODS) { "Unsupported read-only RPC method: $method" }
        val id = nextId.incrementAndGet()
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        RpcReadBudget.acquire(rpcUrl, 1)
        val connection = URL(rpcUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4_000
            connection.readTimeout = 6_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(512) }.orEmpty()
                throw RpcException("$method HTTP ${connection.responseCode}: $detail", httpStatus = connection.responseCode)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = JSONObject(body)
            if (response.has("error")) {
                val error = response.getJSONObject("error")
                throw RpcException("$method: ${error.optString("message")}", rpcCode = error.optInt("code"))
            }
            if (response.optString("jsonrpc") != "2.0" || response.getLong("id") != id) throw IOException("Invalid RPC response envelope")
            return response.opt("result")?.takeUnless { it == JSONObject.NULL } ?: throw IOException("$method returned no result")
        } finally {
            connection.disconnect()
        }
    }

    override fun batch(calls: List<Pair<String, JSONArray>>): List<Any> = retryRateLimited { batchOnce(calls) }

    private fun batchOnce(calls: List<Pair<String, JSONArray>>): List<Any> {
        if (calls.isEmpty()) return emptyList()
        val idToIndex = mutableMapOf<Long, Int>()
        val payload = JSONArray()
        calls.forEachIndexed { index, (method, params) ->
            require(method in READ_METHODS) { "Unsupported read-only RPC method: $method" }
            val id = nextId.incrementAndGet()
            idToIndex[id] = index
            payload.put(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        }
        RpcReadBudget.acquire(rpcUrl, calls.size)
        val connection = URL(rpcUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4_000
            connection.readTimeout = 6_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(512) }.orEmpty()
                throw RpcException("batch HTTP ${connection.responseCode}: $detail", httpStatus = connection.responseCode)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val responseArray = JSONArray(body)
            val results = arrayOfNulls<Any>(calls.size)
            for (i in 0 until responseArray.length()) {
                val resp = responseArray.getJSONObject(i)
                val id = resp.getLong("id")
                val targetIndex = idToIndex[id] ?: throw IOException("Unexpected RPC batch response id: $id")
                if (resp.has("error")) {
                    val err = resp.getJSONObject("error")
                    throw RpcException("batch call [${calls[targetIndex].first}]: ${err.optString("message")}", rpcCode = err.optInt("code"))
                }
                val res = resp.opt("result")?.takeUnless { it == JSONObject.NULL }
                    ?: throw IOException("batch call [${calls[targetIndex].first}] returned no result")
                results[targetIndex] = res
            }
            return results.mapIndexed { idx, res ->
                res ?: throw IOException("Missing RPC batch result for call [${calls[idx].first}]")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> retryRateLimited(read: () -> T): T {
        for (attempt in 0..2) {
            try { return read() } catch (error: RpcException) {
                val limited = error.httpStatus == 429 || Regex("rate|request limit", RegexOption.IGNORE_CASE).containsMatchIn(error.message.orEmpty())
                if (!limited || attempt == 2) throw error
                Thread.sleep(500L shl attempt)
            }
        }
        error("Read retries exhausted")
    }

    companion object {
        private val READ_METHODS = setOf("eth_chainId", "eth_call", "eth_getLogs", "eth_getBlockByNumber")
    }
}

/** Public Base RPC is capped at 25 calls/s, including calls within batches. Share a 20/s budget. */
private object RpcReadBudget {
    private val next = mutableMapOf<String, Long>()
    fun acquire(url: String, calls: Int) {
        val delay = synchronized(next) {
            val now = System.nanoTime()
            val start = maxOf(now, next[url] ?: now)
            next[url] = start + calls * 50_000_000L
            start - now
        }
        if (delay > 0) Thread.sleep(delay / 1_000_000, (delay % 1_000_000).toInt())
    }
}

data class BlockHeader(val number: Long, val hash: String, val timestamp: Long)

class ChainReader(private val rpc: RpcTransport) {
    @Volatile private var logPageSize = 200L
    fun chainId(): Long = quantity(rpc.request("eth_chainId", JSONArray()).toString())
    fun block(tag: String): BlockHeader {
        val value = rpc.request("eth_getBlockByNumber", JSONArray().put(tag).put(false)) as JSONObject
        val number = quantity(value.getString("number"))
        if (tag != "latest") require(number == quantity(tag)) { "Wrong block header returned" }
        val hash = value.getString("hash")
        Abi.hashWord(hash)
        return BlockHeader(number, hash, quantity(value.getString("timestamp")))
    }
    fun call(to: String, data: String, block: Long): Abi = Abi(
        rpc.request("eth_call", JSONArray().put(JSONObject().put("to", to).put("data", data)).put(tag(block))).toString(),
    )
    fun batchCalls(calls: List<Pair<String, String>>, block: Long): List<Abi> {
        if (calls.isEmpty()) return emptyList()
        val blockTag = tag(block)
        val rpcCalls = calls.map { (to, data) ->
            "eth_call" to JSONArray().put(JSONObject().put("to", to).put("data", data)).put(blockTag)
        }
        val rawResults = rpc.batch(rpcCalls)
        return rawResults.map { Abi(it.toString()) }
    }
    fun logs(address: String, from: Long, through: Long, topics: List<String?> = emptyList()): List<ChainLog> {
        val result = mutableListOf<ChainLog>()
        var start = from
        var pageSize = logPageSize
        while (start <= through) {
            val end = minOf(start + pageSize - 1, through)
            val filter = JSONObject().put("address", address).put("fromBlock", tag(start)).put("toBlock", tag(end))
            if (topics.isNotEmpty()) filter.put("topics", JSONArray(topics.map { it ?: JSONObject.NULL }))
            val page = try {
                rpc.request("eth_getLogs", JSONArray().put(filter)) as JSONArray
            } catch (error: RpcException) {
                val rangeLimited = error.httpStatus == 413 || error.rpcCode == -32005 || error.rpcCode == -32614 ||
                    (error.rpcCode == -32602 && error.message.orEmpty().contains("range", ignoreCase = true))
                if (!rangeLimited || pageSize == 1L) throw error
                pageSize = maxOf(1, pageSize / 2)
                logPageSize = pageSize
                continue
            }
            for (i in 0 until page.length()) {
                val log = ChainLog.fromJson(page.getJSONObject(i))
                require(!log.removed && log.blockNumber in start..end && log.address.equals(address, true)) { "Invalid or removed log" }
                require(topics.withIndex().all { (index, topic) -> topic == null || log.topics.getOrNull(index).equals(topic, true) }) { "RPC ignored topic filter" }
                result += log
            }
            start = end + 1
        }
        return result.distinctBy { it.blockHash to it.logIndex }.sortedWith(compareBy({ it.blockNumber }, { it.logIndex }))
    }
    /** One HTTP batch per bounded range; independent ranges are scheduled by EventIndex. */
    fun logBatch(filters: List<Pair<String, List<String?>>>, from: Long, through: Long): List<ChainLog> {
        val calls = filters.map { (address, topics) ->
            val filter = JSONObject().put("address", address).put("fromBlock", tag(from)).put("toBlock", tag(through))
            if (topics.isNotEmpty()) filter.put("topics", JSONArray(topics.map { it ?: JSONObject.NULL }))
            "eth_getLogs" to JSONArray().put(filter)
        }
        val pages = try { rpc.batch(calls) } catch (error: RpcException) {
            val rangeLimited = error.httpStatus == 413 || error.rpcCode in setOf(-32005, -32614) ||
                (error.rpcCode == -32602 && error.message.orEmpty().contains("range", true))
            if (!rangeLimited) throw error
            return filters.flatMap { (address, topics) -> logs(address, from, through, topics) }
        }
        require(pages.size == filters.size)
        return pages.flatMapIndexed { i, raw ->
            val page = raw as JSONArray
            val (address, topics) = filters[i]
            List(page.length()) { n -> ChainLog.fromJson(page.getJSONObject(n)).also { log ->
                require(!log.removed && log.blockNumber in from..through && log.address.equals(address, true)) { "Invalid or removed log" }
                require(topics.withIndex().all { (index, topic) -> topic == null || log.topics.getOrNull(index).equals(topic, true) }) { "RPC ignored topic filter" }
            } }
        }
    }
    companion object {
        fun tag(number: Long): String { require(number >= 0); return "0x" + number.toString(16) }
        fun quantity(value: String): Long {
            require(Regex("0x[0-9a-fA-F]+").matches(value)) { "Malformed RPC quantity" }
            return java.math.BigInteger(value.drop(2), 16).longValueExact()
        }
    }
}

data class ChainLog(
    val address: String,
    val topics: List<String>,
    val data: String,
    val txHash: String,
    val blockNumber: Long,
    val blockHash: String,
    val logIndex: Long,
    val removed: Boolean = false,
) {
    companion object {
        fun fromJson(json: JSONObject): ChainLog {
            val topics = json.getJSONArray("topics")
            return ChainLog(
                json.getString("address"), List(topics.length()) { topics.getString(it).lowercase() },
                json.getString("data"), json.getString("transactionHash"),
                ChainReader.quantity(json.getString("blockNumber")), json.getString("blockHash"),
                ChainReader.quantity(json.getString("logIndex")), json.optBoolean("removed", false),
            )
        }
    }
}
