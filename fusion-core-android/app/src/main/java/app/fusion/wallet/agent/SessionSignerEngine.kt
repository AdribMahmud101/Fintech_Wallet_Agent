package app.fusion.wallet.agent

import app.fusion.wallet.chain.Abi
import app.fusion.wallet.chain.Views
import app.fusion.wallet.chain.ModuleEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

internal interface SessionJournal {
    fun load(): JSONObject?
    fun save(pending: JSONObject)
    fun clear()
    fun recordConfirmed(result: JSONObject) {}
}

/** No caller-supplied tx. Verify -> simulate -> bounded fees -> sign -> journal -> send -> receipt. */
internal class SessionSignerEngine(
    private val module: String,
    private val session: String,
    private val rpc: (String, JSONArray) -> Any,
    private val lease: () -> String,
    private val verify: (JSONObject) -> JSONObject,
    private val sign: (JSONObject, String) -> JSONObject,
    private val journal: SessionJournal,
    private val pause: suspend () -> Unit = { delay(2000) },
    private val readBatch: (List<Pair<String, JSONArray>>) -> List<Any> = { calls -> calls.map { (method, params) -> rpc(method, params) } },
    private val trace: (String) -> Unit = {},
    private val chainId: Long = 84532,
) {
    private val signingPolicy = SessionSigningPolicy(module, chainId)
    private val mutex = Mutex()
    suspend fun execute(report: JSONObject): JSONObject = mutex.withLock {
        trace("start")
        check(report.opt("dryRun") == false) { "DRY_RUN_CANNOT_BROADCAST" }
        check(lease() == report.getString("requestId")) { "SESSION_LOCK_REQUIRED" }
        check(q(rpc("eth_chainId", JSONArray()).toString()) == BigInteger.valueOf(chainId)) { "WRONG_CHAIN" }
        journal.load()?.let { old ->
            val confirmed = awaitConfirmation(old)
            journal.clear()
            check(old.getString("decisionHash") == report.getString("decisionHash")) { "PREVIOUS_ATTEMPT_CONFIRMED_REPLAN" }
            return@withLock confirmed
        }
        // This flag only removes authority: receipt reconciliation cannot ever
        // reach verification of a new action, key access, signing or broadcast.
        check(!report.optBoolean("reconcileOnly")) { "NO_PENDING_SESSION_ATTEMPT" }
        val checked = verify(report)
        trace("verified")
        check(checked.getString("to").equals(module, true)) { "MODULE_MISMATCH" }
        val data = checked.getString("data")
        check(data.contains(checked.getString("decisionHash").removePrefix("0x"), true)) { "PASSPORT_HASH_MISMATCH" }
        val call = JSONObject().put("from", session).put("to", module).put("value", "0x0").put("data", data)
        // These reads are independent. One RPC batch leaves more of the same
        // five-block freshness window for the final authority checks and signing.
        val feeInputs = readBatch(listOf(
            "eth_getTransactionCount" to JSONArray().put(session).put("pending"),
            "eth_getBlockByNumber" to JSONArray().put("latest").put(false),
            "eth_maxPriorityFeePerGas" to JSONArray(),
            "eth_getBalance" to JSONArray().put(session).put("pending"),
            "eth_call" to JSONArray().put(call).put("latest"),
            "eth_estimateGas" to JSONArray().put(call),
            "eth_call" to JSONArray().put(JSONObject().put("to", module).put("data", Views.STATE)).put("latest"),
        ))
        trace("simulation_and_fees_read")
        check(feeInputs[4].toString().equals(checked.getString("actionDigest"), true)) { "SIMULATION_DIGEST_MISMATCH" }
        val nonce = q(feeInputs[0].toString()).longValueExact()
        val block = feeInputs[1] as JSONObject
        val tip = q(feeInputs[2].toString()).max(BigInteger.valueOf(1_000_000))
        val fee = q(block.getString("baseFeePerGas")) * BigInteger.valueOf(2) + tip
        check(fee <= BigInteger.valueOf(10_000_000_000)) { "FEE_LIMIT" }
        val estimate = q(feeInputs[5].toString())
        val gas = (estimate * BigInteger.valueOf(120) / BigInteger.valueOf(100)).max(BigInteger.valueOf(100_000))
        check(gas <= BigInteger.valueOf(1_000_000)) { "GAS_LIMIT" }
        check(q(feeInputs[3].toString()) >= gas * fee) { "SESSION_NEEDS_GAS" }
        val state = Abi(feeInputs[6].toString()).requireSize(17)
        authority(report, checked, block, state)
        trace("authority_checked")
        check(lease() == report.getString("requestId")) { "SESSION_LOCK_REQUIRED" }
        val prepared = JSONObject().put("chainId", chainId).put("nonce", nonce).put("to", module).put("value", "0")
            .put("data", data).put("gasLimit", gas.longValueExact()).put("maxFeePerGas", fee.longValueExact()).put("maxPriorityFeePerGas", tip.longValueExact())
        signingPolicy.validate(prepared)
        val signed = sign(prepared, session)
        trace("signed")
        val raw = signed.getString("rawTx"); val hash = signed.getString("txHash")
        check(AgentCrypto.hashHex(AgentCrypto.bytes(raw)).equals(hash, true)) { "SIGNED_HASH_MISMATCH" }
        val pending = JSONObject().put("txHash", hash).put("rawTx", raw).put("to", module).put("from", session)
            .put("data", data).put("decisionHash", checked.getString("decisionHash")).put("actionDigest", checked.getString("actionDigest"))
            .put("action", checked.getJSONObject("action")).put("passportJson", report.getString("passportJson"))
            .put("requestId", report.getString("requestId"))
        // The helper will independently refuse sendRawTransaction unless its flock is still held.
        check(lease() == report.getString("requestId")) { "SESSION_LOCK_REQUIRED" }
        trace("broadcast_authority_checked")
        journal.save(pending)
        try {
            check(rpc("eth_sendRawTransaction", JSONArray().put(raw)).toString().equals(hash, true)) { "BROADCAST_HASH_MISMATCH" }
        } catch (_: Exception) { /* Lost reply or already-known: only the receipt proves success. */ }
        val confirmed = awaitConfirmation(pending)
        trace("confirmed")
        journal.clear()
        confirmed
    }
    private fun authority(report: JSONObject, checked: JSONObject, block: JSONObject? = null, stateInput: Abi? = null) {
        val b = block ?: (rpc("eth_getBlockByNumber", JSONArray().put("latest").put(false)) as JSONObject)
        val height = q(b.getString("number")).longValueExact()
        val time = q(b.getString("timestamp")).longValueExact()
        val original = report.getJSONObject("snapshot")
        trace("authority_height_gap_${height-original.getLong("blockNumber")}")
        check(height >= original.getLong("blockNumber") && height-original.getLong("blockNumber") < 10) { "STALE_STATE_OR_UNSUPPORTED_ROUTE" }
        val state = stateInput ?: Abi(rpc("eth_call", JSONArray().put(JSONObject().put("to", module).put("data", Views.STATE)).put(b.getString("number"))).toString()).requireSize(17)
        val action = checked.getJSONObject("action")
        check(state.bool(0) && !state.bool(1) && state.address(13).equals(session, true) && state.long(14) > time) { "SESSION_INACTIVE" }
        check(state.uint(8).toString() == action.getString("expectedNonce") && state.uint(2).toString() == action.getString("policyEpoch") &&
            state.bytes32(4) == original.getJSONObject("moduleState").getString("mandateHash") && action.getLong("deadline") >= time) { "STALE_STATE_OR_UNSUPPORTED_ROUTE" }
    }
    private suspend fun awaitConfirmation(pending: JSONObject): JSONObject {
        repeat(60) {
            try {
                val mined = receipt(pending.getString("txHash"))
                if (mined != null) return confirm(pending, mined)
            } catch (_: java.io.IOException) {
                // Only read failures are retried. Integrity refusals propagate;
                // the durable signed attempt is never re-signed or re-sent.
                trace("receipt_read_retry")
            }
            pause()
        }
        error("PENDING_SESSION_ATTEMPT: ${pending.getString("txHash")}")
    }
    private fun receipt(hash: String): JSONObject? = rpc("eth_getTransactionReceipt", JSONArray().put(hash)).let { if (it == JSONObject.NULL) null else it as JSONObject }
    private fun confirm(pending: JSONObject, receipt: JSONObject): JSONObject {
        val hash = pending.getString("txHash")
        check(receipt.getString("transactionHash").equals(hash, true) && receipt.getString("from").equals(session, true) && receipt.getString("to").equals(module, true)) { "RECEIPT_MISMATCH" }
        if (q(receipt.getString("status")) != BigInteger.ONE) { journal.clear(); error("SESSION_TX_REVERTED: $hash") }
        val block = rpc("eth_getBlockByNumber", JSONArray().put(receipt.getString("blockNumber")).put(false)) as JSONObject
        check(block.getString("hash").equals(receipt.getString("blockHash"), true)) { "REORG" }
        val tx = rpc("eth_getTransactionByHash", JSONArray().put(hash)) as JSONObject
        check(tx.getString("input").equals(pending.getString("data"), true) && tx.getString("from").equals(session, true) && tx.getString("to").equals(module, true)) { "RECEIPT_MISMATCH" }
        val logs = receipt.getJSONArray("logs")
        val action = pending.getJSONObject("action")
        val event = (0 until logs.length()).map { logs.getJSONObject(it) }.singleOrNull {
            val topics = it.getJSONArray("topics")
            it.getString("address").equals(module, true) && topics.length() == 4 && topics.getString(0).equals(ModuleEvents.EXECUTED, true) &&
                topics.getString(1).equals(pending.getString("actionDigest"), true) && topics.getString(3).equals(pending.getString("decisionHash"), true)
        } ?: error("EVENT_MISMATCH")
        val eventData = Abi(event.getString("data")).requireSize(8)
        check(eventData.int(0) == action.getInt("kind") && eventData.uint(1).toString() == action.getString("assets") &&
            Abi(event.getJSONArray("topics").getString(2)).uint(0).toString() == action.getString("expectedNonce")) { "EVENT_MISMATCH" }
        return JSONObject().put("txHash", hash).put("status", 1).put("receipt", receipt).put("transaction", tx)
            .put("decisionHash", pending.getString("decisionHash")).put("actionDigest", pending.getString("actionDigest"))
            .put("passportJson", pending.getString("passportJson")).put("from", session).put("to", module).also(journal::recordConfirmed)
    }
    private fun q(value: String): BigInteger {
        require(Regex("0x[0-9a-fA-F]+").matches(value)); return BigInteger(value.drop(2), 16).also { require(it.signum() >= 0 && it.bitLength() <= 256) }
    }
}
