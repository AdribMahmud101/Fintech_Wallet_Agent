package app.fusion.wallet.ui.bills

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.data.DataSource
import app.fusion.wallet.data.LoadStatus
import app.fusion.wallet.data.Obligation
import app.fusion.wallet.data.Payee
import app.fusion.wallet.data.PayeeStatus
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.Fmt
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.common.BentoGap
import app.fusion.wallet.ui.common.BentoRow
import app.fusion.wallet.ui.common.BentoTile
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
import app.fusion.wallet.ui.common.StatTile
import app.fusion.wallet.ui.common.Tone
import app.fusion.wallet.ui.common.vendorName
import app.fusion.wallet.data.AgentAction
import app.fusion.wallet.data.QueuedAction
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.shorten
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Bills & Vendors Screen:
 * - Payees with ACTIVE / COOLING badge and live cooling countdown
 * - "New bank details" payee shown as COOLING with the warning copy from §17 (BEC prevention)
 * - Obligations / Bills breakdown (Due soon, Scheduled, Paid)
 * - Loading, empty, and error states
 * - Fully localized EN / 中文 and accessible
 */
@Composable
fun BillsScreen(
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

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = Vendors, 1 = Bills
    var userDismissedError by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BentoGap),
    ) {
        // Header
        item {
            ScreenHeader(
                title = stringResource(R.string.bv_title),
                subtitle = stringResource(R.string.bv_subtitle),
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

        // Error Banner
        if (state.status == LoadStatus.ERROR && !userDismissedError) {
            val errorMsg = state.error ?: "Can't reach the network"
            item {
                Notice(
                    text = errorMsg,
                    onDismiss = { userDismissedError = true },
                )
            }
        }

        // Summary Bento Row
        item {
            BentoRow {
                StatTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = "Total Owed",
                    value = "${state.buckets?.owed?.let { Fmt.usdc(it) } ?: "—"} USDC",
                    color = Tone.Bills,
                )
                StatTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = "Active / Cooling",
                    value = "${state.payees.count { it.status == PayeeStatus.ACTIVE }} / ${state.payees.count { it.status == PayeeStatus.COOLING }}",
                    color = if (state.payees.any { it.status == PayeeStatus.COOLING }) Tone.Warn else Tone.Good,
                )
            }
        }

        // Sub-Tab Row
        item {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = FusionTheme.surfaces.canvas,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "${stringResource(R.string.bv_tab_payees)} (${state.payees.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "${stringResource(R.string.bv_tab_bills)} (${state.obligations.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        // Loading State
        if (state.status == LoadStatus.LOADING && state.payees.isEmpty() && state.obligations.isEmpty()) {
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
        } else if (selectedTab == 0) {
            // Vendors List
            if (state.payees.isEmpty()) {
                item {
                    BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
                        Text(
                            stringResource(R.string.bv_empty_payees),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Muted,
                        )
                    }
                }
            } else {
                items(state.payees, key = { it.id }) { payee ->
                    PayeeCard(payee, nowSeconds)
                }
            }
        } else {
            // Bills List
            if (state.obligations.isEmpty()) {
                item {
                    val isMismatch = state.accountingMatches == false && state.module != null
                    BentoTile(Modifier.fillMaxWidth(), tint = if (isMismatch) Tone.Warn else null, padding = 16.dp) {
                        Text(
                            text = if (isMismatch) {
                                stringResource(R.string.accounting_mismatch_unavailable, shorten(state.module!!.obligationsRoot))
                            } else {
                                stringResource(R.string.bv_empty_bills)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isMismatch) Tone.Warn else Muted,
                        )
                        if (isMismatch) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Reserved on-chain: ${Fmt.usdc(state.module!!.reserved)} USDC (${state.module!!.obligationCount} bills)",
                                style = MonoStyle.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            } else {
                items(state.obligations, key = { it.id }) { obligation ->
                    ObligationCard(obligation, state.payees, state.queue, state.recentActions, nowSeconds)
                }
            }
        }
    }
}

// ---- Payee Card -------------------------------------------------------------------------------

@Composable
private fun PayeeCard(
    payee: Payee,
    nowSeconds: Long,
) {
    val context = LocalContext.current
    val isCooling = payee.status == PayeeStatus.COOLING
    val remaining = payee.activeAt - nowSeconds
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val activeAtStr = remember(payee.activeAt) {
        if (payee.activeAt > 0) dateFormat.format(Date(payee.activeAt * 1000)) else ""
    }

    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = if (isCooling) 2 else 1,
    ) {
        Column(Modifier.padding(14.dp)) {
            // Top Row: Label + Status Badge
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
                            if (isCooling) Icons.Filled.HourglassTop else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = if (isCooling) Tone.Warn else Tone.Good,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = vendorName(idHash = payee.id, address = payee.address),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = shorten(payee.address),
                            style = MonoStyle.copy(fontSize = 12.sp),
                            color = Muted,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (payee.sanctioned) {
                        StatusPill(
                            label = stringResource(R.string.bv_payee_sanctioned),
                            tone = Tone.Bad,
                        )
                    }
                    StatusPill(
                        label = when (payee.status) {
                            PayeeStatus.ACTIVE -> stringResource(R.string.bv_payee_active)
                            PayeeStatus.COOLING -> stringResource(R.string.bv_payee_cooling)
                            PayeeStatus.CANCELLED -> stringResource(R.string.bv_payee_cancelled)
                        },
                        tone = when (payee.status) {
                            PayeeStatus.ACTIVE -> Tone.Good
                            PayeeStatus.COOLING -> Tone.Warn
                            PayeeStatus.CANCELLED -> Tone.Bad
                        },
                    )
                }
            }

            // Cooling countdown & §17 Warning Copy
            if (isCooling) {
                Spacer(Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FusionDims.radiusSmall))
                        .background(Tone.Warn.copy(alpha = 0.12f))
                        .border(FusionDims.hairline, Tone.Warn.copy(alpha = 0.4f), RoundedCornerShape(FusionDims.radiusSmall))
                        .padding(10.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = Tone.Warn,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (remaining > 0) {
                                    stringResource(R.string.bv_payee_cooling_countdown, Fmt.duration(remaining))
                                } else {
                                    "Cooling completed · pending chain activation"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Tone.Warn,
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        // Warning copy from §17
                        Text(
                            text = stringResource(R.string.bv_bec_warning, activeAtStr),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Footer: Explorer link + Label Hash
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ID: ${payee.id.take(8)}…",
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = Muted,
                )

                Row(
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            val url = "https://sepolia.basescan.org/address/${payee.address}"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.explorer_link),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = Accessible.AccentText,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = null,
                        tint = Accessible.AccentText,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
    }
}

// ---- Obligation Card --------------------------------------------------------------------------

@Composable
private fun ObligationCard(
    obligation: Obligation,
    payees: List<Payee>,
    queue: List<QueuedAction>,
    actions: List<AgentAction>,
    nowSeconds: Long,
) {
    val context = LocalContext.current
    val payeeObj = payees.find { it.id == obligation.payeeId || it.address.equals(obligation.payeeId, ignoreCase = true) }
    val vendorLabel = vendorName(idHash = obligation.payeeId, address = payeeObj?.address)
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dueDateStr = remember(obligation.dueAt) { dateFormat.format(Date(obligation.dueAt * 1000)) }
    val isOverdue = !obligation.paid && nowSeconds > obligation.dueAt

    // A matching on-chain payment gives us the receipt link; a queued action gives us Queued status.
    val payeeAddr = payeeObj?.address
    val payTx = if (obligation.paid && payeeAddr != null) {
        actions.firstOrNull {
            it.kind == app.fusion.wallet.data.ActionKind.PAY_OBLIGATION &&
                it.amount.raw == obligation.amount.raw &&
                it.payee?.equals(payeeAddr, ignoreCase = true) == true
        }?.txHash
    } else null
    val isQueued = !obligation.paid && payeeAddr != null && queue.any {
        it.kind == app.fusion.wallet.data.ActionKind.PAY_OBLIGATION &&
            it.payee.equals(payeeAddr, ignoreCase = true)
    }

    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 1,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(FusionTheme.surfaces.level2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ReceiptLong,
                        contentDescription = null,
                        tint = if (obligation.paid) Tone.Good else Tone.Bills,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = vendorLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = "${Fmt.usdc(obligation.amount)} USDC",
                        style = MonoStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.bv_bill_due, dueDateStr),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = if (isOverdue) Tone.Bad else Muted,
                    )
                    if (payTx != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .clickable(role = Role.Button) {
                                    val url = "https://sepolia.basescan.org/tx/$payTx"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.view_on_basescan),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = Accessible.AccentText,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                Icons.Filled.OpenInNew,
                                contentDescription = null,
                                tint = Accessible.AccentText,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }

            StatusPill(
                label = when {
                    obligation.paid -> stringResource(R.string.bv_bill_paid)
                    isOverdue -> stringResource(R.string.bv_bill_overdue)
                    isQueued -> stringResource(R.string.bv_bill_queued)
                    else -> stringResource(R.string.bv_bill_scheduled)
                },
                tone = when {
                    obligation.paid -> Tone.Good
                    isOverdue -> Tone.Bad
                    isQueued -> Tone.Earning
                    else -> Tone.Warn
                },
            )
        }
    }
}
