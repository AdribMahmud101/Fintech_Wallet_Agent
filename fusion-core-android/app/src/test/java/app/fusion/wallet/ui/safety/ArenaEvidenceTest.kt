package app.fusion.wallet.ui.safety

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArenaEvidenceTest {
    private fun read(name: String) = requireNotNull(javaClass.getResourceAsStream("/k24/$name")).bufferedReader().use { it.readText() }

    @Test fun observedStoppingLayersAccountForEveryStoppedAttack() {
        val data = ArenaEvidenceParser.parse(read("arena-latest.json"))
        assertEquals(24, data.scenarios.size)
        assertEquals(23, data.stopped)
        assertEquals(mapOf("contract" to 18, "rust_candidate_policy" to 2, "rust_policy_preview" to 1,
            "durable_journal" to 1, "off-chain impl pin" to 1), data.layerCounts)
        assertEquals(data.stopped, data.layerCounts.values.sum())
        assertEquals(listOf(0, 0, 3, 0, 18), safetyDiagramLayers.map { (_, keys) -> keys.sumOf { data.layerCounts[it] ?: 0 } })
        assertEquals(listOf("A12"), data.knownLimits.map { it.id })
        assertTrue(data.knownLimits.single().explanation!!.contains("Generic signer accepts arbitrary calldata"))
    }

    @Test fun asrIsParsedSeparatelyFromEscapedArenaScenarios() {
        val arena = ArenaEvidenceParser.parse(read("arena-latest.json"))
        assertEquals(EvidenceRatio(0, 30), arena.frozenAsr)
        assertEquals(EvidenceRatio(0, 20), ArenaEvidenceParser.heldOutAsr(read("heldout.md")))
        val changed = read("heldout.md").replace("**0/20 (0.0%)**", "**2/20 (10.0%)**")
        assertEquals(EvidenceRatio(2, 20), ArenaEvidenceParser.heldOutAsr(changed))
    }

    @Test fun onlyRealBaseSepoliaReceiptsBecomeExplorerLinks() {
        val data = ArenaEvidenceParser.parse(read("arena-latest.json"))
        assertTrue(data.scenarios.flatMap { it.evidence }.any { it.txHash != null })
        assertTrue(data.scenarios.flatMap { it.evidence }.filter { it.txHash != null }.all { it.url == "https://sepolia.basescan.org/tx/${it.txHash}" })
        assertTrue(data.scenarios.single { it.id == "A17" }.evidence.all { it.txHash == null })
        assertTrue(data.scenarios.single { it.id == "A2" }.evidence.any { it.label == "test_recipientMismatch()" })
    }

    @Test fun missingAsrNeverBecomesZeroSuccesses() {
        val root = JSONObject(read("arena-latest.json"))
        root.remove("asr")
        assertNull(ArenaEvidenceParser.parse(root.toString()).frozenAsr)
    }

    @Test(expected = IllegalArgumentException::class) fun inconsistentSummaryIsRejected() {
        val root = JSONObject(read("arena-latest.json"))
        root.getJSONObject("summary").put("stopped", 24)
        ArenaEvidenceParser.parse(root.toString())
    }

    @Test(expected = IllegalStateException::class) fun missingHeldOutEvidenceDoesNotInventResults() {
        ArenaEvidenceParser.heldOutAsr("No evaluation available")
    }
    @Test fun offlineResourcesRetainTheMeasuredResultsAndParseAfterStringEscaping() {
        val path = listOf(java.io.File("src/main/res/values/k24_evidence.xml"), java.io.File("app/src/main/res/values/k24_evidence.xml")).first { it.isFile }
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path)
        val rows = doc.getElementsByTagName("string")
        val strings = (0 until rows.length).associate { index ->
            val row = rows.item(index) as org.w3c.dom.Element
            row.getAttribute("name") to org.json.JSONArray("[${row.textContent}]").getString(0)
        }
        val bundled = ArenaEvidenceParser.parse(strings.getValue("k24_arena_snapshot"))
        val original = ArenaEvidenceParser.parse(read("arena-latest.json"))
        assertEquals(original.stopped, bundled.stopped)
        assertEquals(original.layerCounts, bundled.layerCounts)
        assertEquals(original.knownLimits, bundled.knownLimits)
        assertEquals(original.scenarios.map { it.evidence }, bundled.scenarios.map { it.evidence })
        assertEquals(EvidenceRatio(0, 20), ArenaEvidenceParser.heldOutAsr(strings.getValue("k24_heldout_snapshot")))
    }
}
