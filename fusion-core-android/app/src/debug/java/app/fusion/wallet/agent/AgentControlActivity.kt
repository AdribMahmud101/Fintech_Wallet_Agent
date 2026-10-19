package app.fusion.wallet.agent

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import app.fusion.wallet.inference.PlannerClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** adb-only evidence harness, debug source set. Never accepts keys or candidate overrides. */
class AgentControlActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Foreground evidence ticks must finish before the unchanged live-state
        // freshness bound expires, even on a phone with a short screen timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mode = intent.getStringExtra("mode") ?: "m1"
        val status = TextView(this).apply {
            text = if (mode in setOf("m1", "probe-suite")) "Running on-device AHEM-G probe…" else "Running in-app agent tick…"
            setPadding(32, 64, 32, 32)
        }
        setContentView(status)
        scope.launch {
            val result = try {
                if (mode == "m1") PlannerClient.fixedProbe(this@AgentControlActivity)
                else if (mode == "probe-suite") GateProbeSuite.run(this@AgentControlActivity)
                else {
                    val tick = AgentRuntime.run(this@AgentControlActivity,
                        app.fusion.wallet.data.ChainWalletRepository.fromAssets(this@AgentControlActivity), mode != "broadcast").getOrThrow()
                    JSONObject(tick.reportJson)
                }
            }
            catch (e: Exception) { JSONObject().put("error", e.message ?: "Probe failed") }
            intent.getStringExtra("requestId")?.let { result.put("requestId", it) }
            withContext(Dispatchers.IO) {
                File(filesDir, if (mode == "m1") "agent/m1.json" else "agent/control_result.json").apply { parentFile!!.mkdirs(); writeText(result.toString()) }
            }
            Log.i("FusionPlanner", if (mode == "m1") "M1 $result" else "$mode complete; error=${result.optString("error")}")
            status.text = result.toString(2)
            if (result.has("passportJson") && !result.has("error")) setContent {
                app.fusion.wallet.ui.theme.FusionWalletTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Fusion Wallet · AI Agent", style = MaterialTheme.typography.headlineSmall)
                            Text("Live Base Sepolia · ${android.os.Build.MODEL}", style = MaterialTheme.typography.bodyMedium)
                            AgentRunCard { dryRun -> AgentRuntime.run(this@AgentControlActivity,
                                app.fusion.wallet.data.ChainWalletRepository.fromAssets(this@AgentControlActivity), dryRun) }
                        }
                    }
                }
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
