package app.fusion.wallet.chain

import app.fusion.wallet.data.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production allowlist and JSON envelopes, which RpcTransport fakes bypass. */
class AgentGasRpcTest {
    private val session = "0xb52cb48ec1cc5ad1171c83f162228e31752f87fa"
    private val hash = "0x" + "a".repeat(64)
    private val blockHash = "0x" + "b".repeat(64)
    private val block = BlockHeader(100, blockHash, 1000)
    private val balance = BigInteger("17641102153994847")
    private val calls = mutableListOf<Pair<String, JSONArray>>()

    private fun receipt() = JSONObject().put("transactionHash", hash).put("from", session)
        .put("blockNumber", "0x63").put("blockHash", blockHash)
        .put("gasUsed", "0xa").put("effectiveGasPrice", "0x2").put("l1Fee", "0x5")

    private fun index() = TxIndex.parse(JSONArray().put(JSONObject().put("txHash", hash)
        .put("source", "AGENT").put("kind", "DEPOSIT").put("evidenceFile", "record.json")).toString())

    private fun client(receiptResult: Any = receipt(), snapshotHash: String = blockHash): JsonRpcClient {
        fun respond(request: JSONObject): JSONObject {
            val method = request.getString("method")
            val params = request.getJSONArray("params")
            calls += method to params
            val result = when (method) {
                // Same scalar type and quantity returned by live Base Sepolia for this session.
                "eth_getBalance" -> "0x3eac7d2b55d25f"
                "eth_getTransactionReceipt" -> receiptResult
                "eth_getBlockByNumber" -> JSONObject().put("number", params.getString(0))
                    .put("hash", if (params.getString(0) == "0x64") snapshotHash else blockHash)
                    .put("timestamp", "0x3e8")
                else -> error("Unexpected RPC $method")
            }
            return JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id")).put("result", result)
        }
        return JsonRpcClient(openConnection = { url ->
            object : HttpURLConnection(url) {
                private val output = ByteArrayOutputStream()
                override fun getOutputStream() = output
                override fun getResponseCode() = 200
                override fun getInputStream(): ByteArrayInputStream {
                    val request = JSONTokener(output.toString("UTF-8")).nextValue()
                    val response = when (request) {
                        is JSONObject -> respond(request)
                        is JSONArray -> JSONArray().apply {
                            // Batch responses need not arrive in request order.
                            for (i in request.length() - 1 downTo 0) put(respond(request.getJSONObject(i)))
                        }
                        else -> error("Invalid JSON-RPC payload")
                    }
                    return ByteArrayInputStream(response.toString().toByteArray(Charsets.UTF_8))
                }
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        })
    }

    @Test fun productionClientReadsBalanceAndReceiptFeesAtSnapshot() {
        val gas = AgentGasReader(client()).read(session, block, index())
        assertEquals(AgentGas(balance, 25.toBigDecimal(), balance / 25.toBigInteger()), gas)
        assertEquals(listOf("eth_getBalance", "eth_getTransactionReceipt", "eth_getBlockByNumber", "eth_getBlockByNumber"),
            calls.map { it.first })
        assertEquals(session, calls.first().second.getString(0))
        assertEquals("0x64", calls.first().second.getString(1))
        assertEquals("0x63", calls[2].second.getString(0))
        assertEquals("0x64", calls.last().second.getString(0))
    }

    @Test fun noFeeHistoryStillReturnsExactBalance() {
        assertEquals(AgentGas(balance, null, null), AgentGasReader(client()).read(session, block, TxIndex.Empty))
        assertEquals(listOf("eth_getBalance", "eth_getBlockByNumber"), calls.map { it.first })
    }

    @Test fun unavailableReceiptStillReturnsBalanceWithoutInventingFees() {
        assertEquals(AgentGas(balance, null, null),
            AgentGasReader(client(receiptResult = JSONObject.NULL)).read(session, block, index()))
    }

    @Test fun batchResultsAreUnwrappedAndReorderedByRequestId() {
        val found = client().batch(listOf(
            "eth_getBalance" to JSONArray().put(session).put("0x64"),
            "eth_getTransactionReceipt" to JSONArray().put(hash),
        ))
        assertEquals("0x3eac7d2b55d25f", found[0])
        assertEquals(hash, (found[1] as JSONObject).getString("transactionHash"))
    }

    @Test
    fun reorganizedSnapshotCannotReturnGasBalance() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentGasReader(client(snapshotHash = "0x" + "c".repeat(64))).read(session, block, TxIndex.Empty)
        }
        assertEquals(listOf("eth_getBalance", "eth_getBlockByNumber"), calls.map { it.first })
    }

    @Test fun transactionSubmissionRemainsForbidden() {
        val rpc = JsonRpcClient(openConnection = { error("Forbidden method reached HTTP") })
        listOf("eth_sendRawTransaction", "eth_sendTransaction", "personal_sign").forEach { method ->
            assertThrows(IllegalArgumentException::class.java) { rpc.request(method, JSONArray()) }
            assertThrows(IllegalArgumentException::class.java) { rpc.batch(listOf(method to JSONArray())) }
        }
    }
}
