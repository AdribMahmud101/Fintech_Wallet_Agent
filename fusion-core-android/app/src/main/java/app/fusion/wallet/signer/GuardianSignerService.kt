package app.fusion.wallet.signer

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Same UID, separate process. IPC accepts an empty PAUSE request and returns public data only. */
class GuardianSignerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: GuardianPauseEngine
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == PAUSE && message.sendingUid == Process.myUid() && message.replyTo != null && message.data.isEmpty) {
            val reply = message.replyTo
            scope.launch {
                val result = try {
                    Bundle().apply { putString(TX_HASH, engine.pause()) }
                } catch (error: Exception) {
                    Bundle().apply { putString(ERROR, safeSignerError(error)) }
                }
                try { reply.send(Message.obtain(null, PAUSE).apply { data = result }) } catch (_: Exception) { /* Caller gone. Journal survives. */ }
            }
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        val keys = GuardianKeyStore(this)
        engine = GuardianPauseEngine(SignerRpcClient(), FilePauseJournal(File(noBackupFilesDir, "signer/pending.json")), sign = { nonce, tip, fee, gas ->
            keys.withKey { key ->
                check(GuardianTransaction.address(key).equals(GuardianTransaction.GUARDIAN, true)) { "Guardian key mismatch" }
                GuardianTransaction.sign(key, nonce, tip, fee, gas)
            }
        })
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    internal companion object {
        const val PAUSE = 1
        const val TX_HASH = "txHash"
        const val ERROR = "error"
    }
}

internal class FilePauseJournal(path: File) : PauseJournal {
    private val file = AtomicFile(path.apply { parentFile!!.mkdirs() })
    override fun load(): PendingPause? = if (!file.baseFile.exists()) null else JSONObject(String(file.readFully(), Charsets.UTF_8)).let {
        val pending = PendingPause(it.getString("hash"), it.getString("raw"))
        check(pending.hash == GuardianTransaction.hex(GuardianTransaction.keccak(GuardianTransaction.bytes(pending.raw)))) { "Invalid pause journal" }
        pending
    }
    override fun save(pending: PendingPause) {
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("hash", pending.hash).put("raw", pending.raw).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override fun clear() = file.delete()
}

/** Allow only our fixed messages across IPC; third-party exceptions may contain arbitrary data. */
internal fun safeSignerError(error: Exception): String {
    val message = error.message.orEmpty()
    val fixed = setOf(
        "Import the Base Sepolia guardian using the debug import screen first",
        "Guardian needs Base Sepolia ETH for gas", "Signer RPC is not Base Sepolia",
        "Guardian gas fee exceeds the 10 gwei testnet limit", "Guardian gas estimate exceeds the testnet limit",
        "Guardian wrapping key unavailable; re-import after clearing app data",
    )
    if (message in fixed) return message
    val publicResult = Regex("(?:Pause not confirmed\\. Check BaseScan before retrying|Guardian pause reverted|Receipt succeeded but module pause was not verified): 0x[0-9a-f]{64}")
    return if (publicResult.matches(message)) message else "Guardian pause failed; check network, key import and Base Sepolia gas."
}
