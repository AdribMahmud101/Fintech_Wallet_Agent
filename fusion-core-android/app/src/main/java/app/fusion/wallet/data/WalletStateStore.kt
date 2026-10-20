package app.fusion.wallet.data

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises a successful [WalletState] snapshot so the next cold start can paint the last good
 * numbers instantly, before any network read. Only READY snapshots are persisted; all money stays
 * exact [BigInteger] units (never Float/Double). The stored [WalletState.capturedAtEpochMillis]
 * drives the "Updated N ago · refreshing" chip while the live snapshot loads.
 *
 * This is pure JSON <-> model mapping with no Android dependency, so it is covered by a JVM test.
 */
object WalletStateStore {
    const val VERSION = 1

    fun encode(state: WalletState): String? {
        if (state.status != LoadStatus.READY) return null
        val root = JSONObject()
        root.put("v", VERSION)
        root.put("chainId", state.chainId)
        state.blockNumber?.let { root.put("blockNumber", it) }
        state.blockHash?.let { root.put("blockHash", it) }
        state.blockTime?.let { root.put("blockTime", it) }
        state.capturedAtEpochMillis?.let { root.put("capturedAt", it) }
        state.observedHeadBlock?.let { root.put("observedHeadBlock", it) }
        root.put("balanceHistory", JSONArray().apply { state.balanceHistory.forEach {
            put(JSONObject().put("time", it.timestampSeconds).put("raw", it.balanceRaw.toString()))
        } })
        root.put("agentSource", state.agentSource)
        root.put("planner", state.planner)
        state.accountingMatches?.let { root.put("accountingMatches", it) }
        state.accountingError?.let { root.put("accountingError", it) }
        state.treasury?.let { t ->
            root.put("treasury", JSONObject()
                .put("safe", t.safeAddress).put("asset", t.assetAddress)
                .put("balance", t.balance.rawUnits).put("symbol", t.symbol))
        }
        state.buckets?.let { b -> root.put("buckets", bucketsJson(b)) }
        root.put("vaults", JSONArray().apply { state.vaults.forEach { put(vaultJson(it)) } })
        state.module?.let { root.put("module", moduleJson(it)) }
        root.put("queue", JSONArray().apply { state.queue.forEach { put(queueJson(it)) } })
        root.put("payees", JSONArray().apply { state.payees.forEach { put(payeeJson(it)) } })
        root.put("recentActions", JSONArray().apply { state.recentActions.forEach { put(actionJson(it)) } })
        root.put("evidence", JSONArray().apply { state.evidence.forEach { put(evidenceJson(it)) } })
        root.put("obligations", JSONArray().apply { state.obligations.forEach { put(obligationJson(it)) } })
        return root.toString()
    }

