package app.fusion.wallet.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.data.ChainWalletRepository
import app.fusion.wallet.data.Deployment
import app.fusion.wallet.onboarding.CheckItem
import app.fusion.wallet.onboarding.KeyInventory
import app.fusion.wallet.onboarding.ModelDownloader
import app.fusion.wallet.onboarding.ModelManifest
import app.fusion.wallet.onboarding.ModelSelfTest
import app.fusion.wallet.onboarding.ModelStatus
import app.fusion.wallet.onboarding.OnboardingPrefs
import app.fusion.wallet.onboarding.PolicyReader
import app.fusion.wallet.onboarding.PolicySummary
import app.fusion.wallet.onboarding.SelfTestResult
import app.fusion.wallet.onboarding.TreasuryCheckResult
import app.fusion.wallet.onboarding.TreasuryChecks
import app.fusion.wallet.onboarding.DownloadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the live, config-change-surviving state for the first-run stepper. Every field is fed
 * by a real device/chain/model operation (UX plan rule zero) — there are no placeholder values.
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    data class TreasuryState(
        val loading: Boolean = false,
        val result: TreasuryCheckResult? = null,
    )

    data class ModelState(
        val status: ModelStatus? = null,
        val download: DownloadState = DownloadState.Idle,
        val downloading: Boolean = false,
        val selfTest: SelfTestResult? = null,
        val selfTesting: Boolean = false,
    )

    data class PolicyState(
        val loading: Boolean = false,
        val summary: PolicySummary? = null,
        val error: String? = null,
    )

    private val manifestJson: String by lazy {
        getApplication<Application>().assets.open("deployments/84532.json").bufferedReader().use { it.readText() }
    }
    private val modelManifest: ModelManifest by lazy { ModelManifest.fromAssets(getApplication()) }
    private val downloader: ModelDownloader by lazy { ModelDownloader.create(getApplication()) }

    private val _treasury = MutableStateFlow(TreasuryState())
    val treasury: StateFlow<TreasuryState> = _treasury.asStateFlow()

    private val _model = MutableStateFlow(ModelState())
    val model: StateFlow<ModelState> = _model.asStateFlow()

    private val _policy = MutableStateFlow(PolicyState())
    val policy: StateFlow<PolicyState> = _policy.asStateFlow()

    private val _keys = MutableStateFlow(KeyInventory.read(app))
    val keys: StateFlow<KeyInventory.Keys> = _keys.asStateFlow()

    private var downloadJob: Job? = null

    /** The treasury currency read from the manifest; locked to the deployment. */
    val treasuryCurrency: String = "USDC"
    val currencyNote: String = "Aave test USDC · set by deployment"

    init {
        refreshModelStatus()
    }

    // --- Step 3: live treasury checks ---------------------------------------------------------
    fun runTreasuryChecks() {
        if (_treasury.value.loading) return
        _treasury.value = _treasury.value.copy(loading = true)
        viewModelScope.launch {
            val result = TreasuryChecks().runChecks(manifestJson)
            _treasury.value = TreasuryState(loading = false, result = result)
        }
    }

    // --- Step 4: model provisioning + self-test -----------------------------------------------
    fun refreshModelStatus() {
        viewModelScope.launch {
            val status = runCatching { downloader.getStatus(modelManifest) }.getOrNull()
            _model.value = _model.value.copy(status = status)
        }
    }

    fun startDownload(wifiOnly: Boolean) {
        if (_model.value.downloading) return
        OnboardingPrefs.setWifiOnly(getApplication(), wifiOnly)
        _model.value = _model.value.copy(downloading = true, download = DownloadState.Idle)
        downloadJob = viewModelScope.launch {
            downloader.download(modelManifest, wifiOnly).collect { state ->
                _model.value = _model.value.copy(download = state)
                if (state is DownloadState.Completed || state is DownloadState.Error) {
                    _model.value = _model.value.copy(downloading = false)
                    refreshModelStatus()
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _model.value = _model.value.copy(downloading = false)
        refreshModelStatus()
    }

    fun runSelfTest() {
        if (_model.value.selfTesting) return
        _model.value = _model.value.copy(selfTesting = true)
        viewModelScope.launch {
            val result = ModelSelfTest.run(getApplication())
            if (result.ok && result.decodeMs != null) {
                OnboardingPrefs.setLastModelLatency(getApplication(), result.decodeMs.toLong())
            }
            _model.value = _model.value.copy(selfTesting = false, selfTest = result)
        }
    }

    // --- Step 6: policy review ----------------------------------------------------------------
    fun loadPolicy() {
        if (_policy.value.loading || _policy.value.summary != null) return
        _policy.value = _policy.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                // Phase 1: policy, limits and reserve in one batched read (fast).
                val quick = PolicyReader().read(manifestJson, includeVendors = false)
                _policy.value = PolicyState(loading = false, summary = quick)
                // Consume the wallet's shared persisted event index; never launch a second deployment scan.
                val repository = ChainWalletRepository.fromAssets(getApplication())
                repository.refresh()
                val wallet = repository.state.value
                if (wallet.historyError != null || wallet.error != null) {
                    _policy.value = PolicyState(summary = quick, error = wallet.historyError ?: wallet.error)
                } else {
                    val full = PolicyReader.fromWalletState(wallet, Deployment.parse(manifestJson))
                    if (full != null) _policy.value = PolicyState(summary = full)
                }
            } catch (e: Exception) {
                _policy.value = PolicyState(loading = false, error = e.message?.take(200) ?: "Could not read policy")
            }
        }
    }

    // --- Step 5: keys -------------------------------------------------------------------------
    fun refreshKeys() {
        _keys.value = KeyInventory.read(getApplication())
    }

    fun removeGuardian() {
        KeyInventory.removeGuardian(getApplication())
        refreshKeys()
    }

    fun removeSession() {
        KeyInventory.removeSession(getApplication())
        refreshKeys()
    }

    fun treasuryChecksOrEmpty(): List<CheckItem> = _treasury.value.result?.checks ?: emptyList()
}
