package app.fusion.wallet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.components.FusionBrandLockup
import app.fusion.wallet.ui.components.FusionIcon
import app.fusion.wallet.ui.components.FusionIconRes
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.TokenMonogram
import app.fusion.wallet.ui.model.WhitelistEntry
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian
import app.fusion.wallet.ui.shorten
import java.util.Locale

/**
 * `layout.md` View 4 — settings and configuration drawer.
 *
 * The RPC node is shown read-only. `fusion-core` pins `rpc_url` and `chain_id` at first
 * configure and refuses to re-point a running wallet, so a live text field here would be a
 * control that silently does nothing — worse than one that says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(
    rpcUrl: String,
    dailyCapUsd: Float,
    mnnThreads: Int,
    whitelist: List<WhitelistEntry>,
    onDailyCapChange: (Float) -> Unit,
    onThreadsChange: (Int) -> Unit,
    onToggleAsset: (WhitelistEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaces = FusionTheme.surfaces

    ModalDrawerSheet(
        modifier = modifier,
        drawerContainerColor = surfaces.canvas,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FusionDims.gutter),
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FusionBrandLockup(height = 17.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // The drawer previously had no close affordance at all: the only ways out
                // were the system back gesture or tapping the scrim, neither of which is
                // discoverable and both of which felt like the app had frozen.
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.settings_close),
                        tint = Accessible.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.settings_rpc))
            ObsidianCard(Modifier.fillMaxWidth(), level = 1) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = rpcUrl,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_rpc_pinned),
                        style = MaterialTheme.typography.labelMedium,
                        color = Obsidian.StatusWarning,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel(
                stringResource(R.string.settings_daily_cap) +
                    "  ·  $" + String.format(Locale.US, "%,.0f", dailyCapUsd),
            )
            ObsidianCard(Modifier.fillMaxWidth(), level = 1) {
                Slider(
                    value = dailyCapUsd,
                    onValueChange = onDailyCapChange,
                    valueRange = 100f..1_000f,
                    steps = 17,
                    colors = SliderDefaults.colors(
                        thumbColor = Accessible.ButtonFill,
                        activeTrackColor = Accessible.ButtonFill,
                        inactiveTrackColor = surfaces.level3,
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.settings_whitelist))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                whitelist.forEach { entry ->
                    AssetToggle(entry, onToggleAsset)
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel(stringResource(R.string.settings_threads))
            ObsidianCard(Modifier.fillMaxWidth(), level = 1) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(2, 4, 8).forEach { threads ->
                        ThreadChip(
                            threads = threads,
                            selected = threads == mnnThreads,
                            onClick = { onThreadsChange(threads) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Accessible.TextMuted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun AssetToggle(entry: WhitelistEntry, onToggle: (WhitelistEntry) -> Unit) {
    val surfaces = FusionTheme.surfaces
    ObsidianCard(Modifier.fillMaxWidth(), level = 1, onClick = { onToggle(entry) }) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (entry.symbol) {
                "USDC" -> FusionIcon(FusionIconRes.Usdc, contentDescription = null, size = 22.dp)
                "WETH" -> FusionIcon(FusionIconRes.Weth, contentDescription = null, size = 22.dp)
                else -> TokenMonogram(entry.symbol, size = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = shorten(entry.address),
                    style = MonoStyle,
                    color = Accessible.TextMuted,
                )
            }
            Switch(
                checked = entry.enabled,
                onCheckedChange = { onToggle(entry) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Obsidian.StatusSuccess,
                    checkedTrackColor = surfaces.level3,
                    uncheckedThumbColor = Accessible.TextMuted,
                    uncheckedTrackColor = surfaces.level2,
                    uncheckedBorderColor = surfaces.borderFocused,
                ),
            )
        }
    }
}

@Composable
private fun ThreadChip(threads: Int, selected: Boolean, onClick: () -> Unit) {
    val surfaces = FusionTheme.surfaces
    Text(
        text = "$threads",
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onSurface else Accessible.TextMuted,
        modifier = Modifier
            .background(
                if (selected) surfaces.level3 else surfaces.level2,
                RoundedCornerShape(FusionDims.radiusSmall),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}
