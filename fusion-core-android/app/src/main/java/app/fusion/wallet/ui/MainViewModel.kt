package app.fusion.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.BuildConfig
import app.fusion.wallet.data.ChainWalletRepository
import app.fusion.wallet.data.WalletRepository
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.model.PreFlightReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** SL4: live monitor only. Approval and stop requests belong to the signer integration. */
class MainViewModel @JvmOverloads constructor(
    app: Application,
    private val repository: WalletRepository = ChainWalletRepository.fromAssets(app),
) : AndroidViewModel(app) {
    val state: StateFlow<WalletState> = repository.state
    val walletState: StateFlow<WalletState> = state

    init {
        require(BuildConfig.DEBUG || repository is ChainWalletRepository) {
            "Release builds require ChainWalletRepository"
        }
        viewModelScope.launch {
            while (isActive) {
                repository.refresh()
                delay(10_000)
            }
        }
    }

    fun refresh() { viewModelScope.launch { repository.refresh() } }
}

/* Compatibility declarations for the old, unmounted DemoData.kt previews (owned by K5).
 * These never enter the live data path. Construction is explicitly debug-only. K5 can
 * remove this seam together with the legacy floating-point preview UI models. */
@Deprecated("Debug preview compatibility only; use data.WalletState for live state")
interface IntentSource {
    suspend fun interpret(text: String): DemoIntent?
    suspend fun preflight(intent: DemoIntent): PreFlightReport
    suspend fun execute(intent: DemoIntent, report: PreFlightReport): ExecutionResult
}

@Deprecated("Debug preview compatibility only")
data class DemoIntent(
    val action: String,
    val token: String,
    val amountUsd: Double,
    val destination: String,
    val json: String,
) {
    init { check(BuildConfig.DEBUG) { "DEMO intents are disabled in release" } }
}

@Deprecated("Debug preview compatibility only")
data class ExecutionResult(val txHash: String, val summary: String) {
    init { check(BuildConfig.DEBUG) { "DEMO execution results are disabled in release" } }
}
