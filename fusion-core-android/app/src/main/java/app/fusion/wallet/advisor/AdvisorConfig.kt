package app.fusion.wallet.advisor

import app.fusion.wallet.accountant.cloud.DeepInfraConfig
import app.fusion.wallet.accountant.cloud.EgressGuard

/**
 * K40 cloud advisor: the second brain. It explains and proposes; it never signs, never picks an
 * executable action and never receives keys or session material. Everything it returns is
 * untrusted text or strict JSON that a deterministic validator in this package checks first.
 */
object AdvisorConfig {
    /** DeepInfra OpenAI-compatible endpoint; the client pins the host. */
    const val BASE_URL = DeepInfraConfig.BASE_URL
    /**
     * Built-in route: a small proxy Worker holds the DeepInfra key, so the app ships no secret and judges need
     * no setup. It allows two models, caps output and rate limits; a user's own key still goes straight to DeepInfra.
     */
    const val PROXY_HOST = "fusion-proxy.adribmahmud.workers.dev"
    const val PROXY_BASE_URL = "https://$PROXY_HOST/v1/openai"
    const val PROXY_FEED_URL = "https://$PROXY_HOST/yields/base-usdc"
    /** Not a secret: the proxy ignores it. It only lets the shared client code run without a stored key. */
    const val BUILTIN_TOKEN = "builtin-proxy"

    const val DEFAULT_MODEL = "deepseek-ai/DeepSeek-V3.2"
    const val FALLBACK_MODEL = "Qwen/Qwen3-235B-A22B-Instruct-2507"
    val MODELS = listOf(DEFAULT_MODEL, FALLBACK_MODEL)

    /** One attempt per model, at most two models per request: no retry storm. */
    const val TIMEOUT_MS = 20_000
    const val MAX_RETRIES = 0

    const val MAX_PAYLOAD_BYTES = 16 * 1024
    const val MAX_QUESTION_CHARS = 300
    const val MAX_GOAL_CHARS = 300
    const val MAX_EXTERNAL_TEXT_CHARS = 4_000

    const val OFF_LABEL = "Advisor off"

    fun label(model: String) = "Cloud explanation · $model"
}

/** Shared checks for every string the model returns. Text is displayed, never executed. */
internal object AdvisorText {
    private val URL = Regex("(?i)(https?://|www\\.|ftp://|\\b[a-z0-9-]+\\.(com|io|xyz|org|net|app|ly)\\b)")
    private val ADDRESS = Regex("0x[0-9a-fA-F]{6,}")
    private val CONTROL = Regex("[\\p{Cntrl}&&[^\\n]]")

    /** Null when the text is acceptable, else the reason it is not. */
    fun problem(s: String, max: Int): String? = when {
        s.isBlank() -> "empty text"
        s.length > max -> "text too long"
        URL.containsMatchIn(s) -> "text contains a link"
        ADDRESS.containsMatchIn(s) -> "text contains an address or hash"
        else -> null
    }

    fun clean(s: String) = s.replace(CONTROL, "").trim()
}

sealed interface EgressCheck {
    data object Ok : EgressCheck
    data class Blocked(val reason: String) : EgressCheck
}

/**
 * What may leave the phone for the advisor: public chain state, our decision reports and the user's
 * own free text. Free text is redacted first (addresses and hashes become placeholders); anything
 * that still looks like key material blocks the whole request. Nothing secret exists to strip,
 * because no key, session or signer object is reachable from this package.
 */
object AdvisorEgress {
    private val HEX_0X = Regex("0x[0-9a-fA-F]{16,}")
    private val BARE_HEX = Regex("\\b[0-9a-fA-F]{32,}\\b")
    private val LONG_HEX = Regex("[0-9a-fA-F]{32,}")
    private val API_KEY = Regex("(?i)(sk-[A-Za-z0-9]{10,}|bearer\\s+[A-Za-z0-9._-]{10,}|api[_-]?key\\s*[:=]|-----BEGIN|deepinfra_api_key)")

    /** Replaces addresses and hashes in user-supplied text with placeholders. */
    fun redact(s: String): String = s.replace(HEX_0X) { if (it.value.length == 42) "[address]" else "[hex]" }
        .replace(BARE_HEX, "[hex]")

    /** [freeText] is the user-typed part; only it is scanned for a recovery phrase (JSON keys would false-positive). */
    fun check(payload: String, apiKey: String?, freeText: String = ""): EgressCheck = when {
        payload.toByteArray(Charsets.UTF_8).size > AdvisorConfig.MAX_PAYLOAD_BYTES -> EgressCheck.Blocked("payload too large")
        !apiKey.isNullOrBlank() && payload.contains(apiKey.trim()) -> EgressCheck.Blocked("payload contains the API key")
        API_KEY.containsMatchIn(payload) -> EgressCheck.Blocked("payload looks like it contains an API key")
        LONG_HEX.containsMatchIn(payload) -> EgressCheck.Blocked("payload contains key-like hex")
        EgressGuard.looksLikeSeedPhrase(freeText.replace(Regex("[^A-Za-z\\s]"), " ")) -> EgressCheck.Blocked("text looks like a recovery phrase")
        else -> EgressCheck.Ok
    }
}
