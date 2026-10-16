package app.fusion.wallet.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.RuleRow
import app.fusion.wallet.ui.components.VelocityMeter
import app.fusion.wallet.ui.model.RuleState
import app.fusion.wallet.ui.model.WalletState
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian
import java.util.Locale

/**
 * `layout.md` View "Guardrail Ledger" — the economic policy the engine will actually apply,
 * shown rather than paraphrased. Every figure here is read from the same state that drives
 * the Pre-Flight card, so the two can never disagree.
 */
@Composable
fun LedgerScreen(
    wallet: WalletState,
    dailyCapUsd: Double,
    modifier: Modifier = Modifier,
) {
    val projected = wallet.dailySpentUsd + wallet.maxTxUsd
    val projectedRatio = if (dailyCapUsd <= 0.0) 0f else (projected / dailyCapUsd).toFloat()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(FusionDims.gutter),
    ) {
        ObsidianCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "TODAY",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accessible.TextMuted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "$" + String.format(Locale.US, "%,.2f", wallet.dailySpentUsd),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "of $" + String.format(Locale.US, "%,.2f", dailyCapUsd) + " spent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accessible.TextMuted,
                )
                Spacer(Modifier.height(14.dp))
                VelocityMeter(wallet.dailySpentUsd, dailyCapUsd, showLabel = false, height = 6.dp)
            }
        }

        Spacer(Modifier.height(12.dp))

        ObsidianCard(Modifier.fillMaxWidth()) {
            // RuleRow carries no inset of its own; the Pre-Flight card supplies one via
            // its wrapping Column. Without the same here the labels sat flush against the
            // card edge and the PASS badges clipped.
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    text = "ACTIVE LIMITS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accessible.TextMuted,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
                )
                Column(Modifier.padding(horizontal = 14.dp)) {
                    RuleRow(
                        label = "Per-transaction cap",
                        detail = "$" + String.format(Locale.US, "%,.2f", wallet.maxTxUsd),
                        state = RuleState.Pass,
                    )
                    RuleRow(
                        label = "Daily velocity cap",
                        detail = "$" + String.format(Locale.US, "%,.2f", dailyCapUsd),
                        state = RuleState.Pass,
                    )
                    RuleRow(
                        label = "Remaining after next max-size tx",
                        detail = "$" + String.format(
                            Locale.US, "%,.2f",
                            (dailyCapUsd - projected).coerceAtLeast(0.0),
                        ),
                        state = when {
                            projected > dailyCapUsd -> RuleState.Block
                            projectedRatio >= 0.5f -> RuleState.Warn
                            else -> RuleState.Pass
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        ObsidianCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "ENGINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accessible.TextMuted,
                )
                Spacer(Modifier.height(8.dp))
                LedgerField("Chain", "${wallet.chainName} (${wallet.chainId})")
                LedgerField("Address", wallet.address)
                LedgerField("Native", wallet.nativeBalance)
                LedgerField("Mode", "Demo — engine not linked")
            }
        }

        Spacer(Modifier.height(12.dp))

        ObsidianCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "NOTE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Obsidian.StatusWarning,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Figures shown here are demo state. They are not read from the " +
                        "Rust ledger until preview_intent and wallet_status are wired in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Accessible.TextMuted,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LedgerField(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Accessible.TextMuted,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}