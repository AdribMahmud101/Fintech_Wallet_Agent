package app.fusion.wallet.ui.advisor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.advisor.AccountantAnswer
import app.fusion.wallet.advisor.AdvisorResult
import app.fusion.wallet.advisor.AdvisorServices
import app.fusion.wallet.advisor.AdvisorSettings
import app.fusion.wallet.advisor.CurrentPolicy
import app.fusion.wallet.advisor.DecisionFacts
import app.fusion.wallet.advisor.Explanation
import app.fusion.wallet.advisor.PolicyProposal
import app.fusion.wallet.advisor.RiskSignal
import app.fusion.wallet.data.WalletState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One advisor request on screen: still running, or its result. */
data class AdvisorCall<out T>(val running: Boolean = false, val result: AdvisorResult<T>? = null)

data class AdvisorUiState(
    val settings: AdvisorSettings = AdvisorSettings(),
    val hasKey: Boolean = false,
    val message: String? = null,
    /** Keyed by decision id (passport hash, tick id, "preview"). */
    val explanations: Map<String, AdvisorCall<Explanation>> = emptyMap(),
    val answer: AdvisorCall<AccountantAnswer> = AdvisorCall(),
    val proposal: AdvisorCall<PolicyProposal> = AdvisorCall(),
    val risk: AdvisorCall<RiskSignal> = AdvisorCall(),
) {
    val active: Boolean get() = settings.enabled && hasKey
}

/** Activity-scoped: Settings, the AI Agent screen, the Accountant and Safety share one instance. */
class AdvisorViewModel(app: Application) : AndroidViewModel(app) {
    private val svc = AdvisorServices.get(app)
    private val _state = MutableStateFlow(AdvisorUiState(svc.prefs.snapshot(), svc.keyStore.hasKey()))
    val state: StateFlow<AdvisorUiState> = _state.asStateFlow()

    fun refresh() = _state.update { it.copy(settings = svc.prefs.snapshot(), hasKey = svc.keyStore.hasKey()) }

    fun saveKey(raw: String) {
        val ok = svc.keyStore.save(raw)
        refresh()
        _state.update { it.copy(message = if (ok) "API key stored encrypted on this device (Android Keystore)." else "That does not look like a DeepInfra API key. Nothing was stored.") }
    }

    fun clearKey() {
        svc.keyStore.clear()
        svc.prefs.setEnabled(false)
        refresh()
        _state.update { it.copy(message = "API key removed. Advisor off.") }
    }

    fun setEnabled(on: Boolean) {
        if (on && !svc.keyStore.hasKey()) {
            _state.update { it.copy(message = "Add an API key first.") }
            return
        }
        svc.prefs.setEnabled(on)
        refresh()
    }

    fun setModel(model: String) { svc.prefs.setModel(model); refresh() }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun explain(id: String, facts: DecisionFacts) {
        if (_state.value.explanations[id]?.running == true) return
        _state.update { it.copy(explanations = it.explanations + (id to AdvisorCall(running = true))) }
        viewModelScope.launch {
            val r = svc.advisor.explain(facts)
            _state.update { it.copy(explanations = it.explanations + (id to AdvisorCall(result = r))) }
        }
    }

    fun ask(wallet: WalletState, question: String) {
        if (_state.value.answer.running) return
        _state.update { it.copy(answer = AdvisorCall(running = true)) }
        viewModelScope.launch { _state.update { it.copy(answer = AdvisorCall(result = svc.advisor.ask(wallet, question))) } }
    }

    fun propose(wallet: WalletState, goal: String) {
        if (_state.value.proposal.running) return
        _state.update { it.copy(proposal = AdvisorCall(running = true)) }
        viewModelScope.launch {
            _state.update { it.copy(proposal = AdvisorCall(result = svc.advisor.propose(goal, CurrentPolicy.from(wallet)))) }
        }
    }

    fun classify(text: String) {
        if (_state.value.risk.running) return
        _state.update { it.copy(risk = AdvisorCall(running = true)) }
        viewModelScope.launch { _state.update { it.copy(risk = AdvisorCall(result = svc.advisor.classify(text))) } }
    }
}
