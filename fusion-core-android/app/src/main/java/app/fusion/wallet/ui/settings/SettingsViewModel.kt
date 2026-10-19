package app.fusion.wallet.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.onboarding.DownloadState
import app.fusion.wallet.onboarding.KeyInventory
import app.fusion.wallet.onboarding.ModelDownloader
import app.fusion.wallet.onboarding.ModelManifest
import app.fusion.wallet.onboarding.ModelStatus
import app.fusion.wallet.onboarding.OnboardingPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Backs the §4 Settings screen. Every value is read from the bundled manifest or the device. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    data class ContractInfo(
        val label: String,
        val address: String,
        val explorerUrl: String,
        val sourcifyUrl: String?,
    )

    data class ModelInfo(
        val status: ModelStatus? = null,
        val hash: String = "",
        val lastLatencyMs: Long? = null,
        val download: DownloadState = DownloadState.Idle,
        val downloading: Boolean = false,
    )

    private val manifestJson: String by lazy {
        getApplication<Application>().assets.open("deployments/84532.json").bufferedReader().use { it.readText() }
    }
    private val modelManifest: ModelManifest by lazy { ModelManifest.fromAssets(getApplication()) }
    private val downloader: ModelDownloader by lazy { ModelDownloader.create(getApplication()) }

    val contracts: List<ContractInfo> by lazy { parseContracts() }
    val explorerBaseUrl: String by lazy { JSONObject(manifestJson).optString("explorerBaseUrl", "https://sepolia.basescan.org") }

    private val _model = MutableStateFlow(ModelInfo())
    val model: StateFlow<ModelInfo> = _model.asStateFlow()

    private val _keys = MutableStateFlow(KeyInventory.read(app))
    val keys: StateFlow<KeyInventory.Keys> = _keys.asStateFlow()

    private var downloadJob: Job? = null

    init { refreshModel() }

    private fun parseContracts(): List<ContractInfo> {
        val root = JSONObject(manifestJson)
        val c = root.optJSONObject("contracts") ?: return emptyList()
        val order = listOf(
            "safe" to "Safe",
            "module" to "KavachiModule",
            "asset" to "Treasury asset (USDC)",
            "vaultA" to "Aave vault (waUSDC)",
            "vaultB" to "Demo vault",
            "sanctionsList" to "Sanctions list",
        )
        return order.mapNotNull { (key, label) ->
            val obj = c.optJSONObject(key) ?: return@mapNotNull null
            val address = obj.optString("address")
            if (address.isEmpty()) return@mapNotNull null
            val explorer = obj.optString("explorer").ifEmpty { "${root.optString("explorerBaseUrl")}/address/$address" }
            val sourcify = obj.optJSONObject("verification")?.optString("url")?.takeIf { it.isNotEmpty() }
            ContractInfo(label, address, explorer, sourcify)
        }
    }

    fun refreshModel() {
        viewModelScope.launch {
            val status = runCatching { downloader.getStatus(modelManifest) }.getOrNull()
            _model.value = _model.value.copy(
                status = status,
                hash = status?.hashes?.get("llm.mnn").orEmpty(),
                lastLatencyMs = OnboardingPrefs.getLastModelLatency(getApplication()),
            )
        }
    }

    fun redownload() {
        if (_model.value.downloading) return
        _model.value = _model.value.copy(downloading = true, download = DownloadState.Idle)
        downloadJob = viewModelScope.launch {
            downloader.download(modelManifest, OnboardingPrefs.isWifiOnly(getApplication())).collect { state ->
                _model.value = _model.value.copy(download = state)
                if (state is DownloadState.Completed || state is DownloadState.Error) {
                    _model.value = _model.value.copy(downloading = false)
                    refreshModel()
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel(); downloadJob = null
        _model.value = _model.value.copy(downloading = false)
        refreshModel()
    }

    fun refreshKeys() { _keys.value = KeyInventory.read(getApplication()) }
    fun removeGuardian() { KeyInventory.removeGuardian(getApplication()); refreshKeys() }
    fun removeSession() { KeyInventory.removeSession(getApplication()); refreshKeys() }
}
