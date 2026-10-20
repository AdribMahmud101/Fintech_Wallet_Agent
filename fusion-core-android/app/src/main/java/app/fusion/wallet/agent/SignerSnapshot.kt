package app.fusion.wallet.agent

import android.content.Context
import app.fusion.wallet.chain.*
import app.fusion.wallet.data.Deployment
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** All reads are pinned to one fresh block. Original block hash and code pins are independent. */
internal object SignerSnapshot {
    fun read(context: Context, manifest: JSONObject, original: JSONObject): JSONObject {
        val d = Deployment.parse(manifest.toString())
        val head = AgentNetwork.relay(context, "eth_getBlockByNumber", JSONArray().put("latest").put(false)) as JSONObject
        val tag = head.getString("number")
        val calls = linkedMapOf<String, Pair<String, JSONArray>>()
        fun view(name: String, to: String, data: String) { calls[name] = "eth_call" to JSONArray().put(JSONObject().put("to", to).put("data", data)).put(tag) }
        calls["chain"] = "eth_chainId" to JSONArray()
        calls["originalBlock"] = "eth_getBlockByNumber" to JSONArray().put(ChainReader.tag(original.getLong("blockNumber"))).put(false)
        calls["freshBlock"] = "eth_getBlockByNumber" to JSONArray().put(tag).put(false)
        view("state", d.module, Views.STATE); view("policy", d.module, Views.POLICY); view("queue", d.module, Views.QUEUE)
        view("cash", d.asset, Views.BALANCE + Abi.addressWord(d.safe))
        view("sharesA", d.vaultA, Views.BALANCE + Abi.addressWord(d.safe)); view("sharesB", d.vaultB, Views.BALANCE + Abi.addressWord(d.safe))
        view("assetsA", d.vaultA, Views.CONVERT_TO_ASSETS + Abi.uintWord(BigInteger(original.getString("vaultAShares"))))
        view("assetsB", d.vaultB, Views.CONVERT_TO_ASSETS + Abi.uintWord(BigInteger(original.getString("vaultBShares"))))
        val contracts = manifest.getJSONObject("contracts")
        val pins = listOf("safe", "safeSingleton", "module", "asset", "policyLibrary", "payeeRegistry", "sanctionsList", "vaultA", "vaultB")
        pins.forEach { name -> calls["code:$name"] = "eth_getCode" to JSONArray().put(contracts.getJSONObject(name).getString("address")).put(tag) }
        val vaultA = contracts.getJSONObject("vaultA")
        if (vaultA.has("implementation")) {
            calls["implSlot"] = "eth_getStorageAt" to JSONArray().put(d.vaultA).put("0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc").put(tag)
            calls["implCode"] = "eth_getCode" to JSONArray().put(vaultA.getString("implementation")).put(tag)
        }
        val values = AgentNetwork.batch(context, calls.values.toList())
        val result = calls.keys.zip(values).toMap()
        check(result.getValue("chain") == "0x14a34") { "WRONG_CHAIN" }
        check((result.getValue("originalBlock") as JSONObject).getString("hash").equals(original.getString("blockHash"), true)) { "REORG" }
        check((result.getValue("freshBlock") as JSONObject).getString("hash").equals(head.getString("hash"), true)) { "REORG" }
        pins.forEach { name -> check(AgentCrypto.hashHex(AgentCrypto.bytes(result.getValue("code:$name").toString())).equals(contracts.getJSONObject(name).getString("runtimeCodeHash"), true)) { "CODE_PIN_MISMATCH" } }
        if (vaultA.has("implementation")) {
            check(Abi(result.getValue("implSlot").toString()).address(0).equals(vaultA.getString("implementation"), true)) { "IMPLEMENTATION_PIN_MISMATCH" }
            check(AgentCrypto.hashHex(AgentCrypto.bytes(result.getValue("implCode").toString())).equals(vaultA.getString("implementationCodeHash"), true)) { "IMPLEMENTATION_PIN_MISMATCH" }
        }
        fun abi(name: String) = Abi(result.getValue(name).toString())
        val policy = ContractDecoders.policy(abi("policy"))
        val a = abi("sharesA").uint(0); val b = abi("sharesB").uint(0)
        // A change of holdings requires a new decision, rather than editing its action.
        check(a.toString() == original.getString("vaultAShares") && b.toString() == original.getString("vaultBShares")) { "HOLDINGS_CHANGED" }
        return JSONObject(original.toString()).apply { remove("quant") }.put("blockNumber", ChainReader.quantity(tag)).put("blockHash", head.getString("hash"))
            .put("blockTimestamp", ChainReader.quantity(head.getString("timestamp")))
            .put("safeCash", abi("cash").uint(0).toString()).put("vaultAAssets", abi("assetsA").uint(0).toString()).put("vaultBAssets", abi("assetsB").uint(0).toString())
            .put("moduleState", SnapshotAdapter.moduleJson(abi("state").requireSize(17)))
            .put("queue", SnapshotAdapter.queueJson(abi("queue").requireSize(19)))
            .put("policy", SnapshotAdapter.policyJson(policy.policy, linkedMapOf(
                d.vaultA to requireNotNull(policy.exposureLimits[d.vaultA]).raw,
                d.vaultB to requireNotNull(policy.exposureLimits[d.vaultB]).raw)))
    }
}
