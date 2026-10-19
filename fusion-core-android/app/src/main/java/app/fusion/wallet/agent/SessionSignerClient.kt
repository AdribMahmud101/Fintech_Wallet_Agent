package app.fusion.wallet.agent

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object SessionSignerClient {
    private val lock = Mutex()
    suspend fun execute(context: Context, request: JSONObject): JSONObject = lock.withLock {
        // A lost UI connection does not erase a durable signed attempt.
        withTimeout(240_000) {
            suspendCancellableCoroutine { continuation ->
                var bound = false
                lateinit var connection: ServiceConnection
                fun unbind() { if (bound) { bound = false; runCatching { context.unbindService(connection) } } }
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (message.what == SessionSignerService.EXECUTE && continuation.isActive) {
                        try { continuation.resume(JSONObject(message.data.getString("result") ?: error("Missing session signer result"))) }
                        catch (e: Exception) { continuation.resumeWithException(e) }
                        unbind()
                    }
                    true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        try {
                            Messenger(binder).send(Message.obtain(null, SessionSignerService.EXECUTE).apply {
                                replyTo = reply
                                data = Bundle().apply { putString("report", request.toString()) }
                            })
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e); unbind() }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Session signer process stopped"))
                        unbind()
                    }
                }
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { unbind() } }
                bound = context.bindService(Intent(context, SessionSignerService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("Session signer bind failed"))
            }
        }
    }

}
