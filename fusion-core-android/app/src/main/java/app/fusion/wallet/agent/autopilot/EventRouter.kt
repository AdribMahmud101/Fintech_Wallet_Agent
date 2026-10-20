package app.fusion.wallet.agent.autopilot

import app.fusion.wallet.data.*
import java.math.BigInteger

/** Only validated machine signals cross this boundary; off-chain text is never a signal. */
enum class ReflexSignal { DEPEG, RESERVE_PAUSED, VAULT_CODE_CHANGED }

data class CognitiveMetrics(val actionableBreakEven: Boolean? = null)

/** Deterministic wake policy. No economic claim is inferred when K44 metrics are absent. */
class EventRouter {
    private val seenDue = mutableSetOf<String>()
    private var lastCondition: String? = null
    fun evaluate(state: WalletState, nowMillis: Long, metrics: CognitiveMetrics = CognitiveMetrics()): String? {
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
            b.surplus.raw > IDLE_CASH_THRESHOLD && metrics.actionableBreakEven == true -> "Idle spare cash with actionable break-even"
            else -> null
        }
        val previous = lastCondition
        lastCondition = reason
        return reason?.takeIf { newDue != null || it != previous }
    }
    companion object { val IDLE_CASH_THRESHOLD: BigInteger = Money.SCALE * BigInteger.valueOf(100) }
}

object GasSpikePolicy {
    // 1 gwei base fee. Guardian pause never uses this policy.
    val THRESHOLD_WEI: BigInteger = BigInteger.valueOf(1_000_000_000)
    fun holdReason(baseFee: BigInteger?, kind: Int, dueAt: Long?, now: Long): String? = when {
        baseFee == null || baseFee.signum() < 0 -> "Gas input unreadable; refresh required"
        baseFee > THRESHOLD_WEI && !(kind == 3 && dueAt != null && dueAt > 0 && dueAt <= now) -> "Gas spike, deferred"
        else -> null
    }
}
