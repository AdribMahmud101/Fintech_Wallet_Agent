package app.fusion.wallet.agent

import android.content.Context
import android.os.Build
import app.fusion.wallet.chain.*
import app.fusion.wallet.data.*
import app.fusion.wallet.inference.PlannerClient
import app.fusion.wallet.inference.core.*
import app.fusion.wallet.agent.autopilot.GasSpikePolicy
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

/** A failure before entering the signer definitively requested no transaction. */
class AgentHeldException(val publicReason: String) : IllegalStateException(publicReason)

/** Foreground/manual tick only. No chroot process, server planner or deterministic fallback. */
object AgentRuntime {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AgentRunState())
    val state = mutableState.asStateFlow()
    private const val GOAL = "Pay scheduled obligations, restore cash reserves, and invest available surplus within policy."

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

    /** Read-only recovery of an already signed/mined attempt. Never runs a new
     * planner choice or permits a new signature. Preserve the original passport. */
    suspend fun reconcile(context: Context): Result<AgentTickResult> = mutex.withLock {
        try {
            val result = withContext(Dispatchers.IO) {
                val file = File(context.filesDir, "agent/last_tick.json")
                val report = JSONObject(file.readText())
                check(report.getString("source") == "APP" && !report.getBoolean("dryRun")) { "INVALID_REPORT" }
                report.put("originalRequestId", report.getString("requestId"))
                    .put("requestId", AgentNetwork.requireLease(context)).put("reconcileOnly", true)
                val execution = SessionSignerClient.execute(context, report)
                check(!execution.has("error")) { execution.optString("error", "SESSION_SIGNER_FAILED") }
                check(execution.getString("passportJson") == report.getString("passportJson") &&
                    execution.getString("decisionHash") == report.getString("decisionHash")) { "PASSPORT_HASH_MISMATCH" }
                report.put("execution", execution).put("txHash", execution.getString("txHash"))
                    .put("reconciledAfterReceiptReadFailure", true)
                file.writeText(report.toString())
                val passport = JSONObject(report.getString("passportJson"))
                AgentTickResult(passport.getString("choiceLabel"), passport.getString("reasonCode"), passport.getString("route"),
                    report.getString("decisionHash"), report.getString("passportJson"), report.getJSONObject("snapshot").getLong("blockNumber"),
                    report.getJSONObject("preview").getBoolean("passed"), report.getJSONObject("model").getJSONObject("timings").getDouble("totalMs"),
                    report.getJSONObject("model").getJSONObject("ahem").getJSONObject("g").getLong("vetoes"), false, report.toString(), execution.getString("txHash"))
            }
            mutableState.value = AgentRunState(result = result)
            Result.success(result)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableState.value = AgentRunState(error = e.message); Result.failure(e) }
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
            ChainWalletRepository(manifest, billsFile.readText(), AgentNetwork.readOnly(context, deployment.rpcUrl),
                eventIndex = SharedEventIndexes.forContext(context, deployment))
        } else repository
        decisionRepository.refresh()
        var state = decisionRepository.state.value
        if (state.status == LoadStatus.READY && state.isStale()) {
            decisionRepository.refresh()
            state = decisionRepository.state.value
        }
        check(state.status == LoadStatus.READY) { "Live snapshot unavailable: ${state.error}" }
        check(state.historyError == null) { "Live history unavailable: ${state.historyError}" }
        check(state.accountingMatches == true) { "ACCOUNTING_MISMATCH: ${state.accountingError ?: "commitment or unpaid total differs"}" }
        val reader = ChainReader(AgentNetwork.readOnly(context, deployment.rpcUrl))
        var snapshot = SnapshotAdapter.build(state, deployment, reader)
        // Verify immutable owner history before the critical fresh-snapshot window.
        // The refresh below requires the same mandate hash and nonce, and native
        // allocation generation rechecks expiry/policy/session against that refresh.
        val allocation = OwnerAllocationMandate.read(context, JSONObject(manifest), snapshot)
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
        // K44 quant inputs remain authoritative for utilization, conservative APY,
        // and gas-adjusted break-even. K38's live reserve evidence below is the
        // source for ordinary candidate ordering and proof details.
        val policy = snapshot.getJSONObject("policy")
        val cash = snapshot.getString("safeCash").toBigInteger()
        val spare = (cash - snapshot.getJSONObject("moduleState").getString("reservedRaw").toBigInteger() - policy.getString("operatingBufferRaw").toBigInteger()).max(java.math.BigInteger.ZERO)
        val limit = (0 until policy.getJSONArray("vaults").length()).map { policy.getJSONArray("vaults").getJSONObject(it) }
            .single { it.getString("vault").equals(deployment.vaultA, true) }.getString("maxExposureRaw").toBigInteger()
        val cap = policy.getString(if (policy.getInt("permissionTier") == 2) "microMaxRaw" else "hardMaxRaw").toBigInteger().min(policy.getString("hardMaxRaw").toBigInteger())
        val size = nextQuantSize(spare, cap, snapshot.getString("vaultAAssets").toBigInteger(), limit)
        val header = BlockHeader(snapshot.getLong("blockNumber"), snapshot.getString("blockHash"), snapshot.getLong("blockTimestamp"))
        val quant = QuantReader(reader).read(deployment.vaultA, header, state.agentGas, size)
        snapshot.put("quant", quant)
        // Use the same pinned live reserve APR as Home, never a provisioned scenario.
        // A failed rate read leaves no yield deposit; reserve recovery still works.
        val rates = JSONArray()
        val rateEvidence = JSONObject().put("block", snapshot.getLong("blockNumber"))
            .put("blockHash", snapshot.getString("blockHash")).put("pool", ChartEvidenceReader.AAVE_POOL)
            .put("asset", ChartEvidenceReader.AAVE_USDC)
        runCatching {
            val reserve = quant.getJSONObject("reserve")
            val bps = reserve.getString("liquidityRateRay").toBigInteger().divide(java.math.BigInteger.TEN.pow(23)).longValueExact()
            rates.put(JSONObject().put("vault", deployment.vaultA).put("rateBps", bps).put("isSimulated", false))
            rateEvidence.put("status", "LIVE").put("liquidityRateRay", reserve.getString("liquidityRateRay")).put("aprBps", bps)
        }.onFailure { rateEvidence.put("status", "UNAVAILABLE") }
        var set = checked(candidates(snapshot.toString(), SnapshotAdapter.obligations(state, true).toString(),
            SnapshotAdapter.payees(state).toString(), commitment.getJSONObject("proofs").toString(), rates.toString(),
            deployment.safe, deployment.module)).getJSONObject("set")
        if (allocation != null) {
            set = checked(allocationCandidates(snapshot.toString(), allocation.toString(), deployment.safe, deployment.module)).getJSONObject("set")
        }
        // Authorization is a verified core candidate fact, never an untrusted note.
        val goal = GOAL
        // ERC-4626 shares are not asset units. Bind exact pinned conversions,
        // then rehash the whole candidate set BEFORE the model sees its labels.
        val quotes = JSONArray()
        val unquoted = set.getJSONArray("candidates")
        for (i in 0 until unquoted.length()) {
            val candidate = unquoted.getJSONObject(i)
            val action = candidate.getJSONObject("action")
            val kind = candidate.getInt("kind")
            if (kind != 0 && kind != 1) continue
            val vault = action.getString(if (kind == 0) "destinationVault" else "sourceVault")
            val amount = java.math.BigInteger(action.getString("assets"))
            val tag = snapshot.getLong("blockNumber")
            val conversion = reader.call(vault, "0xc6e6f592" + Abi.uintWord(amount), tag).requireSize(1).uint(0)
            val previewSelector = if (kind == 0) Views.PREVIEW_DEPOSIT else Views.PREVIEW_WITHDRAW
            val previewShares = reader.call(vault, previewSelector + Abi.uintWord(amount), tag).requireSize(1).uint(0)
            quotes.put(JSONObject().put("vault", vault).put("kind", kind).put("assets", amount.toString())
                .put("blockHash", snapshot.getString("blockHash")).put("convertToShares", conversion.toString())
                .put("preview", previewShares.toString()))
        }
        set = checked(quoteCandidates(set.toString(), snapshot.toString(), quotes.toString(), deployment.safe, deployment.module)).getJSONObject("set")
        val prompt = checked(plannerPrompt(set.toString(), goal))
        val request = JSONObject().put("prompt", prompt.getString("prompt")).put("labels", prompt.getJSONArray("labels"))
            .put("candidateSetDigest", set.getString("candidateSetDigest")).put("mode", "ahem-g").put("budgetMs", 25000)
        val model = PlannerClient.choose(context, request)
        check(!model.has("error")) { "On-device planner failed: ${model.optJSONObject("error")}" }
        check(model.getString("candidateSetDigest") == set.getString("candidateSetDigest")) { "Stale candidate-set digest" }
        check(model.getBoolean("rawValid") && !model.getJSONObject("ahem").getJSONObject("g").getBoolean("aborted")) { "Planner aborted; HOLD without execution" }
        val raw = JSONObject(model.getString("raw"))
        check(raw.length() == 1 && raw.has("choice") && raw.getString("choice") == model.getString("label")) { "Invalid planner output" }
        val validated = checked(validateOutput(model.getString("raw"), set.toString()))
        var choice = validated.getString("choiceLabel")
        check(choice == model.getString("label") && !validated.getBoolean("retryable")) { "Output validator rejected choice" }
        val list = set.getJSONArray("candidates")
        var candidate = if (choice == "H") set.getJSONObject("hold") else (0 until list.length()).map { list.getJSONObject(it) }.single { it.getString("label") == choice }
        val gasHeader = reader.block("latest")
        val gasHold = if (choice == "H") null else GasSpikePolicy.holdReason(gasHeader.baseFeePerGas, candidate.getInt("kind"),
            candidate.optJSONObject("action")?.optLong("dueAt"), gasHeader.timestamp)
        if (gasHold != null) { choice = "H"; candidate = set.getJSONObject("hold") }
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
            .put("userGoalSha256", "0x" + MessageDigest.getInstance("SHA-256").digest(goal.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("choiceLabel", choice).put("choiceId", candidate.getString("id")).put("reasonCode", validated.getString("reasonCode"))
            .put("route", if (choice == "H") "HOLD" else candidate.getString("route")).put("createdAtChain", snapshot.getLong("blockTimestamp"))
        val built = checked(buildPassport(passport.toString()))
        val canonical = built.getString("passportJson")
        val hash = built.getString("decisionHash")
        val preview = checked(preview(candidate.toString(), snapshot.toString(), hash, deployment.safe, deployment.module)).getJSONObject("result")
        val nextBill = state.obligations.filterNot { it.paid }.minByOrNull { it.dueAt }
        val explanation = gasHold ?: if (choice == "H") {
            val economics = if (list.length() == 0) economicHoldReason(set.getJSONObject("quant")) else null
            if (economics != null) economics
            else if (list.length() == 0 && nextBill != null && nextBill.dueAt > gasHeader.timestamp + module.policy.payEarlyWindowSeconds)
                "Bill not due until ${app.fusion.wallet.ui.common.formatEpochSeconds(nextBill.dueAt)}"
            else if (list.length() == 0) "Nothing eligible to move within the current policy"
            else "Planner chose to wait; eligible moves remain subject to policy checks"
        } else null
        val report = JSONObject().put("source", "APP").put("requestId", requestId ?: JSONObject.NULL).put("dryRun", dryRun).put("planner", passport.getJSONObject("planner"))
            .put("snapshot", snapshot).put("candidateSet", set).put("chosenCandidate", candidate)
            .put("completedAtMillis", System.currentTimeMillis()).put("decisionExplanation", explanation ?: JSONObject.NULL).put("routerReason", gasHold ?: JSONObject.NULL)
            .put("decisionHash", hash).put("passportJson", canonical).put("preview", preview).put("model", model)
            .put("quant", set.getJSONObject("quant")).put("rates", rates).put("rateEvidence", rateEvidence).put("vaultQuotes", quotes).put("rpcTransport", if (File(context.filesDir, "agent/edge_rpc.json").isFile) "live HTTPS via edge loopback relay" else "Android HTTPS").put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
        if (allocation != null) report.put("allocationMandate", allocation)
        val dir = File(context.filesDir, "agent").apply { mkdirs() }
        File(dir, if (dryRun) "m2_passport.json" else "last_passport.json").writeText(canonical)
        File(dir, "last_tick.json").writeText(report.toString())
        val execution = if (!dryRun && choice != "H") {
            check(preview.getBoolean("passed")) { "Policy preview blocked execution" }
            val first = SessionSignerClient.execute(context, report)
            val signed = recoverSessionReceipt(first, report,
                { File(context.noBackupFilesDir, "agent/pending.json").isFile },
                { original -> SessionSignerClient.execute(context, original) })
            check(!signed.has("error")) { signed.optString("error", "SESSION_SIGNER_FAILED") }
            report.put("execution", signed).put("txHash", signed.getString("txHash"))
            File(dir, "last_tick.json").writeText(report.toString())
            signed
        } else null
        return AgentTickResult(choice, gasHold ?: validated.getString("reasonCode"), if (choice == "H") "HOLD" else passport.getString("route"), hash, canonical,
            snapshot.getLong("blockNumber"), preview.getBoolean("passed"), model.getJSONObject("timings").getDouble("totalMs"), stats.getLong("vetoes"), dryRun, report.toString(), execution?.getString("txHash"))
    }

    internal fun checked(raw: String): JSONObject = JSONObject(raw).also { check(it.optBoolean("ok")) { it.optJSONObject("error")?.toString() ?: "Core rejected request" } }
    private fun summary(c: JSONObject) = JSONObject().put("label", c.getString("label")).put("id", c.getString("id"))
        .put("kind", listOf("DEPOSIT", "WITHDRAW", "REBALANCE", "PAY_OBLIGATION")[c.getInt("kind")])
        .put("assets", c.getJSONObject("action").getString("assets")).put("reason", c.getString("reason"))
}
