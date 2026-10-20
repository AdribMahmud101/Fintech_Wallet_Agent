package app.fusion.wallet.data

import app.fusion.wallet.chain.*
import app.fusion.wallet.ui.bills.BillEvidenceReader
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real RPC measurement; no captured balances or events are injected into app state. */
class LiveEventIndexTest {
    @Test fun measureColdAndPersistedResume(): Unit = runBlocking {
        assumeTrue("Set K26_LIVE=1 for public RPC timing", System.getenv("K26_LIVE") == "1")
        val manifest = File("src/main/assets/deployments/84532.json").readText()
        val deployment = Deployment.parse(manifest)
        val live = JsonRpcClient(deployment.rpcUrl)
        val logRequests = AtomicInteger()
        val rpc = object : RpcTransport {
            override fun batch(calls: List<Pair<String, JSONArray>>): List<Any> {
                logRequests.addAndGet(calls.count { it.first == "eth_getLogs" })
                return live.batch(calls)
            }
            override fun request(method: String, params: JSONArray): Any {
                if (method == "eth_getLogs") logRequests.incrementAndGet()
                return live.request(method, params)
            }
        }
        val reader = ChainReader(rpc)
        assertEquals(deployment.chainId, reader.chainId())
        val head = reader.block("latest")
        val current = reader.call(deployment.asset, Views.BALANCE + Abi.addressWord(deployment.safe), head.number).requireSize(1).uint(0)
        val file = File.createTempFile("k26-live-events", ".json")
        val cache = FileSnapshotCache(file)
        val begin = System.nanoTime()
        val cold = EventIndex(deployment, cache).sync(reader, head)
        val coldMs = (System.nanoTime() - begin) / 1_000_000
        val coldRequests = logRequests.getAndSet(0)
        val warmStart = System.nanoTime()
        val warm = EventIndex(deployment, cache).sync(reader, head, refreshTail = true)
        val warmMs = (System.nanoTime() - warmStart) / 1_000_000
        assertEquals(cold.logs, warm.logs)
        assertEquals(3, logRequests.get())
        val points = BalanceReconstruction.reconstruct(current, head, warm.logs, warm.headers, deployment.asset, deployment.safe)
        val transfers = warm.logs.count { it.topics.firstOrNull() == BalanceReconstruction.TRANSFER }
        val receipts = BillEvidenceReader.fromLogs(head.hash, warm.logs.filter { it.address == deployment.module }, null)
        assertTrue("Expected transfer-backed chart points", points.size >= 4)
        val summary = JSONObject().put("block", head.number).put("blockHash", head.hash)
            .put("coldMs", coldMs).put("resumeMs", warmMs).put("coldLogRequests", coldRequests)
            .put("resumeLogRequests", logRequests.get()).put("transferCount", transfers)
            .put("moduleEvents", warm.logs.size - transfers).put("balancePoints", points.size)
            .put("billReceipts", receipts.paymentTxByBill.size).put("vendorRegistrations", receipts.registrationTxByPayee.size)
            .put("currentCashRaw", current.toString())
        File("/tmp/k26-live-timing.json").writeText(summary.toString(2))
        println("K26_LIVE $summary")
        file.delete()
    }
}
