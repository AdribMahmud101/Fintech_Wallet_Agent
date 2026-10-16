package app.fusion.wallet.ui.tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.components.FusionIcon
import app.fusion.wallet.ui.components.FusionIconRes
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.TokenMonogram
import app.fusion.wallet.ui.model.WalletState
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian

/**
 * Token portfolio.
 *
 * Shows the whitelist and what the demo state claims, and says plainly that the balances
 * are not real. A wallet that renders invented balances as though they were live is worse
 * than one that admits it has none, so the empty state is explicit rather than decorative.
 */
@Composable
fun TokensScreen(
    wallet: WalletState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FusionDims.gutter),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ObsidianCard(Modifier.fillMaxWidth(), level = 1) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FusionIcon(FusionIconRes.Weth, null, size = 26.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "WETH",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Wrapped Ether",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accessible.TextMuted,
                    )
                }
                Text(
                    text = wallet.wethBalance,
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        ObsidianCard(Modifier.fillMaxWidth(), level = 1) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FusionIcon(FusionIconRes.Usdc, null, size = 26.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "USDC",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "USD Coin",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accessible.TextMuted,
                    )
                }
                Text(
                    text = wallet.usdcBalance,
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        ObsidianCard(Modifier.fillMaxWidth(), level = 1) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenMonogram("ETH", size = 26.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "ETH",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Native asset",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accessible.TextMuted,
                    )
                }
                Text(
                    text = wallet.nativeBalance,
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tokens_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = Obsidian.StatusWarning,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Balances require an RPC balanceOf path in the Rust core.",
                style = MaterialTheme.typography.bodySmall,
                color = Accessible.TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}