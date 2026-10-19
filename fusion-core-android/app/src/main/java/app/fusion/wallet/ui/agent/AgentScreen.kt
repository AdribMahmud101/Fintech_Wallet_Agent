package app.fusion.wallet.ui.agent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.data.ActionKind
import app.fusion.wallet.data.AgentAction
import app.fusion.wallet.data.DataSource
import app.fusion.wallet.data.LoadStatus
import app.fusion.wallet.data.Money
import app.fusion.wallet.data.Policy
import app.fusion.wallet.data.QueueStatus
import app.fusion.wallet.data.QueuedAction
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.Fmt
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.charts.RingGauge
import app.fusion.wallet.ui.common.BentoGap
import app.fusion.wallet.ui.common.BentoRow
import app.fusion.wallet.ui.common.BentoTile
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Expander
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.common.StatTile
import app.fusion.wallet.ui.common.TileLabel
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.shorten
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Agent Screen: renders purely from real [WalletState].
 * Features:
 * - Feed of agent actions as decoded cards (kind, amount, payee label, tier, status, decisionHash short form, BaseScan link)
 * - Queued actions with live countdown to readyAt
 * - Big Stop button (guardian pause) with confirmation dialog (shows "not wired" if ViewModel has no callback)
 * - Loading, empty, and error states
 * - DEMO badge if data is from DEMO source
 */
@Composable
fun AgentScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nowSeconds by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis() / 1000
        }
    }

    var showStopDialog by rememberSaveable { mutableStateOf(false) }
    var stopNotWiredNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var userDismissedError by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BentoGap),
    ) {
        // Header
        item {
            ScreenHeader(
                title = stringResource(R.string.ag_title),
                subtitle = stringResource(R.string.ag_subtitle),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (state.source == DataSource.DEMO) {
                            StatusPill(label = stringResource(R.string.badge_demo), tone = Tone.Earning)
                        }
                        IconButton(
                            onClick = { vm.refresh() },
                            modifier = Modifier.size(36.dp),
                        ) {
                            if (state.refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.refresh_button),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                },
            )
        }

        // RPC Error Notice
        if (state.status == LoadStatus.ERROR && !userDismissedError) {
            val errorMsg = state.error ?: "Can't reach the network"
            item {
                Notice(
                    text = errorMsg,
                    onDismiss = { userDismissedError = true },
                )
            }
        }

        // Stop Action Notice (when Guardian pause is triggered)
        stopNotWiredNotice?.let { noticeText ->
            item {
                Notice(
                    text = noticeText,
                    onDismiss = { stopNotWiredNotice = null },
                )
            }
        }

        // Big Stop Button (Guardian Pause)
        item {
            StopButtonCard(
                isPaused = state.module?.paused == true,
                onStopClick = { showStopDialog = true },
            )
        }

        // Policy & Daily Velocity Summary (if module state available)
        state.module?.let { mod ->
            item {
                PolicySummaryTile(mod.policy, mod.dailyUsed, mod.dailyRemaining, mod.paused)
            }
        }

        // Pinned Queue Panel
        item {
            QueuePanel(state.queue, nowSeconds, state.payees)
        }

        // Feed Header
        item {
            Text(
                text = stringResource(R.string.ag_recent_actions_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Actions Feed: Decoded Cards
        if (state.status == LoadStatus.LOADING && state.recentActions.isEmpty() && state.queue.isEmpty()) {
            item {
                BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.tr_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Muted,
                        )
                    }
                }
            }
        } else if (state.recentActions.isEmpty()) {
            item {
                BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Text(
                        stringResource(R.string.ag_recent_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
            }
        } else {
            items(state.recentActions, key = { it.id }) { action ->
                DecodedActionCard(
                    action = action,
                    policy = state.module?.policy,
                    state = state,
                )
            }
        }
    }

    // Stop Confirmation Dialog
    if (showStopDialog) {
        val notWiredMessage = stringResource(R.string.ag_stop_not_wired)
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.ag_stop_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.ag_stop_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopDialog = false
                        // Check if ViewModel exposes a pause callback; K4 has not provided one
                        stopNotWiredNotice = notWiredMessage
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Tone.Bad),
                ) {
                    Text(stringResource(R.string.ag_stop_confirm_action), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text(stringResource(R.string.ag_stop_cancel_action))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

// ---- Big Stop Button --------------------------------------------------------------------------

@Composable
private fun StopButtonCard(
    isPaused: Boolean,
    onStopClick: () -> Unit,
) {
    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 1,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        label = if (isPaused) stringResource(R.string.ag_status_PAUSED) else stringResource(R.string.ag_status_ARMED),
                        tone = if (isPaused) Tone.Warn else Tone.Good,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Guardian Pause",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Emergency circuit-breaker: stop all agent activity immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

            Button(
                onClick = onStopClick,
                colors = ButtonDefaults.buttonColors(containerColor = Tone.Bad),
                shape = RoundedCornerShape(FusionDims.radiusSmall),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.Filled.Block,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ag_stop_button),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

// ---- Policy Summary Tile ----------------------------------------------------------------------

@Composable
private fun PolicySummaryTile(
    policy: Policy,
    dailyUsed: Money,
    dailyRemaining: Money,
    isPaused: Boolean,
) {
    var rulesOpen by rememberSaveable { mutableStateOf(false) }

    BentoTile(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                TileLabel("POLICY & LIMITS")
                Spacer(Modifier.height(2.dp))
                Text(
                    "${dailyUsed.display} / ${policy.dailyLimit.display} USDC",
                    style = MonoStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Remaining: ${dailyRemaining.display} USDC",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            StatusPill(
                label = policy.permissionTier.name,
                tone = Tone.Earning,
            )
        }

        Expander(
            label = stringResource(if (rulesOpen) R.string.ag_rules_hide else R.string.ag_rules_show),
            expanded = rulesOpen,
            onToggle = { rulesOpen = !rulesOpen },
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "• Micro / Autonomous limit: ${policy.microMax.display} USDC",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    "• Delayed queue limit: ${policy.delayedMax.display} USDC (delay: ${policy.delaySeconds}s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    "• Hard maximum: ${policy.hardMax.display} USDC",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    "• Operating buffer target: ${policy.operatingBuffer.display} USDC",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    "• Guardian address: ${shorten(policy.guardian)}",
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = Muted,
                )
            }
        }
    }
}

// ---- Queue Panel ------------------------------------------------------------------------------

@Composable
private fun QueuePanel(
    queue: List<QueuedAction>,
    nowSeconds: Long,
    payees: List<app.fusion.wallet.data.Payee>,
) {
    BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
        CardTitle(
            stringResource(R.string.ag_queue_title),
            trailing = if (queue.isNotEmpty()) "${queue.size} waiting" else null,
        )

        if (queue.isEmpty()) {
            Text(
                stringResource(R.string.ag_queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                queue.forEach { queued ->
                    QueuedActionItem(queued, nowSeconds, payees)
                }
            }
        }
    }
}

@Composable
private fun QueuedActionItem(
    action: QueuedAction,
    nowSeconds: Long,
    payees: List<app.fusion.wallet.data.Payee>,
) {
    val remainingSeconds = action.readyAt - nowSeconds
    val isReady = remainingSeconds <= 0
    val isExpired = action.status == QueueStatus.EXPIRED || (action.expiresAt in 1..nowSeconds)

    val payeeObj = payees.find { it.address.equals(action.payee, ignoreCase = true) || it.id == action.payee }
    val payeeLabel = payeeObj?.label ?: shorten(action.payee)

    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 2,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.HourglassTop,
                        contentDescription = null,
                        tint = if (isReady) Tone.Good else Tone.Warn,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${action.kind.name} · ${action.amount.display} USDC",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                StatusPill(
                    label = when {
                        isExpired -> stringResource(R.string.ag_expired)
                        isReady -> stringResource(R.string.ag_ready_now)
                        else -> stringResource(R.string.ag_ready_in, Fmt.duration(remainingSeconds))
                    },
                    tone = when {
                        isExpired -> Tone.Bad
                        isReady -> Tone.Good
                        else -> Tone.Warn
                    },
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "To: $payeeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    text = "Nonce: ${action.expectedNonce}",
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = Muted,
                )
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = stringResource(R.string.ag_decision_hash, shorten(action.decisionHash)),
                style = MonoStyle.copy(fontSize = 11.sp),
                color = Muted,
            )
        }
    }
}

