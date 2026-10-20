package app.fusion.wallet.scout

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.advisor.AdvisorConfig
import app.fusion.wallet.advisor.AdvisorResult
import app.fusion.wallet.advisor.AdvisorServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ScoutUiState(val loading: Boolean = true, val feed: ScoutFeed? = null, val error: String? = null)

sealed interface ScoutWhy {
    data object Idle : ScoutWhy
    data object Loading : ScoutWhy
    data class Done(val text: String, val model: String) : ScoutWhy
    data class Unavailable(val reason: String) : ScoutWhy
}

/** Loads the open-data yield feed (cached on the phone), and asks the cloud advisor to explain the top pick. */
class ScoutViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("scout_cache", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ScoutUiState())
    val state: StateFlow<ScoutUiState> = _state.asStateFlow()
    private val _why = MutableStateFlow<ScoutWhy>(ScoutWhy.Idle)
    val why: StateFlow<ScoutWhy> = _why.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val live = withContext(Dispatchers.IO) { fetchLive() }
            _state.value = if (live != null) ScoutUiState(false, live) else {
                val cached = runCatching { prefs.getString(KEY, null)?.let { ScoutParser.parse(it, cached = true) } }.getOrNull()
                ScoutUiState(false, cached, if (cached == null) "Could not reach the yield feed" else null)
            }
        }
    }

    private fun fetchLive(): ScoutFeed? = try {
        val c = URL(AdvisorConfig.PROXY_FEED_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000; c.readTimeout = 10_000; c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) null else {
                val body = c.inputStream.use { it.readBytes() }
                if (body.size > MAX_BYTES) null else String(body, Charsets.UTF_8).let { raw ->
                    ScoutParser.parse(raw)?.also { prefs.edit().putString(KEY, raw).apply() }
                }
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    fun explain(top: PoolScore, others: List<PoolScore>, ctx: ScoutContext) {
        if (_why.value is ScoutWhy.Loading) return
        _why.value = ScoutWhy.Loading
        viewModelScope.launch {
            val result = AdvisorServices.get(getApplication()).advisor.explainPick(facts(top, others, ctx))
            _why.value = when (result) {
                is AdvisorResult.Ok -> ScoutWhy.Done(result.value.text, result.model)
                is AdvisorResult.Off -> ScoutWhy.Unavailable(result.reason)
                is AdvisorResult.Blocked -> ScoutWhy.Unavailable(result.reason)
                is AdvisorResult.Failed -> ScoutWhy.Unavailable("Advisor unreachable right now")
                is AdvisorResult.Rejected -> ScoutWhy.Unavailable("The explanation did not pass the safety check")
            }
        }
    }

    fun resetWhy() { _why.value = ScoutWhy.Idle }

    private var lastSpare: Double? = null
    private var lastGas: Double? = null

    /** A refresh briefly drops the live numbers; keep the last measured ones so rules do not flicker to "not measured". */
    fun stable(ctx: ScoutContext): ScoutContext {
        ctx.spareCashUsd?.let { lastSpare = it }
        ctx.agentGasUsd?.let { lastGas = it }
        return ScoutContext(ctx.spareCashUsd ?: lastSpare, ctx.agentGasUsd ?: lastGas)
    }

    companion object {
        private const val KEY = "feed"
        private const val MAX_BYTES = 256 * 1024

        /** Only numbers the app computed or the feed published; every value is a string so citations compare exactly. */
        internal fun facts(top: PoolScore, others: List<PoolScore>, ctx: ScoutContext): JSONObject {
            fun p2(v: Double?) = v?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "not measured"
            return JSONObject().put("schema", "fw.advisor.scout.v1")
                .put("spareCashUsdc", p2(ctx.spareCashUsd))
                .put("pick", JSONObject().put("project", top.pool.project).put("tvlUsd", String.format(java.util.Locale.US, "%.0f", top.pool.tvlUsd))
                    .put("conservativeApyPct", p2(top.conservativeApy)).put("spotApyPct", p2(top.pool.apy)).put("mean30dApyPct", p2(top.pool.apyMean30d))
                    .put("breakEvenDays", p2(top.breakEvenDays)).put("rulesPassed", top.passes.toString()).put("rulesMeasured", top.measured.toString())
                    .put("rules", JSONArray().also { a -> top.rules.forEach { a.put(JSONObject().put("rule", it.title).put("status", it.status.name).put("value", it.value)) } }))
                .put("others", JSONArray().also { a -> others.take(3).forEach {
                    a.put(JSONObject().put("project", it.pool.project).put("conservativeApyPct", p2(it.conservativeApy)).put("eligible", it.eligible.toString()).put("rulesFailed", it.fails.toString()))
                } })
        }
    }
}
