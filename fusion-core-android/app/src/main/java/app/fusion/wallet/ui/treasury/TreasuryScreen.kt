package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.agent.AgentRuntime
import app.fusion.wallet.data.*
import app.fusion.wallet.signer.GuardianStopDialog
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.charts.*
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigInteger

@Composable
fun TreasuryScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val historyVm: BalanceHistoryViewModel = viewModel()
    val history by historyVm.state.collectAsStateWithLifecycle()
    val agent by AgentRuntime.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var askOpen by rememberSaveable { mutableStateOf(false) }
    var stopOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var stopHash by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    LaunchedEffect(state.evidence, state.recentActions) { historyVm.load(state) }
    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                // A title row contains only the bounded refresh action; freshness belongs in the hero.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.nav_home), style = FusionType.HeadingMedium, maxLines = 1)
                        Text(stringResource(R.string.tr_subtitle), style = FusionType.BodySmall)
                    }
                    IconButton(onClick = { vm.refresh(); historyVm.load(state, retry = true) }) {
                        Icon(painterResource(R.drawable.ic_nav_refresh), stringResource(R.string.refresh_button))
                    }
                }
            }
            state.error?.let { error -> item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text(error, style = FusionType.BodySmall, color = FusionColors.Amber)
                    TextButton(onClick = vm::refresh) { Text("Retry snapshot") }
                }
            } }
            if (state.treasury == null && state.status == LoadStatus.LOADING) item {
                SkeletonTile(height = 180.dp)
            } else item { TreasuryHero(state, now) }
            item { BucketsTile(state.buckets) }
            item { BalanceAreaChart(history.points, state.buckets?.total?.raw,
                loading = history.loading, note = history.note) }
            item {
                BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Indigo) {
                    Text(stringResource(R.string.home_agent_title), style = FusionType.HeadingSmall)
                    val module = state.module
                    val status = when {
                        module == null -> "Status unavailable"
                        module.paused -> "Paused"
                        !module.configured -> "Not configured"
                        !module.sessionActive -> "Monitor only · no active session"
                        else -> "Active"
                    }
                    StatusChip(status, if (module?.sessionActive == true) FusionColors.Green else FusionColors.Amber)
                    Text(agent.result?.let { "Last decision: ${it.choiceLabel} · ${it.reason}" }
                        ?: "No in-app decision recorded this session", style = FusionType.BodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { scope.launch { vm.runAgentTick(true) } }, enabled = !agent.running && state.treasury != null) {
                            Text(stringResource(if (agent.running) R.string.home_agent_running else R.string.home_agent_run))
                        }
                        OutlinedButton(onClick = { stopOpen = true }, enabled = module != null && !module.paused) {
                            Text(stringResource(R.string.home_agent_stop), color = FusionColors.Red)
                        }
                    }
                    Text("Dry run · live policy preview, no broadcast", style = FusionType.BodySmall)
                    agent.error?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Amber) }
                    stopHash?.let { TxLink(it) }
                }
            }
            if (state.vaults.isEmpty()) item {
                BentoTile(Modifier.fillMaxWidth()) {
                    Text("Vault positions", style = FusionType.HeadingSmall)
                    Text("Vault balances unavailable until the chain snapshot loads.", style = FusionType.BodySmall)
                }
            } else state.vaults.forEach { vault -> item(key = "vault:${vault.id}") { VaultTile(vault) } }
            item { NextBillTile(state, now / 1000) }
            item {
                BentoTile(Modifier.fillMaxWidth(), onClick = { askOpen = true }) {
                    Text(stringResource(R.string.tr_ask_title), style = FusionType.HeadingSmall)
                    Text(stringResource(R.string.tr_ask_hint), style = FusionType.BodySmall)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (askOpen) {
        val accountant: AccountantViewModel = viewModel()
        AskAccountantSheet(vm = accountant, onDismiss = { askOpen = false })
    }
    if (stopOpen) GuardianStopDialog(requestPause = {
        val result = vm.requestGuardianPause()
        result.onSuccess { hash ->
            stopHash = hash
            scope.launch { snackbar.showSnackbar("Guardian pause confirmed · ${hash.take(10)}…") }
        }
        result
    }, onDismiss = { stopOpen = false })
}

@Composable
private fun TreasuryHero(state: WalletState, now: Long) {
    BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Green) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_crypto_usdc), "USDC", tint = Color.Unspecified, modifier = Modifier.size(32.dp))
            Text("Total treasury", style = FusionType.HeadingSmall)
        }
        val total = state.buckets?.total?.raw
        if (total == null) Text("Balance unavailable", style = FusionType.HeadingMedium)
        else {
            // Currency on a separate line keeps large exact amounts readable on a phone.
            AmountText(total, symbol = "", style = FusionType.NumberLarge, modifier = Modifier.padding(top = 12.dp))
            Text(state.treasury?.symbol ?: "USDC", style = FusionType.BodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_network_base), null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
            StatusChip("Base Sepolia · TEST", FusionColors.Indigo)
        }
        val age = state.capturedAtEpochMillis?.let { ((now - it).coerceAtLeast(0) / 1000) }
        val source = when {
            state.source == DataSource.DEMO -> "Demo data"
            age == null -> "No snapshot yet"
            state.isDisplayStale(now) || state.refreshing -> "Cached snapshot"
            else -> "Live snapshot"
        }
        Text("$source${age?.let { " · Updated $it s ago" } ?: ""}${if (state.refreshing) " · refreshing" else ""}",
            style = FusionType.BodySmall, modifier = Modifier.padding(top = 8.dp))
        state.blockNumber?.let { Text("Block $it", style = FusionType.NumberCaption) }
        state.treasury?.let { Text("Safe cash · ${formatBigIntegerAmount(it.balance.raw)} ${it.symbol}", style = FusionType.BodySmall) }
        if (state.accountingMatches == false) Text(app.fusion.wallet.ui.common.accountingMessageForPeople(state.accountingError).orEmpty(),
            style = FusionType.BodySmall, color = FusionColors.Amber)
    }
}

