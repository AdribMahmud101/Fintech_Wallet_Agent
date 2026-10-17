package app.fusion.wallet.ui.accountant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.accountant.AccountantServices
import app.fusion.wallet.accountant.DemoSeeder
import app.fusion.wallet.accountant.agent.AccountantAgent
import app.fusion.wallet.accountant.agent.AccountantReply
import app.fusion.wallet.accountant.agent.LocalAccountant
import app.fusion.wallet.accountant.agent.LocalFinding
import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.cloud.EgressGuard
import app.fusion.wallet.accountant.cloud.GuardPolicy
import app.fusion.wallet.accountant.cloud.GuardResult
import app.fusion.wallet.accountant.cloud.DeepInfraConfig
import app.fusion.wallet.accountant.data.EgressLogEntity
import app.fusion.wallet.accountant.data.InsightEntity
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.data.PrivacySettingsEntity
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user would send right now, and whether the guard would let it out. */
data class BriefPreview(val json: String, val allowed: Boolean, val blockedRules: List<String>)

data class AccountantUiState(
    val loading: Boolean = true,
    val snapshot: PortfolioSnapshot? = null,
    val local: List<LocalFinding> = emptyList(),
    val settings: PrivacySettingsEntity = PrivacySettingsEntity(),
    val hasApiKey: Boolean = false,
    val insights: List<InsightEntity> = emptyList(),
    val egress: List<EgressLogEntity> = emptyList(),
    val preview: BriefPreview? = null,
    val asking: Boolean = false,
    val reply: AccountantReply? = null,
    val message: String? = null,
)

class AccountantViewModel(app: Application) : AndroidViewModel(app) {
    private val svc = AccountantServices.get(app)
    private val _state = MutableStateFlow(AccountantUiState(hasApiKey = svc.keyStore.hasKey()))
    val state: StateFlow<AccountantUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Debug builds only: seeds the spec's reference state. Release is a no-op.
            DemoSeeder.seedIfEmpty(svc.db)
            refresh()
        }
        viewModelScope.launch { svc.db.sinkDao().observeSettings().collect { s -> _state.update { it.copy(settings = s ?: PrivacySettingsEntity()) } } }
        viewModelScope.launch { svc.db.readDao().observeInsights(30).collect { l -> _state.update { it.copy(insights = l) } } }
        viewModelScope.launch { svc.db.readDao().observeEgress(30).collect { l -> _state.update { it.copy(egress = l) } } }
    }

    fun refresh() {
        viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            val snap = svc.readModel.snapshot(now)
            _state.update {
                it.copy(
                    loading = false, snapshot = snap,
                    local = snap?.let(LocalAccountant::analyze).orEmpty(),
                    hasApiKey = svc.keyStore.hasKey(),
                )
            }
            updatePreview(null)
        }
    }

    /** Recomputes the exact payload that *would* be sent, so the user can inspect it before asking. */
    fun updatePreview(question: String?, topic: InsightTopic = InsightTopic.GENERAL) {
        viewModelScope.launch {
            val snap = _state.value.snapshot ?: return@launch
            val settings = _state.value.settings
            val json = BriefBuilder.build(snap, topic, question?.takeIf { it.isNotBlank() }).toJson()
            val res = EgressGuard.check(json, GuardPolicy(settings.maxPayloadBytes, svc.readModel.privateStrings()))
            _state.update {
                it.copy(preview = BriefPreview(json, res is GuardResult.Ok, (res as? GuardResult.Blocked)?.rules.orEmpty()))
            }
        }
    }

    fun ask(topic: InsightTopic, question: String) {
        if (_state.value.asking) return
        viewModelScope.launch {
            _state.update { it.copy(asking = true, message = null) }
            val reply = try {
                svc.agent.ask(topic, question.takeIf { it.isNotBlank() })
            } catch (e: Exception) {
                _state.update { it.copy(asking = false, message = "Accountant failed: ${e.javaClass.simpleName}") }
                return@launch
            }
            _state.update { it.copy(asking = false, reply = reply, local = reply.local.ifEmpty { it.local }) }
        }
    }

    /** Explicit opt-in. Records which consent version the user saw. */
    fun grantConsent() = saveSettings { it.copy(cloudEnabled = true, consentVersion = AccountantAgent.CONSENT_VERSION, consentedAt = System.currentTimeMillis() / 1000) }

    fun revokeConsent() = saveSettings { it.copy(cloudEnabled = false) }

    fun setDailyLimit(n: Int) = saveSettings { it.copy(dailyCallLimit = n.coerceIn(1, 100)) }

    fun setModel(model: String) {
        if (!DeepInfraConfig.isModelAllowed(model)) {
            _state.update { it.copy(message = "That model is not allowed (Google/Anthropic models are retained by those providers).") }
            return
        }
        saveSettings { it.copy(model = model.trim()) }
    }

    fun saveApiKey(key: String) {
        if (key.isBlank()) return
        svc.keyStore.save(key)
        _state.update { it.copy(hasApiKey = true, message = "API key stored encrypted on this device.") }
    }

    fun clearApiKey() {
        svc.keyStore.clear()
        _state.update { it.copy(hasApiKey = false, message = "API key removed.") }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun saveSettings(f: (PrivacySettingsEntity) -> PrivacySettingsEntity) {
        viewModelScope.launch { svc.db.sinkDao().saveSettings(f(svc.audit.settings())) }
    }
}
