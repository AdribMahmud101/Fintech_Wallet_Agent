package app.fusion.wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.model.EngineStatus
import app.fusion.wallet.ui.model.WalletState
import app.fusion.wallet.ui.elide
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian

enum class NavRoute { Chat, Tokens, Ledger, Accountant }

/**
 * `layout.md` §2 TopAppBar — brand, active address, a daily-velocity sliver, and the two
 * engine status pills.
 *
 * Split across two rows rather than crammed into one. On the 393dp device the single row
 * had to share its width between the lockup, the address, two pills and the terminal
 * button; the address lost and ellipsised to `0x71C8…dA09`, and the lockup was boxed into
 * a 30dp square that rendered it about 9dp tall. Giving identity its own row lets the
 * brand be drawn at its real size, and the freed width lets the address read properly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopHealthAppBar(
    wallet: WalletState,
    engine: EngineStatus,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaces = FusionTheme.surfaces

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaces.canvas),
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = surfaces.canvas,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            navigationIcon = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.open_settings))
                }
            },
            title = {
                FusionBrandLockup(height = 24.dp)
            },
            actions = {
                IconButton(onClick = onOpenTerminal) {
                    Icon(
                        Icons.Filled.ReceiptLong,
                        contentDescription = stringResource(R.string.open_terminal),
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = FusionDims.gutter,
                    end = FusionDims.gutter,
                    top = 2.dp,
                    bottom = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // The strip has the whole width to itself now, so the address can carry
                // enough on each side of the gap to actually be recognised. `shorten`
                // (6+4) is still what the chat feed and card subtitles use, where width
                // is genuinely scarce.
                Text(
                    text = elide(wallet.address, head = 12, tail = 10),
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                VelocityMeter(
                    spentUsd = wallet.dailySpentUsd,
                    capUsd = wallet.dailyCapUsd,
                    showLabel = false,
                    height = 3.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(14.dp))
            // Compact pills: the labelled engine readout already lives on the Chat composer
            // strip, so these only have to answer "which engines are up" at a glance.
            StatusPill(
                label = engine.mnnModel,
                tone = Obsidian.AccentAiViolet,
                compact = true,
                leading = { FusionIcon(FusionIconRes.Mnn, null, size = 16.dp) },
            )
            Spacer(Modifier.width(6.dp))
            StatusPill(
                label = stringResource(
                    if (engine.rustGuardrailOk) R.string.engine_rust else R.string.engine_rust_down,
                ),
                tone = if (engine.rustGuardrailOk) Obsidian.StatusSuccess else Obsidian.StatusError,
                compact = true,
                leading = { FusionIcon(FusionIconRes.Rust, null, size = 16.dp) },
            )
        }
    }
}

private data class TabSpec(
    val route: NavRoute,
    val labelRes: Int,
    val icon: ImageVector,
)

/**
 * Bottom destination bar.
 *
 * Deliberately a plain Row rather than a NavigationBar: Material's NavigationBar enforces a
 * 80dp container and its own item metrics, which would fight the flat, zero-elevation
 * surfaces this design is built on. The three destinations never change, so a `Row` is both
 * simpler and truer to the spec.
 */
@Composable
fun FusionBottomBar(
    selected: NavRoute,
    onSelect: (NavRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaces = FusionTheme.surfaces
    val tabs = listOf(
        TabSpec(NavRoute.Chat, R.string.nav_chat, Icons.Filled.Chat),
        TabSpec(NavRoute.Tokens, R.string.nav_tokens, Icons.Filled.Wallet),
        TabSpec(NavRoute.Ledger, R.string.nav_ledger, Icons.Filled.Shield),
        TabSpec(NavRoute.Accountant, R.string.nav_accountant, Icons.Filled.Insights),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaces.canvas)
            // Edge-to-edge: without this the tabs sit under the system navigation buttons and
            // the right-hand tab cannot be tapped on 3-button-nav devices.
            .navigationBarsPadding()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { tab ->
            val active = tab.route == selected
            Column(
                modifier = Modifier
                    .background(
                        if (active) surfaces.level2 else surfaces.canvas,
                        RoundedCornerShape(FusionDims.radiusSmall),
                    )
                    .clickableSurface { onSelect(tab.route) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (active) MaterialTheme.colorScheme.onSurface else Accessible.TextMuted,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(tab.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onSurface else Accessible.TextMuted,
                )
            }
        }
    }
}

/** Rippleless click: the design is flat, so the default ink would be out of key. */
private fun Modifier.clickableSurface(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = null,
        indication = null,
        onClick = onClick,
    )