@Composable
private fun BucketsTile(buckets: Buckets?) {
    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_buckets_title), style = FusionType.HeadingSmall)
        if (buckets == null) { Text("Buckets unavailable until balances and policy load.", style = FusionType.BodySmall); return@BentoTile }
        // Owed coverage, buffer and surplus partition Safe cash. Deployed vault assets are separate.
        val values = listOf(buckets.reserve.raw, buckets.buffer.raw, buckets.surplus.raw)
        val labels = listOf("Owed · covered", "Buffer", "Surplus")
        val colors = listOf(FusionColors.Amber, FusionColors.Indigo, FusionColors.Green)
        val total = values.fold(BigInteger.ZERO, BigInteger::add)
        val summary = labels.zip(values).joinToString { (label, raw) -> "$label ${formatBigIntegerAmount(raw)} USDC" }
        Canvas(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(20.dp).semantics { contentDescription = "100 percent of Safe cash. $summary" }) {
            var x = 0f
            if (total.signum() == 0) drawRect(FusionColors.Border)
            values.forEachIndexed { i, raw ->
                val width = size.width * chartFraction(raw, total)
                if (width > 0) {
                    drawRect(colors[i], Offset(x, 0f), Size(width, size.height))
                    // Numbered patterns: none / diagonal ticks / vertical ticks; labels below follow this order.
                    if (i > 0) {
                        var tick = x + 3.dp.toPx()
                        while (tick < x + width) {
                            drawLine(FusionColors.Bg.copy(alpha = .6f), Offset(tick, 0f),
                                Offset(if (i == 1) (tick + 5.dp.toPx()).coerceAtMost(x + width) else tick, size.height), 1.dp.toPx())
                            tick += 8.dp.toPx()
                        }
                    }
                    x += width
                }
            }
        }
        values.forEachIndexed { i, raw ->
            Text("${i + 1}. ${labels[i]}", style = FusionType.BodySmall)
            AmountText(raw, style = FusionType.NumberBody)
        }
        if (total.signum() == 0) Text("No Safe cash in this snapshot.", style = FusionType.BodySmall)
        Text("Total owed · ${formatBigIntegerAmount(buckets.owed.raw)} USDC", style = FusionType.BodySmall)
        if (buckets.shortfall.raw.signum() > 0) Text("Uncovered owed · ${formatBigIntegerAmount(buckets.shortfall.raw)} USDC",
            color = FusionColors.Amber, style = FusionType.BodySmall)
        Text("Vault assets · ${formatBigIntegerAmount(buckets.deployed.raw)} USDC (outside cash buckets)", style = FusionType.BodySmall)
    }
}

@Composable
private fun VaultTile(vault: VaultPosition) {
    BentoTile(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!vault.simulatedYield) Icon(painterResource(R.drawable.ic_brand_aave), "Aave", tint = Color.Unspecified, modifier = Modifier.size(32.dp))
            Text(vault.name, style = FusionType.HeadingSmall, modifier = Modifier.weight(1f))
        }
        StatusChip(if (vault.simulatedYield) "Simulated yield" else "On-chain Aave position", if (vault.simulatedYield) FusionColors.Amber else FusionColors.Green)
        AmountText(vault.assets.raw, style = FusionType.NumberSmall, modifier = Modifier.padding(top = 8.dp))
        Text("Withdrawable · ${formatBigIntegerAmount(vault.maxWithdraw.raw)} USDC", style = FusionType.BodySmall)
        Text("Exposure cap · ${formatBigIntegerAmount(vault.exposureLimit.raw)} USDC", style = FusionType.BodySmall)
        Text("Shares · ${vault.sharesRaw}", style = FusionType.NumberCaption)
    }
}

@Composable
private fun NextBillTile(state: WalletState, now: Long) {
    BentoTile(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_next_bill_title), style = FusionType.HeadingSmall)
        val next = state.obligations.filterNot { it.paid }.minByOrNull { it.dueAt }
        if (next == null) {
            Text(when {
                state.module == null -> "Bill data unavailable until the chain snapshot loads."
                state.accountingMatches != true -> "Bill details unavailable: metadata does not match the on-chain commitment."
                else -> "No pending bills in the verified commitment."
            }, style = FusionType.BodySmall)
        } else {
            Text(state.payees.find { it.id.equals(next.payeeId, true) || it.address.equals(next.payeeId, true) }?.label
                ?: "Vendor ${next.payeeId.take(12)}…", style = FusionType.BodyMedium)
            AmountText(next.amount.raw, style = FusionType.NumberSmall)
            val delta = next.dueAt - now
            Text(if (delta < 0) "Overdue by ${(-delta) / 86400} days" else "Due in ${(delta + 86399) / 86400} days", style = FusionType.BodySmall)
            val policy = state.module?.policy
            val tier = when {
                policy == null -> "Tier unavailable"
                next.amount.raw <= policy.microMax.raw -> "Instant tier"
                next.amount.raw <= policy.delayedMax.raw -> "Queued tier"
                else -> "Owner approval tier"
            }
            StatusChip(tier, FusionColors.Amber)
        }
    }
}
