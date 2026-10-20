package app.fusion.wallet.agent

import app.fusion.wallet.chain.Abi
import app.fusion.wallet.chain.Views
import app.fusion.wallet.chain.ModuleEvents
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class SessionSignerEngineTest {
    private class Journal : SessionJournal {
        var pending: JSONObject? = null; var saves = 0; var confirmed = 0
        override fun load() = pending
        override fun save(pending: JSONObject) { this.pending = pending; saves++ }
        override fun clear() { pending = null }
        override fun recordConfirmed(result: JSONObject) { confirmed++ }
    }
    private class Fixture {
        val module = "0x" + "11".repeat(20); val session = "0x" + "22".repeat(20)
        val decision = AgentCrypto.hashHex("test decision".toByteArray()); val digest = AgentCrypto.hashHex("test digest".toByteArray())
        val mandate = AgentCrypto.hashHex("test mandate".toByteArray()); val blockHash = AgentCrypto.hashHex("test block".toByteArray())
        val raw = "0x02c0"; val hash = AgentCrypto.hashHex(AgentCrypto.bytes(raw))
        private val golden = JSONObject(SessionSignerEngineTest::class.java.getResource("/golden_app_transaction.json")!!.readText())
        var data = golden.getString("input").replace(golden.getString("decisionHash").drop(2), decision.drop(2))
        val journal = Journal(); var signs = 0; var sends = 0; var leaseCalls = 0
        var loseReply = false; var availableReceipt = true; var wrongReceipt = false; var chain = "0x14a34"; var fee = "0x1"; var loseLeaseOn = 0
        var failFeeBatch = false
        val action = JSONObject().put("kind", 3).put("assets", "2000000").put("expectedNonce", "9").put("policyEpoch", "12").put("deadline", 200)
        val report = JSONObject().put("dryRun", false).put("requestId", "fixture").put("decisionHash", decision).put("passportJson", "public test passport")
            .put("snapshot", JSONObject().put("blockNumber", 10).put("moduleState", JSONObject().put("mandateHash", mandate)))
        fun word(v: Long) = Abi.uintWord(BigInteger.valueOf(v))
        fun rpc(method: String, params: JSONArray): Any = when(method) {
            "eth_chainId" -> chain
            "eth_getTransactionCount" -> "0x4"
            "eth_getBlockByNumber" -> JSONObject().put("number", "0xa").put("timestamp", "0x64").put("baseFeePerGas", fee).put("hash", blockHash)
            "eth_maxPriorityFeePerGas" -> "0xf4240"
            "eth_getBalance" -> "0xde0b6b3a7640000"
            "eth_estimateGas" -> "0x30d40"
            "eth_call" -> if (params.getJSONObject(0).getString("data") == Views.STATE) {
                val words = MutableList(17) { word(0) }; words[0]=word(1); words[2]=word(12); words[4]=mandate.drop(2); words[8]=word(9); words[13]=Abi.addressWord(session); words[14]=word(1000)
                "0x" + words.joinToString("")
            } else digest
            "eth_sendRawTransaction" -> {
                check(journal.pending != null); check(params.getString(0) == raw); sends++
                if (loseReply) error("RPC reply lost")
                hash
            }
            "eth_getTransactionReceipt" -> if (!availableReceipt) JSONObject.NULL else receipt()
            "eth_getTransactionByHash" -> JSONObject().put("from", session).put("to", module).put("input", data)
            else -> error("Unexpected RPC $method")
        }
        fun receipt() = JSONObject().put("transactionHash", hash).put("from", if(wrongReceipt) module else session).put("to", module)
            .put("status", "0x1").put("blockNumber", "0xa").put("blockHash", blockHash)
            .put("logs", JSONArray().put(JSONObject().put("address", module)
                .put("topics", JSONArray().put(ModuleEvents.EXECUTED).put(digest).put("0x"+word(9)).put(decision))
                .put("data", "0x" + listOf(3L, 2000000, 0, 0, 1, 0, 12, 0).joinToString("") { word(it) })))
        fun engine() = SessionSignerEngine(module, session, ::rpc,
            lease = { leaseCalls++; if (loseLeaseOn == leaseCalls) "wrong lease" else "fixture" },
            verify = { JSONObject().put("to", module).put("data", data).put("decisionHash", decision).put("actionDigest", digest).put("action", action) },
            sign = { _, _ -> signs++; JSONObject().put("rawTx", raw).put("txHash", hash) }, journal = journal, pause = {},
            readBatch = { calls -> check(!failFeeBatch) { "Read batch unavailable" }; calls.map { (method, params) -> rpc(method, params) } })
    }
    @Test fun arbitrarySelectorCannotReachKeyOrBroadcast() = runTest {
        val f = Fixture(); f.data = "0xa9059cbb" + f.decision.drop(2)
        val error = runCatching { f.engine().execute(f.report) }.exceptionOrNull()
        assertTrue(error is SignerRefused)
        assertEquals(SignerRefusal.UnsupportedSelector, (error as SignerRefused).reason)
        assertEquals(0, f.signs); assertEquals(0, f.sends); assertNull(f.journal.pending)
    }
    @Test fun dryRunCannotReachSigningEvenWithAValidLease() = runTest {
        val f=Fixture(); f.report.put("dryRun",true)
        assertTrue(runCatching { f.engine().execute(f.report) }.isFailure)
        assertEquals(0,f.signs); assertEquals(0,f.sends); assertNull(f.journal.pending)
    }
    @Test fun journalsBeforeSendAndConfirmsBoundEvent() = runTest {
        val f=Fixture(); val result=f.engine().execute(f.report)
        assertEquals(1,result.getInt("status")); assertEquals(1,f.sends); assertEquals(1,f.signs)
        assertEquals(1,f.journal.saves); assertEquals(1,f.journal.confirmed); assertNull(f.journal.pending)
    }
    @Test fun lostBroadcastReplyStillUsesOneSignatureAndReceipt() = runTest {
        val f=Fixture(); f.loseReply=true
        assertEquals(f.hash,f.engine().execute(f.report).getString("txHash")); assertEquals(1,f.signs); assertEquals(1,f.sends)
    }
    @Test fun leaseLossBeforeBroadcastSendsNothingAndDoesNotReserveNonce() = runTest {
        val f=Fixture(); f.loseLeaseOn=3
        val result=runCatching { f.engine().execute(f.report) }
        assertTrue(result.isFailure); assertEquals(0,f.sends); assertNull(f.journal.pending)
    }
    @Test fun unresolvedAttemptSurvivesRestartAndPreventsNewNonce() = runTest {
        val f=Fixture(); f.availableReceipt=false
        assertTrue(runCatching { f.engine().execute(f.report) }.isFailure)
        assertNotNull(f.journal.pending); assertEquals(1,f.signs)
        assertTrue(runCatching { f.engine().execute(f.report) }.isFailure)
        assertEquals(1,f.signs); assertEquals(1,f.sends)
        f.availableReceipt=true
        assertEquals(f.hash,f.engine().execute(f.report).getString("txHash")); assertNull(f.journal.pending)
        assertEquals(1,f.signs); assertEquals(1,f.sends)
    }
    @Test fun wrongChainAndExcessiveFeesNeverLoadKey() = runTest {
        val wrong=Fixture(); wrong.chain="0x1"
        assertTrue(runCatching { wrong.engine().execute(wrong.report) }.isFailure); assertEquals(0,wrong.signs); assertEquals(0,wrong.sends)
        val costly=Fixture(); costly.fee="0x2540be400"
        assertTrue(runCatching { costly.engine().execute(costly.report) }.isFailure); assertEquals(0,costly.signs); assertEquals(0,costly.sends)
    }
    @Test fun failedFeeBatchCannotLoadKeyOrReserveNonce() = runTest {
        val f=Fixture(); f.failFeeBatch=true
        assertTrue(runCatching { f.engine().execute(f.report) }.isFailure)
        assertEquals(0,f.signs); assertEquals(0,f.sends); assertNull(f.journal.pending)
    }
    @Test fun mismatchedReceiptCannotClearJournalOrClaimSuccess() = runTest {
        val f=Fixture(); f.wrongReceipt=true
        assertTrue(runCatching { f.engine().execute(f.report) }.isFailure)
        assertNotNull(f.journal.pending); assertEquals(0,f.journal.confirmed)
    }
}
