package app.fusion.wallet.inference

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** One process-global sampler, one generation at a time. Only same-UID callers. */
class PlannerService : Service() {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loaded: JSONObject? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == CHOOSE && message.sendingUid == Process.myUid() && message.replyTo != null) {
            val request = message.data.getString("request").orEmpty()
            val reply = message.replyTo
            scope.launch {
                val watchdog = Handler(Looper.getMainLooper())
                val kill = Runnable { Process.killProcess(Process.myPid()) }
                watchdog.postDelayed(kill, 60_000)
                val result = try {
                    require(request.length <= 16_384)
                    if (loaded == null) {
                        val input = JSONObject().put("configPath", File(filesDir, "models/lfm_230m/config.json").path)
                            .put("nativeLibraryDir", applicationInfo.nativeLibraryDir)
                            .put("expectedHashes", JSONObject(ProvisionedHashes.JSON))
                        val load = JSONObject(NativePlanner.call("load", input.toString()))
                        check(load.optString("state") == "READY") { load.toString() }
                        loaded = load
                    }
                    val output = JSONObject(NativePlanner.call("choose", request))
                    output.put("load", loaded).put("process", ApplicationProcess.name())
                        .put("pid", Process.myPid()).put("plannerKind", "mnn+ahem-g@android")
                    if (output.optJSONObject("error") != null) loaded = null
                    output.toString()
                } catch (error: Exception) {
                    loaded = null
                    JSONObject().put("ok", false).put("error", JSONObject().put("code", error.message?.take(512) ?: "INFERENCE_FAILED")).toString()
                }
                watchdog.removeCallbacks(kill)
                try { reply.send(Message.obtain(null, CHOOSE).apply { data = Bundle().apply { putString("result", result) } }) }
                catch (_: Exception) { /* Caller watchdog expired. */ }
            }
        }
        true
    })
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { scope.cancel(); dispatcher.close(); super.onDestroy() }
    companion object { const val CHOOSE = 1 }
}

private object ApplicationProcess {
    fun name(): String = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else "app.fusion.wallet:inference"
}
