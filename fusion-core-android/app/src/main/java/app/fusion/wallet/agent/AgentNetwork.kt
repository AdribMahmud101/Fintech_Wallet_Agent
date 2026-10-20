package app.fusion.wallet.agent

import android.content.Context
import app.fusion.wallet.chain.JsonRpcClient
import app.fusion.wallet.chain.RpcTransport
import app.fusion.wallet.chain.RpcException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Optional loopback relay is transport only; no snapshot, decisions or signing on edge. */
internal object AgentNetwork {
    fun readOnly(context: Context, rpcUrl: String): RpcTransport {
        val config = File(context.filesDir, "agent/edge_rpc.json")
        if (!config.isFile) return JsonRpcClient(rpcUrl)
        return RpcTransport { method, params ->
            require(method in setOf("eth_chainId", "eth_call", "eth_getLogs", "eth_getBlockByNumber", "eth_getBalance", "eth_getTransactionReceipt"))
            relay(context, method, params)
        }
    }
    fun batch(context: Context, calls: List<Pair<String, JSONArray>>): List<Any> {
        val params = JSONArray().apply { calls.forEach { (method, args) -> put(JSONObject().put("method", method).put("params", args)) } }
        val replies = relay(context, "batch", params) as JSONArray
        val byId = (0 until replies.length()).map { replies.getJSONObject(it) }.associateBy { it.getInt("id") }
        return calls.indices.map { id ->
            val r = byId[id] ?: error("Missing RPC batch response")
            check(!r.has("error")) { "Read batch rejected ${calls[id].first}" }
            r.get("result")
        }
    }
    fun requireLease(context: Context): String {
        val config = JSONObject(File(context.filesDir, "agent/edge_rpc.json").readText())
        val result = relay(context, "lease", JSONArray()) as JSONObject
        check(result.getBoolean("held") && result.getString("requestId") == config.getString("requestId")) { "SESSION_LOCK_REQUIRED" }
        return result.getString("requestId")
    }
    fun relay(context: Context, method: String, params: JSONArray): Any {
        val config = JSONObject(File(context.filesDir, "agent/edge_rpc.json").readText())
        require(config.getString("host") == "127.0.0.1")
        val port = config.getInt("port"); require(port in 1024..65535)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5000); socket.soTimeout = 30_000
            val request = JSONObject().put("token", config.getString("token")).put("method", method).put("params", params)
            socket.getOutputStream().write((request.toString() + "\n").toByteArray())
            val input = socket.getInputStream().bufferedReader()
            val line = input.readLine() ?: error("Edge RPC connection closed")
            check(line.length < 1_048_576) { "Edge RPC response exceeds limit" }
            val envelope = JSONObject(line)
            if (envelope.has("error")) {
                val error = envelope.getJSONObject("error")
                throw RpcException(error.optString("message", "Live RPC transport failed"), httpStatus = if (error.has("httpStatus")) error.getInt("httpStatus") else null, rpcCode = if (error.has("code")) error.getInt("code") else null)
            }
            envelope.get("result")
        }
    }
}
