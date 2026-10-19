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
            if (connection.responseCode !in 200..299) throw IOException("RPC HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = JSONObject(body)
            if (response.has("error")) throw IOException("$method: ${response.getJSONObject("error").optString("message")}")
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
    fun chainId(): Long = quantity(rpc.request("eth_chainId", JSONArray()).toString())
    fun block(tag: String): BlockHeader {
        val value = rpc.request("eth_getBlockByNumber", JSONArray().put(tag).put(false)) as JSONObject
        return BlockHeader(quantity(value.getString("number")), value.getString("hash"), quantity(value.getString("timestamp")))
    }
    fun call(to: String, data: String, block: Long): Abi = Abi(
        rpc.request("eth_call", JSONArray().put(JSONObject().put("to", to).put("data", data)).put(tag(block))).toString(),
    )
    fun logs(address: String, from: Long, through: Long): List<ChainLog> {
        val result = mutableListOf<ChainLog>()
        var start = from
        while (start <= through) {
            val end = minOf(start + 999, through)
            val filter = JSONObject().put("address", address).put("fromBlock", tag(start)).put("toBlock", tag(end))
            val page = rpc.request("eth_getLogs", JSONArray().put(filter)) as JSONArray
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
            require(value.startsWith("0x") && value.length > 2)
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
