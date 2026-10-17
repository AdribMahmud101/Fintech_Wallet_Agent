package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.accountant.agent.FindingCode
import app.fusion.wallet.accountant.agent.LocalFinding
import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.data.BucketSnapshotEntity
import app.fusion.wallet.accountant.data.LendingProtocol
import app.fusion.wallet.accountant.data.Severity
import app.fusion.wallet.accountant.domain.Aave
import app.fusion.wallet.accountant.domain.Analytics
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.accountant.domain.Raw
import app.fusion.wallet.ui.Fmt
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.charts.DonutChart
import app.fusion.wallet.ui.charts.LabeledBar
import app.fusion.wallet.ui.charts.RingGauge
import app.fusion.wallet.ui.charts.SegmentBar
import app.fusion.wallet.ui.charts.Sparkline
import app.fusion.wallet.ui.charts.StackedBars
import app.fusion.wallet.ui.common.BentoGap
import app.fusion.wallet.ui.common.BentoRow
import app.fusion.wallet.ui.common.BentoTile
import app.fusion.wallet.ui.common.Expander
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.common.TileLabel
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.common.severityColor
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.MonoStyle
import java.math.BigInteger

private const val LOW_FUEL_ACTIONS = 20
private const val DAY_SEC = 86_400L

// Categories for the spending chart. Distinct from the bucket colours so the two are never confused.
private val Payroll = Color(0xFF2DD4BF)
private val Suppliers = Color(0xFFFB7185)
private val OtherSpend = Color(0xFF94A3B8)

/**
 * Home as a bento grid. The hero answers "how much can the agent use?"; the tiles beneath each answer one
 * follow-up with a picture: where the money sits, how long cash lasts, how the total moved, what it was spent on.
 */
@Composable
fun TreasuryScreen(modifier: Modifier = Modifier, vm: AccountantViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var askOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BentoGap),
    ) {
        item { ScreenHeader(stringResource(R.string.tr_title), stringResource(R.string.tr_subtitle)) }
        s.message?.let { m -> item { Notice(m, onDismiss = vm::dismissMessage) } }

        val snap = s.snapshot
        if (snap == null) {
            item {
                BentoTile(Modifier.fillMaxWidth(), padding = 18.dp) {
                    Text(stringResource(if (s.loading) R.string.tr_loading else R.string.tr_empty), style = MaterialTheme.typography.bodyLarge, color = Muted)
                }
            }
        } else {
            item { HeroTile(snap) }
            item {
                BentoRow {
                    AllocTile(Modifier.weight(1f).fillMaxHeight(), snap)
                    RunwayTile(Modifier.weight(1f).fillMaxHeight(), snap)
                }
            }
            item { TrendTile(snap, s.history) }
            item {
                BentoRow {
                    SpendTile(Modifier.weight(1.2f).fillMaxHeight(), snap)
                    RatesTile(Modifier.weight(1f).fillMaxHeight(), snap)
                }
            }
            item { AskTile(onClick = { askOpen = true }) }
            if (s.local.isNotEmpty()) item { AttentionTile(s.local) }
            item { HoldingsTile(snap) }
            item { BillsTile(snap) }
        }
    }

    if (askOpen) AskAccountantSheet(vm = vm, onDismiss = { askOpen = false })
}

// ---- hero -------------------------------------------------------------------------------------

