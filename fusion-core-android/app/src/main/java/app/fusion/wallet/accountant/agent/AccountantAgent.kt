package app.fusion.wallet.accountant.agent

import app.fusion.wallet.accountant.cloud.AccountantBrief
import app.fusion.wallet.accountant.cloud.AccountantPrompt
import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.cloud.CloudAdvice
import app.fusion.wallet.accountant.cloud.CloudClient
import app.fusion.wallet.accountant.cloud.CloudResult
import app.fusion.wallet.accountant.cloud.EgressGuard
import app.fusion.wallet.accountant.cloud.GuardPolicy
import app.fusion.wallet.accountant.cloud.GuardResult
import app.fusion.wallet.accountant.cloud.ReplyCheck
import app.fusion.wallet.accountant.cloud.ReplyValidator
import app.fusion.wallet.accountant.data.EgressLogEntity
import app.fusion.wallet.accountant.data.EgressStatus
import app.fusion.wallet.accountant.data.InsightEntity
import app.fusion.wallet.accountant.data.InsightSource
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.data.PrivacySettingsEntity
import app.fusion.wallet.accountant.domain.AccountantReadModel
import java.security.MessageDigest

/**
 * The ONLY things the Accountant may write: its own advisory insights and the egress audit trail.
 * It has no handle on balances, obligations, the ledger, the policy, the planner or the signer.
 */
interface AccountantAudit {
    suspend fun settings(): PrivacySettingsEntity
    suspend fun callsSince(since: Long): Int
    suspend fun logEgress(e: EgressLogEntity): Long
    suspend fun updateEgress(e: EgressLogEntity)
    suspend fun saveInsight(i: InsightEntity): Long
}

sealed interface CloudOutcome {
    data object NotEnabled : CloudOutcome
    data object NoData : CloudOutcome
    data object LimitReached : CloudOutcome
    data class Blocked(val rules: List<String>) : CloudOutcome
    data class Unavailable(val reason: String) : CloudOutcome
    data class Ok(val advice: CloudAdvice, val egressId: Long) : CloudOutcome
}

/** Always carries the deterministic local findings; the cloud part is optional and additive. */
data class AccountantReply(val local: List<LocalFinding>, val cloud: CloudOutcome)

class AccountantAgent(
    private val read: AccountantReadModel,
    private val audit: AccountantAudit,
    private val cloud: CloudClient?,
    private val clock: () -> Long,
) {
    /** What the user will be shown before opting in; also what the guard enforces. */
    fun previewBrief(snapshot: app.fusion.wallet.accountant.domain.PortfolioSnapshot, topic: InsightTopic, question: String?): AccountantBrief =
        BriefBuilder.build(snapshot, topic, question)

    suspend fun ask(topic: InsightTopic, question: String?): AccountantReply {
        val now = clock()
        val snapshot = read.snapshot(now) ?: return AccountantReply(emptyList(), CloudOutcome.NoData)
        val local = LocalAccountant.analyze(snapshot)

        val settings = audit.settings()
        if (cloud == null || !settings.cloudEnabled || settings.consentVersion < CONSENT_VERSION) {
            return AccountantReply(local, CloudOutcome.NotEnabled)
        }
        if (audit.callsSince(now - DAY_SEC) >= settings.dailyCallLimit) {
            logFailure(now, settings, cloud.provider, EgressStatus.LIMIT_REACHED, "daily limit ${settings.dailyCallLimit}")
            return AccountantReply(local, CloudOutcome.LimitReached)
        }

        val brief = BriefBuilder.build(snapshot, topic, question)
        val json = brief.toJson()
        val guard = EgressGuard.check(json, GuardPolicy(settings.maxPayloadBytes, read.privateStrings()))
        if (guard is GuardResult.Blocked) {
            // The payload that tripped the guard is NOT stored: it may contain what the guard caught.
            logFailure(now, settings, cloud.provider, EgressStatus.BLOCKED_BY_GUARD, guard.rules.joinToString(","))
            return AccountantReply(local, CloudOutcome.Blocked(guard.rules))
        }

        // Write the audit row BEFORE the network call so an in-flight crash still leaves a record.
        val bytes = json.toByteArray(Charsets.UTF_8)
        var row = EgressLogEntity(
            createdAt = now, provider = cloud.provider, model = settings.model, purpose = "accountant:${topic.name}",
            status = EgressStatus.FAILED, payloadJson = json, payloadSha256 = sha256(bytes), payloadBytes = bytes.size,
            detail = "in flight", consentVersion = settings.consentVersion,
        )
        val egressId = audit.logEgress(row)
        row = row.copy(id = egressId)

        return when (val res = cloud.complete(settings.model, AccountantPrompt.SYSTEM, json)) {
            is CloudResult.Failure -> {
                audit.updateEgress(row.copy(status = EgressStatus.FAILED, detail = res.kind.name, httpCode = res.httpCode))
                AccountantReply(local, CloudOutcome.Unavailable(res.kind.name))
            }
            is CloudResult.Success -> when (val v = ReplyValidator.validate(res.content)) {
                is ReplyCheck.Rejected -> {
                    audit.updateEgress(
                        row.copy(
                            status = EgressStatus.REJECTED_REPLY, detail = v.reason, httpCode = res.httpCode,
                            promptTokens = res.promptTokens, completionTokens = res.completionTokens,
                        ),
                    )
                    AccountantReply(local, CloudOutcome.Unavailable("reply rejected: ${v.reason}"))
                }
                is ReplyCheck.Valid -> {
                    audit.updateEgress(
                        row.copy(
                            status = EgressStatus.SENT_OK, detail = "", httpCode = res.httpCode,
                            promptTokens = res.promptTokens, completionTokens = res.completionTokens,
                        ),
                    )
                    v.advice.findings.forEach {
                        audit.saveInsight(InsightEntity(topic = it.topic, severity = it.severity, text = it.text, source = InsightSource.CLOUD, egressId = egressId, createdAt = now))
                    }
                    AccountantReply(local, CloudOutcome.Ok(v.advice, egressId))
                }
            }
        }
    }

    private suspend fun logFailure(now: Long, s: PrivacySettingsEntity, provider: String, status: EgressStatus, detail: String) {
        audit.logEgress(
            EgressLogEntity(
                createdAt = now, provider = provider, model = s.model, purpose = "accountant", status = status,
                payloadJson = "", payloadSha256 = "", payloadBytes = 0, detail = detail, consentVersion = s.consentVersion,
            ),
        )
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        /** Bump when the set of fields in the brief changes; forces re-consent. */
        const val CONSENT_VERSION = 2
        private const val DAY_SEC = 86_400L
    }
}
