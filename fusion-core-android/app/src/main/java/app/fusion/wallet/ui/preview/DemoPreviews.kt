package app.fusion.wallet.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.fusion.wallet.ui.DemoData
import app.fusion.wallet.ui.components.FusionBottomBar
import app.fusion.wallet.ui.chat.ChatFeed
import app.fusion.wallet.ui.components.NavRoute
import app.fusion.wallet.ui.components.TopHealthAppBar
import app.fusion.wallet.ui.ledger.LedgerScreen
import app.fusion.wallet.ui.model.ChatMessage
import app.fusion.wallet.ui.model.IntentSummary
import app.fusion.wallet.ui.model.PreFlightReport
import app.fusion.wallet.ui.model.RuleCheck
import app.fusion.wallet.ui.model.RuleState
import app.fusion.wallet.ui.settings.SettingsDrawer
import app.fusion.wallet.ui.theme.FusionWalletTheme
import app.fusion.wallet.ui.tokens.TokensScreen

/**
 * Reviewable states of the demo UI.
 *
 * These live in `main` rather than `test` so they render in Android Studio's preview pane
 * (and in the IDE's interactive preview) with no test harness and no device. The values are
 * frozen rather than pulled from `MainViewModel`, so a rendered diff always means a visual
 * change rather than mock data drifting.
 */

@Preview(name = "Chat · Idle", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun ChatIdlePreview() = Wrap { Body { ChatIdleContent() } }

@Preview(name = "Chat · Pre-Flight card", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun ChatPreFlightPreview() = Wrap { Body { PreFlightContent() } }

@Preview(name = "Chat · Executed & blocked", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun ChatHistoryPreview() = Wrap { Body { HistoryContent() } }

@Preview(name = "Ledger", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun LedgerPreview() = Wrap { Body { LedgerScreen(DemoData.wallet, 500.0) } }

@Preview(name = "Tokens", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun TokensPreview() = Wrap { Body { TokensScreen(DemoData.wallet) } }

@Preview(name = "Settings drawer", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun SettingsPreview() = FusionWalletTheme {
    SettingsDrawer(
        rpcUrl = "https://mainnet.base.org",
        dailyCapUsd = 500f,
        mnnThreads = 4,
        whitelist = DemoData.whitelist,
        onDailyCapChange = {},
        onThreadsChange = {},
        onToggleAsset = {},
        onDismiss = {},
    )
}

/** Canvas + top bar + bottom bar, matching the real shell so spacing is judged in context. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Body(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopHealthAppBar(
            wallet = DemoData.wallet,
            engine = DemoData.engine,
            onOpenSettings = {},
            onOpenTerminal = {},
        )
        Column(Modifier.weight(1f).fillMaxWidth()) { content() }
        FusionBottomBar(selected = NavRoute.Treasury, onSelect = {})
    }
}

@Composable
private fun Wrap(content: @Composable () -> Unit) {
    FusionWalletTheme {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun ChatIdleContent() {
    ChatFeed(
        messages = emptyList(),
        suggestions = DemoData.suggestions,
        onSuggestion = {},
        onConfirm = {},
        onCancel = {},
    )
}

@Composable
private fun PreFlightContent() {
    ChatFeed(
        messages = listOf(
            ChatMessage.Prompt(1, "Transfer 10 USDC to 0x71C8a9F4b2D6e05aC7B3f18D42E9a6C1b8F4dA09"),
            ChatMessage.Guardrail(2, report),
        ),
        suggestions = DemoData.suggestions,
        onSuggestion = {},
        onConfirm = {},
        onCancel = {},
    )
}

@Composable
private fun HistoryContent() {
    ChatFeed(
        messages = listOf(
            ChatMessage.Prompt(
                1,
                "Send 250 USDC to 0x41b7C2A19eD4F80a5C6E93B7d1F0aC85e3D24b16",
            ),
            ChatMessage.Blocked(
                2,
                "daily_velocity",
                "\$60.00 / \$500.00 — this transfer would reach \$310.00 against a \$500.00 cap",
            ),
            ChatMessage.Prompt(
                3,
                "Transfer 10 USDC to 0x71C8a9F4b2D6e05aC7B3f18D42E9a6C1b8F4dA09",
            ),
            ChatMessage.Executed(
                4,
                "0x8f3ba1c4d7e90f21a6b3c5e8d42f97a01b6c4e8d2a5f3b1c7d9e4a60b8c3f21d6a109",
                "\$10.00 USDC → 0x71C8a9F4…4dA09",
            ),
        ),
        suggestions = DemoData.suggestions,
        onSuggestion = {},
        onConfirm = {},
        onCancel = {},
    )
}

private val report = PreFlightReport(
    intent = IntentSummary(
        action = "transfer",
        token = "USDC",
        amountUsd = "\$10.00",
        atomic = "10000000",
        destination = "0x71C8a9F4b2D6e05aC7B3f18D42E9a6C1b8F4dA09",
    ),
    rules = listOf(
        RuleCheck("whitelist_target", "Whitelist Target", "USDC 0x71C8…4dA09", RuleState.Pass),
        RuleCheck("tx_size_cap", "Tx Size Cap", "\$10.00 / \$100.00", RuleState.Pass),
        RuleCheck("daily_velocity", "Daily Velocity", "\$60.00 / \$500.00", RuleState.Warn),
        RuleCheck("atomic_precision", "Atomic Precision", "10000000 wei match", RuleState.Pass),
    ),
    dailySpentUsd = 60.0,
    dailyCapUsd = 500.0,
)