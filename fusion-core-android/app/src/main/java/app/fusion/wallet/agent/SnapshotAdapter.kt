package app.fusion.wallet.agent

import app.fusion.wallet.chain.*
import app.fusion.wallet.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Translates K4 integer DTOs; missing module/queue words are read at the SAME block. */
internal object SnapshotAdapter {
    fun build(state: WalletState, deployment: Deployment, reader: ChainReader): JSONObject {
        check(state.source == DataSource.LIVE && state.status == LoadStatus.READY && !state.isStale()) { "Live snapshot is stale or unavailable" }
        val block = requireNotNull(state.blockNumber)
        check(reader.chainId() == 84532L)
        val header = reader.block(ChainReader.tag(block))
        check(header.hash.equals(state.blockHash, true)) { "Snapshot reorganized" }
        val mod = reader.call(deployment.module, Views.STATE, block).requireSize(17)
        val q = reader.call(deployment.module, Views.QUEUE, block).requireSize(19)
        val m = requireNotNull(state.module)
        check(mod.uint(8) == m.actionNonce && mod.bytes32(4) == m.mandateHash) { "Snapshot state differs" }
        val policy = m.policy
        val policyJson = JSONObject().put("permissionTier", policy.permissionTier.ordinal)
            .put("operatingBufferRaw", policy.operatingBuffer.raw.toString())
            .put("microMaxRaw", policy.microMax.raw.toString()).put("delayedMaxRaw", policy.delayedMax.raw.toString())
            .put("hardMaxRaw", policy.hardMax.raw.toString()).put("dailyLimitRaw", policy.dailyLimit.raw.toString())
            .put("delaySeconds", policy.delaySeconds).put("maxShareSlippageBps", policy.maxShareSlippageBps)
            .put("guardian", policy.guardian).put("payeeDelaySeconds", policy.payeeDelaySeconds)
            .put("payEarlyWindowSeconds", policy.payEarlyWindowSeconds)
            .put("vaults", JSONArray().apply { state.vaults.forEach {
                put(JSONObject().put("vault", it.address).put("maxExposureRaw", it.exposureLimit.raw.toString()))
            } })
        val module = JSONObject().put("configured", mod.bool(0)).put("paused", mod.bool(1))
            .put("policyEpoch", mod.uint(2).toString()).put("policyHash", mod.bytes32(3)).put("mandateHash", mod.bytes32(4))
            .put("obligationsRoot", mod.bytes32(5)).put("reservedRaw", mod.uint(6).toString())
            .put("obligationCount", mod.uint(7).toString()).put("actionNonce", mod.uint(8).toString())
            .put("dailyDayIndex", mod.uint(9).toString()).put("dailyUsedRaw", mod.uint(10).toString())
            .put("sessionId", mod.bytes32(11)).put("sessionStartEpoch", mod.uint(12).toString())
            .put("sessionKey", mod.address(13)).put("sessionExpiresAt", mod.long(14))
            .put("sessionLimitRaw", mod.uint(15).toString()).put("sessionUsedRaw", mod.uint(16).toString())
        val queue = JSONObject().put("exists", q.bool(0)).put("actionDigest", q.bytes32(1))
            .put("sessionId", q.bytes32(2)).put("readyAt", q.long(3)).put("expiresAt", q.long(4)).put("action", action(q, 5))
        val a = state.vaults.single { it.address.equals(deployment.vaultA, true) }
        val b = state.vaults.single { it.address.equals(deployment.vaultB, true) }
        return JSONObject().put("blockNumber", block).put("blockHash", header.hash).put("blockTimestamp", header.timestamp)
            .put("safeCash", requireNotNull(state.treasury).balance.raw.toString())
            .put("vaultAShares", a.sharesRaw.toString()).put("vaultAAssets", a.assets.raw.toString())
            .put("vaultBShares", b.sharesRaw.toString()).put("vaultBAssets", b.assets.raw.toString())
            .put("moduleState", module).put("policy", policyJson).put("queue", queue)
    }

    private fun action(a: Abi, i: Int) = JSONObject().put("kind", a.int(i))
        .put("sourceVault", a.address(i+1)).put("destinationVault", a.address(i+2))
        .put("assets", a.uint(i+3).toString()).put("minSharesOut", a.uint(i+4).toString()).put("maxSharesBurn", a.uint(i+5).toString())
        .put("obligationIdHash", a.bytes32(i+6)).put("payeeIdHash", a.bytes32(i+7)).put("recipient", a.address(i+8))
        .put("dueAt", a.long(i+9)).put("expectedNonce", a.uint(i+10).toString()).put("policyEpoch", a.uint(i+11).toString())
        .put("deadline", a.long(i+12)).put("decisionHash", a.bytes32(i+13))

    fun obligations(state: WalletState, unpaidOnly: Boolean = false) = JSONArray().apply {
        state.obligations.filter { !unpaidOnly || !it.paid }.forEach {
            put(JSONObject().put("obligationId", it.id).put("obligationIdHash", it.id).put("payeeIdHash", it.payeeId)
                .put("amountRaw", it.amount.raw.toString()).put("dueAt", it.dueAt))
        }
    }
    fun payees(state: WalletState) = JSONArray().apply {
        state.payees.filter { !it.sanctioned }.forEach {
            // Core's Payee.labelHash is its lookup identifier (same as agent-runner).
            put(JSONObject().put("account", it.address).put("labelHash", it.id)
                .put("activeAt", it.activeAt).put("cancelled", it.status == PayeeStatus.CANCELLED))
        }
    }
}
