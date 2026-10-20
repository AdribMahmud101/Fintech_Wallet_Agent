package app.fusion.wallet.scout

import java.util.Locale
import kotlin.math.abs

/** Deterministic screening. No model is involved: the rules, thresholds and ranking are fixed code. */
object ScoutRules {
    const val MIN_TVL_USD = 10_000_000.0
    const val MAX_SPIKE_FRACTION = 0.5
    const val MIN_ORGANIC_SHARE = 0.5
    const val MAX_BREAK_EVEN_DAYS = 14.0
    const val MIN_PASSES = 5
    const val TESTNET_PROJECT = "aave-v3"

    /** Lending protocols with public audits; the detail sheet links the DeFiLlama page that lists them. */
    val AUDITED_LENDING = setOf("aave-v3", "compound-v3", "morpho-blue", "morpho-v1", "fluid-lending", "moonwell-lending", "moonwell", "seamless-protocol", "spark")

    fun conservativeApy(p: ScoutPool): Double? {
        val spot = p.apy ?: return null
        val mean = p.apyMean30d ?: return spot
        return minOf(spot, mean)
    }

    fun score(pool: ScoutPool, ctx: ScoutContext): PoolScore {
        val cons = conservativeApy(pool)
        val breakEven = breakEvenDays(ctx, cons)
        val rules = listOf(
            tvl(pool), spike(pool), organic(pool), audited(pool),
            RuleResult("Single-asset stablecoin", RuleStatus.PASS, "USDC · no impermanent loss", "stablecoin, single exposure", "DeFiLlama flags"),
            RuleResult("Liquidity (utilization)", RuleStatus.NOT_MEASURED, "not published by the feed", "below 85%", "measured on testnet only"),
            gas(breakEven, ctx),
        )
        return PoolScore(pool, cons, rules, breakEven, executableOnTestnet = pool.project == TESTNET_PROJECT)
    }

    /** Eligible first, then fewer failures, then higher conservative APY, then bigger pool. */
    fun rank(scores: List<PoolScore>): List<PoolScore> = scores.sortedWith(
        compareByDescending<PoolScore> { it.eligible }
            .thenBy { it.fails }
            .thenByDescending { it.conservativeApy ?: -1.0 }
            .thenByDescending { it.pool.tvlUsd },
    )

    fun breakEvenDays(ctx: ScoutContext, conservativeApyPct: Double?): Double? {
        val spare = ctx.spareCashUsd ?: return null
        val gas = ctx.agentGasUsd ?: return null
        val apy = conservativeApyPct ?: return null
        if (spare <= 0.0 || apy <= 0.0) return null
        return gas / (spare * apy / 100.0 / 365.0)
    }

    private fun tvl(p: ScoutPool) = RuleResult(
        "Pool size (TVL)", if (p.tvlUsd >= MIN_TVL_USD) RuleStatus.PASS else RuleStatus.FAIL,
        usdShort(p.tvlUsd), "at least ${usdShort(MIN_TVL_USD)}", "DeFiLlama",
    )

    private fun spike(p: ScoutPool): RuleResult {
        val apy = p.apy; val mean = p.apyMean30d
        if (apy == null || mean == null || mean <= 0.0) return RuleResult("Not a rate spike", RuleStatus.NOT_MEASURED, "no 30-day history", "within 50% of the 30-day mean", "DeFiLlama")
        val dev = abs(apy - mean) / mean
        return RuleResult(
            "Not a rate spike", if (dev <= MAX_SPIKE_FRACTION) RuleStatus.PASS else RuleStatus.FAIL,
            "${pct(apy)} now vs ${pct(mean)} 30-day mean", "within 50% of the mean", "DeFiLlama",
        )
    }

    private fun organic(p: ScoutPool): RuleResult {
        val apy = p.apy; val base = p.apyBase
        if (apy == null || base == null || apy <= 0.0) return RuleResult("Yield is mostly organic", RuleStatus.NOT_MEASURED, "split not published", "base yield at least 50%", "DeFiLlama")
        val share = base / apy
        return RuleResult(
            "Yield is mostly organic", if (share >= MIN_ORGANIC_SHARE) RuleStatus.PASS else RuleStatus.FAIL,
            "${(share * 100).toInt()}% from lending, rest incentives", "base yield at least 50%", "DeFiLlama",
        )
    }

    private fun audited(p: ScoutPool) = RuleResult(
        "Audited lending protocol", if (p.project in AUDITED_LENDING) RuleStatus.PASS else RuleStatus.FAIL,
        if (p.project in AUDITED_LENDING) "on the allowlist" else "not a vetted lending protocol", "on the audited allowlist", "app allowlist",
    )

    private fun gas(days: Double?, ctx: ScoutContext) = if (days == null) {
        RuleResult("Pays for its gas", RuleStatus.NOT_MEASURED, "needs spare cash, gas and a positive rate", "within 14 days", "measured agent gas")
    } else RuleResult(
        "Pays for its gas", if (days <= MAX_BREAK_EVEN_DAYS) RuleStatus.PASS else RuleStatus.FAIL,
        "${daysText(days)} on ${usd(ctx.spareCashUsd ?: 0.0)} spare cash", "within 14 days", "measured agent gas cost",
    )

    fun daysText(v: Double) = if (v < 0.1) "under 0.1 days" else String.format(Locale.US, "%.1f days", v)

    fun pct(v: Double) = String.format(Locale.US, "%.2f%%", v)
    fun usd(v: Double) = String.format(Locale.US, "%,.0f USDC", v)
    fun usdShort(v: Double) = when {
        v >= 1e9 -> String.format(Locale.US, "$%.1fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "$%.1fM", v / 1e6)
        else -> String.format(Locale.US, "$%.0fK", v / 1e3)
    }
}
