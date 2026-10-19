package app.fusion.wallet.agent

import android.content.Context
import android.os.Build
import app.fusion.wallet.chain.*
import app.fusion.wallet.data.*
import app.fusion.wallet.inference.PlannerClient
import app.fusion.wallet.inference.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Public, keyless result. A HOLD has no transaction status or receipt. */
data class AgentTickResult(
    val choiceLabel: String, val reason: String, val route: String, val decisionHash: String,
    val passportJson: String, val snapshotBlock: Long, val previewPassed: Boolean,
    val decodeMs: Double, val vetoes: Long, val dryRun: Boolean, val reportJson: String,
    val txHash: String? = null,
)
data class AgentRunState(val running: Boolean = false, val result: AgentTickResult? = null, val error: String? = null)

/** Foreground/manual tick only. No chroot process, server planner or deterministic fallback. */
object AgentRuntime {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AgentRunState())
    val state = mutableState.asStateFlow()
    private const val GOAL = "Maintain business runway and yield"

    suspend fun run(context: Context, repository: WalletRepository, dryRun: Boolean): Result<AgentTickResult> = mutex.withLock {
        mutableState.value = AgentRunState(running = true, result = mutableState.value.result)
        try {
            val result = withContext(Dispatchers.IO) { decide(context, repository, dryRun) }
            mutableState.value = AgentRunState(result = result)
            Result.success(result)
        } catch (e: CancellationException) {
            mutableState.value = AgentRunState(error = "Agent tick cancelled; no new action authorized")
            throw e
        } catch (e: Exception) {
            val message = e.message?.take(256) ?: "Agent tick failed"
            mutableState.value = AgentRunState(error = message)
            Result.failure(e)
        }
    }

    private suspend fun decide(context: Context, repository: WalletRepository, dryRun: Boolean): AgentTickResult {
        val requestId = if (dryRun) null else AgentNetwork.requireLease(context)
        val manifest = context.assets.open("deployments/84532.json").bufferedReader().use { it.readText() }
        val deployment = Deployment.parse(manifest)
        // A new on-chain commitment can precede the next APK asset update. Reuse
        // K4's public repository constructor with operator-provisioned public bills;
        // both K4 accounting and Rust's rebuilt Merkle root must match live state.
        val billsFile = File(context.filesDir, "agent/obligations.json")
        val decisionRepository = if (billsFile.isFile) {
            check(billsFile.length() <= 65_536) { "Bill metadata exceeds limit" }
            ChainWalletRepository(manifest, billsFile.readText(), AgentNetwork.readOnly(context, deployment.rpcUrl))
        } else repository
        decisionRepository.refresh()
        var state = decisionRepository.state.value
        if (state.status == LoadStatus.READY && state.isStale()) {
            decisionRepository.refresh()
            state = decisionRepository.state.value
        }
        check(state.status == LoadStatus.READY) { "Live snapshot unavailable: ${state.error}" }
        check(state.accountingMatches == true) { "ACCOUNTING_MISMATCH: commitment or unpaid total differs" }
        val reader = ChainReader(AgentNetwork.readOnly(context, deployment.rpcUrl))
        var snapshot = SnapshotAdapter.build(state, deployment, reader)
        if (!dryRun) {
            val fresh = SignerSnapshot.read(context, JSONObject(manifest), snapshot)
            check(fresh.getJSONObject("moduleState").getString("mandateHash") == snapshot.getJSONObject("moduleState").getString("mandateHash") &&
                fresh.getJSONObject("moduleState").getString("actionNonce") == snapshot.getJSONObject("moduleState").getString("actionNonce")) { "Live state changed; rerun agent" }
            snapshot = fresh
        }
        val module = requireNotNull(state.module)
        val allBills = SnapshotAdapter.obligations(state)
        val commitment = checked(buildCommitment(allBills.toString()))
        check(commitment.getString("root").equals(module.obligationsRoot, true)) { "Commitment proof root mismatch" }
        // Optional, explicitly provisioned rate scenarios. Missing rates cannot invent yield.
        val rateFile = File(context.filesDir, "agent/rates.json")
        val rates = if (rateFile.isFile) JSONArray(rateFile.readText()) else JSONArray()
        val set = checked(candidates(snapshot.toString(), SnapshotAdapter.obligations(state, true).toString(),
            SnapshotAdapter.payees(state).toString(), commitment.getJSONObject("proofs").toString(), rates.toString(),
            deployment.safe, deployment.module)).getJSONObject("set")
        val prompt = checked(plannerPrompt(set.toString(), GOAL))
        val request = JSONObject().put("prompt", prompt.getString("prompt")).put("labels", prompt.getJSONArray("labels"))
            .put("candidateSetDigest", set.getString("candidateSetDigest")).put("mode", "ahem-g").put("budgetMs", 25000)
        val model = PlannerClient.choose(context, request)
        check(!model.has("error")) { "On-device planner failed: ${model.optJSONObject("error")}" }
        check(model.getString("candidateSetDigest") == set.getString("candidateSetDigest")) { "Stale candidate-set digest" }
        check(model.getBoolean("rawValid") && !model.getJSONObject("ahem").getJSONObject("g").getBoolean("aborted")) { "Planner aborted; HOLD without execution" }
        val raw = JSONObject(model.getString("raw"))
        check(raw.length() == 1 && raw.has("choice") && raw.getString("choice") == model.getString("label")) { "Invalid planner output" }
        val validated = checked(validateOutput(model.getString("raw"), set.toString()))
        val choice = validated.getString("choiceLabel")
        check(choice == model.getString("label") && !validated.getBoolean("retryable")) { "Output validator rejected choice" }
        val list = set.getJSONArray("candidates")
        val candidate = if (choice == "H") set.getJSONObject("hold") else (0 until list.length()).map { list.getJSONObject(it) }.single { it.getString("label") == choice }
        val stats = model.getJSONObject("ahem").getJSONObject("g")
        val hashes = model.getJSONObject("load").getJSONObject("hashes")
        val passport = JSONObject().put("version", "4.1").put("chainId", 84532).put("safe", JSONObject(manifest).getString("safe")).put("module", JSONObject(manifest).getString("module"))
            .put("snapshot", JSONObject().put("block", snapshot.getLong("blockNumber")).put("hash", snapshot.getString("blockHash")))
            .put("mandateHash", module.mandateHash).put("candidateSetDigest", set.getString("candidateSetDigest"))
            .put("candidateSet", JSONArray().apply { (0 until list.length()).forEach { put(summary(list.getJSONObject(it))) }; put(summary(set.getJSONObject("hold"))) })
            .put("planner", JSONObject().put("kind", "mnn+ahem-g@android").put("host", "device")
                .put("modelId", "LFM2.5-230M-MNN").put("modelSha256", hashes.getString("llm.mnn"))
                .put("libllmSha256", hashes.getString("libllm.so")).put("libMnnSha256", hashes.getString("libMNN.so"))
                .put("ahem", JSONObject().put("g", JSONObject().put("mode", "ENFORCED").put("vetoes", stats.getLong("vetoes"))
                    .put("retries", stats.getLong("retries")).put("forcedTokens", stats.getLong("forcedTokens")))
                    .put("l", JSONObject().put("mode", "OFF"))))
            .put("userGoalSha256", "0x" + MessageDigest.getInstance("SHA-256").digest(GOAL.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("choiceLabel", choice).put("choiceId", candidate.getString("id")).put("reasonCode", validated.getString("reasonCode"))
            .put("route", if (choice == "H") "HOLD" else candidate.getString("route")).put("createdAtChain", snapshot.getLong("blockTimestamp"))
        val built = checked(buildPassport(passport.toString()))
        val canonical = built.getString("passportJson")
        val hash = built.getString("decisionHash")
        val preview = checked(preview(candidate.toString(), snapshot.toString(), hash, deployment.safe, deployment.module)).getJSONObject("result")
        val report = JSONObject().put("source", "APP").put("requestId", requestId ?: JSONObject.NULL).put("dryRun", dryRun).put("planner", passport.getJSONObject("planner"))
            .put("snapshot", snapshot).put("candidateSet", set).put("chosenCandidate", candidate)
            .put("decisionHash", hash).put("passportJson", canonical).put("preview", preview).put("model", model)
            .put("rates", rates).put("rpcTransport", if (File(context.filesDir, "agent/edge_rpc.json").isFile) "live HTTPS via edge loopback relay" else "Android HTTPS").put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
        val dir = File(context.filesDir, "agent").apply { mkdirs() }
        File(dir, if (dryRun) "m2_passport.json" else "last_passport.json").writeText(canonical)
        File(dir, "last_tick.json").writeText(report.toString())
        val execution = if (!dryRun && choice != "H") {
            check(preview.getBoolean("passed")) { "Policy preview blocked execution" }
            val signed = SessionSignerClient.execute(context, report)
            check(!signed.has("error")) { signed.optString("error", "SESSION_SIGNER_FAILED") }
            report.put("execution", signed).put("txHash", signed.getString("txHash"))
            File(dir, "last_tick.json").writeText(report.toString())
            signed
        } else null
        return AgentTickResult(choice, validated.getString("reasonCode"), passport.getString("route"), hash, canonical,
            snapshot.getLong("blockNumber"), preview.getBoolean("passed"), model.getJSONObject("timings").getDouble("totalMs"), stats.getLong("vetoes"), dryRun, report.toString(), execution?.getString("txHash"))
    }

    internal fun checked(raw: String): JSONObject = JSONObject(raw).also { check(it.optBoolean("ok")) { it.optJSONObject("error")?.toString() ?: "Core rejected request" } }
    private fun summary(c: JSONObject) = JSONObject().put("label", c.getString("label")).put("id", c.getString("id"))
        .put("kind", listOf("DEPOSIT", "WITHDRAW", "REBALANCE", "PAY_OBLIGATION")[c.getInt("kind")])
        .put("assets", c.getJSONObject("action").getString("assets")).put("reason", c.getString("reason"))
}
