package app.fusion.wallet.agent

import android.content.Context
import app.fusion.wallet.chain.Abi
import app.fusion.wallet.data.Deployment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger

/** An expiring, nonce-bound strategy exception signed by BOTH Safe owners on-chain.
 * This authorizes a gas-paying allocation rehearsal, never a claim of profitable yield.
 * The inference process still chooses A/H. The signer independently repeats these reads.
 */
internal object OwnerAllocationMandate {
    const val REASON = "OWNER_ALLOCATION_REHEARSAL:"
    private const val SELECTOR = "0xb0c49d37"
    private const val SUCCESS = "0x442e715f626346e8c54381002da614f62bee8d27386535b2521ec8540898556e"

    fun read(context: Context, manifest: JSONObject, snapshot: JSONObject): JSONObject? {
        val file = File(context.filesDir, "agent/owner_allocation.json")
        if (!file.isFile) return null
        require(file.length() <= 1024) { "INVALID_OWNER_ALLOCATION" }
        val hash = JSONObject(file.readText()).getString("txHash")
        return verify(context, manifest, snapshot, hash)
    }

    fun verify(context: Context, manifest: JSONObject, snapshot: JSONObject, hash: String): JSONObject? {
        check(Regex("0x[0-9a-f]{64}").matches(hash)) { "INVALID_OWNER_ALLOCATION" }
        return verify(manifest, snapshot, hash,
            { method, params -> AgentNetwork.relay(context, method, params) },
            { calls -> AgentNetwork.batch(context, calls) })
    }

