package app.fusion.wallet.inference

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PlannerClient {
    private val lock = Mutex()
    suspend fun choose(context: Context, request: JSONObject): JSONObject = lock.withLock {
        // Prefill is synchronous in MNN. A dead or hung process must never yield an action.
        withTimeout(65_000) {
            suspendCancellableCoroutine { continuation ->
                var bound = false
                lateinit var connection: ServiceConnection
                fun unbind() { if (bound) { bound = false; runCatching { context.unbindService(connection) } } }
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (message.what == PlannerService.CHOOSE && continuation.isActive) {
                        try { continuation.resume(JSONObject(message.data.getString("result") ?: error("Missing inference result"))) }
                        catch (e: Exception) { continuation.resumeWithException(e) }
                        unbind()
                    }
                    true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        try {
                            Messenger(binder).send(Message.obtain(null, PlannerService.CHOOSE).apply {
                                replyTo = reply
                                data = Bundle().apply { putString("request", request.toString()) }
                            })
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e); unbind() }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Inference process stopped"))
                        unbind()
                    }
                }
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { unbind() } }
                bound = context.bindService(Intent(context, PlannerService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("Inference bind failed"))
            }
        }
    }

    suspend fun fixedProbe(context: Context): JSONObject = choose(context, JSONObject()
        .put("prompt", "A) Pay 8 USDC; bill due window ELIGIBLE; payee ACTIVE; cash FUNDED; route INSTANT\nH) Hold")
        .put("labels", JSONArray().put("A").put("H")).put("candidateSetDigest", "android-m1-fixed")
        .put("mode", "ahem-g").put("budgetMs", 25000))
}
