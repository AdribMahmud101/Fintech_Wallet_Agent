package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.cloud.ApiKeyProvider
import app.fusion.wallet.accountant.cloud.CloudResult
import app.fusion.wallet.accountant.cloud.DeepInfraClient
import app.fusion.wallet.accountant.cloud.DeepInfraConfig
import app.fusion.wallet.accountant.cloud.HttpResult
import app.fusion.wallet.accountant.cloud.HttpTransport
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class ScriptedTransport(private val script: List<() -> HttpResult>) : HttpTransport {
    var calls = 0
    var lastUrl: String? = null
    var lastHeaders: Map<String, String> = emptyMap()
    var lastBody: String? = null
    override suspend fun postJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult {
        lastUrl = url; lastHeaders = headers; lastBody = body
        return script[minOf(calls++, script.lastIndex)]()
    }
}

private fun ok(content: String) = HttpResult(
    200,
    JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))))
        .put("usage", JSONObject().put("prompt_tokens", 120).put("completion_tokens", 80)).toString(),
)

class DeepInfraClientTest {
    private val key = ApiKeyProvider { "test-key-123" }
    private fun client(t: HttpTransport, k: ApiKeyProvider = key) = DeepInfraClient(t, k, backoffMs = { 0L })
    private val model = "meta-llama/Llama-3.3-70B-Instruct-Turbo"

    @Test fun refuses_retaining_providers_without_touching_the_network() = runTest {
        val t = ScriptedTransport(listOf { ok("{}") })
        for (m in listOf("google/gemma-3-12b-it", "Anthropic/claude-x", "  GOOGLE/anything", "bad model!", "")) {
            val r = client(t).complete(m, "sys", "{}")
            assertEquals(m, CloudResult.Kind.REFUSED_MODEL, (r as CloudResult.Failure).kind)
        }
        assertEquals(0, t.calls)
        assertTrue(DeepInfraConfig.isModelAllowed(model))
    }

    @Test fun missing_key_fails_before_any_request() = runTest {
        val t = ScriptedTransport(listOf { ok("{}") })
        val r = client(t, ApiKeyProvider { null }).complete(model, "s", "{}") as CloudResult.Failure
        assertEquals(CloudResult.Kind.NO_API_KEY, r.kind)
        assertEquals(0, t.calls)
    }

    @Test fun builds_the_documented_openai_compatible_request() = runTest {
        val t = ScriptedTransport(listOf { ok("""{"summary":"x"}""") })
        val r = client(t).complete(model, "SYS", """{"a":1}""") as CloudResult.Success
        assertEquals("""{"summary":"x"}""", r.content)
        assertEquals(120, r.promptTokens)
        assertEquals("https://api.deepinfra.com/v1/openai/chat/completions", t.lastUrl)
        assertEquals("Bearer test-key-123", t.lastHeaders["Authorization"])
        val body = JSONObject(t.lastBody!!)
        assertEquals(model, body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals(listOf("system", "user"), (0 until 2).map { body.getJSONArray("messages").getJSONObject(it).getString("role") })
        assertFalse("the API key must never be in the body", t.lastBody!!.contains("test-key-123"))
    }

    @Test fun retries_429_then_succeeds() = runTest {
        val t = ScriptedTransport(listOf({ HttpResult(429, "{}") }, { ok("{}") }))
        assertTrue(client(t).complete(model, "s", "{}") is CloudResult.Success)
        assertEquals(2, t.calls)
    }

    @Test fun gives_up_after_retries_with_a_typed_failure() = runTest {
        val t = ScriptedTransport(listOf { HttpResult(429, "{}") })
        assertEquals(CloudResult.Kind.RATE_LIMITED, (client(t).complete(model, "s", "{}") as CloudResult.Failure).kind)
        assertEquals(3, t.calls) // 1 try + 2 retries
        val t2 = ScriptedTransport(listOf { HttpResult(503, "{}") })
        assertEquals(CloudResult.Kind.SERVER, (client(t2).complete(model, "s", "{}") as CloudResult.Failure).kind)
    }

    @Test fun auth_errors_are_not_retried() = runTest {
        val t = ScriptedTransport(listOf { HttpResult(401, "{}") })
        assertEquals(CloudResult.Kind.AUTH, (client(t).complete(model, "s", "{}") as CloudResult.Failure).kind)
        assertEquals(1, t.calls)
    }

    @Test fun network_errors_retry_then_report_network() = runTest {
        val t = ScriptedTransport(listOf { throw IOException("boom") })
        val r = client(t).complete(model, "s", "{}") as CloudResult.Failure
        assertEquals(CloudResult.Kind.NETWORK, r.kind)
        assertEquals(3, t.calls)
        assertFalse(r.detail.contains("boom")) // no raw exception text
    }

    @Test fun garbage_success_body_is_a_bad_response_not_a_crash() = runTest {
        val t = ScriptedTransport(listOf { HttpResult(200, "<html>") })
        assertEquals(CloudResult.Kind.BAD_RESPONSE, (client(t).complete(model, "s", "{}") as CloudResult.Failure).kind)
    }

    @Test fun base_url_is_pinned_to_deepinfra_over_https() {
        for (bad in listOf("http://api.deepinfra.com/v1/openai", "https://evil.example/v1/openai", "https://api.deepinfra.com.evil.example/v1")) {
            try {
                DeepInfraClient(ScriptedTransport(listOf { ok("{}") }), key, baseUrl = bad)
                fail("accepted $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }
}
