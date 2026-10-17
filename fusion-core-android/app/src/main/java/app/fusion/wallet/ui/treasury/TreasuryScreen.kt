package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.accountant.agent.FindingCode
import app.fusion.wallet.accountant.agent.LocalFinding
import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.cloud.BriefLimits
import app.fusion.wallet.accountant.data.LendingProtocol
import app.fusion.wallet.accountant.domain.Aave
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.accountant.domain.Raw
import app.fusion.wallet.ui.Fmt
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.common.severityColor
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian
import java.math.BigInteger

/** Home: what you have, what is spoken for, and the one number the agent is allowed to act on. */
@Composable
fun TreasuryScreen(modifier: Modifier = Modifier, vm: AccountantViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var askOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(stringResource(R.string.tr_title), stringResource(R.string.tr_subtitle)) {
                StatusPill(
                    label = stringResource(if (s.settings.cloudEnabled) R.string.pill_cloud_on else R.string.pill_local_only),
                    tone = if (s.settings.cloudEnabled) Obsidian.AccentAiViolet else Obsidian.StatusSuccess,
                )
            }
        }
        s.message?.let { m -> item { Notice(m, onDismiss = vm::dismissMessage) } }

        val snap = s.snapshot
        if (snap == null) {
            item {
                ObsidianCard {
                    Text(
                        stringResource(if (s.loading) R.string.tr_loading else R.string.tr_empty),
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Muted,
                    )
                }
            }
        } else {
            item { HeroCard(snap) }
            item { HoldingsCard(snap) }
            item { BillsCard(snap) }
            item { FindingsCard(s.local) }
        }
        item {
            Button(
                onClick = { askOpen = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accessible.ButtonFill),
            ) { Text(stringResource(R.string.tr_ask_button)) }
        }
    }

    if (askOpen) AskAccountantSheet(vm = vm, onDismiss = { askOpen = false })
}

@Composable
private fun HeroCard(snap: PortfolioSnapshot) {
    val b = snap.buckets
    val dec = snap.unit.decimals
    val u = snap.unit.symbol
    val total = b.totalAssets
    fun w(x: BigInteger): Float = if (total.signum() <= 0) 0f else (x.multiply(BigInteger.valueOf(10_000)).divide(total).toInt() / 10_000f)

    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tr_safe_to_deploy), style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(
                "${Fmt.money(b.sweepable, dec)} $u",
                style = MaterialTheme.typography.headlineMedium,
                color = if (b.shortfall.signum() > 0) Obsidian.StatusError else Obsidian.StatusSuccess,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.tr_safe_hint), style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.height(14.dp))

            CardTitle(stringResource(R.string.tr_total_assets), "${Fmt.money(total, dec)} $u")
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(FusionTheme.surfaces.level3)) {
                listOf(w(b.free) to Obsidian.StatusSuccess, w(b.reserved) to Obsidian.StatusWarning, w(b.deployed) to Obsidian.AccentAiViolet)
                    .filter { it.first > 0f }
                    .forEach { (weight, color) -> Box(Modifier.weight(weight).height(10.dp).background(color)) }
            }
            Spacer(Modifier.height(8.dp))
            BucketRow(stringResource(R.string.tr_bucket_available), Fmt.money(b.free, dec), Obsidian.StatusSuccess, stringResource(R.string.tr_bucket_available_note, Fmt.money(b.sweepable, dec)))
            BucketRow(stringResource(R.string.tr_bucket_reserved), Fmt.money(b.reserved, dec), Obsidian.StatusWarning, stringResource(R.string.tr_bucket_reserved_note, Fmt.money(b.obligationTarget, dec)))
            BucketRow(stringResource(R.string.tr_bucket_earning), Fmt.money(b.deployed, dec), Obsidian.AccentAiViolet, stringResource(R.string.tr_bucket_earning_note))
            if (b.shortfall.signum() > 0) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tr_shortfall, Fmt.money(b.shortfall, dec), u), style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusError)
            }
        }
    }
}

