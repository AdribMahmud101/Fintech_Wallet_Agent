package app.fusion.wallet.accountant.agent

import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.data.Severity
import app.fusion.wallet.accountant.domain.Aave
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.accountant.domain.Raw
import java.math.BigInteger

enum class FindingCode { SHORTFALL, BELOW_BUFFER, SURPLUS, OVERDUE, DUE_SOON, CONCENTRATION, YIELD, GAS_LOW, RUNWAY }

/**
 * A finding is a [code] plus its [args] so the UI can render it in the user's language; [text] is the
 * English rendering used by logs, tests and any non-UI consumer.
 */
data class LocalFinding(
    val topic: InsightTopic,
    val severity: Severity,
    val code: FindingCode,
    val args: List<String>,
    val text: String,
)

/**
 * Deterministic accountant that runs entirely on-device with exact figures. It is the baseline the
 * user always gets, works with the cloud off, and is what the cloud text is read alongside.
 */
object LocalAccountant {
    fun analyze(s: PortfolioSnapshot): List<LocalFinding> {
        val dec = s.unit.decimals
        val u = s.unit.symbol
        val b = s.buckets
        fun f(x: BigInteger) = "${Raw.pretty(x, dec)} $u"
        val out = mutableListOf<LocalFinding>()
        fun add(topic: InsightTopic, sev: Severity, code: FindingCode, args: List<String>, text: String) {
            out += LocalFinding(topic, sev, code, args, text)
        }

        if (b.shortfall.signum() > 0) {
            val a = listOf(f(b.obligationTarget), f(b.cash), f(b.shortfall))
            add(InsightTopic.RESERVES, Severity.CRITICAL, FindingCode.SHORTFALL, a,
                "Bills (${a[0]}) exceed cash (${a[1]}) by ${a[2]}. Nothing is deployed; withdraw from lending to cover the gap.")
        } else if (b.cash < b.obligationTarget + b.buffer) {
            val a = listOf(f(b.cash), f(b.obligationTarget + b.buffer))
            add(InsightTopic.RESERVES, Severity.WARNING, FindingCode.BELOW_BUFFER, a,
                "Cash (${a[0]}) is below bills plus safety buffer (${a[1]}), so there is no surplus to deploy.")
        } else {
            val a = listOf(f(b.sweepable), f(b.obligationTarget), f(b.buffer))
            add(InsightTopic.LIQUIDITY, Severity.INFO, FindingCode.SURPLUS, a,
                "${a[0]} is safe to deploy after reserving ${a[1]} for bills and keeping a ${a[2]} buffer.")
        }

        val overdue = s.obligations.count { it.dueAt < s.asOf }
        if (overdue > 0) add(InsightTopic.OBLIGATIONS, Severity.WARNING, FindingCode.OVERDUE, listOf("$overdue"), "$overdue bill(s) are past due.")
        val soon = s.obligations.filter { it.dueAt >= s.asOf && BriefBuilder.dueWindow(it.dueAt - s.asOf) == "0-7d" }
        if (soon.isNotEmpty()) {
            val sum = soon.fold(BigInteger.ZERO) { a, o -> a + Raw.rescale(o.amount, o.decimals, dec) }
            add(InsightTopic.OBLIGATIONS, Severity.NOTICE, FindingCode.DUE_SOON, listOf("${soon.size}", f(sum)),
                "${soon.size} bill(s) totalling ${f(sum)} fall due within 7 days.")
        }

        val stableLending = s.lending.filter { it.isStable && it.supplied.signum() > 0 }
        val deployed = b.deployed
        if (deployed.signum() > 0) {
            val top = stableLending.maxByOrNull { it.supplied }
            if (top != null) {
                val share = Raw.pct(Raw.rescale(top.supplied, top.decimals, dec), deployed)
                if (share > CONCENTRATION_PCT && stableLending.size > 1) {
                    add(InsightTopic.CONCENTRATION, Severity.NOTICE, FindingCode.CONCENTRATION, listOf("$share", top.positionSymbol),
                        "$share% of earning funds sit in one position (${top.positionSymbol}).")
                }
            }
            val weighted = stableLending.fold(BigInteger.ZERO) { a, l -> a + Raw.rescale(l.supplied, l.decimals, dec) * BigInteger.valueOf(l.supplyAprBps.toLong()) }
            val avgBps = weighted.divide(deployed).toInt()
            add(InsightTopic.YIELD, Severity.INFO, FindingCode.YIELD, listOf(f(deployed), Aave.pct(avgBps), "${stableLending.size}"),
                "Earning ${f(deployed)} at a weighted supply rate of ${Aave.pct(avgBps)} APR across ${stableLending.size} position(s).")
        }

        s.gas?.let { g ->
            if (g.runwayActions < GAS_WARN_ACTIONS) {
                add(InsightTopic.LIQUIDITY, Severity.WARNING, FindingCode.GAS_LOW, listOf("${g.runwayActions}"),
                    "Gas fuel covers only about ${g.runwayActions} agent action(s). Top up the agent's ETH or it cannot act.")
            }
        }

        BriefBuilder.runwayDays(s)?.let { days ->
            val sev = if (days < RUNWAY_WARN_DAYS) Severity.WARNING else Severity.INFO
            add(InsightTopic.CASHFLOW, sev, FindingCode.RUNWAY, listOf("$days"), "Cash covers about $days day(s) at the 30-day average spending.")
        }
        return out
    }

    private const val CONCENTRATION_PCT = 60
    private const val RUNWAY_WARN_DAYS = 30
    private const val GAS_WARN_ACTIONS = 20
}
