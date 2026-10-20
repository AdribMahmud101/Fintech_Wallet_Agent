package app.fusion.wallet.agent.autopilot

import app.fusion.wallet.data.*
import java.math.BigInteger

/** Only validated machine signals cross this boundary; off-chain text is never a signal. */
enum class ReflexSignal { DEPEG, RESERVE_PAUSED, VAULT_CODE_CHANGED }

/** Deterministic wake policy, using the same block-pinned economics as Home. */
class EventRouter {
    private val seenDue = mutableSetOf<String>()
    private var lastCondition: String? = null
    fun evaluate(state: WalletState, nowMillis: Long): String? {
        if (state.source != DataSource.LIVE || state.isStale(nowMillis) || state.refreshing ||
            state.accountingMatches != true || state.module == null || state.buckets == null) return "Input unreadable or incomplete; refresh required"
        val now = maxOf(nowMillis / 1000, state.blockTime ?: 0)
        val due = state.obligations.filter { !it.paid && it.dueAt <= now + state.module.policy.payEarlyWindowSeconds }
        val newDue = due.firstOrNull { it.id !in seenDue }
        seenDue.addAll(due.map { it.id })
        val b = state.buckets
        val targetCash = b.owed.raw + b.bufferTarget.raw
        val fundedCash = b.reserve.raw + b.buffer.raw
        // Percentage points of total assets, against the on-chain bills + operating-buffer target.
        val drift = b.total.raw.signum() > 0 && (targetCash - fundedCash).abs() * BigInteger.valueOf(100) > b.total.raw * BigInteger.valueOf(5)
        val reason = when {
            newDue != null -> "New obligation due soon"
            drift -> "Bucket drift > 5% from bills and buffer target"
            b.surplus.raw > IDLE_CASH_THRESHOLD && hasActionableBreakEven(state) -> "Idle spare cash with actionable break-even"
            else -> null
        }
        val previous = lastCondition
        lastCondition = reason
        return reason?.takeIf { newDue != null || it != previous }
    }
    companion object { val IDLE_CASH_THRESHOLD: BigInteger = Money.SCALE * BigInteger.valueOf(100) }
}

/** Missing, malformed or mismatched reports never authorize a profitable-cash wake. */
private fun hasActionableBreakEven(state: WalletState): Boolean = runCatching {
    val report = org.json.JSONObject(state.quantReport ?: return false)
    if (report.getLong("blockNumber") != state.blockNumber || !report.getBoolean("depositAllowed")) return false
    val candidates = report.getJSONArray("candidates")
    (0 until candidates.length()).any {
        val candidate = candidates.getJSONObject(it)
        candidate.getString("status") == "ACTIONABLE" && candidate.getString("sizeRaw").toBigInteger().signum() > 0
    }
}.getOrDefault(false)

object GasSpikePolicy {
    // 1 gwei base fee. Guardian pause never uses this policy.
    val THRESHOLD_WEI: BigInteger = BigInteger.valueOf(1_000_000_000)
    fun holdReason(baseFee: BigInteger?, kind: Int, dueAt: Long?, now: Long): String? = when {
        baseFee == null || baseFee.signum() < 0 -> "Gas input unreadable; refresh required"
        baseFee > THRESHOLD_WEI && !(kind == 3 && dueAt != null && dueAt > 0 && dueAt <= now) -> "Gas spike, deferred"
        else -> null
    }
}
