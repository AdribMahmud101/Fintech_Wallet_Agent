package app.fusion.wallet.advisor

import app.fusion.wallet.accountant.cloud.ApiKeyProvider
import app.fusion.wallet.accountant.cloud.CloudClient
import app.fusion.wallet.accountant.cloud.CloudResult
import app.fusion.wallet.data.WalletState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AdvisorSettings(val enabled: Boolean = false, val model: String = AdvisorConfig.DEFAULT_MODEL)

sealed interface AdvisorResult<out T> {
    /** Advisor disabled or no key: every feature shows "Advisor off" and nothing is sent. */
    data class Off(val reason: String) : AdvisorResult<Nothing>
    /** Refused on the device before anything was sent. */
    data class Blocked(val reason: String) : AdvisorResult<Nothing>
    /** Network, auth or server failure on every model tried. */
    data class Failed(val reason: String, val model: String) : AdvisorResult<Nothing>
    /** The model answered but the deterministic validator refused the reply. */
    data class Rejected(val reasons: List<String>, val model: String) : AdvisorResult<Nothing>
    data class Ok<T>(val value: T, val model: String) : AdvisorResult<T>
}

/**
 * The advisor. Its only inputs are public facts and the user's text; its only outputs are validated
 * text, a policy PROPOSAL for owners to sign, or a bounded [RiskSignal]. It holds no signer, session,
 * planner or repository handle, so there is no path from a model reply to a transaction.
 */
class CloudAdvisor(
    private val client: CloudClient,
    private val keys: ApiKeyProvider,
    private val settings: () -> AdvisorSettings,
    private val riskSink: (RiskSignalRecord) -> Unit = RiskSignalBus::publish,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One request at a time: a slow or failing endpoint cannot be hammered from the UI. */
    private val gate = Mutex()

    suspend fun explain(facts: DecisionFacts): AdvisorResult<Explanation> =
        call(AdvisorPrompts.EXPLAIN, facts.toJson(), freeText = "") { ExplanationValidator.validate(it) }

    suspend fun ask(state: WalletState, question: String, nowSec: Long = clock() / 1000): AdvisorResult<AccountantAnswer> {
        val q = question.trim()
        if (q.isEmpty()) return AdvisorResult.Blocked("type a question first")
        if (q.length > AdvisorConfig.MAX_QUESTION_CHARS) return AdvisorResult.Blocked("question too long")
        val payload = WalletFacts.build(state, nowSec, nowSec * 1000) ?: return AdvisorResult.Blocked("live wallet data not loaded")
        payload.put("question", AdvisorEgress.redact(q))
        val sent = WalletFacts.flatten(payload).filterKeys { it != "question" && it != "schema" }
        return call(AdvisorPrompts.ACCOUNTANT, payload.toString(), freeText = q) { AnswerValidator.validate(it, sent) }
    }

    suspend fun propose(goal: String, current: CurrentPolicy?): AdvisorResult<PolicyProposal> {
        val g = goal.trim()
        if (g.isEmpty()) return AdvisorResult.Blocked("type a goal first")
        if (g.length > AdvisorConfig.MAX_GOAL_CHARS) return AdvisorResult.Blocked("goal too long")
        current ?: return AdvisorResult.Blocked("current on-chain policy not loaded")
        return call(AdvisorPrompts.POLICY, PolicyProposalValidator.request(g, current), freeText = g) {
            PolicyProposalValidator.validate(it, current)
        }
    }

    suspend fun classify(untrustedText: String): AdvisorResult<RiskSignal> {
        val t = untrustedText.trim()
        if (t.isEmpty()) return AdvisorResult.Blocked("paste some text first")
        if (t.length > AdvisorConfig.MAX_EXTERNAL_TEXT_CHARS) return AdvisorResult.Blocked("text too long")
        val result = call(AdvisorPrompts.CLASSIFIER, RiskClassifierValidator.request(t), freeText = t) { RiskClassifierValidator.validate(it) }
        if (result is AdvisorResult.Ok) riskSink(RiskSignalRecord(result.value, result.model, clock()))
        return result
    }

    private suspend fun <T> call(system: String, payload: String, freeText: String, validate: (String) -> Checked<T>): AdvisorResult<T> {
        val s = settings()
        if (!s.enabled) return AdvisorResult.Off(AdvisorConfig.OFF_LABEL)
        val key = keys.apiKey()?.takeIf { it.isNotBlank() } ?: return AdvisorResult.Off("${AdvisorConfig.OFF_LABEL} · no API key")
        when (val e = AdvisorEgress.check(payload, key, freeText)) {
            is EgressCheck.Blocked -> return AdvisorResult.Blocked(e.reason)
            EgressCheck.Ok -> {}
        }
        return gate.withLock {
            val models = listOf(s.model) + AdvisorConfig.MODELS.filter { it != s.model }.take(1)
            var last: AdvisorResult<T> = AdvisorResult.Failed("no model tried", s.model)
            for (model in models) {
                when (val r = client.complete(model, system, payload)) {
                    is CloudResult.Success -> return@withLock when (val v = validate(r.content)) {
                        // A refused reply is final: asking another model until one passes would defeat the validator.
                        is Checked.Rejected -> AdvisorResult.Rejected(v.reasons, model)
                        is Checked.Valid -> AdvisorResult.Ok(v.value, model)
                    }
                    is CloudResult.Failure -> {
                        last = AdvisorResult.Failed(r.kind.name, model)
                        if (r.kind !in FALLBACK_ON) break
                    }
                }
            }
            last
        }
    }

    private companion object {
        val FALLBACK_ON = setOf(CloudResult.Kind.SERVER, CloudResult.Kind.RATE_LIMITED, CloudResult.Kind.NETWORK, CloudResult.Kind.BAD_RESPONSE, CloudResult.Kind.REFUSED_MODEL)
    }
}
