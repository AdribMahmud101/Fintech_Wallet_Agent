package app.fusion.wallet.ui.treasury

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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.R
import app.fusion.wallet.data.DataSource
import app.fusion.wallet.data.LoadStatus
import app.fusion.wallet.data.Money
import app.fusion.wallet.data.VaultPosition
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.MainViewModel
import app.fusion.wallet.ui.accountant.AccountantViewModel
import app.fusion.wallet.ui.charts.SegmentBar
import app.fusion.wallet.ui.common.BentoGap
import app.fusion.wallet.ui.common.BentoRow
import app.fusion.wallet.ui.common.BentoTile
import app.fusion.wallet.ui.common.CardTitle
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

/**
 * Treasury (Home): renders purely from [WalletState] provided by [MainViewModel].
 * Features:
 * - Total balance
 * - The four buckets (reserve / owed / buffer / surplus) as a horizontal bar + breakdown
 * - Vault positions (Aave real waUSDC vs Demo vault SIMULATED yield)
 * - Network badge "Base Sepolia · TEST"
 * - Last-refresh block number
 * - Loading skeletons, empty, error, and accounting mismatch states
 */
@Composable
fun TreasuryScreen(
    modifier: Modifier = Modifier,
    vm: MainViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var askOpen by rememberSaveable { mutableStateOf(false) }
    var userDismissedError by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FusionDims.gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(BentoGap),
    ) {
        // Screen Header with Network Badge, Last-refresh Block, and Refresh button
        item {
            ScreenHeader(
                title = stringResource(R.string.tr_title),
                subtitle = stringResource(R.string.tr_subtitle),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (state.isDisplayStale() && state.blockNumber != null) {
                            StatusPill(
                                label = stringResource(R.string.chip_stale),
                                tone = Tone.Warn,
                            )
                        }
                        if (state.source == DataSource.DEMO) {
                            StatusPill(
                                label = stringResource(R.string.badge_demo),
                                tone = Tone.Earning,
                            )
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

        // Accounting Mismatch Warning
        if (state.accountingMatches == false) {
            item {
                ObsidianCard(level = 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Tone.Warn,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.module?.obligationsRoot?.let { root ->
                                stringResource(R.string.accounting_mismatch_unavailable, shorten(root))
                            } ?: stringResource(R.string.accounting_mismatch_warning, state.accountingError ?: "DISCREPANCY"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Tone.Warn,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
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

        // Loading State: skeletons, never zeros
        if (state.status == LoadStatus.LOADING && state.treasury == null && state.buckets == null) {
            item {
                BentoTile(Modifier.fillMaxWidth(), padding = 18.dp) {
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
        } else if (state.treasury == null && state.buckets == null) {
            // Empty State
            item {
                BentoTile(Modifier.fillMaxWidth(), padding = 18.dp) {
                    Text(
                        stringResource(R.string.tr_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Muted,
                    )
                }
            }
        } else {
            // Hero Tile: Total balance + Spare Cash + Block Number
            item {
                TreasuryHeroTile(state)
            }

            // The Four Buckets as a Bar
            item {
                FourBucketsTile(state)
            }

            // Vault Positions
            item {
                VaultPositionsTile(state.vaults)
            }

            // Ask the Accountant Card
            item {
                AskTile(onClick = { askOpen = true })
            }

            // Upcoming Obligations / Bills Preview
            if (state.obligations.isNotEmpty()) {
                item {
                    ObligationsSummaryTile(state)
                }
            }
        }
    }

    if (askOpen) {
        val accountantVm: AccountantViewModel = viewModel()
        AskAccountantSheet(vm = accountantVm, onDismiss = { askOpen = false })
    }
}

// ---- Hero Tile --------------------------------------------------------------------------------

@Composable
private fun TreasuryHeroTile(state: WalletState) {
    val buckets = state.buckets
    val treasury = state.treasury
    val totalBalance = buckets?.total ?: treasury?.balance ?: Money.ZERO
    val surplus = buckets?.surplus ?: Money.ZERO
    val shortfall = buckets?.shortfall ?: Money.ZERO
    val isShort = shortfall.raw > BigInteger.ZERO
    val blockText = state.blockNumber?.let { "#$it" } ?: "—"

    BentoTile(
        modifier = Modifier.fillMaxWidth(),
        tint = if (isShort) Tone.Bad else Tone.Good,
        padding = 18.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.tr_hero_total),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
            Text(
                stringResource(R.string.tr_last_refresh_block, blockText),
                style = MonoStyle,
                color = Muted,
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = totalBalance.display,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = " USDC",
                style = MaterialTheme.typography.titleMedium,
                color = Muted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        // Spare cash indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    stringResource(R.string.tr_safe_to_deploy),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(
                    text = "${surplus.display} USDC",
                    style = MonoStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    color = Tone.Cash,
                )
            }
            treasury?.safeAddress?.let { addr ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Safe",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    Text(
                        text = shorten(addr),
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Health Status Line
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isShort) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (isShort) Tone.Bad else Tone.Good,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isShort) {
                    stringResource(R.string.tr_status_short, shortfall.display, "USDC")
                } else {
                    stringResource(R.string.tr_status_ok)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isShort) Tone.Bad else Tone.Good,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ---- The Four Buckets as a Bar ----------------------------------------------------------------

@Composable
private fun FourBucketsTile(state: WalletState) {
    val b = state.buckets ?: return
    val reserveRaw = b.reserve.raw.toFloat()
    val owedRaw = b.owed.raw.toFloat()
    val bufferRaw = b.buffer.raw.toFloat()
    val surplusRaw = b.surplus.raw.toFloat()
    val shortfallRaw = b.shortfall.raw.toFloat()

    val values = if (shortfallRaw > 0f) {
        listOf(reserveRaw, shortfallRaw, bufferRaw, surplusRaw)
    } else {
        listOf(reserveRaw, bufferRaw, surplusRaw)
    }

    val colors = if (shortfallRaw > 0f) {
        listOf(Tone.Bills, Tone.Bad, Tone.Earning, Tone.Cash)
    } else {
        listOf(Tone.Bills, Tone.Earning, Tone.Cash)
    }

    val description = "Four Buckets: Reserve ${b.reserve.display} USDC, Owed ${b.owed.display} USDC, Buffer ${b.buffer.display} USDC, Surplus ${b.surplus.display} USDC"

    BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
        CardTitle(stringResource(R.string.tr_four_buckets_title))

        // Horizontal Segment Bar
        SegmentBar(
            values = values,
            colors = colors,
            description = description,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Spacer(Modifier.height(14.dp))

        // Breakdown grid (2 rows x 2 columns)
        BentoRow {
            BucketItem(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = stringResource(R.string.tr_bucket_reserve),
                amount = b.reserve.display,
                color = Tone.Bills,
                detail = "Owed: ${b.owed.display}",
            )
            BucketItem(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = stringResource(R.string.tr_bucket_owed),
                amount = b.owed.display,
                color = if (b.shortfall.raw > BigInteger.ZERO) Tone.Bad else MaterialTheme.colorScheme.onSurface,
                detail = if (b.shortfall.raw > BigInteger.ZERO) "Short by ${b.shortfall.display}" else "Fully covered",
            )
        }

        Spacer(Modifier.height(8.dp))

        BentoRow {
            BucketItem(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = stringResource(R.string.tr_bucket_buffer),
                amount = b.buffer.display,
                color = Tone.Earning,
                detail = "Target: ${b.bufferTarget.display}",
            )
            BucketItem(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = stringResource(R.string.tr_bucket_surplus),
                amount = b.surplus.display,
                color = Tone.Cash,
                detail = "Safe to deploy",
            )
        }
    }
}

@Composable
private fun BucketItem(
    modifier: Modifier = Modifier,
    label: String,
    amount: String,
    color: Color,
    detail: String,
) {
    ObsidianCard(
        modifier = modifier,
        level = 2,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$amount USDC",
                style = MonoStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                color = color,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = Muted,
                maxLines = 1,
            )
        }
    }
}

// ---- Vault Positions --------------------------------------------------------------------------

@Composable
private fun VaultPositionsTile(vaults: List<VaultPosition>) {
    BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
        CardTitle(stringResource(R.string.tr_vaults_title))

        if (vaults.isEmpty()) {
            Text(
                stringResource(R.string.tr_vaults_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vaults.forEach { vault ->
                    VaultRowItem(vault)
                }
            }
        }
    }
}

@Composable
private fun VaultRowItem(vault: VaultPosition) {
    ObsidianCard(
        modifier = Modifier.fillMaxWidth(),
        level = 2,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    vault.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (vault.simulatedYield) {
                    StatusPill(
                        label = stringResource(R.string.tr_vault_simulated),
                        tone = Tone.Warn,
                    )
                } else {
                    StatusPill(
                        label = stringResource(R.string.tr_vault_real),
                        tone = Tone.Good,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        stringResource(R.string.tr_vault_assets, vault.assets.display, "USDC"),
                        style = MonoStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.tr_vault_shares, vault.sharesRaw.toString()),
                        style = MonoStyle.copy(fontSize = 12.sp),
                        color = Muted,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        stringResource(R.string.tr_vault_max_withdraw, vault.maxWithdraw.display, "USDC"),
                        style = MonoStyle.copy(fontSize = 12.sp),
                        color = Tone.Cash,
                    )
                    Text(
                        stringResource(R.string.tr_vault_exposure_cap, vault.exposureLimit.display, "USDC"),
                        style = MonoStyle.copy(fontSize = 12.sp),
                        color = Muted,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                shorten(vault.address),
                style = MonoStyle.copy(fontSize = 11.sp),
                color = Muted,
            )
        }
    }
}

// ---- Obligations Summary Tile -----------------------------------------------------------------

@Composable
private fun ObligationsSummaryTile(state: WalletState) {
    val obligations = state.obligations
    val unpaid = obligations.filter { !it.paid }
    val nextBill = unpaid.minByOrNull { it.dueAt }

    BentoTile(Modifier.fillMaxWidth(), padding = 16.dp) {
        CardTitle(
            stringResource(R.string.tr_bills),
            trailing = if (obligations.isNotEmpty()) {
                "${unpaid.size} unpaid / ${obligations.size} total"
            } else if (state.module != null && state.module.obligationCount > BigInteger.ZERO) {
                "${state.module.obligationCount} on chain"
            } else {
                "—"
            },
        )

        if (nextBill != null) {
            val dueFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
            val dueDateStr = dueFormat.format(Date(nextBill.dueAt * 1000))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Next: ${nextBill.amount.display} USDC",
                        style = MonoStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Due: $dueDateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.Bills,
                    )
                }
                StatusPill(
                    label = if (System.currentTimeMillis() / 1000 > nextBill.dueAt) "OVERDUE" else "SCHEDULED",
                    tone = if (System.currentTimeMillis() / 1000 > nextBill.dueAt) Tone.Bad else Tone.Good,
                )
            }
        } else if (state.module != null && state.module.reserved.raw > BigInteger.ZERO) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Owed: ${state.module.reserved.display} USDC",
                        style = MonoStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Root: ${shorten(state.module.obligationsRoot)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tone.Bills,
                    )
                }
                StatusPill(
                    label = "ON-CHAIN",
                    tone = Tone.Warn,
                )
            }
        }
    }
}

// ---- Ask Tile ---------------------------------------------------------------------------------

@Composable
private fun AskTile(onClick: () -> Unit) {
    BentoTile(Modifier.fillMaxWidth(), onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(FusionTheme.surfaces.level2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Tone.Earning,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    stringResource(R.string.tr_ask_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.tr_ask_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}
