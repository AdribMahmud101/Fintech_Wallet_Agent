package app.fusion.wallet.ui.bills

import app.fusion.wallet.chain.*
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class BillEvidenceTest {
    private val first = "0x" + "1".repeat(64)
    private val second = "0x" + "2".repeat(64)
    private val payee = "0x" + "3".repeat(64)
    private val recipient = "0x" + "4".repeat(40)
    private fun paid(id: String, tx: String, index: Long, removed: Boolean = false) = ChainLog(
        recipient, listOf(ModuleEvents.PAID, id, payee, "0x" + Abi.addressWord(recipient)),
        "0x" + Abi.uintWord(BigInteger.TEN) + Abi.uintWord(BigInteger.ZERO) + Abi.uintWord(BigInteger.ZERO),
        tx, 100, "blockhash", index, removed)

    @Test fun sameVendorAndAmountAreJoinedByBillId() {
        val evidence = BillEvidenceReader.fromLogs("block", listOf(paid(first, "tx1", 0), paid(second, "tx2", 1)), null)
        assertEquals("tx1", evidence.paymentTxByBill[first])
        assertEquals("tx2", evidence.paymentTxByBill[second])
        assertNull(evidence.queuedBillId)
    }

    @Test fun removedPaymentsDoNotProduceReceiptLinks() {
        val evidence = BillEvidenceReader.fromLogs("block", listOf(paid(first, "reorg", 0, true)), null)
        assertTrue(evidence.paymentTxByBill.isEmpty())
    }

    @Test fun queueBillIdComesFromTheExactQueuedAction() {
        val words = MutableList(19) { Abi.uintWord(BigInteger.ZERO) }
        words[0] = Abi.uintWord(BigInteger.ONE)
        words[5] = Abi.uintWord(BigInteger.valueOf(3))
        words[11] = Abi.hashWord(second)
        val evidence = BillEvidenceReader.fromLogs("block", emptyList(), Abi("0x" + words.joinToString("")))
        assertEquals(second, evidence.queuedBillId)
        words[5] = Abi.uintWord(BigInteger.ZERO)
        assertNull(BillEvidenceReader.fromLogs("block", emptyList(), Abi("0x" + words.joinToString(""))).queuedBillId)
    }
}
