package app.fusion.wallet.ui.judge

import app.fusion.wallet.ui.common.startActivitySafe
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
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
import app.fusion.wallet.ui.common.BentoTile
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Expander
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.common.Notice
import app.fusion.wallet.ui.common.ScreenHeader
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
 * Judge Mode Screen:
 * - A list of every evidence row (id, what it proves, tx link, block) — this is what a judge taps through.
 * - Contract verification panel with BaseScan explorer links.
 * - Honest disclosures section (§21.2 "What We Don't Claim").
 * - Loading, empty, and error states.
 */
@Composable
fun JudgeScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel = viewModel(),
    onBack: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var userDismissedError by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BentoGap),
    ) {
        // Screen Header
        item {
            ScreenHeader(
                title = stringResource(R.string.judge_title),
                subtitle = stringResource(R.string.judge_subtitle),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (state.source == DataSource.DEMO) {
                            StatusPill(label = stringResource(R.string.badge_demo), tone = Tone.Earning)
                        }
                        IconButton(
                            onClick = { vm.refresh() },
                            modifier = Modifier.size(48.dp),
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
                        if (onBack != null) {
                            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
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

        // Contract Verification Panel
        item {
            ContractVerificationPanel(state)
        }

        // Honest Disclosures (§21.2)
        item {
            HonestDisclosuresCard()
        }

        // Evidence Ledger Title
        item {
            Text(
                text = stringResource(R.string.judge_evidence_title, state.evidence.size.toString()),
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
        } else if (state.evidence.isEmpty()) {
            // Empty State
            item {
                BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
                    Text(
                        stringResource(R.string.judge_evidence_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
            }
        } else {
            // Full Evidence Rows List
            items(state.evidence, key = { it.id }) { row ->
                EvidenceRowCard(row)
            }
        }
    }
}

// ---- Contract Verification Panel --------------------------------------------------------------

@Composable
private fun ContractVerificationPanel(state: WalletState) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    // Official pinned Base Sepolia addresses from deployments/84532.json
    val safeAddr = state.treasury?.safeAddress ?: "0x029869910714D9e09b854a2688311AFd42fBb44E"
    val moduleAddr = state.module?.address ?: "0xA560ec10259657E9bADd297E68286d2838Cd5a8f"
    val vaultA = "0xf430cb6E2b85f99222fBFA6dFEa18Ff60FA6B32a"
    val vaultB = "0xf052C0ED8404adEaAEA773A5F8Ae4628134aff6D"
    val assetAddr = state.treasury?.assetAddress ?: "0xba50Cd2A20f6DA35D788639E581bca8d0B5d4D5f"
    val sanctionsAddr = "0xd2716e08BCe6eb5B1f01329FF5E1a2dd0468B678"

    BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
        CardTitle(
            stringResource(R.string.judge_verify_title),
            trailing = "Chain #84532",
        )

        Expander(
            label = if (expanded) "Hide Contract Addresses" else "Show Verified Contracts (6)",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ContractAddressRow(stringResource(R.string.judge_safe_label), safeAddr)
                ContractAddressRow(stringResource(R.string.judge_module_label), moduleAddr)
                ContractAddressRow(stringResource(R.string.judge_vault_a_label), vaultA)
                ContractAddressRow(stringResource(R.string.judge_vault_b_label), vaultB)
                ContractAddressRow(stringResource(R.string.judge_asset_label), assetAddr)
                ContractAddressRow(stringResource(R.string.judge_sanctions_label), sanctionsAddr)
            }
        }
    }
}

@Composable
private fun ContractAddressRow(label: String, address: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 2,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    text = address,
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(address)) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy $label address",
                        tint = Muted,
                        modifier = Modifier.size(16.dp),
                    )
                }

                IconButton(
                    onClick = {
                        val url = "https://sepolia.basescan.org/address/$address"
                        context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "View on BaseScan",
                        tint = Accessible.AccentText,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

// ---- Evidence Row Card ------------------------------------------------------------------------

@Composable
private fun EvidenceRowCard(row: EvidenceRow) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(row.blockTime) {
        if (row.blockTime > 0) dateFormat.format(Date(row.blockTime * 1000)) else "—"
    }

    val whatItProves = remember(row.kind) {
        when {
            row.kind.contains("REVERT", ignoreCase = true) ->
                "Proves on-chain enforcement of smart contract policy invariant (reverted transaction)"
            row.kind.contains("ActionExecuted", ignoreCase = true) ->
                "Proves valid agent action execution authorized by policy parameters and signed mandate"
            row.kind.contains("ObligationPaid", ignoreCase = true) ->
                "Proves payment settlement to verified payee matching on-chain obligation commitment"
            row.kind.contains("PayeeAdded", ignoreCase = true) ->
                "Proves vendor registration and mandatory cooling period delay enforcement"
            row.kind.contains("PolicyConfigured", ignoreCase = true) ->
                "Proves multi-sig owner configuration of spending tiers and daily limits"
            row.kind.contains("SessionKeyRegistered", ignoreCase = true) ->
                "Proves bounded ephemeral session key delegation"
            else ->
                "Proves on-chain emission of event '${row.kind}' anchored at block #${row.blockNumber}"
        }
    }

    val isRevert = row.kind.contains("REVERT", ignoreCase = true)

    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 1,
    ) {
        Column(Modifier.padding(14.dp)) {
            // Header: ID + Kind
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(FusionTheme.surfaces.level2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isRevert) Icons.Filled.VerifiedUser else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = if (isRevert) Tone.Warn else Tone.Good,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.judge_evidence_id, row.id),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                StatusPill(
                    label = row.kind,
                    tone = if (isRevert) Tone.Warn else Tone.Earning,
                )
            }

            Spacer(Modifier.height(8.dp))

            // What it proves
            Text(
                text = stringResource(R.string.judge_proves, whatItProves),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(6.dp))

            // Block & Time
            Text(
                text = stringResource(R.string.judge_block_info, row.blockNumber.toString(), timeStr),
                style = MonoStyle.copy(fontSize = 12.sp),
                color = Muted,
            )

            // Decision Hash
            row.decisionHash?.let { hash ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Decision Hash: ${shorten(hash)}",
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = Muted,
                )
            }

            Spacer(Modifier.height(6.dp))

            // BaseScan Link
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) {
                        val url = row.explorerUrl.ifEmpty { "https://sepolia.basescan.org/tx/${row.txHash}" }
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivitySafe(intent)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tx: ${shorten(row.txHash)} · ${stringResource(R.string.view_on_basescan)}",
                    style = MonoStyle.copy(fontSize = 12.sp),
                    color = Accessible.AccentText,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = Accessible.AccentText,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

// ---- Honest Disclosures (§21.2) ---------------------------------------------------------------

@Composable
private fun HonestDisclosuresCard() {
    var open by rememberSaveable { mutableStateOf(false) }

    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 1,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Tone.Earning,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.judge_disclaimers_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Expander(
                label = if (open) "Hide Disclosures" else "Show Full Honest Disclosures",
                expanded = open,
                onToggle = { open = !open },
            ) {
                Text(
                    text = stringResource(R.string.judge_disclaimer_content),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
