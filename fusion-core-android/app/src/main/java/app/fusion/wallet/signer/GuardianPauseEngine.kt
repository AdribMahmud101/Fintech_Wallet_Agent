package app.fusion.wallet.signer

import app.fusion.wallet.chain.Abi
import app.fusion.wallet.chain.Views
import java.math.BigInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal fun interface SignerRpc { fun request(method: String, params: JSONArray): Any }
internal data class PendingPause(val hash: String, val raw: String)
internal interface PauseJournal {
    fun load(): PendingPause?
    fun save(pending: PendingPause)
    fun clear()
}

/** Serialized nonce use and a durable pre-broadcast journal make retries reuse the same tx. */
internal class GuardianPauseEngine(
    private val rpc: SignerRpc,
    private val journal: PauseJournal,
    private val sign: (BigInteger, BigInteger, BigInteger, BigInteger) -> ByteArray,
    private val waitForReceipt: suspend () -> Unit = { delay(2_000) },
) {
    private val mutex = Mutex()

    suspend fun pause(): String = mutex.withLock {
        check(quantity(rpc.request("eth_chainId", JSONArray()).toString()) == BigInteger.valueOf(GuardianTransaction.CHAIN_ID)) {
            "Signer RPC is not Base Sepolia"
        }
        val pending = journal.load() ?: prepare().also(journal::save)
        // A previous process may have sent this exact tx before dying or losing its reply.
        var receipt = receipt(pending.hash)
        if (receipt == null) {
            try {
                val sent = rpc.request("eth_sendRawTransaction", JSONArray().put(pending.raw)).toString()
                check(sent.equals(pending.hash, true)) { "RPC returned a different transaction hash" }
            } catch (_: Exception) {
                // 'Already known', a lost response, or rejection: only a successful receipt is success.
                // Keep the signed journal; the next request retries identical bytes, never a new nonce.
            }
            repeat(60) {
                if (receipt == null) { waitForReceipt(); receipt = receipt(pending.hash) }
            }
        }
        val mined = receipt ?: error("Pause not confirmed. Check BaseScan before retrying: ${pending.hash}")
        check(mined.getString("transactionHash").equals(pending.hash, true) &&
            mined.getString("to").equals(GuardianTransaction.MODULE, true) &&
            mined.getString("from").equals(GuardianTransaction.GUARDIAN, true)) { "Invalid pause receipt" }
        if (quantity(mined.getString("status")) != BigInteger.ONE) {
            journal.clear()
            error("Guardian pause reverted: ${pending.hash}")
        }
        val block = mined.getString("blockNumber")
        quantity(block)
        val state = rpc.request("eth_call", JSONArray().put(JSONObject()
            .put("to", GuardianTransaction.MODULE).put("data", Views.STATE)).put(block)).toString()
        check(Abi(state).requireSize(17).bool(1)) { "Receipt succeeded but module pause was not verified: ${pending.hash}" }
        journal.clear()
        pending.hash
    }

    private fun prepare(): PendingPause {
        val nonce = quantity(rpc.request("eth_getTransactionCount", JSONArray().put(GuardianTransaction.GUARDIAN).put("pending")).toString())
        val block = rpc.request("eth_getBlockByNumber", JSONArray().put("latest").put(false)) as JSONObject
        val baseFee = quantity(block.getString("baseFeePerGas"))
        val tip = quantity(rpc.request("eth_maxPriorityFeePerGas", JSONArray()).toString()).max(BigInteger.valueOf(1_000_000))
        val fee = baseFee * BigInteger.valueOf(2) + tip
        check(fee <= BigInteger.valueOf(10_000_000_000)) { "Guardian gas fee exceeds the 10 gwei testnet limit" }
        val call = JSONObject().put("from", GuardianTransaction.GUARDIAN).put("to", GuardianTransaction.MODULE)
            .put("value", "0x0").put("data", GuardianTransaction.callData)
            .put("maxFeePerGas", hexQuantity(fee)).put("maxPriorityFeePerGas", hexQuantity(tip))
        val estimate = quantity(rpc.request("eth_estimateGas", JSONArray().put(call)).toString())
        val gas = (estimate * BigInteger.valueOf(120) / BigInteger.valueOf(100)).max(BigInteger.valueOf(100_000))
        check(gas <= BigInteger.valueOf(500_000)) { "Guardian gas estimate exceeds the testnet limit" }
        val balance = quantity(rpc.request("eth_getBalance", JSONArray().put(GuardianTransaction.GUARDIAN).put("pending")).toString())
        check(balance >= gas * fee) { "Guardian needs Base Sepolia ETH for gas" }
        val raw = sign(nonce, tip, fee, gas)
        return PendingPause(GuardianTransaction.hex(GuardianTransaction.keccak(raw)), GuardianTransaction.hex(raw))
    }

    private fun receipt(hash: String): JSONObject? =
        rpc.request("eth_getTransactionReceipt", JSONArray().put(hash)).let { if (it == JSONObject.NULL) null else it as JSONObject }

    private fun quantity(value: String): BigInteger {
        require(Regex("0x[0-9a-fA-F]+").matches(value)) { "Malformed signer RPC quantity" }
        return BigInteger(value.drop(2), 16).also { require(it.bitLength() <= 256) { "Oversized signer RPC quantity" } }
    }
    private fun hexQuantity(value: BigInteger) = "0x" + value.toString(16)
}