// ---- Decoded Action Card ----------------------------------------------------------------------

@Composable
private fun DecodedActionCard(
    action: AgentAction,
    policy: Policy?,
    state: WalletState,
) {
    val context = LocalContext.current
    val payeeObj = state.payees.find { it.address.equals(action.payee, ignoreCase = true) || it.id == action.payee }
    val payeeLabel = payeeObj?.label ?: action.payee?.let { shorten(it) } ?: "Contract"

    val tierLabel = resolveTierLabel(action.amount, policy)
    val timeStr = remember(action.blockTime) {
        if (action.blockTime > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(action.blockTime * 1000))
        } else ""
    }

    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 1,
    ) {
        Column(Modifier.padding(14.dp)) {
            // Top Row: Kind + Amount + Tier Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(FusionTheme.surfaces.level2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = null,
                            tint = Tone.Earning,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "${action.kind.name} ${action.amount.display} USDC",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "To: $payeeLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }

                StatusPill(
                    label = tierLabel,
                    tone = Tone.Earning,
                )
            }

            Spacer(Modifier.height(10.dp))

            // Middle Row: Status + Block + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Tone.Good,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Confirmed · Block #${action.blockNumber}",
                        style = MonoStyle.copy(fontSize = 12.sp),
                        color = Tone.Good,
                    )
                }

                if (timeStr.isNotEmpty()) {
                    Text(
                        text = timeStr,
                        style = MonoStyle.copy(fontSize = 12.sp),
                        color = Muted,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Bottom Row: decisionHash + BaseScan Link
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ag_decision_hash, shorten(action.decisionHash)),
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = Muted,
                )

                // View on BaseScan clickable link
                Row(
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            val url = "https://sepolia.basescan.org/tx/${action.txHash}"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.view_on_basescan),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Accessible.AccentText,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.view_on_basescan),
                        tint = Accessible.AccentText,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun resolveTierLabel(amount: Money, policy: Policy?): String {
    if (policy == null) return stringResource(R.string.ag_tier_autonomous)
    return when {
        amount.raw <= policy.microMax.raw -> stringResource(R.string.ag_tier_autonomous)
        amount.raw <= policy.delayedMax.raw -> stringResource(R.string.ag_tier_delayed)
        else -> stringResource(R.string.ag_tier_owner)
    }
}
