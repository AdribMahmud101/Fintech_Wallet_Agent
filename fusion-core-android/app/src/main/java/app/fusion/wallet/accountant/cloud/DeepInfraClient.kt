package app.fusion.wallet.accountant.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Minimal HTTP seam so the client is testable without a network. */
interface HttpTransport {
    suspend fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult
}

data class HttpResult(val code: Int, val body: String)

fun interface ApiKeyProvider {
    fun apiKey(): String?
}

sealed interface CloudResult {
    data class Success(val content: String, val promptTokens: Int?, val completionTokens: Int?, val httpCode: Int) : CloudResult
    data class Failure(val kind: Kind, val httpCode: Int? = null, val detail: String) : CloudResult
    enum class Kind { NO_API_KEY, REFUSED_MODEL, RATE_LIMITED, SERVER, NETWORK, BAD_RESPONSE, AUTH }
}

/** What the Accountant needs from a cloud model. The agent depends on this, not on DeepInfra. */
interface CloudClient {
    val provider: String
    suspend fun complete(model: String, system: String, userJson: String): CloudResult
}

object DeepInfraConfig {
    const val PROVIDER = "deepinfra"

    /** Pinned. A different host or scheme is refused, so a bad config cannot redirect traffic. */
    const val BASE_URL = "https://api.deepinfra.com/v1/openai"
    const val HOST = "api.deepinfra.com"
    const val MAX_TOKENS = 700
    const val TIMEOUT_MS = 25_000
    const val MAX_RETRIES = 2
    const val MAX_RESPONSE_BYTES = 64 * 1024

    /**
     * DeepInfra's data-privacy page says prompts/outputs are not stored on disk, EXCEPT for Google
     * and Anthropic models, which go to those providers under their own retention. We refuse them
     * outright rather than rely on the user noticing.
     */
    private val RETAINING_PREFIXES = listOf("google/", "anthropic/")

    fun isModelAllowed(model: String): Boolean {
        val m = model.trim().lowercase()
        return m.isNotEmpty() && m.length <= 120 && Regex("^[a-z0-9._/:-]+$").matches(m) &&
            RETAINING_PREFIXES.none { m.startsWith(it) }
    }
}

class DeepInfraClient(
    private val transport: HttpTransport,
    private val keys: ApiKeyProvider,
    private val baseUrl: String = DeepInfraConfig.BASE_URL,
    private val maxTokens: Int = DeepInfraConfig.MAX_TOKENS,
    private val temperature: Double = 0.2,
    private val timeoutMs: Int = DeepInfraConfig.TIMEOUT_MS,
    private val maxRetries: Int = DeepInfraConfig.MAX_RETRIES,
    private val backoffMs: (attempt: Int) -> Long = { 1_000L shl it },
) : CloudClient {
    override val provider: String get() = DeepInfraConfig.PROVIDER

    init {
        val u = URI(baseUrl)
        require(u.scheme == "https" && u.host == DeepInfraConfig.HOST) { "base URL must be https://${DeepInfraConfig.HOST}" }
    }

    override suspend fun complete(model: String, system: String, userJson: String): CloudResult {
        if (!DeepInfraConfig.isModelAllowed(model)) {
            return CloudResult.Failure(CloudResult.Kind.REFUSED_MODEL, detail = "model not allowed by privacy policy")
        }
        val key = keys.apiKey()?.trim().orEmpty()
        if (key.isEmpty()) return CloudResult.Failure(CloudResult.Kind.NO_API_KEY, detail = "no API key configured")

        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", userJson)),
            )
            .toString()
        val headers = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json", "Accept" to "application/json")
        val url = "$baseUrl/chat/completions"

        var attempt = 0
        while (true) {
            val res = try {
                transport.postJson(url, headers, body, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt < maxRetries) { delay(backoffMs(attempt++)); continue }
                return CloudResult.Failure(CloudResult.Kind.NETWORK, detail = e.javaClass.simpleName)
            }
            when {
                res.code in 200..299 -> return parse(res)
                res.code == 401 || res.code == 403 -> return CloudResult.Failure(CloudResult.Kind.AUTH, res.code, "authentication rejected")
                (res.code == 429 || res.code in 500..599) && attempt < maxRetries -> delay(backoffMs(attempt++))
                res.code == 429 -> return CloudResult.Failure(CloudResult.Kind.RATE_LIMITED, res.code, "rate limited")
                res.code in 500..599 -> return CloudResult.Failure(CloudResult.Kind.SERVER, res.code, "server error")
                else -> return CloudResult.Failure(CloudResult.Kind.BAD_RESPONSE, res.code, "unexpected status")
            }
        }
    }

    private fun parse(res: HttpResult): CloudResult = try {
        val root = JSONObject(res.body)
        val content = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val usage = root.optJSONObject("usage")
        CloudResult.Success(content, usage?.optInt("prompt_tokens")?.takeIf { it > 0 }, usage?.optInt("completion_tokens")?.takeIf { it > 0 }, res.code)
    } catch (e: JSONException) {
        CloudResult.Failure(CloudResult.Kind.BAD_RESPONSE, res.code, "unparseable response")
    }
}

/** Real transport over HttpURLConnection. No redirects, bounded body, closed on every path. */
class HttpUrlConnectionTransport : HttpTransport {
    override suspend fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult =
        withContext(Dispatchers.IO) {
            val conn = (java.net.URL(url).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.use { s ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = s.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > DeepInfraConfig.MAX_RESPONSE_BYTES) throw IOException("response too large")
                    }
                    buf.toString(Charsets.UTF_8.name())
                }.orEmpty()
                HttpResult(code, text)
            } finally {
                conn.disconnect()
            }
        }
}
