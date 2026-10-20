package app.fusion.wallet.data

import app.fusion.wallet.chain.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Optional read-only metric. Every balance is pinned to the displayed snapshot block. */
class AgentGasReader(private val rpc: RpcTransport) {
    private val receipts = mutableMapOf<String, JSONObject>()
    fun read(session: String, block: BlockHeader, index: TxIndex): AgentGas {
        require(Regex("0x[0-9a-fA-F]{40}").matches(session) && session.drop(2).any { it != '0' })
        val reader = ChainReader(rpc)
        fun quantity(value: String): BigInteger {
            require(Regex("0x[0-9a-fA-F]+").matches(value))
            return BigInteger(value.drop(2), 16)
        }
        val balance = quantity(rpc.request("eth_getBalance", JSONArray().put(session).put(ChainReader.tag(block.number))).toString())
        // Batch missing receipts and candidate headers so this optional metric cannot add a
        // serial request per indexed transaction to every live refresh.
        index.all().filter { it.txHash !in receipts }.chunked(50).forEach { chunk ->
            runCatching {
                val found = rpc.batch(chunk.map { "eth_getTransactionReceipt" to JSONArray().put(it.txHash) })
                require(found.size == chunk.size)
                found.forEachIndexed { i, value -> if (value is JSONObject) receipts[chunk[i].txHash] = value }
            }
        }
        val candidates = index.all().mapNotNull { entry ->
            val receipt = receipts[entry.txHash] ?: return@mapNotNull null
            runCatching {
                require(receipt.getString("transactionHash").equals(entry.txHash, true))
                if (!receipt.getString("from").equals(session, true)) return@mapNotNull null
                val height = ChainReader.quantity(receipt.getString("blockNumber"))
                if (height > block.number) return@mapNotNull null
                height to receipt
            }.getOrNull()
        }.sortedByDescending { it.first }.take(20)
        val heights = candidates.map { it.first }.distinct()
        val headers = runCatching {
            val found = rpc.batch(heights.map { "eth_getBlockByNumber" to JSONArray().put(ChainReader.tag(it)).put(false) })
            require(found.size == heights.size)
            heights.zip(found).associate { (height, raw) ->
                val json = raw as JSONObject
                require(ChainReader.quantity(json.getString("number")) == height)
                height to BlockHeader(height, json.getString("hash"), ChainReader.quantity(json.getString("timestamp")))
            }
        }.getOrDefault(emptyMap())
        val samples = candidates.mapNotNull { (height, receipt) ->
            runCatching {
                val header = headers[height] ?: return@mapNotNull null
                if (!header.hash.equals(receipt.getString("blockHash"), true)) {
                    receipts.remove(receipt.getString("transactionHash").lowercase())
                    return@mapNotNull null
                }
                if (header.timestamp !in (block.timestamp - 30 * 86400L)..block.timestamp) return@mapNotNull null
                quantity(receipt.getString("gasUsed")) * quantity(receipt.getString("effectiveGasPrice")) +
                    listOf("l1Fee", "operatorFee").fold(BigInteger.ZERO) { sum, key ->
                        sum + if (receipt.has(key) && !receipt.isNull(key)) quantity(receipt.getString(key)) else BigInteger.ZERO
                    }
            }.getOrNull()
        }
        require(reader.block(ChainReader.tag(block.number)).hash.equals(block.hash, true))
        return gasRunway(balance, samples)
    }
}
