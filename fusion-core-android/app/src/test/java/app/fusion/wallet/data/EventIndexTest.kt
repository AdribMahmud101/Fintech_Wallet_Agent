package app.fusion.wallet.data

import app.fusion.wallet.chain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class EventIndexTest {
    private val deployment = Deployment.parse(javaClass.classLoader!!.getResource("chain/deployment.json")!!.readText())
    private val start get() = deployment.deploymentBlock
    private class Cache : SnapshotCache {
        var json: String? = null
        override fun read() = json
        override fun write(json: String) { this.json = json }
    }
    private fun header(n: Long, version: Int = 1) = BlockHeader(n, "0x" + (n * 10 + version).toString(16).padStart(64, '0'), n * 2)
    private inner class Rpc : RpcTransport {
        val ranges = Collections.synchronizedList(mutableListOf<Pair<String, LongRange>>())
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        var failFrom: Long? = null
        var logs = emptyList<ChainLog>()
        var version = 1
        override fun request(method: String, params: JSONArray): Any = when (method) {
            "eth_getBlockByNumber" -> {
                val n = ChainReader.quantity(params.getString(0))
                val h = header(n, version)
                JSONObject().put("number", ChainReader.tag(n)).put("hash", h.hash).put("timestamp", ChainReader.tag(h.timestamp))
            }
            "eth_getLogs" -> {
                val filter = params.getJSONObject(0)
                val range = ChainReader.quantity(filter.getString("fromBlock"))..ChainReader.quantity(filter.getString("toBlock"))
                ranges += filter.getString("address") to range
                val count = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, count) }
                try {
                    // Small overlap gives a deterministic opportunity for concurrent requests.
                    Thread.sleep(5)
                    if (failFrom?.let { range.first >= it } == true) throw IOException("injected timeout")
                    val topics = filter.optJSONArray("topics")
                    JSONArray().apply { logs.filter { log ->
                        log.blockNumber in range && log.address.equals(filter.getString("address"), true) &&
                            (topics == null || (0 until topics.length()).all { topics.isNull(it) || topics.getString(it) == log.topics.getOrNull(it) })
                    }.forEach { log -> put(JSONObject().put("address", log.address).put("topics", JSONArray(log.topics))
                        .put("data", log.data).put("transactionHash", log.txHash).put("blockNumber", ChainReader.tag(log.blockNumber))
                        .put("blockHash", log.blockHash).put("logIndex", ChainReader.tag(log.logIndex))) } }
                } finally { active.decrementAndGet() }
            }
            else -> error(method)
        }
    }
    private fun transfer(block: Long, from: String, to: String) = ChainLog(deployment.asset,
        listOf(BalanceReconstruction.TRANSFER, "0x" + Abi.addressWord(from), "0x" + Abi.addressWord(to)),
        "0x" + Abi.uintWord(java.math.BigInteger.TEN), "0x" + "ab".repeat(32), block, header(block).hash, 0)

    @Test fun persistedCursorResumesWithOverlapAndRetainsOlderVendorsAndTransfers() = runBlocking {
        val cache = Cache()
        val rpc = Rpc()
        val oldTransfer = transfer(start + 1, deployment.safe, deployment.safe) // matches both transfer filters
        val vendor = oldTransfer.copy(address = deployment.module, topics = listOf(ModuleEvents.PAYEE_ADDED), logIndex = 1)
        rpc.logs = listOf(oldTransfer, vendor)
        val head = header(start + 1000)
        val first = EventIndex(deployment, cache, 100).sync(ChainReader(rpc), head)
        assertEquals(2, first.logs.size)
        assertTrue(rpc.maximum.get() in 2..4)
        assertTrue(rpc.ranges.all { it.second.last - it.second.first < 100 })
        rpc.ranges.clear()
        val restored = EventIndex(deployment, cache, 100)
        val second = restored.sync(ChainReader(rpc), header(head.number + 5))
        assertEquals(first.logs, second.logs)
        assertEquals(head.number - 63, rpc.ranges.minOf { it.second.first })
        rpc.ranges.clear()
        assertEquals(second, restored.sync(ChainReader(rpc), header(head.number + 5)))
        assertTrue(rpc.ranges.isEmpty())
    }

    @Test fun timeoutKeepsContiguousCheckpointAndRestartNeverSkipsFailedRange() = runBlocking {
        val cache = Cache()
        val rpc = Rpc().apply { failFrom = start + 400 }
        try { EventIndex(deployment, cache, 100).sync(ChainReader(rpc), header(start + 800)); fail("expected timeout") }
        catch (_: IOException) { }
        assertEquals(start + 399, JSONObject(cache.json!!).getLong("through"))
        rpc.failFrom = null
        rpc.ranges.clear()
        val resumed = EventIndex(deployment, cache, 100).sync(ChainReader(rpc), header(start + 800))
        assertEquals(start + 800, resumed.through)
        assertEquals(start + 336, rpc.ranges.minOf { it.second.first })
    }

    @Test fun shallowReorgAndRollbackRemoveOldLogsAndCorruptCacheStartsSafely() = runBlocking {
        val cache = Cache()
        val rpc = Rpc()
        rpc.logs = listOf(transfer(start + 195, deployment.safe, deployment.safe))
        val index = EventIndex(deployment, cache, 100)
        assertEquals(1, index.sync(ChainReader(rpc), header(start + 200)).logs.size)
        rpc.version = 2
        rpc.logs = emptyList()
        assertTrue(index.sync(ChainReader(rpc), header(start + 198, 2)).logs.isEmpty())
        cache.json = "broken"
        rpc.ranges.clear()
        EventIndex(deployment, cache, 100).sync(ChainReader(rpc), header(start + 201, 2))
        assertEquals(start, rpc.ranges.minOf { it.second.first })
    }
}
