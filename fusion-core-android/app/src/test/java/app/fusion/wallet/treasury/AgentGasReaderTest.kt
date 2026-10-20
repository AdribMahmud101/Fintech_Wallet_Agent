package app.fusion.wallet.treasury

import app.fusion.wallet.chain.*
import app.fusion.wallet.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class AgentGasReaderTest {
    private val session = "0x" + "1".repeat(40)
    private val hash = "0x" + "a".repeat(64)
    private val blockHash = "0x" + "b".repeat(64)
    private val head = BlockHeader(100, blockHash, 1000)
    private fun index() = TxIndex.parse(JSONArray().put(JSONObject().put("txHash", hash).put("source", "AGENT")
        .put("kind", "DEPOSIT").put("evidenceFile", "record.json")).toString())
    private fun receipt() = JSONObject().put("transactionHash", hash).put("from", session).put("blockNumber", "0x63")
        .put("blockHash", blockHash).put("gasUsed", "0xa").put("effectiveGasPrice", "0x2").put("l1Fee", "0x5")
    private fun rpc(receipt: JSONObject, onBalance: (JSONArray) -> Unit = {}) = RpcTransport { method, params ->
        when (method) {
            "eth_getBalance" -> { onBalance(params); "0x64" }
            "eth_getTransactionReceipt" -> receipt
            "eth_getBlockByNumber" -> JSONObject().put("number", params.getString(0)).put("hash", blockHash).put("timestamp", "0x3e8")
            else -> error("Unexpected RPC $method")
        }
    }
    @Test fun pinnedBalanceAndActualReceiptFeesDetermineMoves() {
        var queried = false
        val gas = AgentGasReader(rpc(receipt()) {
            assertEquals(session, it.getString(0)); assertEquals("0x64", it.getString(1)); queried = true
        }).read(session, head, index())
        assertTrue(queried)
        assertEquals(BigInteger.valueOf(100), gas.balanceWei)
        assertEquals(BigInteger.valueOf(4), gas.estimatedMoves) // (10*2 + 5) wei per move, including L1 fee.
    }
    @Test fun unrelatedFutureOrReorganizedReceiptsCannotSupplyMoveEstimate() {
        val invalid = listOf(receipt().put("from", "0x" + "2".repeat(40)), receipt().put("blockNumber", "0x65"),
            receipt().put("blockHash", "0x" + "c".repeat(64)), receipt().put("transactionHash", "0x" + "d".repeat(64)))
        invalid.forEach { assertNull(AgentGasReader(rpc(it)).read(session, head, index()).estimatedMoves) }
    }
    @Test fun unknownReceiptHistoryKeepsBalanceWithoutEstimate() {
        assertNull(AgentGasReader(rpc(receipt())).read(session, head, TxIndex.Empty).estimatedMoves)
    }
    @Test fun thirtyDayWindowUsesPinnedBlockTimeAndKeepsOldHistoryBalanceOnly() {
        val recentHead = head.copy(timestamp = 31 * 86400L)
        val oldHeaderRpc = rpc(receipt()) // timestamp=1000, older than the pinned 30-day window
        assertEquals(AgentGas(BigInteger.valueOf(100), null, null),
            AgentGasReader(oldHeaderRpc).read(session, recentHead, index()))
        assertNotNull(AgentGasReader(oldHeaderRpc).read(session,
            head.copy(timestamp = 30 * 86400L + 1000), index()).estimatedMoves)
    }

    @Test fun mixedCaseReceiptHashIsEvictedAfterReorgAndFetchedAgain() {
        var reads = 0
        val normal = rpc(receipt())
        val reader = AgentGasReader(RpcTransport { method, params ->
            if (method == "eth_getTransactionReceipt") {
                reads++
                receipt().put("transactionHash", "0x" + "A".repeat(64))
                    .put("blockHash", if (reads == 1) "0x" + "c".repeat(64) else blockHash)
            } else normal.request(method, params)
        })
        assertNull(reader.read(session, head, index()).estimatedMoves)
        assertNotNull(reader.read(session, head, index()).estimatedMoves)
        assertEquals(2, reads)
    }
}
