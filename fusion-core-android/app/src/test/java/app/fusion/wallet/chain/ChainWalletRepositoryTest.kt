package app.fusion.wallet.chain

import app.fusion.wallet.data.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChainWalletRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun repository(rpc: RpcTransport, obligations: String = resource("obligations.json")) =
        ChainWalletRepository(resource("deployment.json"), obligations, rpc)

    @Test fun gasSamplingRefreshesMissingIndexWithoutUiAndUsesCacheWhenOffline() = runBlocking {
        val replay = CapturedRpc()
        val snapshot = JSONObject(resource("live-snapshot.json")).getJSONObject("summary")
        val session = "0xb52cb48ec1cc5ad1171c83f162228e31752f87fa"
        val tx = "0x" + "a".repeat(64)
        val file = temp.root.resolve("evidence/tx_index.json")
        var offline = false
        val index = TxIndexCache(file) {
            if (offline) throw IOException("offline")
            """[{"txHash":"$tx","source":"SCRIPT","kind":"DEPOSIT","evidenceFile":"record.json"}]"""
        }
        val rpc = RpcTransport { method, params ->
            when (method) {
                "eth_getBalance" -> { assertTrue(params.getString(0).equals(session, true)); "0x64" }
                "eth_getTransactionReceipt" -> JSONObject().put("transactionHash", tx).put("from", session)
                    .put("blockNumber", ChainReader.tag(snapshot.getLong("block"))).put("blockHash", snapshot.getString("blockHash"))
                    .put("gasUsed", "0xa").put("effectiveGasPrice", "0x2").put("l1Fee", "0x5")
                "eth_call" -> {
                    val value = replay.request(method, params)
                    // The older replay predates the live session; supply it in the module ABI too.
                    if (params.getJSONObject(0).getString("data") == Views.STATE)
                        value.toString().replaceRange(2 + 13 * 64, 2 + 14 * 64, Abi.addressWord(session)) else value
                }
                else -> replay.request(method, params)
            }
        }
        val repository = ChainWalletRepository(resource("deployment.json"), resource("obligations.json"), rpc,
            txIndex = index::refresh) // Same provider as fromAssets; no Compose/UI refresh participates.
        assertFalse(file.exists())
        repository.refresh()
        assertEquals(repository.state.value.historyError, 4.toBigInteger(), repository.state.value.agentGas?.estimatedMoves)
        assertTrue(file.exists())
        offline = true
        repository.refresh()
        assertEquals(4.toBigInteger(), repository.state.value.agentGas?.estimatedMoves)
    }

    @Test fun oneBlockLiveReplayMatchesManifestAndPaymentEvidence() = runBlocking {
        val replay = CapturedRpc()
        val repository = repository(replay)
        assertNull(repository.state.value.treasury)
        repository.refresh()
        val state = repository.state.value
        assertEquals(state.error, LoadStatus.READY, state.status)
        assertEquals(DataSource.LIVE, state.source)
        assertEquals(48320776L, state.blockNumber)
        assertEquals("9467001235", state.treasury!!.balance.rawUnits)
        assertEquals("9467.001235", state.treasury!!.balance.display)
        assertEquals("50000000", state.module!!.reserved.rawUnits)
        assertEquals("8917001235", state.buckets!!.surplus.rawUnits)
        assertTrue(state.module!!.paused)
        assertFalse(state.module!!.sessionActive)
        assertTrue(state.queue.isEmpty())
        assertTrue(state.accountingMatches == true)
        assertEquals(5, state.obligations.size)
        assertEquals(4, state.obligations.count { it.paid })
        assertEquals(3, state.payees.size)
        assertEquals(2, state.payees.count { it.status == PayeeStatus.ACTIVE })
        assertEquals(1, state.payees.count { it.status == PayeeStatus.COOLING })
        assertTrue(state.vaults.all { it.sharesRaw.signum() == 0 && it.assets == Money.ZERO })
        assertEquals(5, state.recentActions.size)
        assertEquals(25, state.evidence.size)
        assertTrue(state.evidence.all { it.explorerUrl.startsWith("https://sepolia.basescan.org/tx/0x") })
        val payments = state.recentActions.filter { it.kind == ActionKind.PAY_OBLIGATION }
        assertEquals(3, payments.size)
        val evidence = JSONObject(resource("evidence.json")).getJSONArray("rows")
        payments.forEach { payment ->
            val paymentEvidence = (0 until evidence.length()).map { evidence.getJSONObject(it) }
                .first { it.getString("txHash") == payment.txHash }
            assertEquals(paymentEvidence.getLong("block"), payment.blockNumber)
            assertTrue(state.payees.any { it.address == payment.payee })
        }
        assertTrue(state.recentActions.all { it.blockTime > 0 && it.blockNumber <= state.blockNumber!! })
        assertTrue(replay.requests.filter { it.first == "eth_call" }.all { it.second.getString(1) == "0x2e15108" })
    }

    @Test fun failedViewNeverBecomesZeroAndLastSnapshotIsRetained() = runBlocking {
        val replay = CapturedRpc()
        val repository = repository(replay)
        repository.refresh()
        val before = repository.state.value
        replay.failSelector = Views.CONVERT_TO_ASSETS
        repository.refresh()
        val after = repository.state.value
        assertEquals(LoadStatus.ERROR, after.status)
        assertEquals(before.treasury, after.treasury)
        assertEquals(before.blockHash, after.blockHash)
        assertEquals(before.vaults, after.vaults)
        assertFalse(after.refreshing)
        assertTrue(after.error!!.contains("injected view failure"))
    }

    @Test fun initialFailureLeavesAllBalancesUnavailable() = runBlocking {
        val replay = CapturedRpc().apply { failSelector = Views.STATE }
        val repository = repository(replay)
        repository.refresh()
        assertEquals(LoadStatus.ERROR, repository.state.value.status)
        assertNull(repository.state.value.treasury)
        assertNull(repository.state.value.buckets)
        assertTrue(repository.state.value.vaults.isEmpty())
    }

    @Test fun wrongChainAndReorganizedSnapshotAreRejected() = runBlocking {
        val wrongChain = repository(CapturedRpc().apply { wrongChain = true })
        wrongChain.refresh()
        assertEquals(LoadStatus.ERROR, wrongChain.state.value.status)
        assertTrue(wrongChain.state.value.error!!.contains("chain"))
        val reorg = repository(CapturedRpc().apply { reorganize = true })
        reorg.refresh()
        assertEquals(LoadStatus.ERROR, reorg.state.value.status)
        assertTrue(reorg.state.value.error!!.contains("reorganized"))
        assertNull(reorg.state.value.treasury)
    }

    @Test fun accountingMismatchLatchesWithoutHidingLiveBalance() = runBlocking {
        val local = JSONObject(resource("obligations.json"))
        local.getJSONArray("bills").getJSONObject(4).put("assets", "50000001")
        val repository = repository(CapturedRpc(), local.toString())
        repository.refresh()
        assertEquals(LoadStatus.READY, repository.state.value.status)
        assertFalse(repository.state.value.accountingMatches!!)
        assertTrue(repository.state.value.accountingError!!.startsWith("ACCOUNTING_MISMATCH"))
        assertEquals("9467001235", repository.state.value.treasury!!.balance.rawUnits)
    }

    @Test fun staleBundledBillsReplacedByMatchingCandidate() = runBlocking {
        val staleBundled = JSONObject(resource("obligations.json")).apply {
            put("root", "0x" + "0".repeat(64))
        }.toString()
        val matchingRemote = resource("obligations.json")
        val source = object : ObligationsCandidateSource {
            override fun fetchCandidate(url: String): String? = matchingRemote
            override fun listAgentEvidenceUrls(): List<String> = emptyList()
        }
        val resolver = ObligationsResolver(source)
        val repository = ChainWalletRepository(resource("deployment.json"), staleBundled, CapturedRpc(), resolver)
        repository.refresh()
        val state = repository.state.value
        assertEquals(LoadStatus.READY, state.status)
        assertTrue(state.accountingMatches == true)
        assertEquals(5, state.obligations.size)
        assertEquals("9467001235", state.treasury!!.balance.rawUnits)
    }

    @Test fun noCandidateMatchesLeavesTotalsFromChainAvailable() = runBlocking {
        val staleBundled = JSONObject(resource("obligations.json")).apply {
            put("root", "0x" + "0".repeat(64))
        }.toString()
        val source = object : ObligationsCandidateSource {
            override fun fetchCandidate(url: String): String? = null
            override fun listAgentEvidenceUrls(): List<String> = emptyList()
        }
        val resolver = ObligationsResolver(source)
        val repository = ChainWalletRepository(resource("deployment.json"), staleBundled, CapturedRpc(), resolver)
        repository.refresh()
        val state = repository.state.value
        assertEquals(LoadStatus.READY, state.status)
        assertFalse(state.accountingMatches!!)
        assertTrue(state.accountingError!!.startsWith("ACCOUNTING_MISMATCH"))
        assertTrue(state.obligations.isEmpty())
        assertEquals("9467001235", state.treasury!!.balance.rawUnits)
        assertEquals("50000000", state.module!!.reserved.rawUnits)
        assertEquals("8917001235", state.buckets!!.surplus.rawUnits)
    }

    @Test fun repeatRefreshReconcilesHistoryWithoutDuplicatingActions() = runBlocking {
        val replay = CapturedRpc()
        val repository = repository(replay)
        repository.refresh()
        val before = repository.state.value
        val firstReadCount = replay.requests.size
        repository.refresh()
        val after = repository.state.value
        assertEquals(after.error, LoadStatus.READY, after.status)
        assertEquals(before.recentActions, after.recentActions)
        assertEquals(before.evidence, after.evidence)
        val range = replay.requests.drop(firstReadCount).first { it.first == "eth_getLogs" }.second.getJSONObject(0)
        assertEquals(before.blockNumber!! - 63, ChainReader.quantity(range.getString("fromBlock")))
    }

    @Test fun providerLogLimitsShrinkPagesWithoutGaps() {
        val ranges = mutableListOf<LongRange>()
        val reader = ChainReader(RpcTransport { _, params ->
            val filter = params.getJSONObject(0)
            val start = ChainReader.quantity(filter.getString("fromBlock"))
            val end = ChainReader.quantity(filter.getString("toBlock"))
            if (end - start >= 100) throw RpcException("range too large", httpStatus = 413)
            ranges += start..end
            JSONArray()
        })
        assertTrue(reader.logs("0xmodule", 10, 1010).isEmpty())
        assertEquals(10L, ranges.first().first)
        assertEquals(1010L, ranges.last().last)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test fun transportRejectsWritesAndPlaintextBeforeNetworkAccess() {
        assertThrows(IllegalArgumentException::class.java) { JsonRpcClient().request("eth_sendRawTransaction", JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { JsonRpcClient("http://localhost:8545") }
    }
}

/** Replay captured live RPC bytes. Historical logs are filtered to the requested range. */
internal class CapturedRpc : RpcTransport {
    private val capture = JSONObject(resource("live-snapshot.json"))
    private val calls = capture.getJSONArray("calls").let { array -> List(array.length()) { array.getJSONObject(it) } }
    private val allLogs = calls.filter { it.getString("method") == "eth_getLogs" }.flatMap {
        val page = it.getJSONArray("result"); List(page.length()) { index -> page.getJSONObject(index) }
    }
    val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, JSONArray>>())
    var failSelector: String? = null
    var wrongChain = false
    var reorganize = false
    override fun request(method: String, params: JSONArray): Any {
        requests += method to JSONArray(params.toString())
        if (method == "eth_call" && failSelector?.let { params.getJSONObject(0).getString("data").startsWith(it) } == true) {
            throw IOException("injected view failure")
        }
        if (method == "eth_chainId" && wrongChain) return "0x1"
        if (method == "eth_getLogs") {
            val filter = params.getJSONObject(0)
            val from = ChainReader.quantity(filter.getString("fromBlock"))
            val to = ChainReader.quantity(filter.getString("toBlock"))
            return JSONArray(allLogs.filter { log ->
                ChainReader.quantity(log.getString("blockNumber")) in from..to &&
                    log.getString("address").equals(filter.getString("address"), true) &&
                    (filter.optJSONArray("topics")?.let { topics -> (0 until topics.length()).all { i ->
                        topics.isNull(i) || topics.getString(i).equals(log.getJSONArray("topics").optString(i), true)
                    } } ?: true)
            })
        }
        val captured = calls.firstOrNull { it.getString("method") == method && it.getJSONArray("params").similar(params) }
            ?: error("Uncaptured RPC request $method $params")
        val result = captured.get("result")
        if (reorganize && method == "eth_getBlockByNumber" && params.getString(0) == "0x2e15108") {
            return JSONObject((result as JSONObject).toString()).put("hash", "0x" + "ff".repeat(32))
        }
        return result
    }
}
