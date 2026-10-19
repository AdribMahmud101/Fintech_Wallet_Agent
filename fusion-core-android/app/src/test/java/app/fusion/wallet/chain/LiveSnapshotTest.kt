package app.fusion.wallet.chain

import app.fusion.wallet.data.ChainWalletRepository
import app.fusion.wallet.data.LoadStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live check: FUSION_CAPTURE_DIR=/absolute/path ./gradlew testDebugUnitTest --tests '*LiveSnapshotTest'. */
class LiveSnapshotTest {
    @Test fun captureLiveSnapshot() = runBlocking {
        val destination = System.getenv("FUSION_CAPTURE_DIR")
        assumeTrue("Set FUSION_CAPTURE_DIR to capture real RPC evidence", !destination.isNullOrBlank())
        val calls = JSONArray()
        val live = JsonRpcClient(System.getenv("FUSION_RPC_URL") ?: "https://sepolia.base.org")
        // Capture every raw result so deterministic JVM tests can replay the actual deployed ABI.
        val recorder = RpcTransport { method, params ->
            val result = live.request(method, params)
            calls.put(JSONObject().put("method", method).put("params", params).put("result", result))
            result
        }
        val repository = ChainWalletRepository(resource("deployment.json"), resource("obligations.json"), recorder)
        repository.refresh()
        val state = repository.state.value
        assertEquals(state.error, LoadStatus.READY, state.status)
        assertTrue(state.accountingError, state.accountingMatches == true)
        val summary = JSONObject().put("block", state.blockNumber).put("blockHash", state.blockHash).put("timestamp", state.blockTime)
            .put("cashRaw", state.treasury!!.balance.rawUnits).put("reservedRaw", state.module!!.reserved.rawUnits)
            .put("bufferRaw", state.buckets!!.bufferTarget.rawUnits).put("surplusRaw", state.buckets!!.surplus.rawUnits)
            .put("paused", state.module!!.paused).put("sessionActive", state.module!!.sessionActive)
            .put("dailyLimitRaw", state.module!!.policy.dailyLimit.rawUnits).put("dailyUsedRaw", state.module!!.dailyUsed.rawUnits)
            .put("vaults", JSONArray(state.vaults.map { JSONObject().put("address", it.address).put("sharesRaw", it.sharesRaw.toString()).put("assetsRaw", it.assets.rawUnits) }))
            .put("actionCount", state.recentActions.size).put("evidenceCount", state.evidence.size)
        val capture = JSONObject().put("source", "Live Base Sepolia JSON-RPC; captured by LiveSnapshotTest").put("summary", summary).put("calls", calls)
        File(destination!!).apply { mkdirs() }.resolve("live-snapshot.json").writeText(capture.toString(2))
        println("LIVE_SNAPSHOT ${summary}")
    }
}

internal fun resource(name: String): String = requireNotNull(LiveSnapshotTest::class.java.getResourceAsStream("/chain/$name"))
    .bufferedReader().use { it.readText() }