    internal fun verify(manifest: JSONObject, snapshot: JSONObject, hash: String, transport: (String, JSONArray) -> Any,
        readBatch: ((List<Pair<String, JSONArray>>) -> List<Any>)? = null): JSONObject? {
        check(Regex("0x[0-9a-f]{64}").matches(hash)) { "INVALID_OWNER_ALLOCATION" }
        fun params(vararg values: Any) = JSONArray().apply { values.forEach { put(it) } }
        fun batch(calls: List<Pair<String, JSONArray>>): List<Any> = readBatch?.invoke(calls)
            ?: calls.map { (method, args) -> transport(method, args) }
        val d = Deployment.parse(manifest.toString())
        val first = batch(listOf("eth_chainId" to params(), "eth_getTransactionReceipt" to params(hash),
            "eth_getTransactionByHash" to params(hash)))
        check(first[0] == "0x14a34") { "WRONG_CHAIN" }
        val receipt = first[1] as JSONObject
        val tx = first[2] as JSONObject
        check(receipt.getString("status") == "0x1" && tx.getString("chainId").removePrefix("0x").toLong(16) == 84532L) { "INVALID_OWNER_ALLOCATION" }
        check(receipt.getString("to").equals(d.safe, true) && tx.getString("to").equals(d.safe, true)) { "INVALID_OWNER_ALLOCATION" }
        val owner = manifest.getJSONObject("roles").getString("owner")
        check(receipt.getString("from").equals(owner, true) && tx.getString("from").equals(owner, true)) { "INVALID_OWNER_ALLOCATION" }
        check(receipt.getString("transactionHash") == hash && tx.getString("hash") == hash) { "INVALID_OWNER_ALLOCATION" }
        val block = receipt.getString("blockNumber")
        check(block.removePrefix("0x").toLong(16) <= snapshot.getLong("blockNumber")) { "INVALID_OWNER_ALLOCATION" }
        val second = batch(listOf("eth_getBlockByNumber" to params(block, false), "eth_getCode" to params(owner, block),
            "eth_call" to params(JSONObject().put("to", d.safe).put("data", "0xe75235b8"), block),
            "eth_call" to params(JSONObject().put("to", d.safe).put("data", "0xa0e67e2b"), block)))
        check(second[1] == "0x") { "INVALID_OWNER_ALLOCATION" }
        val header = second[0] as JSONObject
        check(header.getString("hash") == receipt.getString("blockHash") && tx.getString("blockHash") == receipt.getString("blockHash")) { "REORG" }
        val logs = receipt.getJSONArray("logs")
        check((0 until logs.length()).count {
            val log = logs.getJSONObject(it)
            log.getString("address").equals(d.safe, true) && log.getJSONArray("topics").optString(0) == SUCCESS
        } == 1) { "INVALID_OWNER_ALLOCATION" }
        check(Abi(second[2].toString()).int(0) == 2) { "INVALID_OWNER_ALLOCATION" } // getThreshold()
        val owners = Abi(second[3].toString()) // getOwners()
        check(owners.int(1) == 2 && setOf(owners.address(2), owners.address(3)) ==
            manifest.getJSONArray("owners").let { a -> (0 until a.length()).map { a.getString(it).lowercase() }.toSet() }) { "INVALID_OWNER_ALLOCATION" }
        // Decode the successful Safe call. No delegatecall, ETH, refund or token
        // movement is permitted. Only an inert authorization payload to an EOA.
        val input = tx.getString("input").lowercase()
        check(input.startsWith("0x6a761202")) { "INVALID_OWNER_ALLOCATION" }
        val arguments = input.drop(10)
        val outer = Abi("0x$arguments")
        check(outer.address(0).equals(owner, true) && outer.uint(1) == BigInteger.ZERO && outer.int(3) == 0 &&
            (4..8).all { outer.uint(it) == BigInteger.ZERO }) { "INVALID_OWNER_ALLOCATION" }
        val offset = outer.offset(2)
        val length = outer.int(offset)
        check(length == 4 + 9 * 32) { "INVALID_OWNER_ALLOCATION" }
        val payload = "0x" + arguments.substring((offset + 1) * 64, (offset + 1) * 64 + length * 2)
        check(payload.startsWith(SELECTOR)) { "INVALID_OWNER_ALLOCATION" }
        val mandate = Abi("0x" + payload.drop(10)).requireSize(9)
        val module = snapshot.getJSONObject("moduleState")
        check(mandate.long(0) == 84532L && mandate.address(1).equals(d.safe, true) &&
            mandate.address(2).equals(d.module, true) && mandate.address(3).equals(d.vaultA, true)) { "INVALID_OWNER_ALLOCATION" }
        check(mandate.bytes32(4) == module.getString("sessionId") && mandate.bytes32(5) == module.getString("policyHash")) { "INVALID_OWNER_ALLOCATION" }
        val amount = mandate.uint(6)
        check(amount.signum() > 0 && amount <= BigInteger.valueOf(10_000_000) &&
            amount <= BigInteger(snapshot.getJSONObject("policy").getString("microMaxRaw"))) { "INVALID_OWNER_ALLOCATION" }
        val expiry = mandate.long(8)
        check(expiry <= module.getLong("sessionExpiresAt")) { "INVALID_OWNER_ALLOCATION" }
        val nonce = BigInteger(module.getString("actionNonce"))
        val firstNonce = mandate.uint(7)
        if (snapshot.getLong("blockTimestamp") >= expiry || nonce < firstNonce || nonce > firstNonce + BigInteger.ONE) return null
        check(module.getString("reservedRaw") == "0") { "INVALID_OWNER_ALLOCATION" }
        return JSONObject().put("ownerTxHash", hash).put("assetsRaw", amount.toString())
            .put("depositNonce", firstNonce.toString()).put("expiresAt", expiry)
            .put("sessionId", mandate.bytes32(4)).put("policyHash", mandate.bytes32(5))
            .put("vault", d.vaultA).put("phase", if (nonce == firstNonce) "DEPOSIT" else "WITHDRAW")
            .put("purpose", "Owner-authorized allocation rehearsal; profitability exception limited to one round trip")
    }

    fun verifyForSigning(context: Context, manifest: JSONObject, fresh: JSONObject, report: JSONObject) {
        val candidate = report.getJSONObject("chosenCandidate")
        val reason = candidate.getString("reason")
        if (!reason.startsWith(REASON)) {
            check(!report.has("allocationMandate")) { "INVALID_OWNER_ALLOCATION" }
            return
        }
        val hash = reason.removePrefix(REASON)
        val mandate = checkNotNull(verify(context, manifest, fresh, hash)) { "INVALID_OWNER_ALLOCATION" }
        check(report.getJSONObject("allocationMandate").toString() == mandate.toString()) { "INVALID_OWNER_ALLOCATION" }
        val action = candidate.getJSONObject("action")
        val deposit = mandate.getString("phase") == "DEPOSIT"
        check(action.getInt("kind") == if (deposit) 0 else 1) { "INVALID_OWNER_ALLOCATION" }
        check(action.getString(if (deposit) "destinationVault" else "sourceVault").equals(mandate.getString("vault"), true)) { "INVALID_OWNER_ALLOCATION" }
        val amount = BigInteger(action.getString("assets"))
        check(amount.signum() > 0 && amount <= BigInteger(mandate.getString("assetsRaw")) &&
            (!deposit || amount.toString() == mandate.getString("assetsRaw"))) { "INVALID_OWNER_ALLOCATION" }
    }
}
