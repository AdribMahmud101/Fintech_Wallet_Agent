package app.fusion.wallet.signer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object GuardianSignerClient {
    suspend fun requestPause(context: Context): Result<String> = withContext(Dispatchers.Main.immediate) {
        var bound = false
        var connection: ServiceConnection? = null
        try {
            val hash = withTimeout(240_000) {
                suspendCancellableCoroutine<String> { continuation ->
                    val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (continuation.isActive && message.what == GuardianSignerService.PAUSE) {
                            val tx = message.data.getString(GuardianSignerService.TX_HASH)
                            if (tx != null && Regex("0x[0-9a-f]{64}").matches(tx)) continuation.resume(tx)
                            else continuation.resumeWith(Result.failure(IllegalStateException(
                                message.data.getString(GuardianSignerService.ERROR) ?: "Signer returned an invalid response",
                            )))
                        }
                        true
                    })
                    val callback = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            try {
                                Messenger(binder).send(Message.obtain(null, GuardianSignerService.PAUSE).apply { replyTo = reply })
                            } catch (_: Exception) { fail("Could not contact guardian signer") }
                        }
                        override fun onServiceDisconnected(name: ComponentName?) = fail("Signer disconnected. Check BaseScan before retrying.")
                        override fun onNullBinding(name: ComponentName?) = fail("Guardian signer unavailable")
                        override fun onBindingDied(name: ComponentName?) = fail("Signer stopped. Check BaseScan before retrying.")
                        private fun fail(message: String) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException(message)))
                        }
                    }
                    connection = callback
                    bound = context.bindService(Intent(context, GuardianSignerService::class.java), callback, Context.BIND_AUTO_CREATE)
                    if (!bound) continuation.resumeWith(Result.failure(IllegalStateException("Could not bind guardian signer")))
                }
            }
            Result.success(hash)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(IllegalStateException("Signer timed out. Check BaseScan before retrying; the transaction may still confirm."))
        } catch (error: CancellationException) { throw error }
        catch (error: IllegalStateException) { Result.failure(error) }
        catch (_: Exception) { Result.failure(IllegalStateException("Could not request guardian pause")) }
        finally { if (bound) connection?.let { context.unbindService(it) } }
    }
}
