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
    private val batchSizes = mutableListOf<Int>()

    private fun receipt() = JSONObject().put("transactionHash", hash).put("from", session)
        .put("blockNumber", "0x63").put("blockHash", blockHash)
        .put("gasUsed", "0xa").put("effectiveGasPrice", "0x2").put("l1Fee", "0x5")

    private fun index() = TxIndex.parse(JSONArray().put(JSONObject().put("txHash", hash)
        .put("source", "AGENT").put("kind", "DEPOSIT").put("evidenceFile", "record.json")).toString())

    private fun client(receiptResult: Any = receipt(), snapshotHash: String = blockHash,
        receiptFor: ((String) -> Any)? = null): JsonRpcClient {
        fun respond(request: JSONObject): JSONObject {
            val method = request.getString("method")
            val params = request.getJSONArray("params")
            calls += method to params
            val result = when (method) {
                // Same scalar type and quantity returned by live Base Sepolia for this session.
                "eth_getBalance" -> "0x3eac7d2b55d25f"
                "eth_getTransactionReceipt" -> receiptFor?.invoke(params.getString(0)) ?: receiptResult
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
                            batchSizes += request.length()
                            // Batch responses need not arrive in request order.
                            for (i in request.length() - 1 downTo 0) {
                                val item = request.getJSONObject(i)
                                // Live Base Sepolia returns some valid receipts and some -32007
                                // errors for the old 37-item burst. One error rejects the batch.
                                put(if (request.length() > 25 && i >= 25) JSONObject().put("jsonrpc", "2.0")
                                    .put("id", item.getLong("id")).put("error", JSONObject().put("code", -32007)
                                        .put("message", "25/second request limit reached")) else respond(item))
                            }
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

    @Test fun liveSizedIndexSurvivesProviderBurstLimitAndCachesReceipts() {
        // The live index has 37 rows, including SCRIPT/APP rows sent by the session:
        // receipt.from, rather than the evidence source label, determines eligibility.
        val rows = JSONArray().apply {
            repeat(37) { i -> put(JSONObject().put("txHash", "0x" + (i + 1).toString(16).padStart(64, '0'))
                .put("source", if (i < 17) "SCRIPT" else "OWNER").put("kind", "DEPOSIT").put("evidenceFile", "record.json")) }
        }
        val index = TxIndex.parse(rows.toString())
        val rpc = client(receiptFor = { tx -> receipt().put("transactionHash", tx)
            .put("blockNumber", ChainReader.tag(100L - tx.drop(2).toInt(16)))
            .put("from", if (tx.drop(2).toInt(16) <= 17) session.uppercase().replace("0X", "0x") else "0x" + "2".repeat(40)) })
        val reader = AgentGasReader(rpc)
        val gas = reader.read(session, block, index)
        assertEquals(AgentGas(balance, 25.toBigDecimal(), balance / 25.toBigInteger()), gas)
        assertEquals(listOf(20, 17, 17), batchSizes) // Receipts split at transport, then the 17 headers.
        assertEquals(37, calls.count { it.first == "eth_getTransactionReceipt" })
        assertEquals(18, calls.count { it.first == "eth_getBlockByNumber" })
        val firstReadCalls = calls.size
        assertEquals(gas, reader.read(session, block, index))
        assertFalse(calls.drop(firstReadCalls).any { it.first == "eth_getTransactionReceipt" })
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