@Composable
private fun BucketRow(label: String, value: String, color: Color, note: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(note, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Text(value, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- holdings: cash / earning / gas --------------------------------------------------------------

@Composable
private fun HoldingsCard(snap: PortfolioSnapshot) {
    val cash = snap.balances.filter { it.isStable }
    val other = snap.balances.filter { !it.isStable && !it.isNative }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.tr_holdings))

            GroupLabel(stringResource(R.string.tr_group_cash))
            cash.forEach { Line(it.symbol, null, Fmt.money(it.raw, it.decimals), null) }

            GroupLabel(stringResource(R.string.tr_group_earning))
            snap.lending.forEach { l ->
                val name = stringResource(if (l.protocol == LendingProtocol.AAVE_V3) R.string.tr_pos_aave else R.string.tr_pos_vault)
                Line(name, l.positionSymbol, Fmt.money(l.supplied, l.decimals), stringResource(R.string.tr_rate_apr, Aave.pct(l.supplyAprBps)) to Obsidian.StatusSuccess)
            }

            snap.gas?.let { g ->
                GroupLabel(stringResource(R.string.tr_group_gas))
                val low = g.runwayActions < LOW_FUEL_ACTIONS
                Line(
                    "ETH", stringResource(R.string.tr_gas_note), Fmt.native(g.balanceWei),
                    (if (low) stringResource(R.string.tr_gas_low) else stringResource(R.string.tr_gas_runway, g.runwayActions.toString())) to
                        (if (low) Obsidian.StatusWarning else Muted),
                )
            }

            if (other.isNotEmpty()) {
                GroupLabel(stringResource(R.string.tr_group_other))
                other.forEach { Line(it.symbol, null, Fmt.money(it.raw, it.decimals), null) }
            }
            if (cash.isEmpty() && snap.lending.isEmpty()) Text("—", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val LOW_FUEL_ACTIONS = 20

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun Line(title: String, subtitle: String?, amount: String, tag: Pair<String, Color>?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(amount, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
            if (tag != null) Text(tag.first, style = MaterialTheme.typography.labelSmall, color = tag.second)
        }
    }
}

// ---- bills --------------------------------------------------------------------------------------

@Composable
private fun BillsCard(snap: PortfolioSnapshot) {
    val dec = snap.unit.decimals
    val groups = snap.obligations.groupBy { BriefBuilder.dueWindow(it.dueAt - snap.asOf) }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.tr_bills), Fmt.money(snap.reserved, dec) + " " + snap.unit.symbol)
            if (groups.isEmpty()) Text(stringResource(R.string.tr_bills_none), style = MaterialTheme.typography.bodySmall, color = Muted)
            BriefLimits.DUE_WINDOWS.forEach { w ->
                val list = groups[w] ?: return@forEach
                val sum = list.fold(BigInteger.ZERO) { a, o -> a + Raw.rescale(o.amount, o.decimals, dec) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        (if (w == "OVERDUE") stringResource(R.string.tr_due_overdue) else stringResource(R.string.tr_due_window, w)) + " · ${list.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (w == "OVERDUE") Obsidian.StatusError else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(Fmt.money(sum, dec), style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ---- on-device analysis -------------------------------------------------------------------------

@Composable
private fun findingText(f: LocalFinding): String {
    val res = when (f.code) {
        FindingCode.SHORTFALL -> R.string.f_shortfall
        FindingCode.BELOW_BUFFER -> R.string.f_below
        FindingCode.SURPLUS -> R.string.f_surplus
        FindingCode.OVERDUE -> R.string.f_overdue
        FindingCode.DUE_SOON -> R.string.f_due_soon
        FindingCode.CONCENTRATION -> R.string.f_concentration
        FindingCode.YIELD -> R.string.f_yield
        FindingCode.GAS_LOW -> R.string.f_gas_low
        FindingCode.RUNWAY -> R.string.f_runway
    }
    return stringResource(res, *f.args.toTypedArray())
}

@Composable
private fun FindingsCard(findings: List<LocalFinding>) {
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.tr_findings), stringResource(R.string.tr_findings_note))
            findings.forEach { f ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(severityColor(f.severity)))
                    Text(findingText(f), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
