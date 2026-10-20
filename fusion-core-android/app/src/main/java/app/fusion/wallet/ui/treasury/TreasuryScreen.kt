package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.agent.AgentRuntime
import app.fusion.wallet.ui.activity.activityProvenance
import app.fusion.wallet.ui.activity.activityTitle
import app.fusion.wallet.ui.activity.RecordedProvenanceChip
import app.fusion.wallet.ui.activity.rememberTxIndex
import app.fusion.wallet.data.*
import app.fusion.wallet.signer.GuardianStopDialog
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.charts.*
import app.fusion.wallet.ui.components.*
import app.fusion.wallet.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TreasuryScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val historyVm: BalanceHistoryViewModel = viewModel()
    val history by historyVm.state.collectAsStateWithLifecycle()
    val agent by AgentRuntime.state.collectAsStateWithLifecycle()
    val txIndex = rememberTxIndex()
    val lastDecisionTx = if (agent.result != null) agent.result?.txHash
        else state.recentActions.maxByOrNull { it.blockNumber }?.txHash
    val lastDecisionEntry = txIndex.find(lastDecisionTx)
    val scope = rememberCoroutineScope()
    var askOpen by rememberSaveable { mutableStateOf(false) }
    var stopOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var stopHash by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    LaunchedEffect(state.balanceHistory, state.refreshing, state.historyError) { historyVm.load(state) }
    Box(modifier.fillMaxSize().background(Color(0xFF080A0D))) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            item { HomeAllocationAndRunway(state) }
            item { BalanceAreaChart(history.points, state.treasury?.balance?.raw,
                loading = history.loading, note = history.note) }
            item { HomeSpendingAndRates(state, txIndex) }
            item { WorthKnowing(state) }
            item {
                ChartTile(Modifier.fillMaxWidth()) {
                    ChartTileHeader(stringResource(R.string.home_agent_title),
                        "Dry run is a live policy preview without a broadcast. The latest decision uses the recorded transaction provenance where available; planner labels identify the recorded planner. Stop requests the guardian pause.")
                    val module = state.module
                    val status = when {
                        module == null -> "Status unavailable"
                        module.paused -> "Paused"
                        !module.configured -> "Not configured"
                        !module.sessionActive -> "Monitor only · no active session"
                        else -> "Active"
                    }
                    StatusChip(status, if (module?.sessionActive == true) FusionColors.Green else FusionColors.Amber)
                    Text(agent.result?.let { "Last decision: ${it.choiceLabel} · ${app.fusion.wallet.ui.common.readableCode(it.reason)}" }
                        ?: lastDecisionEntry?.let { "Last decision: ${activityTitle(it.kind)}" }
                        ?: "No in-app decision recorded this session", style = FusionType.BodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    lastDecisionTx?.let { RecordedProvenanceChip(activityProvenance(lastDecisionEntry?.source)) }
                    lastDecisionEntry?.planner?.let { StatusChip(it, FusionColors.Indigo) }
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
                ChartTile(Modifier.fillMaxWidth()) {
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
    ChartTile(Modifier.fillMaxWidth(), hero = true) {
        val age = state.capturedAtEpochMillis?.let { ((now - it).coerceAtLeast(0) / 1000) }
        val source = when {
            state.source == DataSource.DEMO -> "Demo data"
            age == null -> "No snapshot yet"
            state.isDisplayStale(now) || state.refreshing -> "Cached snapshot"
            else -> "Live snapshot"
        }
        val explanation = buildString {
            append("What is left after bills and the safety cushion. Your AI agent can only use this.")
            append("\n\n$source")
            age?.let { append(" · Updated $it s ago") }
            if (state.refreshing) append(" · refreshing")
            state.blockNumber?.let { append("\nBlock $it") }
            state.treasury?.let { append("\nSafe cash · ${formatBigIntegerAmount(it.balance.raw)} ${it.symbol}") }
            if (state.accountingMatches == false) append("\n" + app.fusion.wallet.ui.common.accountingMessageForPeople(state.accountingError).orEmpty())
        }
        ChartTileHeader("Spare cash", explanation)
        val total = state.buckets?.surplus?.raw
        if (total == null) Text("Balance unavailable", style = FusionType.HeadingMedium)
        else ChartAmount(formatBigIntegerAmount(total), state.treasury?.symbol ?: "USDC")
        state.buckets?.let { buckets ->
            val covered = buckets.shortfall.raw.signum() == 0
            val ok = covered && state.accountingMatches == true
            val tone = if (ok) FusionChart.Cash else FusionChart.Reserved
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                androidx.compose.material3.Icon(
                    if (ok) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Filled.Warning,
                    contentDescription = null, tint = tone, modifier = Modifier.padding(end = 8.dp).size(22.dp))
                Text(if (state.accountingMatches != true) "Bill coverage unverified" else if (covered) "Your bills are covered" else "${formatBigIntegerAmount(buckets.shortfall.raw)} USDC bill shortfall",
                    style = FusionChart.Status, color = tone)
            }
        }
        Text("What is left after bills and the safety cushion. Your AI agent can only use this.",
            style = FusionType.BodyMedium, color = FusionChart.Muted, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun VaultTile(vault: VaultPosition) {
    ChartTile(Modifier.fillMaxWidth()) {
        ChartTileHeader(vault.name, buildString {
            append(if (vault.simulatedYield) "Simulated yield from sponsor donations." else "On-chain Aave position.")
            append("\n\nWithdrawable · ${formatBigIntegerAmount(vault.maxWithdraw.raw)} USDC")
            append("\nExposure cap · ${formatBigIntegerAmount(vault.exposureLimit.raw)} USDC")
            append("\nShares · ${vault.sharesRaw}")
        })
        ChartAmount(formatBigIntegerAmount(vault.assets.raw), large = false)
        Text(if (vault.simulatedYield) "Simulated yield" else "On-chain Aave position",
            style = FusionChart.Status, color = if (vault.simulatedYield) FusionChart.Reserved else FusionChart.Cash,
            modifier = Modifier.padding(top = 8.dp))
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
