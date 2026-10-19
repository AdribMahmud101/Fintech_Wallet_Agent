package app.fusion.wallet.agent

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.Bundle
import android.util.Log
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
        val status = TextView(this).apply { text = "Running on-device AHEM-G probe…"; setPadding(32, 64, 32, 32) }
        setContentView(status)
        scope.launch {
            val mode = intent.getStringExtra("mode") ?: "m1"
            val result = try {
                if (mode == "m1") PlannerClient.fixedProbe(this@AgentControlActivity)
                else {
                    val tick = AgentRuntime.run(this@AgentControlActivity,
                        app.fusion.wallet.data.ChainWalletRepository.fromAssets(this@AgentControlActivity), true).getOrThrow()
                    JSONObject(tick.reportJson)
                }
            }
            catch (e: Exception) { JSONObject().put("error", e.message ?: "Probe failed") }
            withContext(Dispatchers.IO) {
                File(filesDir, if (mode == "m1") "agent/m1.json" else "agent/control_result.json").apply { parentFile!!.mkdirs(); writeText(result.toString()) }
            }
            Log.i("FusionPlanner", if (mode == "m1") "M1 $result" else "DRY_RUN complete; error=${result.optString("error")}")
            status.text = result.toString(2)
            if (mode != "m1" && !result.has("error")) setContent {
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
