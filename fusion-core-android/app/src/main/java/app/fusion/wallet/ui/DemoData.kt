package app.fusion.wallet.ui

import app.fusion.wallet.ui.model.EngineStatus
import app.fusion.wallet.ui.model.IntentSummary
import app.fusion.wallet.ui.model.PreFlightReport
import app.fusion.wallet.ui.model.RuleCheck
import app.fusion.wallet.ui.model.RuleState
import app.fusion.wallet.ui.model.WhitelistEntry
import app.fusion.wallet.ui.model.WalletState
import java.util.Locale

/**
 * Fixed values for the demo build.
 *
 * Numbers are deliberately mid-range: $60 spent against a $500 cap puts the velocity meter
 * in its amber "approaching" band and lets the Pre-Flight card show a real `WARN` row, so
 * every state in `layout.md` View 2 is reachable from the suggestions on the empty state.
 */
object DemoData {

    val wallet = WalletState(
        address = "0x71C8a9F4b2D6e05aC7B3f18D42E9a6C1b8F4dA09",
        chainId = 1,
        chainName = "Ethereum",
        dailySpentUsd = 60.00,
        dailyCapUsd = 500.00,
        maxTxUsd = 100.00,
        nativeBalance = "1.4820 ETH",
        usdcBalance = "2,140.55 USDC",
        wethBalance = "0.7500 WETH",
    )

    val engine = EngineStatus(mnnModel = "MNN 1.5B", mnnLatencyMs = 118, rustGuardrailOk = true)

    val whitelist = listOf(
        WhitelistEntry("ETH", "0x0000000000000000000000000000000000000000", false),
        WhitelistEntry("USDC", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", true),
        WhitelistEntry("WETH", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", true),
    )

    val suggestions = listOf(
        "Transfer 10 USDC to 0x71C8a9F4b2D6e05aC7B3f18D42E9a6C1b8F4dA09",
        "Send 250 USDC to 0x41b7C2A19eD4F80a5C6E93B7d1F0aC85e3D24b16",
        "Check my daily limit",
    )
}

/**
 * Stands in for MNN + the Rust core.
 *
 * Each method is the seam where the real integration lands: [interpret] becomes the local
 * model call, [preflight] becomes `preview_intent`, [execute] becomes `execute_intent`.
 * The rule *shapes* mirror `guardrails.rs` so the card renders the same fields the engine
 * will actually return.
 */
class DemoIntentSource : IntentSource {

    override suspend fun interpret(text: String): DemoIntent? {
        val lower = text.lowercase()
        if (lower.contains("limit") || lower.contains("cap")) return null // answered by the ledger view, not a transaction

        val amount = Regex("""(\d+(?:\.\d+)?)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return null
        val token = when {
            lower.contains("usdc") -> "USDC"
            lower.contains("weth") -> "WETH"
            lower.contains("eth") -> "ETH"
            else -> "USDC"
        }
        val destination = Regex("""0x[0-9a-fA-F]{40}""").find(text)?.value ?: return null

        val json = """
            {"action":"transfer","amount":$amount,"token_in":"$token","destination_address":"$destination"}
        """.trimIndent()

        return DemoIntent(
            action = "transfer",
            token = token,
            amountUsd = amount,
            destination = destination,
            json = json,
        )
    }

    override suspend fun preflight(intent: DemoIntent): PreFlightReport {
        val wallet = DemoData.wallet
        val enabled = DemoData.whitelist.filter { it.enabled }.map { it.symbol }
        val decimals = if (intent.token == "USDC") 6 else 18
        val atomic = (intent.amountUsd * Math.pow(10.0, decimals.toDouble())).toLong()

        val projected = wallet.dailySpentUsd + intent.amountUsd
        val velocityRatio = projected / wallet.dailyCapUsd

        val rules = listOf(
            RuleCheck(
                key = "whitelist_target",
                label = "Whitelist Target",
                detail = "${intent.token} ${shorten(intent.destination)}",
                state = if (intent.token in enabled) RuleState.Pass else RuleState.Block,
            ),
            RuleCheck(
                key = "tx_size_cap",
                label = "Tx Size Cap",
                detail = "$${"%.2f".format(Locale.US, intent.amountUsd)} / $${"%.2f".format(Locale.US, wallet.maxTxUsd)}",
                state = if (intent.amountUsd <= wallet.maxTxUsd) RuleState.Pass else RuleState.Block,
            ),
            RuleCheck(
                key = "daily_velocity",
                label = "Daily Velocity",
                detail = "$${"%.2f".format(Locale.US, wallet.dailySpentUsd)} / $${"%.2f".format(Locale.US, wallet.dailyCapUsd)}",
                state = when {
                    projected > wallet.dailyCapUsd -> RuleState.Block
                    velocityRatio >= 0.5 -> RuleState.Warn
                    else -> RuleState.Pass
                },
            ),
            RuleCheck(
                key = "atomic_precision",
                label = "Atomic Precision",
                detail = "$atomic wei match",
                state = RuleState.Pass,
            ),
        )

        return PreFlightReport(
            intent = IntentSummary(
                action = intent.action,
                token = intent.token,
                amountUsd = "$${"%.2f".format(Locale.US, intent.amountUsd)}",
                atomic = atomic.toString(),
                destination = intent.destination,
            ),
            rules = rules,
            dailySpentUsd = wallet.dailySpentUsd,
            dailyCapUsd = wallet.dailyCapUsd,
        )
    }

    override suspend fun execute(intent: DemoIntent, report: PreFlightReport): ExecutionResult {
        // Deterministic 32-byte hash: same intent always yields the same demo hash, so a
        // screenshot is reproducible. Not a real transaction -- it is not signed and not
        // on any chain.
        val seed = (intent.destination + intent.json + report.intent.atomic)
            .fold(17L) { acc, c -> acc * 31 + c.code }
        val hash = String.format(
            Locale.US,
            "0x%016x%048x",
            seed,
            java.lang.Long.remainderUnsigned(seed * -7046029254386353131L, Long.MAX_VALUE),
        )
        return ExecutionResult(
            txHash = hash,
            summary = "${report.intent.amountUsd} ${intent.token} → ${shorten(intent.destination)}",
        )
    }

    private fun shorten(address: String): String =
        if (address.length <= 12) address else address.take(6) + "…" + address.takeLast(4)
}

/** `0x71C8a9F4…4dA09` — addresses are never rendered in full outside the mono log. */
fun shorten(address: String): String = elide(address, head = 6, tail = 4)

/**
 * Elide the middle of a long value, keeping [head] leading and [tail] trailing characters.
 *
 * Shared so every place that abbreviates an address abbreviates it the same way, and so
 * a caller with more room on screen can ask for a wider form instead of hand-rolling
 * `take`/`takeLast` at the call site.
 */
fun elide(value: String, head: Int, tail: Int): String =
    if (value.length <= head + tail + 1) value else value.take(head) + "…" + value.takeLast(tail)
