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
}

class RpcException(message: String, val httpStatus: Int? = null, val rpcCode: Int? = null) : IOException(message)

class JsonRpcClient(val rpcUrl: String = "https://sepolia.base.org") : RpcTransport {
    private val nextId = AtomicLong()
    init { require(URL(rpcUrl).protocol == "https") { "RPC requires HTTPS" } }

    override fun request(method: String, params: JSONArray): Any {
        require(method in READ_METHODS) { "Unsupported read-only RPC method: $method" }
        val id = nextId.incrementAndGet()
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val connection = URL(rpcUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
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
    companion object {
        private val READ_METHODS = setOf("eth_chainId", "eth_call", "eth_getLogs", "eth_getBlockByNumber")
    }
}

data class BlockHeader(val number: Long, val hash: String, val timestamp: Long)

class ChainReader(private val rpc: RpcTransport) {
    private var logPageSize = 1000L
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
    fun logs(address: String, from: Long, through: Long): List<ChainLog> {
        val result = mutableListOf<ChainLog>()
        var start = from
        var pageSize = logPageSize
        while (start <= through) {
            val end = minOf(start + pageSize - 1, through)
            val filter = JSONObject().put("address", address).put("fromBlock", tag(start)).put("toBlock", tag(end))
            val page = try {
                rpc.request("eth_getLogs", JSONArray().put(filter)) as JSONArray
            } catch (error: RpcException) {
                val rangeLimited = error.httpStatus == 413 || error.rpcCode == -32005 ||
                    (error.rpcCode == -32602 && error.message.orEmpty().contains("range", ignoreCase = true))
                if (!rangeLimited || pageSize == 1L) throw error
                pageSize = maxOf(1, pageSize / 2)
                logPageSize = pageSize
                continue
            }
            for (i in 0 until page.length()) {
                val log = ChainLog.fromJson(page.getJSONObject(i))
                require(!log.removed && log.blockNumber in start..end && log.address.equals(address, true)) { "Invalid or removed log" }
                result += log
            }
            start = end + 1
        }
        return result.distinctBy { it.blockHash to it.logIndex }.sortedWith(compareBy({ it.blockNumber }, { it.logIndex }))
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