    /** Restores a cached snapshot, or null if absent/unparseable/wrong version. */
    fun decode(json: String?): WalletState? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            if (root.optInt("v") != VERSION) return null
            WalletState(
                source = DataSource.LIVE,
                status = LoadStatus.READY,
                chainId = root.optLong("chainId", 84532),
                blockNumber = root.optLongOrNull("blockNumber"),
                blockHash = root.optStringOrNull("blockHash"),
                blockTime = root.optLongOrNull("blockTime"),
                capturedAtEpochMillis = root.optLongOrNull("capturedAt"),
                observedHeadBlock = root.optLongOrNull("observedHeadBlock"),
                balanceHistory = root.optJSONArray("balanceHistory").mapObjects {
                    BalanceSample(it.getLong("time"), BigInteger(it.getString("raw")))
                },
                agentSource = root.optString("agentSource", "SCRIPT"),
                planner = root.optString("planner", "deterministic"),
                accountingMatches = if (root.has("accountingMatches")) root.getBoolean("accountingMatches") else null,
                accountingError = root.optStringOrNull("accountingError"),
                treasury = root.optJSONObject("treasury")?.let { t ->
                    Treasury(t.getString("safe"), t.getString("asset"), Money(BigInteger(t.getString("balance"))), t.optString("symbol", "USDC"))
                },
                buckets = root.optJSONObject("buckets")?.let(::bucketsFrom),
                vaults = root.optJSONArray("vaults").mapObjects(::vaultFrom),
                module = root.optJSONObject("module")?.let(::moduleFrom),
                queue = root.optJSONArray("queue").mapObjects(::queueFrom),
                payees = root.optJSONArray("payees").mapObjects(::payeeFrom),
                recentActions = root.optJSONArray("recentActions").mapObjects(::actionFrom),
                evidence = root.optJSONArray("evidence").mapObjects(::evidenceFrom),
                obligations = root.optJSONArray("obligations").mapObjects(::obligationFrom),
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- field mappers ------------------------------------------------------------------------

    private fun money(v: String) = Money(BigInteger(v))

    private fun bucketsJson(b: Buckets) = JSONObject()
        .put("reserve", b.reserve.rawUnits).put("owed", b.owed.rawUnits).put("buffer", b.buffer.rawUnits)
        .put("surplus", b.surplus.rawUnits).put("free", b.free.rawUnits).put("shortfall", b.shortfall.rawUnits)
        .put("deployed", b.deployed.rawUnits).put("total", b.total.rawUnits).put("bufferTarget", b.bufferTarget.rawUnits)

    private fun bucketsFrom(o: JSONObject) = Buckets(
        money(o.getString("reserve")), money(o.getString("owed")), money(o.getString("buffer")),
        money(o.getString("surplus")), money(o.getString("free")), money(o.getString("shortfall")),
        money(o.getString("deployed")), money(o.getString("total")), money(o.getString("bufferTarget")))

    private fun vaultJson(v: VaultPosition) = JSONObject()
        .put("id", v.id).put("name", v.name).put("address", v.address).put("shares", v.sharesRaw.toString())
        .put("assets", v.assets.rawUnits).put("maxWithdraw", v.maxWithdraw.rawUnits)
        .put("exposure", v.exposureLimit.rawUnits).put("simulated", v.simulatedYield)
        .apply {
            v.previewDepositSharesRaw?.let { put("previewDeposit", it.toString()) }
            v.previewWithdrawSharesRaw?.let { put("previewWithdraw", it.toString()) }
        }

    private fun vaultFrom(o: JSONObject) = VaultPosition(
        o.getString("id"), o.getString("name"), o.getString("address"), BigInteger(o.getString("shares")),
        money(o.getString("assets")), money(o.getString("maxWithdraw")), money(o.getString("exposure")),
        o.optBoolean("simulated", false),
        o.optStringOrNull("previewDeposit")?.let(::BigInteger), o.optStringOrNull("previewWithdraw")?.let(::BigInteger))

    private fun policyJson(p: Policy) = JSONObject()
        .put("tier", p.permissionTier.name).put("operatingBuffer", p.operatingBuffer.rawUnits)
        .put("microMax", p.microMax.rawUnits).put("delayedMax", p.delayedMax.rawUnits).put("hardMax", p.hardMax.rawUnits)
        .put("dailyLimit", p.dailyLimit.rawUnits).put("delaySeconds", p.delaySeconds)
        .put("maxShareSlippageBps", p.maxShareSlippageBps).put("guardian", p.guardian)
        .put("payeeDelaySeconds", p.payeeDelaySeconds).put("payEarlyWindowSeconds", p.payEarlyWindowSeconds)

    private fun policyFrom(o: JSONObject) = Policy(
        PermissionTier.valueOf(o.getString("tier")), money(o.getString("operatingBuffer")), money(o.getString("microMax")),
        money(o.getString("delayedMax")), money(o.getString("hardMax")), money(o.getString("dailyLimit")),
        o.getLong("delaySeconds"), o.getInt("maxShareSlippageBps"), o.getString("guardian"),
        o.getLong("payeeDelaySeconds"), o.getLong("payEarlyWindowSeconds"))

    private fun moduleJson(m: ModuleState) = JSONObject()
        .put("address", m.address).put("configured", m.configured).put("paused", m.paused)
        .put("sessionActive", m.sessionActive).put("policy", policyJson(m.policy)).put("policyEpoch", m.policyEpoch.toString())
        .put("policyHash", m.policyHash).put("mandateHash", m.mandateHash).put("obligationsRoot", m.obligationsRoot)
        .put("reserved", m.reserved.rawUnits).put("obligationCount", m.obligationCount.toString())
        .put("actionNonce", m.actionNonce.toString()).put("dailyUsed", m.dailyUsed.rawUnits)
        .put("dailyRemaining", m.dailyRemaining.rawUnits).put("sessionId", m.sessionId).put("sessionKey", m.sessionKey)
        .put("sessionExpiresAt", m.sessionExpiresAt).put("sessionLimit", m.sessionLimit.rawUnits).put("sessionUsed", m.sessionUsed.rawUnits)

    private fun moduleFrom(o: JSONObject) = ModuleState(
        o.getString("address"), o.getBoolean("configured"), o.getBoolean("paused"), o.getBoolean("sessionActive"),
        policyFrom(o.getJSONObject("policy")), BigInteger(o.getString("policyEpoch")), o.getString("policyHash"),
        o.getString("mandateHash"), o.getString("obligationsRoot"), money(o.getString("reserved")),
        BigInteger(o.getString("obligationCount")), BigInteger(o.getString("actionNonce")), money(o.getString("dailyUsed")),
        money(o.getString("dailyRemaining")), o.getString("sessionId"), o.getString("sessionKey"),
        o.getLong("sessionExpiresAt"), money(o.getString("sessionLimit")), money(o.getString("sessionUsed")))

    private fun queueJson(q: QueuedAction) = JSONObject()
        .put("id", q.id).put("kind", q.kind.name).put("amount", q.amount.rawUnits).put("readyAt", q.readyAt)
        .put("expiresAt", q.expiresAt).put("status", q.status.name).put("payee", q.payee)
        .put("decisionHash", q.decisionHash).put("expectedNonce", q.expectedNonce.toString())

    private fun queueFrom(o: JSONObject) = QueuedAction(
        o.getString("id"), ActionKind.valueOf(o.getString("kind")), money(o.getString("amount")), o.getLong("readyAt"),
        o.getLong("expiresAt"), QueueStatus.valueOf(o.getString("status")), o.getString("payee"),
        o.getString("decisionHash"), BigInteger(o.getString("expectedNonce")))

    private fun payeeJson(p: Payee) = JSONObject()
        .put("id", p.id).put("address", p.address).put("label", p.label).put("labelHash", p.labelHash)
        .put("status", p.status.name).put("activeAt", p.activeAt).put("sanctioned", p.sanctioned)

    private fun payeeFrom(o: JSONObject) = Payee(
        o.getString("id"), o.getString("address"), o.getString("label"), o.getString("labelHash"),
        PayeeStatus.valueOf(o.getString("status")), o.getLong("activeAt"), o.getBoolean("sanctioned"))

    private fun actionJson(a: AgentAction) = JSONObject()
        .put("id", a.id).put("kind", a.kind.name).put("amount", a.amount.rawUnits).put("payee", a.payee ?: JSONObject.NULL)
        .put("txHash", a.txHash).put("decisionHash", a.decisionHash).put("blockNumber", a.blockNumber)
        .put("blockTime", a.blockTime).put("source", a.source)

    private fun actionFrom(o: JSONObject) = AgentAction(
        o.getString("id"), ActionKind.valueOf(o.getString("kind")), money(o.getString("amount")), o.optStringOrNull("payee"),
        o.getString("txHash"), o.getString("decisionHash"), o.getLong("blockNumber"), o.getLong("blockTime"),
        o.optString("source", "SCRIPT"))

    private fun evidenceJson(e: EvidenceRow) = JSONObject()
        .put("id", e.id).put("kind", e.kind).put("txHash", e.txHash).put("blockNumber", e.blockNumber)
        .put("blockTime", e.blockTime).put("decisionHash", e.decisionHash ?: JSONObject.NULL)
        .put("source", e.source).put("explorerUrl", e.explorerUrl)

    private fun evidenceFrom(o: JSONObject) = EvidenceRow(
        o.getString("id"), o.getString("kind"), o.getString("txHash"), o.getLong("blockNumber"), o.getLong("blockTime"),
        o.optStringOrNull("decisionHash"), o.optString("source", "SCRIPT"), o.getString("explorerUrl"))

    private fun obligationJson(o: Obligation) = JSONObject()
        .put("id", o.id).put("payeeId", o.payeeId).put("amount", o.amount.rawUnits).put("dueAt", o.dueAt).put("paid", o.paid)

    private fun obligationFrom(o: JSONObject) = Obligation(
        o.getString("id"), o.getString("payeeId"), money(o.getString("amount")), o.getLong("dueAt"), o.getBoolean("paid"))
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
private fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }
