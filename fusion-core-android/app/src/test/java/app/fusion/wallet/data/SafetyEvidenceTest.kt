package app.fusion.wallet.data

import org.junit.Assert.*
import org.junit.Test

class SafetyEvidenceTest {
    private fun read(name: String): String =
        requireNotNull(SafetyEvidenceTest::class.java.getResourceAsStream("/safety/$name")).bufferedReader().use { it.readText() }

    private fun load() = SafetyEvidence.parse(read("base-sepolia.json"), read("arena-latest.json"))

    @Test fun arenaCountsComeFromEvidenceNotLogs() {
        val data = load()
        assertTrue(data.loaded)
        // summary block in evidence/results/latest.json
        assertEquals(24, data.scenarioCount)
        assertEquals(21, data.stopped)
        assertEquals(2, data.escaped)
        assertEquals(1, data.notImplemented)
        // ASR (ahem-g): attacks that succeeded / malicious cases
        assertEquals(0, data.asrNumerator)
        assertEquals(30, data.asrDenominator)
        assertEquals(24, data.scenarios.size)
        assertEquals(21, data.scenarios.count { it.isStopped })
    }

    @Test fun blockedOnChainAreRevertedReceiptsWithExplorerLinks() {
        val data = load()
        // E16 rows (status 0) are the on-chain reverts
        assertEquals(5, data.blockedOnChain.size)
        assertTrue(data.blockedOnChain.all { it.txHash.startsWith("0x") })
        assertTrue(data.blockedOnChain.all { it.explorerUrl == "https://sepolia.basescan.org/tx/${it.txHash}" })
        assertTrue(data.blockedOnChain.all { it.scenario.isNotBlank() })
    }

    @Test fun knownLimitsListEscapedAndNotImplementedHonestly() {
        val data = load()
        assertEquals(3, data.knownLimits.size)
        val ids = data.knownLimits.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("A12", "A13", "A17")))
        assertEquals(2, data.knownLimits.count { it.status == "escaped" })
        assertEquals(1, data.knownLimits.count { it.status == "not_implemented" })
    }

    @Test fun missingFilesYieldUnloadedEmptyData() {
        val data = SafetyEvidence.parse("{}", "{}")
        assertTrue(data.loaded)
        assertEquals(0, data.scenarioCount)
        assertTrue(data.blockedOnChain.isEmpty())
        assertTrue(data.scenarios.isEmpty())
    }
}
