package app.fusion.wallet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.ui.model.ChatMessage
import app.fusion.wallet.ui.model.EngineStatus
import app.fusion.wallet.ui.model.LogLine
import app.fusion.wallet.ui.model.LogTone
import app.fusion.wallet.ui.model.PreFlightReport
import app.fusion.wallet.ui.model.RuleState
import app.fusion.wallet.ui.model.WhitelistEntry
import app.fusion.wallet.ui.model.WalletState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the chat feed gets its intents.
 *
 * Today that is [DemoIntentSource], which fabricates the exact payloads the real engine
 * will see. Swapping in MNN or the FFI call means another implementation and a one-line
 * change in [MainViewModel] — no composable knows the difference.
 */
interface IntentSource {
    suspend fun interpret(text: String): DemoIntent?
    suspend fun preflight(intent: DemoIntent): PreFlightReport
    suspend fun execute(intent: DemoIntent, report: PreFlightReport): ExecutionResult
}

data class DemoIntent(
    val action: String,
    val token: String,
    val amountUsd: Double,
    val destination: String,
    /** Exactly the JSON the real `execute_intent` will receive. */
    val json: String,
)

data class ExecutionResult(val txHash: String, val summary: String)

data class FusionUiState(
    val wallet: WalletState = WalletState("", 1, "Ethereum", 0.0, 500.0, 100.0, "—", "—", "—"),
    val engine: EngineStatus = EngineStatus("MNN 1.5B", 118, true),
    val messages: List<ChatMessage> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val whitelist: List<WhitelistEntry> = emptyList(),
    val dailyCapUsd: Float = 500f,
    val mnnThreads: Int = 4,
    val rpcUrl: String = "https://mainnet.base.org",
    /** Set while a pre-flight card awaits the user. Both halves are needed to confirm. */
    val pending: Pair<DemoIntent, PreFlightReport>? = null,
    val terminalOpen: Boolean = false,
)

