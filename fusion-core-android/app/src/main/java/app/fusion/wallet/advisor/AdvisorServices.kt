package app.fusion.wallet.advisor

import android.content.Context
import app.fusion.wallet.accountant.cloud.DeepInfraClient
import app.fusion.wallet.accountant.cloud.HttpUrlConnectionTransport
import app.fusion.wallet.accountant.cloud.KeystoreApiKeyStore

/** Advisor on/off and model choice. Off by default; nothing secret is stored here. */
class AdvisorPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("advisor_prefs", Context.MODE_PRIVATE)

    fun snapshot() = AdvisorSettings(
        enabled = prefs.getBoolean(K_ENABLED, false),
        model = prefs.getString(K_MODEL, null)?.takeIf { it in AdvisorConfig.MODELS } ?: AdvisorConfig.DEFAULT_MODEL,
    )

    fun setEnabled(on: Boolean) = prefs.edit().putBoolean(K_ENABLED, on).apply()

    fun setModel(model: String) {
        if (model in AdvisorConfig.MODELS) prefs.edit().putString(K_MODEL, model).apply()
    }

    private companion object {
        const val K_ENABLED = "enabled"
        const val K_MODEL = "model"
    }
}

/** Process-wide wiring for the K40 advisor. The client gets the key store and nothing else. */
class AdvisorServices private constructor(context: Context) {
    val keyStore = KeystoreApiKeyStore(context)
    val prefs = AdvisorPrefs(context)
    private val client = DeepInfraClient(
        HttpUrlConnectionTransport(), keyStore, baseUrl = AdvisorConfig.BASE_URL,
        timeoutMs = AdvisorConfig.TIMEOUT_MS, maxRetries = AdvisorConfig.MAX_RETRIES,
    )
    val advisor = CloudAdvisor(client, keyStore, settings = prefs::snapshot)

    companion object {
        @Volatile private var instance: AdvisorServices? = null
        fun get(context: Context): AdvisorServices =
            instance ?: synchronized(this) { instance ?: AdvisorServices(context.applicationContext).also { instance = it } }
    }
}
