package app.fusion.wallet.data

import android.content.Context
import app.fusion.wallet.chain.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One durable, deployment-scoped index. A checkpoint advances only after a complete contiguous page.
 * Failed pages leave earlier checkpoints on disk. The last 64 blocks are replaced on new heads.
 * Snapshot cache height is deliberately never used as an event cursor.
 */
class EventIndex(private val deployment: Deployment, private val cache: SnapshotCache? = null,
    private val pageSize: Long = 200) {
    data class Snapshot(val through: Long, val hash: String, val logs: List<ChainLog>, val headers: Map<Long, BlockHeader>)
    private val lock = Mutex()
    private val io = Dispatchers.IO.limitedParallelism(4)
    private val identity = "${deployment.chainId}:${deployment.module}:${deployment.asset}:${deployment.safe}:${deployment.deploymentBlock}"
    private var saved: Snapshot? = decode(cache?.read())
    init { require(pageSize > 0) }

    suspend fun sync(reader: ChainReader, head: BlockHeader, refreshTail: Boolean = false): Snapshot = lock.withLock {
        saved?.takeIf { !refreshTail && it.through == head.number && it.hash.equals(head.hash, true) }?.let { return@withLock it }
        require(head.number >= deployment.deploymentBlock)
        val old = saved
        var start = old?.let { maxOf(deployment.deploymentBlock, minOf(it.through, head.number) - 63) }
            ?: deployment.deploymentBlock
        var logs = old?.logs.orEmpty().filter { it.blockNumber < start }
        val headers = old?.headers.orEmpty().filterKeys { it < start }.toMutableMap()
        val ownerTopic = "0x" + Abi.addressWord(deployment.safe)
        // Bounded waves: four pages, three independent filters each, at most four in-flight requests.
        while (start <= head.number) {
            val ranges = (0..3).map { start + it * pageSize }.filter { it <= head.number }
                .map { it..minOf(it + pageSize - 1, head.number) }
            val pages = coroutineScope {
                ranges.map { range -> async(io) {
                    val filters = listOf(
                        deployment.module to emptyList<String?>(),
                        deployment.asset to listOf(BalanceReconstruction.TRANSFER, ownerTopic),
                        deployment.asset to listOf(BalanceReconstruction.TRANSFER, null, ownerTopic))
                    reader.logBatch(filters, range.first, range.last)
                } }.awaitAll()
            }
            // Publish in range order, so a resumed index never skips an earlier page.
            ranges.zip(pages).forEach { (range, page) ->
                val distinct = page.distinctBy { it.blockHash to it.logIndex }
                val fetched = coroutineScope { distinct.map { it.blockNumber }.distinct().map { number ->
                    async(io) { number to reader.block(ChainReader.tag(number)) }
                }.awaitAll().toMap() }
                require(distinct.all { fetched.getValue(it.blockNumber).hash.equals(it.blockHash, true) }) { "Event block reorganized" }
                headers.putAll(fetched)
                logs = (logs + distinct).sortedWith(compareBy({ it.blockNumber }, { it.logIndex }))
                val endHash = if (range.last == head.number) head.hash else fetched[range.last]?.hash.orEmpty()
                if (range.last == head.number) {
                    require(withContext(io) { reader.block(ChainReader.tag(head.number)) }.hash.equals(head.hash, true)) { "Event head reorganized" }
                }
                val checkpoint = Snapshot(range.last, endHash, logs, headers.toMap())
                cache?.write(encode(checkpoint))
                saved = checkpoint
            }
            start = ranges.last().last + 1
        }
        requireNotNull(saved)
    }

    private fun encode(value: Snapshot): String = JSONObject().put("v", 1).put("identity", identity)
        .put("through", value.through).put("hash", value.hash)
        .put("logs", JSONArray().apply { value.logs.forEach { put(JSONObject()
            .put("address", it.address).put("topics", JSONArray(it.topics)).put("data", it.data)
            .put("transactionHash", it.txHash).put("blockNumber", ChainReader.tag(it.blockNumber))
            .put("blockHash", it.blockHash).put("logIndex", ChainReader.tag(it.logIndex))) } })
        .put("headers", JSONArray().apply { value.headers.values.forEach { put(JSONObject()
            .put("number", it.number).put("hash", it.hash).put("time", it.timestamp)) } }).toString()

    private fun decode(raw: String?): Snapshot? = try {
        if (raw == null) null else {
            val root = JSONObject(raw)
            require(root.getInt("v") == 1 && root.getString("identity") == identity)
            val through = root.getLong("through")
            val logs = root.getJSONArray("logs").let { a -> List(a.length()) { ChainLog.fromJson(a.getJSONObject(it)) } }
            require(logs.all { it.blockNumber in deployment.deploymentBlock..through && !it.removed })
            val headers = root.getJSONArray("headers").let { a -> List(a.length()) {
                a.getJSONObject(it).let { h -> BlockHeader(h.getLong("number"), h.getString("hash"), h.getLong("time")) }
            } }.associateBy { it.number }
            require(logs.all { headers[it.blockNumber]?.hash.equals(it.blockHash, true) })
            Snapshot(through, root.getString("hash"), logs, headers)
        }
    } catch (_: Exception) { null }
}

/** Home, onboarding, receipts and Activity resolve the same index and private atomic cache file. */
object SharedEventIndexes {
    private val indexes = mutableMapOf<String, EventIndex>()
    @Synchronized fun forContext(context: Context, deployment: Deployment): EventIndex {
        val key = "${context.noBackupFilesDir}:${deployment.chainId}:${deployment.module}:${deployment.safe}:${deployment.asset}"
        return indexes.getOrPut(key) { EventIndex(deployment, FileSnapshotCache(File(context.noBackupFilesDir,
            "events-${deployment.chainId}-${deployment.module}-${deployment.safe}-${deployment.asset}.json"))) }
    }
}
