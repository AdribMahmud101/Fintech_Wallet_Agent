package app.fusion.wallet.ui.proof

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.data.DataSource
import app.fusion.wallet.data.EvidenceRow
import app.fusion.wallet.data.LoadStatus
import app.fusion.wallet.data.WalletState
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

/**
 * Safety Screen:
 * - Attacks blocked, dynamically generated from [WalletState.evidence] rows of kind INTENTIONAL_REVERT
 * - Dynamic counts generated from live evidence without hardcoding
 * - Each blocked attack row has its explorer transaction link
 * - Emergency Unwind card (§17.10) with confirmation dialog
 * - Loading, empty, and error states
 */
@Composable
fun ProofScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showUnwindDialog by rememberSaveable { mutableStateOf(false) }
    var unwindNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var userDismissedError by rememberSaveable { mutableStateOf(false) }

    // Filter evidence rows for blocked attacks (INTENTIONAL_REVERT or containing REVERT)
    val blockedAttacks = remember(state.evidence) {
        state.evidence.filter {
            it.kind.equals("INTENTIONAL_REVERT", ignoreCase = true) ||
                it.kind.contains("REVERT", ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BentoGap),
    ) {
        // Header
        item {
            ScreenHeader(
                title = stringResource(R.string.safe_title),
                subtitle = stringResource(R.string.safe_subtitle),
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

        // Unwind Action Notice
        unwindNotice?.let { noticeText ->
            item {
                Notice(
                    text = noticeText,
                    onDismiss = { unwindNotice = null },
                )
            }
        }

        // Headline Tile (Dynamic Count from Real Evidence)
        item {
            SafetyHeadlineTile(blockedAttacks.size, state.evidence.size)
        }

        // Honest Scorecard: Measured vs Not Measured (§17.10)
        item {
            BentoRow {
                StatTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = stringResource(R.string.pf_asr),
                    value = if (blockedAttacks.isEmpty()) "0 observed" else "0/${blockedAttacks.size}",
                    color = Tone.Good,
                )
                StatTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = stringResource(R.string.pf_fbr),
                    value = stringResource(R.string.pf_not_measured),
                    color = Muted,
                )
                StatTile(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = "Blocked",
                    value = blockedAttacks.size.toString(),
                    color = if (blockedAttacks.isNotEmpty()) Tone.Good else Muted,
                )
            }
        }

        // Emergency Unwind Card (§17.10)
        item {
            EmergencyUnwindCard(onTrigger = { showUnwindDialog = true })
        }

        // Blocked Attack Attempts Header
        item {
            Text(
                text = stringResource(R.string.safe_revert_evidence_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Loading State
        if (state.status == LoadStatus.LOADING && state.evidence.isEmpty()) {
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
        } else if (blockedAttacks.isEmpty()) {
            // Empty State
            item {
                BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Text(
                        stringResource(R.string.safe_no_reverts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            }
        } else {
            // List of blocked attack cards with transaction links
            items(blockedAttacks, key = { it.id }) { row ->
                BlockedAttackCard(row)
            }
        }
    }

    // Emergency Unwind Confirmation Dialog
    if (showUnwindDialog) {
        val notWiredMessage = stringResource(R.string.safe_emergency_unwind_not_wired)
        AlertDialog(
            onDismissRequest = { showUnwindDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.safe_emergency_unwind_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.safe_emergency_unwind_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnwindDialog = false
                        unwindNotice = notWiredMessage
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Tone.Bad),
                ) {
                    Text(stringResource(R.string.safe_emergency_unwind_btn), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnwindDialog = false }) {
                    Text(stringResource(R.string.ag_stop_cancel_action))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

// ---- Headline Tile ----------------------------------------------------------------------------

@Composable
private fun SafetyHeadlineTile(blockedCount: Int, totalEvidenceCount: Int) {
    val hasBlocked = blockedCount > 0
    val tone = if (hasBlocked) Tone.Good else Tone.Earning

    BentoTile(
        modifier = Modifier.fillMaxWidth(),
        tint = tone,
        padding = 18.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = if (hasBlocked) {
                        stringResource(R.string.safe_blocked_count, blockedCount.toString())
                    } else {
                        "Guards Armed · $totalEvidenceCount logs"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (hasBlocked) {
                        "Enforced by smart contract reverts on Base Sepolia"
                    } else {
                        "Active contract assertions on Base Sepolia"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

// ---- Blocked Attack Card ----------------------------------------------------------------------

@Composable
private fun BlockedAttackCard(row: EvidenceRow) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val timeStr = remember(row.blockTime) {
        if (row.blockTime > 0) dateFormat.format(Date(row.blockTime * 1000)) else ""
    }

    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 1,
    ) {
        Column(Modifier.padding(14.dp)) {
            // Top Row: ID + Kind Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Tone.Good.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Tone.Good,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Blocked Attack: ${row.id}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Block #${row.blockNumber}${if (timeStr.isNotEmpty()) " · $timeStr" else ""}",
                            style = MonoStyle.copy(fontSize = 12.sp),
                            color = Muted,
                        )
                    }
                }

                StatusPill(
                    label = "REVERTED",
                    tone = Tone.Good,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Reason: ${row.kind}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Tone.Good,
            )

            row.decisionHash?.let { hash ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Decision: ${shorten(hash)}",
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = Muted,
                )
            }

            Spacer(Modifier.height(6.dp))

            // Transaction Link
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button) {
                        val url = row.explorerUrl.ifEmpty { "https://sepolia.basescan.org/tx/${row.txHash}" }
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

// ---- Emergency Unwind Card --------------------------------------------------------------------

@Composable
private fun EmergencyUnwindCard(onTrigger: () -> Unit) {
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
                Text(
                    stringResource(R.string.safe_emergency_unwind_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.safe_emergency_unwind_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

            OutlinedButton(
                onClick = onTrigger,
                shape = RoundedCornerShape(FusionDims.radiusSmall),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    stringResource(R.string.safe_emergency_unwind_btn),
                    color = Tone.Bad,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
