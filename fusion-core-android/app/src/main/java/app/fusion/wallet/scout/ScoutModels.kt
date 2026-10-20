package app.fusion.wallet.scout

enum class RuleStatus { PASS, FAIL, NOT_MEASURED }

/** One USDC lending pool on Base, as published by the DeFiLlama feed. Every field is untrusted until parsed. */
data class ScoutPool(
    val id: String,
    val project: String,
    val tvlUsd: Double,
    val apy: Double?,
    val apyBase: Double?,
    val apyReward: Double?,
    val apyMean30d: Double?,
    val spark: List<Double>,
)

data class ScoutFeed(val fetchedAtSec: Long, val pools: List<ScoutPool>, val fromCache: Boolean = false)

/** What the app measured on-chain for this wallet; null means "not measured", never a guess. */
data class ScoutContext(val spareCashUsd: Double?, val agentGasUsd: Double?)

data class RuleResult(
    val title: String,
    val status: RuleStatus,
    val value: String,
    val threshold: String,
    val source: String,
)

data class PoolScore(
    val pool: ScoutPool,
    val conservativeApy: Double?,
    val rules: List<RuleResult>,
    val breakEvenDays: Double?,
    val executableOnTestnet: Boolean,
) {
    val passes get() = rules.count { it.status == RuleStatus.PASS }
    val fails get() = rules.count { it.status == RuleStatus.FAIL }
    val measured get() = rules.count { it.status != RuleStatus.NOT_MEASURED }
    /** Eligible = nothing failed and enough rules could actually be measured. */
    val eligible get() = fails == 0 && passes >= ScoutRules.MIN_PASSES
}
