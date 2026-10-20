package app.fusion.wallet.ui.activity

import app.fusion.wallet.data.*
import app.fusion.wallet.ui.components.Provenance
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class ActivityTxIndexTest {
    private val tx = "0x4d09be10bc6c75395cf5910844d9126715ebd15e8f20dbb4a6ab38f0d3be5c89"
    private val decision = "0x" + "b".repeat(64)
    private fun index() = TxIndex.parse(javaClass.getResource("/activity/tx_index.json")!!.readText())
    private fun event(id: String, kind: String, hash: String = tx) =
        EvidenceRow(id, kind, hash, 48332176, 1792432640, decision, source = "SCRIPT", explorerUrl = "https://sepolia.basescan.org/tx/$hash")
    private fun action(hash: String = tx) = AgentAction("action", ActionKind.PAY_OBLIGATION,
        Money(BigInteger("8000000")), null, hash, decision, 48332176, 1792432640, source = "SCRIPT")

    @Test fun phonePaymentResolvesToAppWithAmountPayeeAndPlannerOnEveryModuleRow() {
        val state = WalletState(recentActions = listOf(action()), evidence = listOf(
            event("paid", "ObligationPaid"), event("executed", "ActionExecuted")))
        val rows = ActivityTimelineBuilder.build(state, SafetyData(), txIndex = index())
        // Both events describe the same payment ("Bill paid"): one card, not two.
        assertEquals(1, rows.size)
        for (row in rows) {
            assertEquals(Provenance.App, row.provenance)
            assertEquals(BigInteger("8000000"), row.amountRaw)
            assertEquals("Acme Hosting", row.counterparty)
            assertEquals("mnn+ahem-g@android", row.planner)
            assertEquals(decision, row.passportHash)
            assertTrue(row.matches(ActivityFilter.App))
            assertFalse(row.matches(ActivityFilter.Agent))
        }
    }

    @Test fun indexPrecedesFrozenAndDeploymentProvenanceAndMissingLiveMetadata() {
        val frozen = ActivityItem("frozen", "Bill paid", amountRaw = BigInteger.ONE, counterparty = "stale",
            provenance = Provenance.Script, txHash = tx, blockNumber = 48332176)
        val state = WalletState(evidence = listOf(event("paid", "ObligationPaid")))
        val row = ActivityTimelineBuilder.build(state, SafetyData(), listOf(frozen), index()).single()
        assertEquals("paid", row.id)
        assertEquals(Provenance.App, row.provenance)
        assertEquals(BigInteger("8000000"), row.amountRaw)
        assertEquals("Acme Hosting", row.counterparty)
    }

    @Test fun unknownModuleTransactionsNeverInheritScriptFromDeploymentDefaults() {
        val unknown = "0x" + "a".repeat(64)
        val state = WalletState(recentActions = listOf(action(unknown)), evidence = listOf(event("unknown", "ActionExecuted", unknown)))
        val row = ActivityTimelineBuilder.build(state, SafetyData(), txIndex = index()).single()
        assertNull(row.provenance)
        assertNull(row.planner)
        assertTrue(row.matches(ActivityFilter.All))
        for (filter in listOf(ActivityFilter.Agent, ActivityFilter.Owner, ActivityFilter.App)) assertFalse(row.matches(filter))
        assertNull(activityProvenance("unrecorded"))
        assertNull(activityProvenance(null))
    }

    @Test fun actionOnlyAndFrozenOnlyRowsUseTheSameIndex() {
        val live = ActivityTimelineBuilder.build(WalletState(recentActions = listOf(action())), SafetyData(), txIndex = index()).single()
        val frozen = live.copy(provenance = Provenance.Script, amountRaw = null, counterparty = null, planner = null)
        val archived = ActivityTimelineBuilder.build(WalletState(), SafetyData(), listOf(frozen), index()).single()
        for (row in listOf(live, archived)) {
            assertEquals(Provenance.App, row.provenance)
            assertEquals(BigInteger("8000000"), row.amountRaw)
            assertEquals("Acme Hosting", row.counterparty)
        }
    }

    @Test fun recordedScriptAndOwnerOriginsArePreservedWithoutInferringFromKind() {
        for ((source, expected) in listOf("SCRIPT" to Provenance.Script, "OWNER" to Provenance.Owner, "AGENT" to Provenance.Agent)) {
            val indexed = TxIndex.parse("""[{"txHash":"$tx","source":"$source","kind":"OWNER_PAY","evidenceFile":"fixture.json"}]""")
            val row = ActivityTimelineBuilder.build(WalletState(evidence = listOf(event("paid", "ObligationPaid"))), SafetyData(), txIndex = indexed).single()
            assertEquals(expected, row.provenance)
            assertNull(row.amountRaw)
            assertNull(row.counterparty)
        }
    }
}
