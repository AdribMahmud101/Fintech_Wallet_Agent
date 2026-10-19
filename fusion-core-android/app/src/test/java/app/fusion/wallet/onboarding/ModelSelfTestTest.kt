package app.fusion.wallet.onboarding

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelSelfTestTest {

    @Test
    fun parse_successReadsChoiceAndLatencies() {
        val model = JSONObject(
            """
            {
              "label": "A",
              "rawValid": true,
              "plannerKind": "mnn+ahem-g@android",
              "timings": { "totalMs": 241.0 },
              "load": { "state": "READY", "loadMs": 812.0, "hashes": { "llm.mnn": "abc" } }
            }
            """.trimIndent()
        )
        val r = SelfTestResult.parse(model)
        assertTrue(r.ok)
        assertEquals("A", r.choice)
        assertEquals(812.0, r.loadMs!!, 0.001)
        assertEquals(241.0, r.decodeMs!!, 0.001)
        assertTrue(r.latencyLabel.contains("load 812 ms"))
        assertTrue(r.latencyLabel.contains("decode 241 ms"))
        assertNull(r.error)
    }

    @Test
    fun parse_loadMsFallsBackToNestedTimings() {
        val model = JSONObject(
            """
            { "label": "H", "rawValid": true,
              "timings": { "totalMs": 300.0 },
              "load": { "timings": { "totalMs": 900.0 } } }
            """.trimIndent()
        )
        val r = SelfTestResult.parse(model)
        assertEquals(900.0, r.loadMs!!, 0.001)
        assertEquals("H", r.choice)
    }

    @Test
    fun parse_errorObjectIsFailure() {
        val model = JSONObject("""{ "ok": false, "error": { "code": "INFERENCE_FAILED" } }""")
        val r = SelfTestResult.parse(model)
        assertFalse(r.ok)
        assertEquals("INFERENCE_FAILED", r.error)
        assertEquals("", r.choice)
    }

    @Test
    fun parse_missingLatenciesDoesNotCrash() {
        val model = JSONObject("""{ "label": "A", "rawValid": true }""")
        val r = SelfTestResult.parse(model)
        assertTrue(r.ok)
        assertNull(r.loadMs)
        assertNull(r.decodeMs)
        assertEquals("", r.latencyLabel)
    }
}