class MainViewModel(
    private val source: IntentSource = DemoIntentSource(),
) : ViewModel() {

    private val _state = MutableStateFlow(FusionUiState())
    val state: StateFlow<FusionUiState> = _state.asStateFlow()

    private var nextId = 1L
    private var logSeq = 1L

    init {
        _state.update {
            it.copy(
                wallet = DemoData.wallet,
                whitelist = DemoData.whitelist,
                engine = DemoData.engine,
            )
        }
        log("MNN_INFERENCE_ENGINE", "Model ready · Qwen-1.5B INT4 · 4 threads · NNAPI", LogTone.Engine)
        log("RUST_CORE", "fusion-core loaded · guardrails armed", LogTone.Success)
        log("JNI_BRIDGE_CALL", "execute_intent not linked in this build (demo mode)", LogTone.Muted)
        log(
            "LEDGER",
            "Restored daily spend ${usd(DemoData.wallet.dailySpentUsd)} of ${usd(DemoData.wallet.dailyCapUsd)}",
        )
    }

    fun log(tag: String, body: String, tone: LogTone = LogTone.Default) {
        val line = LogLine(
            id = logSeq++,
            timestamp = CLOCK.get().format(Date()),
            tag = tag,
            body = body,
            tone = tone,
        )
        _state.update { it.copy(logs = (it.logs + line).takeLast(300)) }
    }

    fun clearLogs() = _state.update { it.copy(logs = emptyList()) }

    fun setTerminalOpen(open: Boolean) = _state.update { it.copy(terminalOpen = open) }

    fun setDailyCap(cap: Float) {
        _state.update { it.copy(dailyCapUsd = cap) }
        log("SETTINGS", "Daily velocity threshold set to ${usd(cap.toDouble())} (engine unchanged in demo)")
    }

    fun setMnnThreads(threads: Int) {
        _state.update { it.copy(mnnThreads = threads) }
        log("SETTINGS", "MNN inference threads set to $threads (demo)", LogTone.Muted)
    }

    fun toggleWhitelist(entry: WhitelistEntry) {
        _state.update { s ->
            s.copy(
                whitelist = s.whitelist.map {
                    if (it.address == entry.address) it.copy(enabled = !it.enabled) else it
                },
            )
        }
        val now = _state.value.whitelist.first { it.address == entry.address }
        log(
            "SETTINGS",
            "${entry.symbol} ${if (now.enabled) "added to" else "removed from"} whitelist (demo)",
            LogTone.Warn,
        )
    }

    fun useSuggestion(text: String) = submit(text)

    fun submit(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val promptId = nextId++
            _state.update { it.copy(messages = it.messages + ChatMessage.Prompt(promptId, text)) }
            log("MNN_INFERENCE_ENGINE", "Parsing \"$text\"", LogTone.Engine)

            val intent = source.interpret(text)
            if (intent == null) {
                val id = nextId++
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage.Blocked(
                            id,
                            "unparsable",
                            "The agent could not turn that into a transaction.",
                        ),
                    )
                }
                log("MNN_INFERENCE_ENGINE", "No actionable intent extracted", LogTone.Warn)
                return@launch
            }

            val parsingId = nextId++
            _state.update { it.copy(messages = it.messages + ChatMessage.Parsing(parsingId, 0)) }
            delay(DEMO_PARSE_MS)

            log(
                "RUST_GUARDRAIL",
                "Pre-flight · ${intent.token} ${usd(intent.amountUsd)} → ${intent.destination}",
            )
            val report = source.preflight(intent)

            if (report.isBlocked) {
                val failed = report.rules.first { it.state == RuleState.Block }
                val id = nextId++
                _state.update {
                    it.copy(
                        messages = it.messages
                            .filterNot { m -> m.id == parsingId } +
                            ChatMessage.Blocked(id, failed.key.uppercase(), failed.detail),
                    )
                }
                log("RUST_GUARDRAIL", "REJECTED · ${failed.key} · ${failed.detail}", LogTone.Error)
                return@launch
            }

            val guardId = nextId++
            _state.update {
                it.copy(
                    messages = it.messages.filterNot { m -> m.id == parsingId } +
                        ChatMessage.Guardrail(guardId, report),
                    pending = intent to report,
                )
            }
            log("RUST_GUARDRAIL", "Pre-flight passed · awaiting confirmation", LogTone.Success)
        }
    }

    fun confirmPending() {
        val (intent, report) = _state.value.pending ?: return
        // Retire the pre-flight card as part of confirming. Left in place it keeps live
        // Confirm/Cancel buttons and the user can broadcast the same intent repeatedly.
        _state.update { s ->
            s.copy(
                pending = null,
                messages = s.messages.filterNot { m -> m is ChatMessage.Guardrail },
            )
        }

        viewModelScope.launch {
            log("JNI_BRIDGE_CALL", "execute_intent(payload_json, ***key_redacted***)", LogTone.Engine)
            delay(DEMO_SIGN_MS)
            val result = source.execute(intent, report)
            val id = nextId++
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage.Executed(id, result.txHash, result.summary),
                    wallet = it.wallet.copy(
                        dailySpentUsd = (it.wallet.dailySpentUsd + intent.amountUsd)
                            .coerceAtMost(it.wallet.dailyCapUsd),
                    ),
                )
            }
            log("ALLOY_EXECUTOR", "Signed EIP-1559 → ${result.txHash}", LogTone.Success)
        }
    }

    fun cancelPending() {
        _state.update { s ->
            s.copy(
                pending = null,
                messages = s.messages.filterNot { m -> m is ChatMessage.Guardrail },
            )
        }
        log("RUST_GUARDRAIL", "Reservation released · nothing broadcast", LogTone.Muted)
    }

    private fun usd(value: Double) = "$" + String.format(Locale.US, "%,.2f", value)
}

/**
 * `java.time` needs API 26 and minSdk is 24. Rather than pull in core library desugaring
 * for a timestamp, use the API-24-safe formatter. A fresh instance per format is correct
 * here — SimpleDateFormat is not thread-safe and ViewModel logs come from one dispatcher
 * but the class is shared.
 */
private val CLOCK = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm:ss", Locale.US)
}

private const val DEMO_PARSE_MS = 900L
private const val DEMO_SIGN_MS = 1_100L