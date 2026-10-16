package app.fusion.wallet.ui.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.fusion.wallet.R

/**
 * Demo data model for the pre-flight card.
 *
 * Mirrors the JSON that `preview_intent` is planned to return (see `ui_build_plan.md` §2.1),
 * so that swapping the mock source for the real FFI call is a change of *source*, not of
 * every call site. The rule keys and ordering are fixed by `layout.md` View 2.
 */
@Immutable
enum class RuleState { Pass, Warn, Block }

@Immutable
data class RuleCheck(
    val key: String,
    val label: String,
    val detail: String,
    val state: RuleState,
)

@Immutable
data class IntentSummary(
    val action: String,
    val token: String,
    val amountUsd: String,
    val atomic: String,
    val destination: String,
)

@Immutable
data class PreFlightReport(
    val intent: IntentSummary,
    val rules: List<RuleCheck>,
    val dailySpentUsd: Double,
    val dailyCapUsd: Double,
) {
    val isBlocked: Boolean get() = rules.any { it.state == RuleState.Block }
    val hasWarning: Boolean get() = rules.any { it.state == RuleState.Warn }
}

/** `layout.md` View 1: the five states a prompt moves through. */
@Immutable
sealed interface ChatMessage {
    val id: Long

    @Immutable
    data class Prompt(override val id: Long, val text: String) : ChatMessage

    @Immutable
    data class Parsing(override val id: Long, val elapsedMs: Int) : ChatMessage

    @Immutable
    data class Guardrail(override val id: Long, val report: PreFlightReport) : ChatMessage

    @Immutable
    data class Executed(override val id: Long, val hash: String, val summary: String) : ChatMessage

    @Immutable
    data class Blocked(override val id: Long, val code: String, val reason: String) : ChatMessage
}

@Immutable
data class EngineStatus(
    val mnnModel: String,
    val mnnLatencyMs: Int,
    val rustGuardrailOk: Boolean,
)

@Immutable
data class WalletState(
    val address: String,
    val chainId: Long,
    val chainName: String,
    val dailySpentUsd: Double,
    val dailyCapUsd: Double,
    val maxTxUsd: Double,
    val nativeBalance: String,
    val usdcBalance: String,
    val wethBalance: String,
) {
    val velocityFraction: Float
        get() = if (dailyCapUsd <= 0.0) 0f else (dailySpentUsd / dailyCapUsd).toFloat().coerceIn(0f, 1f)
}

@Immutable
data class LogLine(
    val id: Long,
    val timestamp: String,
    val tag: String,
    val body: String,
    val tone: LogTone = LogTone.Default,
)

enum class LogTone { Default, Muted, Success, Warn, Error, Engine }

@Immutable
data class WhitelistEntry(
    val symbol: String,
    val address: String,
    val enabled: Boolean,
)

enum class NavTab(val route: String, @StringRes val labelRes: Int) {
    Chat("chat", R.string.nav_chat),
    Tokens("tokens", R.string.nav_tokens),
    Ledger("ledger", R.string.nav_ledger),
}