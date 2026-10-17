package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.accountant.agent.FindingCode
import app.fusion.wallet.accountant.agent.LocalFinding
import app.fusion.wallet.accountant.data.LendingProtocol
import app.fusion.wallet.accountant.data.Severity
import app.fusion.wallet.accountant.domain.Aave
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.ui.Fmt
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Expander
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.common.severityColor
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import java.math.BigInteger

private const val LOW_FUEL_ACTIONS = 20
private const val DAY_SEC = 86_400L

/**
 * Home. One question answered first: "how much money can the agent safely use?" Everything else is
 * secondary and tucked behind a tap so the screen never feels like a spreadsheet.
 */
@Composable
fun TreasuryScreen(modifier: Modifier = Modifier, vm: AccountantViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var askOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.tr_title), stringResource(R.string.tr_subtitle)) }
        s.message?.let { m -> item { Notice(m, onDismiss = vm::dismissMessage) } }

        val snap = s.snapshot
        if (snap == null) {
            item {
                ObsidianCard {
                    Text(
                        stringResource(if (s.loading) R.string.tr_loading else R.string.tr_empty),
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = Muted,
                    )
                }
            }
        } else {
            item { HeroCard(snap) }
            item { AskTile(onClick = { askOpen = true }) }
            if (s.local.isNotEmpty()) item { AttentionCard(s.local) }
            item { WhereCard(snap) }
            item { BillsCard(snap) }
        }
    }

    if (askOpen) AskAccountantSheet(vm = vm, onDismiss = { askOpen = false })
}

// ---- hero -------------------------------------------------------------------------------------

@Composable
private fun HeroCard(snap: PortfolioSnapshot) {
    val b = snap.buckets
    val dec = snap.unit.decimals
    val u = snap.unit.symbol
    val total = b.totalAssets
    val short = b.shortfall.signum() > 0
    var details by rememberSaveable { mutableStateOf(false) }
    fun w(x: BigInteger): Float = if (total.signum() <= 0) 0f else (x.multiply(BigInteger.valueOf(10_000)).divide(total).toInt() / 10_000f)

    ObsidianCard {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.tr_safe_to_deploy), style = MaterialTheme.typography.bodyMedium, color = Muted)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    Fmt.money(b.sweepable, dec),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(" $u", style = MaterialTheme.typography.titleMedium, color = Muted, modifier = Modifier.padding(bottom = 5.dp))
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (short) Icons.Filled.Warning else Icons.Filled.CheckCircle, contentDescription = null,
                    tint = if (short) Tone.Bad else Tone.Good, modifier = Modifier.size(18.dp),
                )
                Text(
                    if (short) stringResource(R.string.tr_status_short, Fmt.money(b.shortfall, dec), u) else stringResource(R.string.tr_status_ok),
                    Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                    color = if (short) Tone.Bad else Tone.Good, fontWeight = FontWeight.Medium,
                )
            }
            Text(stringResource(R.string.tr_safe_hint), style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 8.dp))

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(FusionTheme.surfaces.level3)) {
                listOf(w(b.free) to Tone.Cash, w(b.reserved) to Tone.Bills, w(b.deployed) to Tone.Earning)
                    .filter { it.first > 0f }
                    .forEach { (weight, color) -> Box(Modifier.weight(weight).height(10.dp).background(color)) }
            }
            Spacer(Modifier.height(8.dp))
            BucketRow(stringResource(R.string.tr_bucket_available), Fmt.money(b.free, dec), Tone.Cash, if (details) stringResource(R.string.tr_bucket_available_note, Fmt.money(b.sweepable, dec)) else null)
            BucketRow(stringResource(R.string.tr_bucket_reserved), Fmt.money(b.reserved, dec), Tone.Bills, if (details) stringResource(R.string.tr_bucket_reserved_note, Fmt.money(b.obligationTarget, dec)) else null)
            BucketRow(stringResource(R.string.tr_bucket_earning), Fmt.money(b.deployed, dec), Tone.Earning, if (details) stringResource(R.string.tr_bucket_earning_note) else null)
            Expander(
                label = stringResource(if (details) R.string.common_details_hide else R.string.common_details_show),
                expanded = details, onToggle = { details = !details },
            ) {}
        }
    }
}

