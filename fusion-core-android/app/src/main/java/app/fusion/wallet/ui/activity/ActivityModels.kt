package app.fusion.wallet.ui.activity

import app.fusion.wallet.data.*
import app.fusion.wallet.ui.components.Provenance
import org.json.JSONObject
import java.math.BigInteger

enum class ActivityFilter { All, Agent, Owner, App, Blocked }

data class ActivityItem(
    val id: String,
    val title: String,
    val amountRaw: BigInteger? = null,
    val counterparty: String? = null,
    val timestampSeconds: Long? = null,
    val provenance: Provenance,
    val txHash: String,
    val blockNumber: Long,
    val passportHash: String? = null,
    val gasUsed: String? = null,
    val isBlocked: Boolean = false,
    val details: String? = null,
    val explorerUrl: String? = null,
)

fun activityProvenance(source: String): Provenance = when (source.uppercase()) {
    "AGENT" -> Provenance.Agent
    "OWNER" -> Provenance.Owner
    "APP" -> Provenance.App
    else -> Provenance.Script
}

fun activityTitle(kind: String): String = when (kind.uppercase()) {
    "DEPOSIT" -> "Deposit to vault"
    "WITHDRAW" -> "Withdraw from vault"
    "REBALANCE" -> "Rebalance vault position"
    "PAY_OBLIGATION", "OBLIGATIONPAID", "OBLIGATION_PAID" -> "Bill paid"
    "ACTIONEXECUTED", "ACTION_EXECUTED" -> "Policy action executed"
    "ACTIONQUEUED", "ACTION_QUEUED" -> "Action queued"
    "PAUSED" -> "Guardian pause"
    "EMERGENCYUNWIND", "EMERGENCY_UNWIND" -> "Emergency unwind"
    "PAYEEADDED", "PAYEE_ADDED" -> "Vendor cooling started"
    "OWNER_PAY" -> "Bill paid by owner"
    "SAFE_CREATION" -> "Safe treasury created"
    "MODULE_ENABLE" -> "Wallet module enabled"
    else -> kind.replace('_', ' ').replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase().replaceFirstChar { it.uppercase() }
}

object ActivityTimelineBuilder {
    fun build(state: WalletState, safety: SafetyData, frozen: List<ActivityItem> = emptyList()): List<ActivityItem> {
        // Keep each module log/evidence row; a transaction can contain several distinct actions.
        val frozenByTx = frozen.groupBy { it.txHash.lowercase() }
        val actionsByTx = state.recentActions.associateBy { it.txHash.lowercase() }
        val rows = state.evidence.map { ev ->
            val action = actionsByTx[ev.txHash.lowercase()].takeIf { ev.kind == "ActionExecuted" }
            val evidence = frozenByTx[ev.txHash.lowercase()]?.firstOrNull()
            val address = action?.payee
            ActivityItem(ev.id, action?.let { activityTitle(it.kind.name) } ?: activityTitle(ev.kind),
                amountRaw = action?.amount?.raw ?: evidence?.amountRaw,
                counterparty = address?.let { a -> state.payees.find { it.address.equals(a, true) }?.label ?: a } ?: evidence?.counterparty,
                timestampSeconds = ev.blockTime.takeIf { it > 0 },
                provenance = evidence?.provenance ?: activityProvenance(action?.source ?: ev.source),
                txHash = ev.txHash, blockNumber = ev.blockNumber,
                passportHash = validPassport(action?.decisionHash ?: ev.decisionHash) ?: evidence?.passportHash,
                gasUsed = evidence?.gasUsed,
                details = "Observed module event: ${ev.kind}", explorerUrl = ev.explorerUrl)
        }.toMutableList()
        val liveTx = rows.map { it.txHash.lowercase() }.toSet()
        state.recentActions.filter { it.txHash.lowercase() !in liveTx }.forEach { action ->
            rows += ActivityItem("action:${action.id}", activityTitle(action.kind.name), action.amount.raw,
                action.payee?.let { a -> state.payees.find { it.address.equals(a, true) }?.label ?: a },
                action.blockTime.takeIf { it > 0 }, activityProvenance(action.source), action.txHash,
                action.blockNumber, validPassport(action.decisionHash))
        }
        // A matching live transaction already exposes its logs. Retain evidence metadata there.
        val observed = rows.map { it.txHash.lowercase() }.toSet()
        rows += frozen.filter { it.txHash.lowercase() !in observed }
        val allTx = rows.map { it.txHash.lowercase() }.toSet()
        safety.blockedOnChain.filter { it.txHash.lowercase() !in allTx }.forEach {
            rows += ActivityItem("blocked:${it.id}:${it.txHash}", "Blocked: ${it.scenario}",
                provenance = Provenance.Script, txHash = it.txHash, blockNumber = it.blockNumber,
                passportHash = validPassport(it.decisionHash), isBlocked = true,
                details = "Evidence records a reverted transaction. No success event was emitted.", explorerUrl = it.explorerUrl)
        }
        return rows.distinctBy { it.id }.sortedWith(compareByDescending<ActivityItem> { it.blockNumber }.thenBy { it.id })
    }

    fun parseFrozen(json: String): List<ActivityItem> {
        val rows = JSONObject(json).getJSONArray("rows")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val hash = row.optString("txHash")
            if (!Regex("0x[0-9a-fA-F]{64}").matches(hash)) return@mapNotNull null
            val fields = row.optJSONObject("expectedEvent")?.optJSONObject("fields")
            val raw = fields?.optString("amountRaw")?.takeIf { it.isNotBlank() }?.toBigIntegerOrNull()
            val blocked = row.optInt("status", 1) == 0 || row.optString("kind") == "INTENTIONAL_REVERT"
            ActivityItem("frozen:$index:$hash", if (blocked) "Blocked: ${row.optString("scenario")}" else activityTitle(row.optString("kind")),
                amountRaw = raw, counterparty = fields?.optString("recipient")?.takeIf { it.isNotBlank() },
                timestampSeconds = row.optLong("blockTime").takeIf { it > 0 },
                provenance = activityProvenance(row.optString("source")), txHash = hash,
                blockNumber = row.optLong("block"), passportHash = validPassport(row.optString("decisionHash")),
                gasUsed = row.optString("gasUsed").takeIf { it.isNotBlank() && it != "null" },
                isBlocked = blocked, details = row.optString("scenario"))
        }
    }

    private fun validPassport(hash: String?): String? = hash?.takeIf {
        Regex("0x[0-9a-fA-F]{64}").matches(it) && it.drop(2).any { c -> c != '0' }
    }
}

fun ActivityItem.matches(filter: ActivityFilter): Boolean = when (filter) {
    ActivityFilter.All -> true
    ActivityFilter.Blocked -> isBlocked
    else -> provenance.name == filter.name
}
