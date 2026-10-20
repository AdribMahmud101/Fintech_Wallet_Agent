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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.onboarding.OnboardingPrefs
import app.fusion.wallet.ui.receive.ReceiveSheet
import app.fusion.wallet.ui.owner.OwnerSendSheet
import app.fusion.wallet.ui.owner.OwnerSwapSheet
import app.fusion.wallet.R
import app.fusion.wallet.agent.autopilot.autopilotStatus
import app.fusion.wallet.agent.AgentRuntime
import app.fusion.wallet.ui.activity.activityProvenance
import app.fusion.wallet.ui.activity.activityTitle
import app.fusion.wallet.ui.activity.RecordedProvenanceChip
import app.fusion.wallet.ui.common.CustodySplitPill
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
fun TreasuryScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel(), onOpenProof: () -> Unit = {}, onOpenScout: () -> Unit = {}) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    // Keep evidence parsing/refresh out of the first Home frame and snapshot path.
    var evidenceReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; evidenceReady = true }
    val arena = if (evidenceReady) {
        val arenaVm: app.fusion.wallet.ui.safety.SafetyScreenModel = viewModel()
        val evidence by arenaVm.state.collectAsStateWithLifecycle()
        evidence.arena
    } else null
    val historyVm: BalanceHistoryViewModel = viewModel()
    val history by historyVm.state.collectAsStateWithLifecycle()
    val agent by AgentRuntime.state.collectAsStateWithLifecycle()
    val autopilot by vm.autopilot.state.collectAsStateWithLifecycle()
    val txIndex = rememberTxIndex()
    val lastDecisionTx = if (agent.result != null) agent.result?.txHash
        else state.recentActions.maxByOrNull { it.blockNumber }?.txHash
    val lastDecisionEntry = txIndex.find(lastDecisionTx)
    val scope = rememberCoroutineScope()
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var receiveOpen by rememberSaveable { mutableStateOf(false) }
    var sendOpen by rememberSaveable { mutableStateOf(false) }
    var swapOpen by rememberSaveable { mutableStateOf(false) }
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    val previewMove: () -> Unit = {
        previewOpen = true
        scope.launch { vm.runAgentTick(dryRun = true) }
    }
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
            } else item { TreasuryHero(state, now, onReceive = { receiveOpen = true }, onEarn = previewMove,
                onSend = { sendOpen = true }, onSwap = { swapOpen = true }, running = agent.running) }
            if (approvalBannerVisible(state.queue)) item { ApprovalBanner(state, now / 1000, txIndex) }
            item { app.fusion.wallet.ui.scout.ScoutTile(state, onOpenScout) }
            item { ProofChip(txIndex, onOpenProof) }
            if (OnboardingPrefs.isWatchOnly(context)) item {
                BentoTile(Modifier.fillMaxWidth(), tint = FusionColors.Indigo) {
                    Text(stringResource(R.string.ob_watch_title), style = FusionType.HeadingSmall)
                    Text(stringResource(R.string.ob_watch_desc), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                }
            }
            item { HomeAllocationAndRunway(state) }
            if (moreOpen) item { BalanceAreaChart(history.points, state.treasury?.balance?.raw,
                loading = history.loading, note = history.note) }
            if (moreOpen) item { HomeSpendingAndRates(state, txIndex) }
            if (moreOpen) item { WorthKnowing(state) }
            item {
                ChartTile(Modifier.fillMaxWidth()) {
                    ChartTileHeader(stringResource(R.string.home_agent_title),
                        "Dry run is a live policy preview without a broadcast. The latest decision uses the recorded transaction provenance where available; planner labels identify the recorded planner. Stop requests the guardian pause.")
                    val module = state.module
                    val status = when {
                        module == null -> "Status unavailable"
                        module.paused -> "Paused"
                        !module.configured -> "Not configured"
                        !module.sessionActive -> "Monitor only"
                        else -> "Active"
                    }
                    // One calm header line: state, where the AI runs, and the Autopilot countdown when it is on.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(status, if (module?.sessionActive == true) FusionColors.Green else FusionChart.Muted)
                        Text("On-device AI · private", style = FusionType.BodySmall, color = FusionColors.TextMuted)
                    }
                    if (autopilot.enabled) {
                        Text(autopilotStatus(autopilot, now), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                        AutopilotCountdown(autopilot.nextTickAtMillis, autopilot.intervalMinutes, now, autopilot.enabled && !autopilot.running)
                    }
                    val runtimeReport = agent.result?.let { runCatching { org.json.JSONObject(it.reportJson) }.getOrNull() }
                    val lastTick = autopilot.timeline.firstOrNull { it.outcome != "RUNNING" }
                        ?.takeIf { it.timeMillis >= (runtimeReport?.optLong("completedAtMillis") ?: 0) || it.reportJson == agent.result?.reportJson }
                    Text(lastTick?.let { "Last decision: ${it.reason}" }
                        ?: agent.result?.let { "Last decision: ${app.fusion.wallet.ui.common.decisionReason(org.json.JSONObject(it.reportJson), it.reason)}" }
                        ?: lastDecisionEntry?.let { "Last decision: ${activityTitle(it.kind)}" }
                        ?: "No in-app decision recorded", style = FusionType.BodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    module?.let { m ->
                        val allowance = aiAllowance(m.dailyUsed.raw, m.policy.dailyLimit.raw, m.policy.microMax.raw)
                        val lines = app.fusion.wallet.ui.common.allowanceLabels(m, state.blockTime)
                        // Two lines of detail (daily limit with the reset time, hard max) live on the AI Agent screen.
                        val left = lines.getOrNull(0)?.replace(Regex("""(\d[\d,]*)\.(\d{3,})""")) { r -> "%,.2f".format(java.util.Locale.US, r.value.replace(",", "").toDouble()) }
                        val per = lines.getOrNull(1)?.substringBefore(" · max")
                        Text(listOfNotNull(left, per).joinToString(" · "), style = FusionType.BodySmall, color = FusionColors.TextMuted)
                        LinearProgressIndicator(progress = { allowance.fraction }, modifier = Modifier.fillMaxWidth(), color = FusionColors.Green)
                    } ?: Text("AI allowance unavailable until policy loads", style = FusionType.BodySmall, color = FusionColors.TextMuted)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = previewMove, enabled = !agent.running && !autopilot.running && state.treasury != null,
                            modifier = Modifier.weight(1f).height(44.dp)) {
                            Text(stringResource(if (agent.running) R.string.home_agent_running else R.string.home_agent_run), maxLines = 1)
                        }
                        OutlinedButton(onClick = { vm.autopilot.setEnabled(false); stopOpen = true }, enabled = autopilot.enabled || (module != null && !module.paused),
                            modifier = Modifier.height(44.dp)) {
                            Text(stringResource(R.string.home_agent_stop), color = FusionColors.Red, maxLines = 1)
                        }
                    }
                    agent.error?.let { Text(it, style = FusionType.BodySmall, color = FusionColors.Red) }
                    stopHash?.let { TxLink(it) }
                }
            }
            item { TextButton(onClick = { moreOpen = !moreOpen }) { Text(if (moreOpen) "Hide treasury details" else "Charts, vaults and upcoming bills") } }
            if (moreOpen) {
            if (state.vaults.isEmpty()) item {
                ChartTile(Modifier.fillMaxWidth()) {
                    Text("Vault positions", style = FusionType.HeadingSmall)
                    Text("Vault balances unavailable until the chain snapshot loads.", style = FusionType.BodySmall)
                }
            } else state.vaults.forEach { vault -> item(key = "vault:${vault.id}") { VaultTile(vault) } }
            item { NextBillTile(state, now / 1000) }
            }
            item {
                BentoTile(Modifier.fillMaxWidth(), onClick = { askOpen = true }) {
                    Text(stringResource(R.string.tr_ask_title), style = FusionType.HeadingSmall)
                    Text(stringResource(R.string.tr_ask_hint), style = FusionType.BodySmall)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    if (receiveOpen) state.treasury?.let { ReceiveSheet(it.safeAddress, onDismiss = { receiveOpen = false }) }
    if (sendOpen) OwnerSendSheet(state, onSwap = { sendOpen = false; swapOpen = true }, onDismiss = { sendOpen = false })
    if (swapOpen) OwnerSwapSheet(state.treasury?.safeAddress, onDismiss = { swapOpen = false })
    if (previewOpen) EarnPreviewSheet(agent, state, txIndex, onDismiss = { previewOpen = false })
    if (askOpen) {
        val accountant: AccountantViewModel = viewModel()
        AskAccountantSheet(vm = accountant, wallet = state, onDismiss = { askOpen = false })
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
private fun TreasuryHero(state: WalletState, now: Long, onReceive: () -> Unit, onEarn: () -> Unit, onSend: () -> Unit, onSwap: () -> Unit, running: Boolean) {
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
        else ChartAmount(formatBigIntegerAmount(app.fusion.wallet.ui.motion.refreshAmount(total)), state.treasury?.symbol ?: "USDC")
        state.buckets?.let { buckets ->
            val covered = buckets.shortfall.raw.signum() == 0
            // A refresh first publishes a quick read with the bill check unset. The hero keeps the last verified
            // result meanwhile (the repository never reuses it), so it does not flicker; "Checking" only when it never had one.
            var lastVerified by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
            state.accountingMatches?.let { lastVerified = it }
            val shown = state.accountingMatches ?: lastVerified
            val ok = covered && shown == true
            val checking = shown == null
            val tone = if (ok) FusionChart.Cash else FusionChart.Reserved
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                androidx.compose.material3.Icon(
                    if (ok) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Filled.Warning,
                    contentDescription = null, tint = tone, modifier = Modifier.padding(end = 8.dp).size(22.dp))
                Text(if (checking) "Checking bill coverage…" else if (shown != true) "Bill coverage unverified" else if (covered) "Your bills are covered" else "${formatBigIntegerAmount(buckets.shortfall.raw)} USDC bill shortfall",
                    style = FusionChart.Status, color = tone)
            }
        }
        Text("What is left after bills and the safety cushion. Your AI agent can only use this.",
            style = FusionType.BodyMedium, color = FusionChart.Muted, modifier = Modifier.padding(top = 10.dp))
        // Four equal, single-line actions; compact labels so nothing wraps.
        val tight = PaddingValues(horizontal = 2.dp)
        val label = FusionType.BodyMedium.copy(fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onSend, enabled = state.treasury != null && state.source == DataSource.LIVE,
                contentPadding = tight, modifier = Modifier.weight(1f).height(44.dp)) { Text("Send", style = label, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = onSwap, enabled = state.treasury != null,
                contentPadding = tight, modifier = Modifier.weight(1f).height(44.dp)) { Text("Swap", style = label, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = onReceive, enabled = state.treasury != null,
                contentPadding = tight, modifier = Modifier.weight(1f).height(44.dp)) { Text("Receive", style = label, maxLines = 1, softWrap = false) }
            Button(onClick = onEarn, enabled = !running && state.treasury != null,
                contentPadding = tight, modifier = Modifier.weight(1f).height(44.dp)) { Text("Earn", style = label, maxLines = 1, softWrap = false) }
        }
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
            append("\nVault address · ${vault.address}")
        })
        ChartAmount(formatBigIntegerAmount(app.fusion.wallet.ui.motion.refreshAmount(vault.assets.raw)), large = false)
        Text("Available anytime: ${formatBigIntegerAmount(vault.maxWithdraw.raw)} USDC", style = FusionType.BodySmall, modifier = Modifier.padding(top = 8.dp))
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

/** Home chip into the proof screen: the count is the recorded tx index, not a live guess. */
@Composable
private fun ProofChip(txIndex: TxIndex, onOpenProof: () -> Unit) {
    BentoTile(Modifier.fillMaxWidth(), onClick = onOpenProof, tint = FusionColors.Green) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Verified, contentDescription = null, tint = FusionColors.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.prf_chip), style = FusionType.BodyMedium, fontWeight = FontWeight.Medium)
        }
        val indexed = txIndex.all().size
        Text(
            text = if (indexed > 0) stringResource(R.string.prf_chip_count, indexed.toString())
            else stringResource(R.string.prf_chip_hint),
            style = FusionType.BodySmall, color = FusionColors.TextMuted,
        )
    }
}
