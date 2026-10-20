package app.fusion.wallet.agent.autopilot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

interface AutopilotStore {
    fun load(): AutopilotState
    fun save(state: AutopilotState)
}

/** App-private, keyless journal. Sync then rename keeps the previous journal intact on failed writes. */
class FileAutopilotStore(private val file: File) : AutopilotStore {
    @Synchronized override fun load(): AutopilotState {
        if (!file.exists()) return AutopilotState()
        require(file.length() <= 4_194_304) { "Autopilot journal exceeds limit" }
        val json = JSONObject(file.readText())
        require(json.getInt("version") == 1) { "Unsupported Autopilot journal" }
        val ticks = json.getJSONArray("timeline")
        val timeline = (0 until minOf(ticks.length(), 50)).map { index ->
            val t = ticks.getJSONObject(index)
            val checks = t.getJSONArray("checks")
            AutopilotTick(t.getLong("id"), t.getLong("timeMillis"), t.getString("letter"), t.getString("title"), t.getString("reason"),
                t.getString("outcome"), t.getString("mode"), (0 until checks.length()).map { i ->
                    val c = checks.getJSONObject(i)
                    TickCheck(c.getString("name"), c.getString("status"), c.getString("message"))
                }, t.optString("txHash").takeIf { Regex("0x[0-9a-fA-F]{64}").matches(it) }, t.optString("wakePath", "Heartbeat"),
                t.optString("reportJson").takeIf { it.isNotBlank() && it != "null" },
                t.optString("passportJson").takeIf { it.isNotBlank() && it != "null" })
        }.map { tick ->
            if (tick.outcome == "RUNNING") tick.copy(letter = "—", title = "Interrupted tick", outcome = "UNCONFIRMED",
                reason = "App closed during tick; execution not confirmed. Check Activity before retrying.") else tick
        }
        return AutopilotState(enabled = json.getBoolean("enabled"), intervalMinutes = json.getInt("intervalMinutes").takeIf { it in setOf(1, 5, 15) } ?: 5, timeline = timeline, previewOnly = json.optBoolean("previewOnly"), autoProtect = json.optBoolean("autoProtect"))
    }

    @Synchronized override fun save(state: AutopilotState) {
        val timeline = JSONArray().apply { state.timeline.take(50).forEach { t ->
            put(JSONObject().put("id", t.id).put("timeMillis", t.timeMillis).put("letter", t.letter).put("title", t.title)
                .put("reason", t.reason.take(512)).put("outcome", t.outcome).put("mode", t.mode).put("txHash", t.txHash ?: JSONObject.NULL)
                .put("wakePath", t.wakePath).put("reportJson", t.reportJson ?: JSONObject.NULL).put("passportJson", t.passportJson ?: JSONObject.NULL)
                .put("checks", JSONArray().apply { t.checks.forEach { c -> put(JSONObject().put("name", c.name).put("status", c.status).put("message", c.message.take(512))) } }))
        } }
        val json = JSONObject().put("version", 1).put("enabled", state.enabled).put("intervalMinutes", state.intervalMinutes).put("previewOnly", state.previewOnly).put("autoProtect", state.autoProtect).put("timeline", timeline)
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot create Autopilot storage" } }
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { stream -> stream.write(json.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        check(tmp.renameTo(file)) { "Cannot persist Autopilot timeline" }
    }
}
