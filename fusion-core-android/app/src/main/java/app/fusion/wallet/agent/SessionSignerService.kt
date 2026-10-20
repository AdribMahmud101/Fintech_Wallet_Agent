package app.fusion.wallet.agent

import android.app.Service
import android.content.Intent
import android.os.*
import android.util.AtomicFile
import app.fusion.wallet.data.Deployment
import app.fusion.wallet.inference.ProvisionedHashes
import app.fusion.wallet.inference.core.signPrepared
import app.fusion.wallet.inference.core.verifyForSigning
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** Same-UID Binder only, separate :signer process. Takes a complete bound decision, never a tx. */
class SessionSignerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var engine: SessionSignerEngine
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == EXECUTE && message.sendingUid == Process.myUid() && message.replyTo != null) {
            val input = message.data.getString("report").orEmpty(); val reply = message.replyTo
            scope.launch {
                val output = try {
                    require(input.length <= 65_536)
                    engine.execute(JSONObject(input)).put("signerProcess", android.app.Application.getProcessName()).put("signerPid", Process.myPid()).put("keyProtection", "Android Keystore AES-GCM session envelope").toString()
                } catch (error: Exception) { JSONObject().put("error", safeSessionError(error)).toString() }
                try { reply.send(Message.obtain(null, EXECUTE).apply { data = Bundle().apply { putString("result", output) } }) }
                catch (_: Exception) { /* Durable journal survives a lost reply. */ }
            }
        }
        true
    })
    override fun onCreate() {
        super.onCreate()
        val manifest = JSONObject(assets.open("deployments/84532.json").bufferedReader().use { it.readText() })
        val d = Deployment.parse(manifest.toString()); val keys = SessionKeys(this)
        val signingPolicy = SessionSigningPolicy(d.module, d.chainId)
        var traceStart = 0L
        var traceStages = JSONArray()
        engine = SessionSignerEngine(d.module, manifest.getJSONObject("roles").getString("session"),
            chainId = d.chainId,
            rpc = { method, params -> AgentNetwork.relay(this, method, params) },
            readBatch = { calls -> AgentNetwork.batch(this, calls) },
            trace = { stage ->
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    if (stage == "start") { traceStart = SystemClock.elapsedRealtime(); traceStages = JSONArray() }
                    traceStages.put(JSONObject().put("stage", stage).put("elapsedMs", SystemClock.elapsedRealtime() - traceStart))
                    // Public timings only: never include report bytes, keys or signed tx bytes.
                    runCatching { File(filesDir, "agent/signer_timing.json").writeText(traceStages.toString()) }
                }
            },
            lease = { AgentNetwork.requireLease(this) },
            verify = { report ->
                val hashes = JSONObject(ProvisionedHashes.JSON)
                val measured = report.getJSONObject("model").getJSONObject("load").getJSONObject("hashes")
                check(hashes.length() == measured.length() && hashes.keys().asSequence().all { hashes.getString(it) == measured.optString(it) }) { "PLANNER_PIN_MISMATCH" }
                val planner = JSONObject(report.getString("passportJson")).getJSONObject("planner")
                check(planner.getString("libMnnSha256") == hashes.getString("libMNN.so") && planner.getString("libllmSha256") == hashes.getString("libllm.so") && planner.getString("modelSha256") == hashes.getString("llm.mnn")) { "PLANNER_PIN_MISMATCH" }
                val fresh = SignerSnapshot.read(this, manifest, report.getJSONObject("snapshot"))
                AgentRuntime.checked(verifyForSigning(report.toString(), fresh.toString(), d.safe, d.module))
            },
            sign = { tx, activeAddress ->
                signingPolicy.validate(tx)
                keys.withKey(activeAddress) { key ->
                    AgentRuntime.checked(signPrepared(tx.toString(), AgentCrypto.hex(key), signingPolicy.json().toString()))
                }
            }, journal = FileSessionJournal(File(noBackupFilesDir, "agent")))
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    internal companion object { const val EXECUTE = 17 }
}
internal class FileSessionJournal(private val dir: File) : SessionJournal {
    private val file = AtomicFile(File(dir, "pending.json").apply { parentFile!!.mkdirs() })
    override fun load(): JSONObject? = if (!file.baseFile.isFile) null else JSONObject(String(file.readFully(), Charsets.UTF_8)).also {
        check(AgentCrypto.hashHex(AgentCrypto.bytes(it.getString("rawTx"))).equals(it.getString("txHash"), true)) { "JOURNAL_HASH_MISMATCH" }
    }
    override fun save(pending: JSONObject) = write(file, pending.toString())
    override fun clear() = file.delete()
    override fun recordConfirmed(result: JSONObject) {
        val hash = result.getString("txHash"); check(Regex("0x[0-9a-f]{64}").matches(hash))
        write(AtomicFile(File(dir, "receipts/$hash.json").apply { parentFile!!.mkdirs() }), result.toString())
    }
    private fun write(target: AtomicFile, value: String) {
        val stream = target.startWrite()
        try { stream.write(value.toByteArray()); target.finishWrite(stream) } catch (e: Exception) { target.failWrite(stream); throw e }
    }
}
internal fun safeSessionError(error: Exception): String {
    if (error is GasHoldException) return error.publicReason
    var code = error.message.orEmpty()
    if (code.startsWith("{")) code = runCatching { JSONObject(code).optString("message") }.getOrDefault("")
    val allowed = setOf("DRY_RUN_CANNOT_BROADCAST", "SESSION_LOCK_REQUIRED", "WRONG_CHAIN", "MODULE_MISMATCH", "PASSPORT_HASH_MISMATCH", "SIMULATION_DIGEST_MISMATCH", "FEE_LIMIT", "GAS_LIMIT", "SESSION_NEEDS_GAS", "SIGNED_HASH_MISMATCH", "STALE_STATE_OR_UNSUPPORTED_ROUTE", "SESSION_INACTIVE", "RECEIPT_MISMATCH", "EVENT_MISMATCH", "REORG", "CODE_PIN_MISMATCH", "IMPLEMENTATION_PIN_MISMATCH", "PLANNER_PIN_MISMATCH", "HOLDINGS_CHANGED", "INVALID_REPORT", "INVALID_PASSPORT", "INVALID_CONTEXT_OR_HOLD", "INVALID_SNAPSHOT", "INVALID_SET", "INVALID_CANDIDATE", "CANDIDATE_BINDING_MISMATCH", "INVALID_MODEL_OUTPUT", "FRESH_POLICY_PREVIEW_FAILED", "SESSION_KEY_NOT_IMPORTED", "SESSION_KEY_MISMATCH", "SESSION_WRAPPING_KEY_UNAVAILABLE", "PREVIOUS_ATTEMPT_CONFIRMED_REPLAN", "JOURNAL_HASH_MISMATCH", "SIGNING_FAILED")
    if (error is SignerRefused) return error.message!!
    if (SignerRefusal.entries.any { code == "SignerRefused::${it.name}" }) return code
    return if (code in allowed || Regex("(?:PENDING_SESSION_ATTEMPT|SESSION_TX_REVERTED): 0x[0-9a-f]{64}").matches(code)) code else "SESSION_SIGNER_FAILED"
}
