package app.fusion.wallet.onboarding

import android.content.Context
import app.fusion.wallet.inference.PlannerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Warm-up self-test for the on-device planner (onboarding step 4). Runs K17's in-process
 * planner once with a fixed A/H probe under AHEM-G and reports the letter it chose plus the
 * measured latency. This is the same engine the Agent tab uses — no stubbed result.
 */
data class SelfTestResult(
    val ok: Boolean,
    val choice: String,
    val loadMs: Double?,
    val decodeMs: Double?,
    val plannerKind: String,
    val error: String?,
) {
    /** "A · load 812 ms · decode 240 ms" style summary for the UI. */
    val latencyLabel: String
        get() = buildString {
            loadMs?.let { append("load ${it.toLong()} ms") }
            if (loadMs != null && decodeMs != null) append(" · ")
            decodeMs?.let { append("decode ${it.toLong()} ms") }
        }

    companion object {
        /** Parse the raw planner reply into a presentable result. Pure, so it is unit-tested. */
        fun parse(model: JSONObject): SelfTestResult {
            val error = model.optJSONObject("error")
            if (error != null || model.has("error")) {
                return SelfTestResult(
                    ok = false, choice = "", loadMs = null, decodeMs = null,
                    plannerKind = model.optString("plannerKind", ""),
                    error = (error?.optString("code") ?: model.optString("error")).ifEmpty { "INFERENCE_FAILED" },
                )
            }
            val load = model.optJSONObject("load")
            val loadMs = load?.let { it.optDoubleOrNull("loadMs") ?: it.optJSONObject("timings")?.optDoubleOrNull("totalMs") }
            val decodeMs = model.optJSONObject("timings")?.optDoubleOrNull("totalMs")
            return SelfTestResult(
                ok = model.optBoolean("rawValid", model.has("label")),
                choice = model.optString("label"),
                loadMs = loadMs,
                decodeMs = decodeMs,
                plannerKind = model.optString("plannerKind", "mnn+ahem-g@android"),
                error = null,
            )
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key) else null
    }
}

object ModelSelfTest {
    suspend fun run(context: Context): SelfTestResult = withContext(Dispatchers.IO) {
        try {
            SelfTestResult.parse(PlannerClient.fixedProbe(context))
        } catch (e: Exception) {
            SelfTestResult(
                ok = false, choice = "", loadMs = null, decodeMs = null,
                plannerKind = "mnn+ahem-g@android",
                error = e.message?.take(200) ?: "Self-test failed",
            )
        }
    }
}
