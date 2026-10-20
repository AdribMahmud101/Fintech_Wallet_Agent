package app.fusion.wallet.ui.proof

import app.fusion.wallet.data.SafetyData
import app.fusion.wallet.data.TxIndex
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.activity.ActivityItem
import app.fusion.wallet.ui.activity.ActivityTimelineBuilder
import app.fusion.wallet.ui.activity.activityProvenance
import app.fusion.wallet.ui.activity.activityTitle

/**
 * Topic F operations: the proof screen groups every indexed transaction under the operation it
 * performed (asset management / transactions / risk control) or under owner and deployer setup.
 * The mapping is over the recorded index kind only — provenance stays what the index recorded.
 */
enum class ProofOperation {
    AssetManagement, Transactions, RiskControl, OwnerSetup;

    companion object {
        /**
         * Map an `evidence/tx_index.json` `kind` to its Topic F operation. Compound kinds recorded
         * for combined owner transactions read the first recorded operation; anything unrecorded
         * falls to the Owner/setup catch-all rather than being guessed into a money group.
         */
        fun operationOf(kind: String): ProofOperation {
            val token = kind.trim().uppercase().substringBefore("+").trim().replace("_", "").replace(" ", "")
            return when {
                token in setOf("DEPOSIT", "WITHDRAW", "REBALANCE", "OWNERALLOCATION") -> AssetManagement
                token in setOf("PAYOBLIGATION", "OBLIGATIONPAID",
                    "PAYINSTANT", "PAYDELAYED", "PAYOWNERTIER", "OWNERPAY",
                    "ACTIONQUEUED", "ACTIONAPPROVED") -> Transactions
                token in setOf("GUARDIANPAUSE", "PAUSED", "EMERGENCYUNWIND",
                    "OWNERRESUME", "RESUMED", "QUEUECANCELLED", "INTENTIONALREVERT") -> RiskControl
                else -> OwnerSetup
            }
        }
    }
}

/** One grouped section plus the proof-screen headline counts. */
data class ProofSections(
    val sections: List<ProofSection>,
    val indexedCount: Int,
    val aiExecutedCount: Int,
)

/** [rows] are newest first (index block order or the merged timeline's block order). */
data class ProofSection(val operation: ProofOperation, val rows: List<ActivityItem>)

/**
 * Turns the merged on-chain timeline the app already builds (live logs + frozen evidence, provenance
 * and amounts from `evidence/tx_index.json`) into the grouped proof rows. Only indexed transactions
 * are claimed; an index entry whose merged row is missing still appears from the index record alone
 * with whatever it honestly states (no invented time, block or amount).
 */
object ProofBuilder {
    fun build(state: WalletState, safety: SafetyData, frozen: List<ActivityItem> = emptyList(), txIndex: TxIndex = TxIndex.Empty): ProofSections {
        val merged = ActivityTimelineBuilder.build(state, safety, frozen, txIndex)
            .filter { txIndex.find(it.txHash) != null }
        val groups = ProofOperation.entries.associateWith { mutableListOf<ActivityItem>() }
        for (row in merged) {
            groups.getValue(ProofOperation.operationOf(rowTitleKind(txIndex, row.txHash))).add(row)
        }
        for (entry in txIndex.all()) {
            if (merged.any { it.txHash.equals(entry.txHash, ignoreCase = true) }) continue
            groups.getValue(ProofOperation.operationOf(entry.kind)) += ActivityItem(
                id = "index:${entry.txHash}",
                title = activityTitle(entry.kind),
                amountRaw = entry.amountRaw,
                counterparty = entry.payeeLabel ?: entry.recipient,
                timestampSeconds = null,
                provenance = activityProvenance(entry.source),
                txHash = entry.txHash,
                blockNumber = 0L,
                details = entry.evidenceFile,
                note = entry.notes,
            )
        }
        val sections = ProofOperation.entries.mapNotNull { operation ->
            val rows = groups.getValue(operation).sortedWith(
                compareByDescending<ActivityItem> { it.blockNumber }.thenBy { it.id })
            if (rows.isEmpty()) null else ProofSection(operation, rows)
        }
        val all = txIndex.all()
        return ProofSections(
            sections = sections,
            indexedCount = all.size,
            aiExecutedCount = all.count { it.source.equals("AGENT", ignoreCase = true) },
        )
    }

    /** The index is the recorded truth for what this transaction did (kind drives the group). */
    private fun rowTitleKind(txIndex: TxIndex, txHash: String): String =
        txIndex.find(txHash)!!.kind
}
