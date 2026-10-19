package app.fusion.wallet.signer

import java.math.BigInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GuardianPauseEngineTest {
    private class Journal : PauseJournal {
        var pending: PendingPause? = null
        override fun load() = pending
        override fun save(pending: PendingPause) { this.pending = pending }
        override fun clear() { pending = null }
    }
    private class FakeRpc(val journal: Journal) : SignerRpc {
        var chain = "0x14a34"
        var balance = "0xffffffffffffffff"
        var status = "0x1"
        var paused = true
        var baseFee = "0x64"
        var confirmed = true
        var loseSendResponse = false
        var sends = 0
        var readReceipt = 0
        var receiptFrom = GuardianTransaction.GUARDIAN
        var stateBlock: String? = null
        override fun request(method: String, params: JSONArray): Any = when (method) {
            "eth_chainId" -> chain
            "eth_getTransactionCount" -> { assertEquals("pending", params.getString(1)); "0x7" }
            "eth_getBlockByNumber" -> JSONObject().put("baseFeePerGas", baseFee)
            "eth_maxPriorityFeePerGas" -> "0xf4240"
            "eth_estimateGas" -> {
                val tx = params.getJSONObject(0)
                assertEquals(GuardianTransaction.MODULE, tx.getString("to"))
                assertEquals(GuardianTransaction.callData, tx.getString("data"))
                assertEquals("0x0", tx.getString("value"))
                "0x186a0"
            }
            "eth_getBalance" -> balance
            "eth_sendRawTransaction" -> {
                sends++
                assertEquals(journal.pending!!.raw, params.getString(0))
                if (loseSendResponse) error("Untrusted RPC content")
                journal.pending!!.hash
            }
            "eth_getTransactionReceipt" -> {
                readReceipt++
                if (sends == 0 || !confirmed) JSONObject.NULL else JSONObject()
                    .put("transactionHash", journal.pending!!.hash).put("status", status)
                    .put("to", GuardianTransaction.MODULE).put("from", receiptFrom).put("blockNumber", "0x123")
            }
            "eth_call" -> {
                stateBlock = params.getString(1)
                "0x" + "1".padStart(64, '0') + (if (paused) "1" else "0").padStart(64, '0') + "0".repeat(64 * 15)
            }
            else -> error("Unexpected RPC method")
        }
    }
    private class Fixture {
        val journal = Journal()
        val rpc = FakeRpc(journal)
        var signed = 0
        var waited = 0
        val engine = GuardianPauseEngine(rpc, journal, { nonce, tip, fee, gas ->
            signed++
            assertEquals(BigInteger.valueOf(7), nonce)
            assertEquals(BigInteger.valueOf(1_000_000), tip)
            assertEquals(BigInteger.valueOf(1_000_200), fee)
            assertEquals(BigInteger.valueOf(120_000), gas)
            byteArrayOf(2, 3, 4)
        }, { waited++ })
    }

    @Test fun `success requires receipt and pinned block paused state`() = runTest {
        val f = Fixture()
        val hash = f.engine.pause()
        assertTrue(Regex("0x[0-9a-f]{64}").matches(hash))
        assertEquals(1, f.signed)
        assertEquals(1, f.rpc.sends)
        assertEquals("0x123", f.rpc.stateBlock)
        assertNull(f.journal.pending)
    }

    @Test fun `wrong chain and insufficient funds never sign or send`() = runTest {
        val f = Fixture()
        f.rpc.chain = "0x1"
        assertTrue(runCatching { f.engine.pause() }.isFailure)
        f.rpc.chain = "0x14a34"
        f.rpc.balance = "0x0"
        val error = runCatching { f.engine.pause() }.exceptionOrNull()!!
        assertEquals("Guardian needs Base Sepolia ETH for gas", safeSignerError(error as Exception))
        assertEquals(0, f.signed)
        assertEquals(0, f.rpc.sends)
    }

    @Test fun `lost send reply still succeeds only through receipt`() = runTest {
        val f = Fixture()
        f.rpc.loseSendResponse = true
        assertTrue(f.engine.pause().startsWith("0x"))
        assertEquals(1, f.signed)
    }

    @Test fun `excessive network fees cannot reach the signer`() = runTest {
        val f = Fixture()
        f.rpc.baseFee = "0xffffffffffff"
        assertTrue(runCatching { f.engine.pause() }.isFailure)
        assertEquals(0, f.signed)
        assertEquals(0, f.rpc.sends)
        assertNull(f.journal.pending)
    }

    @Test fun `successful receipt with unpaused module does not report success`() = runTest {
        val f = Fixture()
        f.rpc.paused = false
        val error = runCatching { f.engine.pause() }.exceptionOrNull()!!
        assertTrue(error.message!!.startsWith("Receipt succeeded but module pause was not verified:"))
        assertNotNull(f.journal.pending)
    }

    @Test fun `timeout preserves journal and retry does not sign a new nonce`() = runTest {
        val f = Fixture()
        f.rpc.confirmed = false
        val failure = runCatching { f.engine.pause() }.exceptionOrNull()!!
        val pending = f.journal.pending!!
        assertTrue(failure.message!!.contains(pending.hash))
        assertEquals(60, f.waited)
        f.rpc.confirmed = true
        assertEquals(pending.hash, f.engine.pause())
        assertEquals(1, f.signed)
        assertEquals(1, f.rpc.sends)
    }

    @Test fun `reverted receipt is failure and clears completed journal`() = runTest {
        val f = Fixture()
        f.rpc.status = "0x0"
        assertTrue(runCatching { f.engine.pause() }.exceptionOrNull()!!.message!!.startsWith("Guardian pause reverted:"))
        assertNull(f.journal.pending)
        assertNull(f.rpc.stateBlock)
    }

    @Test fun `receipt for a different sender cannot report success`() = runTest {
        val f = Fixture()
        f.rpc.receiptFrom = "0x" + "11".repeat(20)
        assertTrue(runCatching { f.engine.pause() }.isFailure)
        assertNotNull(f.journal.pending)
    }

    @Test fun `concurrent callers serialize and share unresolved transaction`() = runTest {
        val f = Fixture()
        f.rpc.confirmed = false
        val first = async { runCatching { f.engine.pause() } }
        val second = async { runCatching { f.engine.pause() } }
        assertTrue(first.await().isFailure)
        assertTrue(second.await().isFailure)
        assertEquals(1, f.signed)
        assertEquals(2, f.rpc.sends) // same bytes both times
    }
}
