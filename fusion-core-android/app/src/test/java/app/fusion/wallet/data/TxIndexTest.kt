package app.fusion.wallet.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.math.BigInteger

class TxIndexTest {
    @get:Rule val temp = TemporaryFolder()
    private val tx = "0x4d09be10bc6c75395cf5910844d9126715ebd15e8f20dbb4a6ab38f0d3be5c89"
    private fun fixture() = javaClass.getResource("/activity/tx_index.json")!!.readText()

    @Test fun appFixtureParsesWithExactAmountAndCaseInsensitiveLookup() {
        val index = TxIndex.parse(fixture())
        val entry = index.find(tx.uppercase().replace("0X", "0x"))!!
        assertEquals("APP", entry.source)
        assertEquals(BigInteger("8000000"), entry.amountRaw)
        assertEquals("0x61dd06ce2f07d3e4f97d097741b0d7fd50f97f79", entry.recipient)
        assertEquals("Acme Hosting", entry.payeeLabel)
        assertEquals("mnn+ahem-g@android", entry.planner)
        assertEquals("evidence/app/agent/golden_app.json", entry.evidenceFile)
        assertNull(index.find("0x" + "f".repeat(64)))
        assertNull(index.find(null))
    }

    @Test fun controlTransactionsDoNotInventPaymentDetailsOrPlanner() {
        val entry = TxIndex.parse("""[{"txHash":"$tx","source":"OWNER","kind":"OWNER_RESUME","evidenceFile":"evidence/app/guardian_pause.json"}]""").find(tx)!!
        assertEquals("OWNER", entry.source)
        assertNull(entry.amountRaw)
        assertNull(entry.recipient)
        assertNull(entry.payeeLabel)
        assertNull(entry.planner)
    }

    @Test fun amountsBeyondFloatingPointPrecisionRemainExact() {
        val raw = "1234567890123456789012345678901234567890"
        val entry = TxIndex.parse(fixture().replace("8000000", raw)).find(tx)!!
        assertEquals(BigInteger(raw), entry.amountRaw)
    }

    @Test fun malformedIndexCannotBecomeRecordedProvenance() {
        val row = fixture().trim().removePrefix("[").removeSuffix("]")
        for (json in listOf(
            fixture().replace(tx, "0x123"),
            fixture().replace("APP", "MODEL"),
            fixture().replace("8000000", "-1"),
            fixture().replace("8000000", "1.5"),
            fixture().replace("0x61dd06ce2f07d3e4f97d097741b0d7fd50f97f79", "invalid"),
            "[$row,$row]",
        )) {
            assertTrue("Accepted invalid index: $json", runCatching { TxIndex.parse(json) }.isFailure)
        }
    }

    @Test fun cacheSurvivesRestartAndFailedOrMalformedRefresh() {
        val file = temp.newFolder().resolve("evidence/tx_index.json")
        assertNull(TxIndexCache(file).cached().find(tx))
        assertEquals("APP", TxIndexCache(file) { fixture() }.refresh().find(tx)?.source)
        assertEquals("APP", TxIndexCache(file).cached().find(tx)?.source)
        assertEquals("APP", TxIndexCache(file) { throw IOException("offline") }.refresh().find(tx)?.source)
        assertEquals("APP", TxIndexCache(file) { "<html>404</html>" }.refresh().find(tx)?.source)
        assertEquals(fixture(), file.readText())
    }

    @Test fun missingCacheAndUnavailableRemoteLeaveSourceUnknown() {
        val file = temp.newFolder().resolve("tx_index.json")
        val index = TxIndexCache(file) { throw IOException("offline") }.refresh()
        assertNull(index.find(tx))
        assertFalse(file.exists())
    }
}
