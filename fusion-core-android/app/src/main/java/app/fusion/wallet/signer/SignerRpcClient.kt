package app.fusion.wallet.signer

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Pinned HTTPS endpoint. Untrusted RPC error strings and response bodies never enter UI/logs. */
internal class SignerRpcClient : SignerRpc {
    private val nextId = AtomicLong()
    override fun request(method: String, params: JSONArray): Any {
        require(method in METHODS) { "Unsupported signer RPC" }
        val id = nextId.incrementAndGet()
        val connection = URL(GuardianTransaction.RPC_URL).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) throw IOException("Signer RPC HTTP failure")
            val body = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1_048_576) { "Oversized signer RPC response" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val response = JSONObject(String(body, Charsets.UTF_8))
            check(response.optString("jsonrpc") == "2.0" && response.getLong("id") == id) { "Invalid signer RPC envelope" }
            if (response.has("error")) throw IOException("Signer RPC rejected request")
            return response.get("result")
        } finally { connection.disconnect() }
    }
    private companion object {
        val METHODS = setOf("eth_chainId", "eth_getTransactionCount", "eth_getBlockByNumber", "eth_maxPriorityFeePerGas",
            "eth_estimateGas", "eth_getBalance", "eth_sendRawTransaction", "eth_getTransactionReceipt", "eth_call")
    }
}
