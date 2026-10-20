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
    val provenance: Provenance?,
    val txHash: String,
    val blockNumber: Long,
    val passportHash: String? = null,
    val gasUsed: String? = null,
    val isBlocked: Boolean = false,
    val details: String? = null,
    val explorerUrl: String? = null,
    val planner: String? = null,
)

fun activityProvenance(source: String?): Provenance? = when (source?.trim()?.uppercase()) {
    "AGENT" -> Provenance.Agent
    "OWNER" -> Provenance.Owner
    "APP" -> Provenance.App
    "SCRIPT" -> Provenance.Script
    else -> null
}

fun activityTitle(kind: String): String = when (kind.uppercase()) {
    "DEPOSIT" -> "Deposit to vault"
    "WITHDRAW" -> "Withdraw from vault"
    "REBALANCE" -> "Rebalance vault position"
    "PAY_OBLIGATION", "OBLIGATIONPAID", "OBLIGATION_PAID" -> "Bill paid"
    "PAY_INSTANT" -> "Instant bill payment"
    "PAY_DELAYED" -> "Delayed bill payment"
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
    fun build(state: WalletState, safety: SafetyData, frozen: List<ActivityItem> = emptyList(), txIndex: TxIndex = TxIndex.Empty): List<ActivityItem> {
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
                // Module event/action sources inherit the deployment's SCRIPT default.
                // Only explicit evidence and the index can establish provenance.
                provenance = evidence?.provenance,
                txHash = ev.txHash, blockNumber = ev.blockNumber,
                passportHash = validPassport(action?.decisionHash ?: ev.decisionHash) ?: evidence?.passportHash,
                gasUsed = evidence?.gasUsed,
                details = "Observed module event: ${activityTitle(ev.kind)}", explorerUrl = ev.explorerUrl)
        }.toMutableList()
        val liveTx = rows.map { it.txHash.lowercase() }.toSet()
        state.recentActions.filter { it.txHash.lowercase() !in liveTx }.forEach { action ->
            rows += ActivityItem("action:${action.id}", activityTitle(action.kind.name), action.amount.raw,
                action.payee?.let { a -> state.payees.find { it.address.equals(a, true) }?.label ?: a },
                action.blockTime.takeIf { it > 0 }, null, action.txHash,
                action.blockNumber, validPassport(action.decisionHash))
        }
        // A matching live transaction already exposes its logs. Retain evidence metadata there.
        val observed = rows.map { it.txHash.lowercase() }.toSet()
        rows += frozen.filter { it.txHash.lowercase() !in observed }
        val allTx = rows.map { it.txHash.lowercase() }.toSet()
        safety.blockedOnChain.filter { it.txHash.lowercase() !in allTx }.forEach {
            rows += ActivityItem("blocked:${it.id}:${it.txHash}", "Blocked: ${it.scenario}",
                provenance = null, txHash = it.txHash, blockNumber = it.blockNumber,
                passportHash = validPassport(it.decisionHash), isBlocked = true,
                details = "Evidence records a reverted transaction. No success event was emitted.", explorerUrl = it.explorerUrl)
        }
        return rows.map { row ->
            val indexed = txIndex.find(row.txHash) ?: return@map row
            row.copy(provenance = activityProvenance(indexed.source),
                amountRaw = indexed.amountRaw ?: row.amountRaw,
                counterparty = indexed.payeeLabel ?: indexed.recipient?.let { address ->
                    state.payees.find { it.address.equals(address, true) }?.label ?: address
                } ?: row.counterparty,
                planner = indexed.planner)
        }.distinctBy { it.id }
            // A payment emits both ActionExecuted and ObligationPaid, which render as the
            // same "Bill paid" card twice. Merge only rows that would look identical
            // (same tx and title); distinct events in one tx (e.g. a pause) stay separate.
            .groupBy { (it.txHash.lowercase().ifBlank { it.id }) to it.title }
            .map { (_, same) -> same.maxByOrNull { (if (it.amountRaw != null) 2 else 0) + (if (it.counterparty != null) 1 else 0) }!! }
            .sortedWith(compareByDescending<ActivityItem> { it.blockNumber }.thenBy { it.id })
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
    else -> provenance?.name == filter.name
}