@Composable
private fun BucketRow(label: String, value: String, color: Color, note: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text(value, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- ask --------------------------------------------------------------------------------------

@Composable
private fun AskTile(onClick: () -> Unit) {
    ObsidianCard(level = 2, onClick = onClick, modifier = Modifier.semantics { role = Role.Button }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Accessible.ButtonFill), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(R.string.tr_ask_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.tr_ask_hint), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

// ---- worth knowing: only the most important two, the rest on request -------------------------

@Composable
private fun AttentionCard(all: List<LocalFinding>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ranked = all.sortedByDescending { it.severity.ordinal }
    // Informational notes are only shown when there is nothing more important.
    val important = ranked.filter { it.severity != Severity.INFO }.ifEmpty { ranked }
    val shown = if (expanded) ranked else important.take(2)
    val hidden = ranked.size - shown.size

    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.tr_attention))
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

// ---- where the money is -----------------------------------------------------------------------

@Composable
private fun WhereCard(snap: PortfolioSnapshot) {
    val other = snap.balances.filter { !it.isStable && !it.isNative }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.tr_where))
            snap.balances.filter { it.isStable }.forEach {
                Item(stringResource(R.string.tr_row_cash, it.symbol), null, Fmt.money(it.raw, it.decimals), null, Tone.Cash)
            }
            snap.lending.forEach { l ->
                val aave = l.protocol == LendingProtocol.AAVE_V3
                Item(
                    title = stringResource(if (aave) R.string.tr_row_earn_aave else R.string.tr_row_earn_vault),
                    subtitle = stringResource(R.string.tr_row_earn_rate, Aave.pct(l.supplyAprBps)),
                    amount = Fmt.money(l.supplied, l.decimals), tag = null, color = Tone.Earning,
                )
            }
            snap.gas?.let { g ->
                val low = g.runwayActions < LOW_FUEL_ACTIONS
                Item(
                    title = stringResource(R.string.tr_row_gas),
                    subtitle = if (low) stringResource(R.string.tr_gas_low) else stringResource(R.string.tr_gas_enough, g.runwayActions.toString()),
                    amount = Fmt.native(g.balanceWei), tag = null, color = if (low) Tone.Warn else Muted, subtitleColor = if (low) Tone.Warn else Muted,
                )
            }
            other.forEach { Item(stringResource(R.string.tr_row_other), it.symbol, Fmt.money(it.raw, it.decimals), null, Muted) }
        }
    }
}

@Composable
private fun Item(title: String, subtitle: String?, amount: String, tag: String?, color: Color, subtitleColor: Color = Muted) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(amount, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
            if (tag != null) Text(tag, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

// ---- bills: one calm line ---------------------------------------------------------------------

@Composable
private fun BillsCard(snap: PortfolioSnapshot) {
    val dec = snap.unit.decimals
    val overdue = snap.obligations.count { it.dueAt < snap.asOf }
    val next = snap.obligations.filter { it.dueAt >= snap.asOf }.minByOrNull { it.dueAt }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.tr_bills))
            if (snap.obligations.isEmpty()) {
                Text(stringResource(R.string.tr_bills_none), style = MaterialTheme.typography.bodyLarge, color = Muted)
            } else {
                if (next != null) {
                    val days = ((next.dueAt - snap.asOf + DAY_SEC - 1) / DAY_SEC).coerceAtLeast(0)
                    Text(
                        stringResource(R.string.tr_next_bill, Fmt.money(app.fusion.wallet.accountant.domain.Raw.rescale(next.amount, next.decimals, dec), dec), pluralStringResource(R.plurals.tr_in_days_p, days.toInt(), days.toString())),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    pluralStringResource(R.plurals.tr_bills_total_p, snap.obligations.size, snap.obligations.size.toString(), Fmt.money(snap.reserved, dec)),
                    style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp),
                )
                if (overdue > 0) Text(stringResource(R.string.tr_bills_overdue, overdue.toString()), style = MaterialTheme.typography.bodyMedium, color = Tone.Bad, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
