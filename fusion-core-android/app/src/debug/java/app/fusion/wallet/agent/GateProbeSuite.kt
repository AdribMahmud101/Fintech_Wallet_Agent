package app.fusion.wallet.agent

import android.content.Context
import app.fusion.wallet.inference.PlannerClient
import org.json.JSONArray
import org.json.JSONObject

/** Real-device regression for the patched native dimension handling and callback lifecycle. */
internal object GateProbeSuite {
    private fun request(label: String?, digest: String, ban: Boolean = false): JSONObject = JSONObject()
        .put("prompt", if (label == null) "H) Hold; no eligible action is available." else "$label) Pay 8 USDC; bill due window ELIGIBLE; payee ACTIVE; cash FUNDED; route INSTANT\nH) Hold")
        .put("labels", JSONArray().apply { label?.let { put(it) }; put("H") }).put("candidateSetDigest", digest)
        .put("mode", "ahem-g").put("budgetMs", 25000).put("banAll", ban)
    suspend fun run(context: Context): JSONObject {
        val runs = JSONArray()
        repeat(10) { i ->
            val label = if (i % 4 == 3) null else listOf("A", "B", "G")[i%3]
            val req = request(label, "android-gate-$i")
            val result = PlannerClient.choose(context, req)
            check(result.optBoolean("rawValid") && result.getString("label") in listOfNotNull(label, "H") &&
                !result.getJSONObject("ahem").getJSONObject("g").getBoolean("aborted") && result.getString("candidateSetDigest") == req.getString("candidateSetDigest"))
            runs.put(JSONObject().put("request", req).put("result", result))
        }
        val banned = PlannerClient.choose(context, request("A", "android-ban-all", true))
        check(banned.getString("label") == "H" && banned.getJSONObject("ahem").getJSONObject("g").getBoolean("aborted") &&
            banned.getJSONObject("ahem").getJSONObject("g").getLong("vetoes") > 0)
        val after = PlannerClient.choose(context, request("A", "android-after-abort"))
        check(after.getBoolean("rawValid") && !after.getJSONObject("ahem").getJSONObject("g").getBoolean("aborted"))
        return JSONObject().put("pass", true).put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("android", android.os.Build.VERSION.RELEASE).put("runs", runs).put("banAll", banned).put("afterAbort", after)
    }
}
