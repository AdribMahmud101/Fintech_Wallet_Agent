package app.fusion.wallet.accountant.agent

import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.data.Severity
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.accountant.domain.Raw
import java.math.BigInteger

data class LocalFinding(val topic: InsightTopic, val severity: Severity, val text: String)

/**
 * Deterministic accountant that runs entirely on-device with exact figures. It is the baseline the
 * user always gets, works with the cloud off, and is what the cloud text is read alongside.
 */
object LocalAccountant {
    fun analyze(s: PortfolioSnapshot): List<LocalFinding> {
        val dec = s.unit.decimals
        val u = s.unit.symbol
        val b = s.buckets
        fun f(x: BigInteger) = "${Raw.format(x, dec)} $u"
        val out = mutableListOf<LocalFinding>()

        if (b.shortfall.signum() > 0) {
            out += LocalFinding(
                InsightTopic.RESERVES, Severity.CRITICAL,
                "Obligations (${f(b.obligationTarget)}) exceed wallet cash (${f(b.cash)}) by ${f(b.shortfall)}. Nothing is swept; withdraw from lending to cover the gap.",
            )
        } else if (b.cash < b.obligationTarget + b.buffer) {
            out += LocalFinding(
                InsightTopic.RESERVES, Severity.WARNING,
                "Cash (${f(b.cash)}) is below reserves plus operating buffer (${f(b.obligationTarget + b.buffer)}); no surplus is available to deploy.",
            )
        } else {
            out += LocalFinding(InsightTopic.LIQUIDITY, Severity.INFO, "Sweepable surplus is ${f(b.sweepable)} after reserving ${f(b.obligationTarget)} and keeping a ${f(b.buffer)} buffer.")
        }

        val overdue = s.obligations.count { it.dueAt < s.asOf }
        if (overdue > 0) out += LocalFinding(InsightTopic.OBLIGATIONS, Severity.WARNING, "$overdue active obligation(s) are past due.")
        val soon = s.obligations.filter { it.dueAt >= s.asOf && BriefBuilder.dueWindow(it.dueAt - s.asOf) == "0-7d" }
        if (soon.isNotEmpty()) {
            val sum = soon.fold(BigInteger.ZERO) { a, o -> a + Raw.rescale(o.amount, o.decimals, dec) }
            out += LocalFinding(InsightTopic.OBLIGATIONS, Severity.NOTICE, "${soon.size} obligation(s) totalling ${f(sum)} fall due within 7 days.")
        }

        val stableLending = s.lending.filter { it.isStable && it.supplied.signum() > 0 }
        val deployed = b.deployed
        if (deployed.signum() > 0) {
            val top = stableLending.maxByOrNull { it.supplied }
            if (top != null) {
                val share = Raw.pct(Raw.rescale(top.supplied, top.decimals, dec), deployed)
                if (share > CONCENTRATION_PCT && stableLending.size > 1) {
                    out += LocalFinding(InsightTopic.CONCENTRATION, Severity.NOTICE, "$share% of deployed funds sit in one position (${top.positionSymbol}).")
                }
            }
            val weighted = stableLending.fold(BigInteger.ZERO) { a, l -> a + Raw.rescale(l.supplied, l.decimals, dec) * BigInteger.valueOf(l.supplyApyBps.toLong()) }
            val avgBps = weighted.divide(deployed).toInt()
            out += LocalFinding(InsightTopic.YIELD, Severity.INFO, "Deployed ${f(deployed)} earns a weighted average of ${avgBps / 100}.${(avgBps % 100).toString().padStart(2, '0')}% APY across ${stableLending.size} position(s).")
        }

        BriefBuilder.runwayDays(s)?.let { days ->
            val sev = if (days < RUNWAY_WARN_DAYS) Severity.WARNING else Severity.INFO
            out += LocalFinding(InsightTopic.CASHFLOW, sev, "Wallet cash covers about $days day(s) at the 30-day average outflow.")
        }
        return out
    }

    private const val CONCENTRATION_PCT = 60
    private const val RUNWAY_WARN_DAYS = 30
}
