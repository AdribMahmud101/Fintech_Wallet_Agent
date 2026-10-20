package app.fusion.wallet.ui.proof

import app.fusion.wallet.data.EvidenceRow
import app.fusion.wallet.data.SafetyData
import app.fusion.wallet.data.TxIndex
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.components.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class ProofBuilderTest {
    private val deposit = "0x" + "1".repeat(64)
    private val payment = "0x" + "2".repeat(64)
    private val pause = "0x" + "3".repeat(64)
    private val setup = "0x" + "4".repeat(64)

    @Test
    fun indexKindsMapToTheirTopicFOperation() {
        for (kind in listOf("DEPOSIT", "WITHDRAW", "REBALANCE", "OWNER_ALLOCATION")) {
            assertEquals(ProofOperation.AssetManagement, ProofOperation.operationOf(kind))
        }
        for (kind in listOf("PAY_OBLIGATION", "OBLIGATION_PAID", "PAY_INSTANT", "ACTION_APPROVED", "OBLIGATIONPAID+PAUSED")) {
            assertEquals(ProofOperation.Transactions, ProofOperation.operationOf(kind))
        }
        for (kind in listOf("PAUSED", "EMERGENCYUNWIND", "OWNER_RESUME", "INTENTIONAL_REVERT")) {
            assertEquals(ProofOperation.RiskControl, ProofOperation.operationOf(kind))
        }
        for (kind in listOf("SAFE_CREATION", "MODULEENABLE", "OBLIGATIONS_COMMITTED", "unrecorded kind")) {
            assertEquals(ProofOperation.OwnerSetup, ProofOperation.operationOf(kind))
        }
    }

    @Test
    fun groupedByOperationWithHeaderCountsFromTheRecordedIndexOnly() {
        val index = index()
        val result = ProofBuilder.build(state(), SafetyData(), txIndex = index)
        assertEquals(4, result.indexedCount)
        // AGENT = deposit + pause; APP = payment; SCRIPT = Safe creation.
        assertEquals(2, result.aiExecutedCount)
        val asset = result.sections.single { it.operation == ProofOperation.AssetManagement }
        assertEquals(deposit, asset.rows.single().txHash)
        assertEquals(Provenance.Agent, asset.rows.single().provenance)
        val transactions = result.sections.single { it.operation == ProofOperation.Transactions }
        assertEquals(payment, transactions.rows.single().txHash)
        assertEquals(BigInteger("8000000"), transactions.rows.single().amountRaw)
        val risk = result.sections.single { it.operation == ProofOperation.RiskControl }
        assertEquals(pause, risk.rows.single().txHash)
        // The Safe creation entry stays under Owner/setup; no money group claims it.
        val owner = result.sections.single { it.operation == ProofOperation.OwnerSetup }
        assertEquals(setup, owner.rows.single().txHash)
        assertEquals(Provenance.Script, owner.rows.single().provenance)
    }

    @Test
    fun everySectionIsSortedNewestBlockFirst() {
        val newer = "0x" + "5".repeat(64)
        val index = TxIndex.parse("""[
            {"txHash":"$deposit","source":"AGENT","kind":"DEPOSIT","amountRaw":"5000000","evidenceFile":"e.json"},
            {"txHash":"$newer","source":"APP","kind":"PAY_INSTANT","evidenceFile":"e.json"}]""")
        val state = WalletState(
            evidence = listOf(
                EvidenceRow("evOld", "VaultDeposited", deposit, 99, 1700000000, "0x" + "a".repeat(64),
                    source = "SCRIPT", explorerUrl = "https://sepolia.basescan.org/tx/$deposit"),
                EvidenceRow("evNew", "ObligationPaid", newer, 100, 1700000100, "0x" + "b".repeat(64),
                    source = "SCRIPT", explorerUrl = "https://sepolia.basescan.org/tx/$newer"),
            ),
        )
        val result = ProofBuilder.build(state, SafetyData(), txIndex = index)
        // Deposit stays in asset management; the payment is a transaction.
        val asset = result.sections.single { it.operation == ProofOperation.AssetManagement }
        assertEquals(listOf(deposit), asset.rows.map { it.txHash })
        val transactions = result.sections.single { it.operation == ProofOperation.Transactions }
        assertEquals(listOf(newer), transactions.rows.map { it.txHash })
        assertEquals(2, result.indexedCount)
    }

    @Test
    fun indexEntryWithoutMergedStillShowsWithIndexRecordOnly() {
        val lone = "0x" + "5".repeat(64)
        val index = TxIndex.parse(
            """[{"txHash":"$lone","source":"OWNER","kind":"SAFE_CREATION","evidenceFile":"evidence/proof/setup.json"}]""")
        val result = ProofBuilder.build(WalletState(), SafetyData(), txIndex = index)
        val row = result.sections.single { it.operation == ProofOperation.OwnerSetup }.rows.single()
        assertEquals("index:$lone", row.id)
        assertNull(row.timestampSeconds)
        assertNull(row.amountRaw)
        assertEquals(Provenance.Owner, row.provenance)
        assertEquals("evidence/proof/setup.json", row.details)
        assertEquals(1, result.indexedCount)
        assertEquals(0, result.aiExecutedCount)
        assertTrue(result.sections.none { it.operation == ProofOperation.Transactions })
    }

    @Test
    fun transactionNoteIsShownFromTheRecordedIndex() {
        val note = "Owner-mandated rehearsal (2-of-2)"
        val index = TxIndex.parse(
            """[{"txHash":"$deposit","source":"APP","kind":"DEPOSIT","evidenceFile":"evidence/app.json","notes":"$note"}]""")
        val row = ProofBuilder.build(WalletState(), SafetyData(), txIndex = index)
            .sections.single { it.operation == ProofOperation.AssetManagement }.rows.single()
        assertEquals(note, row.note)
    }

    private fun index() = TxIndex.parse(
        """[
            {"txHash":"$deposit","source":"AGENT","kind":"DEPOSIT","amountRaw":"5000000","recipient":"0x00000000000000000000000000000000000d0001","evidenceFile":"evidence/vault/deposit.json","planner":"mnn+ahem-g@android"},
            {"txHash":"$payment","source":"APP","kind":"PAY_OBLIGATION","amountRaw":"8000000","payeeLabel":"Acme Hosting","evidenceFile":"evidence/pay/payment.json"},
            {"txHash":"$pause","source":"AGENT","kind":"PAUSED","evidenceFile":"evidence/risk/pause.json"},
            {"txHash":"$setup","source":"SCRIPT","kind":"SAFE_CREATION","evidenceFile":"evidence/setup/safe.json"}
        ]""")

    private fun state() = WalletState(
        evidence = listOf(
            EvidenceRow("ev1", "VaultDeposited", deposit, 100, 1700000000, "0x" + "a".repeat(64),
                source = "SCRIPT", explorerUrl = "https://sepolia.basescan.org/tx/$deposit"),
            EvidenceRow("ev2", "ObligationPaid", payment, 101, 1700000100, "0x" + "b".repeat(64),
                source = "SCRIPT", explorerUrl = "https://sepolia.basescan.org/tx/$payment"),
            EvidenceRow("ev3", "Paused", pause, 102, 1700000200, "0x" + "c".repeat(64),
                source = "SCRIPT", explorerUrl = "https://sepolia.basescan.org/tx/$pause"),
            EvidenceRow("ev4", "ActionExecuted", "0x" + "7".repeat(64), 103, 1700000300, "0x" + "d".repeat(64),
                source = "SCRIPT", explorerUrl = "https://sepolia.basescan.org/tx/" + "0x" + "7".repeat(64)),
        ),
        recentActions = emptyList(),
    )
}