@Composable
private fun HeroTile(snap: PortfolioSnapshot) {
    val b = snap.buckets
    val dec = snap.unit.decimals
    val u = snap.unit.symbol
    val short = b.shortfall.signum() > 0
    BentoTile(Modifier.fillMaxWidth(), tint = if (short) Tone.Bad else Tone.Good, padding = 18.dp) {
        Text(stringResource(R.string.tr_safe_to_deploy), style = MaterialTheme.typography.bodyMedium, color = Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.money(b.sweepable, dec), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(" $u", style = MaterialTheme.typography.titleMedium, color = Muted, modifier = Modifier.padding(bottom = 5.dp))
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (short) Icons.Filled.Warning else Icons.Filled.CheckCircle, contentDescription = null, tint = if (short) Tone.Bad else Tone.Good, modifier = Modifier.size(18.dp))
            Text(
                if (short) stringResource(R.string.tr_status_short, Fmt.money(b.shortfall, dec), u) else stringResource(R.string.tr_status_ok),
                Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = if (short) Tone.Bad else Tone.Good, fontWeight = FontWeight.Medium,
            )
        }
        Text(stringResource(R.string.tr_safe_hint), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---- allocation donut -------------------------------------------------------------------------

@Composable
private fun AllocTile(modifier: Modifier, snap: PortfolioSnapshot) {
    val b = snap.buckets
    val dec = snap.unit.decimals
    val total = b.totalAssets
    val pctCash = Raw.pct(b.free, total)
    val pctBills = Raw.pct(b.reserved, total)
    val pctEarn = Raw.pct(b.deployed, total)
    BentoTile(modifier) {
        TileLabel(stringResource(R.string.tr_alloc))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DonutChart(
                values = listOf(b.free.toFloat(), b.reserved.toFloat(), b.deployed.toFloat()),
                colors = listOf(Tone.Cash, Tone.Bills, Tone.Earning),
                size = 108.dp, stroke = 15.dp,
                description = stringResource(R.string.cd_alloc, "${Fmt.money(total, dec)} ${snap.unit.symbol}", pctCash.toString(), pctBills.toString(), pctEarn.toString()),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.compact(total, dec), style = MonoStyle.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(snap.unit.symbol, style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Legend(Tone.Cash, stringResource(R.string.tr_legend_cash, pctCash.toString()))
        Legend(Tone.Bills, stringResource(R.string.tr_legend_bills, pctBills.toString()))
        Legend(Tone.Earning, stringResource(R.string.tr_legend_earning, pctEarn.toString()))
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- runway gauge -----------------------------------------------------------------------------

@Composable
private fun RunwayTile(modifier: Modifier, snap: PortfolioSnapshot) {
    val days = BriefBuilder.runwayDays(snap)
    val tone = when {
        days == null -> Muted
        days < 14 -> Tone.Bad
        days < 30 -> Tone.Warn
        else -> Tone.Good
    }
    val verdict = when {
        days == null -> R.string.tr_runway_none
        days < 14 -> R.string.rw_low
        days < 30 -> R.string.rw_watch
        else -> R.string.rw_good
    }
    BentoTile(modifier) {
        TileLabel(stringResource(R.string.tr_runway))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RingGauge(
                progress = if (days == null) 0f else (days / 60f).coerceIn(0f, 1f), color = tone, size = 108.dp, stroke = 15.dp,
                description = if (days == null) stringResource(R.string.tr_runway_none) else stringResource(R.string.cd_runway, days.toString()),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(days?.toString() ?: "—", style = MonoStyle.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.tr_runway_days), style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(verdict), style = MaterialTheme.typography.bodyMedium, color = tone, fontWeight = FontWeight.Medium)
    }
}

// ---- 30-day trend -----------------------------------------------------------------------------

@Composable
private fun TrendTile(snap: PortfolioSnapshot, history: List<BucketSnapshotEntity>) {
    val dec = snap.unit.decimals
    val totals = Analytics.totals(history)
    val change = if (totals.size >= 2) Analytics.changePct(totals.first(), totals.last()) else null
    BentoTile(Modifier.fillMaxWidth()) {
        TileLabel(stringResource(R.string.tr_trend))
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(Fmt.money(snap.buckets.totalAssets, dec), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(" ${snap.unit.symbol}", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (totals.size < 2) {
            Text(stringResource(R.string.tr_trend_none), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
        } else {
            val (text, color) = when {
                change == null || change == 0 -> stringResource(R.string.tr_trend_flat) to Muted
                change > 0 -> stringResource(R.string.tr_trend_up, change.toString()) to Tone.Good
                else -> stringResource(R.string.tr_trend_down, (-change).toString()) to Tone.Bad
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
            Sparkline(
                values = totals.map { it.toFloat() }, color = Tone.Earning,
                description = stringResource(R.string.cd_trend, Fmt.money(totals.first(), dec), Fmt.money(totals.last(), dec), snap.unit.symbol),
                modifier = Modifier.padding(top = 8.dp).height(76.dp),
            )
        }
    }
}

// ---- weekly spending --------------------------------------------------------------------------

@Composable
private fun SpendTile(modifier: Modifier, snap: PortfolioSnapshot) {
    val dec = snap.unit.decimals
    val weeks = Analytics.weeklySpend(snap.flows30d, snap.asOf, dec)
    val any = weeks.any { it.total.signum() > 0 }
    val sums = listOf(
        weeks.fold(BigInteger.ZERO) { a, w -> a + w.payroll },
        weeks.fold(BigInteger.ZERO) { a, w -> a + w.suppliers },
        weeks.fold(BigInteger.ZERO) { a, w -> a + w.other },
    )
    BentoTile(modifier) {
        TileLabel(stringResource(R.string.tr_spend))
        if (!any) {
            Text(stringResource(R.string.tr_spend_none), style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 8.dp))
        } else {
            Spacer(Modifier.height(8.dp))
            StackedBars(
                groups = weeks.map { listOf(it.payroll.toFloat(), it.suppliers.toFloat(), it.other.toFloat()) },
                colors = listOf(Payroll, Suppliers, OtherSpend),
                labels = listOf(R.string.wk_3, R.string.wk_2, R.string.wk_1, R.string.wk_0).map { stringResource(it) },
                description = stringResource(R.string.cd_spend, weeks.joinToString { Fmt.compact(it.total, dec) }),
                height = 96.dp,
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                Triple(Payroll, R.string.cat_payroll, sums[0]),
                Triple(Suppliers, R.string.cat_suppliers, sums[1]),
                Triple(OtherSpend, R.string.cat_other, sums[2]),
            ).filter { it.third.signum() > 0 }.forEach { (c, name, amt) ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Text(stringResource(name), Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Text(Fmt.compact(amt, dec), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}

// ---- interest rates ---------------------------------------------------------------------------

@Composable
private fun RatesTile(modifier: Modifier, snap: PortfolioSnapshot) {
    val maxBps = (snap.lending.maxOfOrNull { it.supplyAprBps } ?: 0).coerceAtLeast(1)
    BentoTile(modifier) {
        TileLabel(stringResource(R.string.tr_rates))
        Spacer(Modifier.height(10.dp))
        snap.lending.forEach { l ->
            val aave = l.protocol == LendingProtocol.AAVE_V3
            LabeledBar(
                label = stringResource(if (aave) R.string.tr_row_earn_aave else R.string.tr_row_earn_vault),
                valueText = Aave.pct(l.supplyAprBps), fraction = l.supplyAprBps.toFloat() / maxBps, color = Tone.Earning,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

// ---- ask --------------------------------------------------------------------------------------

@Composable
private fun AskTile(onClick: () -> Unit) {
    BentoTile(Modifier.fillMaxWidth(), onClick = onClick, tint = Accessible.ButtonFill, padding = 14.dp) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Accessible.ButtonFill), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(R.string.tr_ask_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.tr_ask_hint), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

// ---- worth knowing ----------------------------------------------------------------------------

@Composable
private fun AttentionTile(all: List<LocalFinding>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ranked = all.sortedByDescending { it.severity.ordinal }
    // Informational notes are only shown when there is nothing more important.
    val important = ranked.filter { it.severity != Severity.INFO }.ifEmpty { ranked }
    val shown = if (expanded) ranked else important.take(2)
    val hidden = ranked.size - shown.size

    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.tr_attention), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        shown.forEach { f ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(severityColor(f.severity)))
                Text(findingText(f), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (hidden > 0 || expanded) {
            Expander(
                label = if (expanded) stringResource(R.string.tr_see_less) else stringResource(R.string.tr_see_all, ranked.size.toString()),
                expanded = expanded, onToggle = { expanded = !expanded },
            ) {}
        }
    }
}

@Composable
private fun findingText(f: LocalFinding): String {
    val a = f.args
    return when (f.code) {
        FindingCode.SHORTFALL -> stringResource(R.string.f_shortfall, a[0], a[1], a[2])
        FindingCode.BELOW_BUFFER -> stringResource(R.string.f_below, a[0], a[1])
        FindingCode.SURPLUS -> stringResource(R.string.f_surplus, a[0], a[1], a[2])
        FindingCode.CONCENTRATION -> stringResource(R.string.f_concentration, a[0], a[1])
        FindingCode.OVERDUE -> pluralStringResource(R.plurals.f_overdue_p, a[0].toInt(), a[0])
        FindingCode.DUE_SOON -> pluralStringResource(R.plurals.f_due_soon_p, a[0].toInt(), a[0], a[1])
        FindingCode.YIELD -> pluralStringResource(R.plurals.f_yield_p, a[2].toInt(), a[0], a[1], a[2])
        FindingCode.GAS_LOW -> pluralStringResource(R.plurals.f_gas_low_p, a[0].toInt(), a[0])
        FindingCode.RUNWAY -> pluralStringResource(R.plurals.f_runway_p, a[0].toInt(), a[0])
    }
}

// ---- holdings ---------------------------------------------------------------------------------

@Composable
private fun HoldingsTile(snap: PortfolioSnapshot) {
    val other = snap.balances.filter { !it.isStable && !it.isNative }
    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.tr_where), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        snap.balances.filter { it.isStable }.forEach {
            Item(stringResource(R.string.tr_row_cash, it.symbol), null, Fmt.money(it.raw, it.decimals), Tone.Cash)
        }
        snap.lending.forEach { l ->
            val aave = l.protocol == LendingProtocol.AAVE_V3
            Item(
                stringResource(if (aave) R.string.tr_row_earn_aave else R.string.tr_row_earn_vault),
                stringResource(R.string.tr_row_earn_rate, Aave.pct(l.supplyAprBps)), Fmt.money(l.supplied, l.decimals), Tone.Earning,
            )
        }
        snap.gas?.let { g ->
            val low = g.runwayActions < LOW_FUEL_ACTIONS
            Item(
                stringResource(R.string.tr_row_gas),
                if (low) stringResource(R.string.tr_gas_low) else stringResource(R.string.tr_gas_enough, g.runwayActions.toString()),
                Fmt.native(g.balanceWei), if (low) Tone.Warn else Muted, if (low) Tone.Warn else Muted,
            )
        }
        other.forEach { Item(stringResource(R.string.tr_row_other), it.symbol, Fmt.money(it.raw, it.decimals), Muted) }
    }
}

@Composable
private fun Item(title: String, subtitle: String?, amount: String, color: Color, subtitleColor: Color = Muted) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        Text(amount, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
    }
}

// ---- bills ------------------------------------------------------------------------------------

@Composable
private fun BillsTile(snap: PortfolioSnapshot) {
    val dec = snap.unit.decimals
    val timing = Analytics.billTiming(snap.obligations, snap.asOf, dec)
    val next = snap.obligations.filter { it.dueAt >= snap.asOf }.minByOrNull { it.dueAt }
    val colors = listOf(Tone.Bad, Tone.Warn, Tone.Cash, OtherSpend)
    val parts = listOf(
        Triple(R.string.bill_overdue, timing.overdue, Tone.Bad),
        Triple(R.string.bill_week, timing.thisWeek, Tone.Warn),
        Triple(R.string.bill_month, timing.thisMonth, Tone.Cash),
        Triple(R.string.bill_later, timing.later, OtherSpend),
    ).filter { it.second.signum() > 0 }
    val partNames = parts.map { stringResource(it.first) }

    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.tr_bills), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        if (snap.obligations.isEmpty()) {
            Text(stringResource(R.string.tr_bills_none), style = MaterialTheme.typography.bodyLarge, color = Muted)
        } else {
            if (next != null) {
                val days = ((next.dueAt - snap.asOf + DAY_SEC - 1) / DAY_SEC).coerceAtLeast(0)
                Text(
                    stringResource(R.string.tr_next_bill, Fmt.money(Raw.rescale(next.amount, next.decimals, dec), dec), pluralStringResource(R.plurals.tr_in_days_p, days.toInt(), days.toString())),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            SegmentBar(
                values = listOf(timing.overdue, timing.thisWeek, timing.thisMonth, timing.later).map { it.toFloat() }, colors = colors,
                description = stringResource(R.string.cd_bills, parts.indices.joinToString { partNames[it] + " " + Fmt.money(parts[it].second, dec) }),
            )
            Spacer(Modifier.height(8.dp))
            parts.forEach { (name, amount, c) ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Text(stringResource(name), Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(Fmt.money(amount, dec), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
