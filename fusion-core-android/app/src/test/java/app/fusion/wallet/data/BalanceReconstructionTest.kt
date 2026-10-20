package app.fusion.wallet.data

import app.fusion.wallet.chain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class BalanceReconstructionTest {
    private val asset = "0x" + "aa".repeat(20)
    private val safe = "0x" + "bb".repeat(20)
    private val other = "0x" + "cc".repeat(20)
    private fun header(n: Long) = BlockHeader(n, "0x" + n.toString(16).padStart(64, '0'), n * 10)
    private fun transfer(n: Long, from: String, to: String, amount: BigInteger, index: Long = 0) = ChainLog(
        asset, listOf(BalanceReconstruction.TRANSFER, "0x" + Abi.addressWord(from), "0x" + Abi.addressWord(to)),
        "0x" + Abi.uintWord(amount), "tx$n", n, header(n).hash, index)

    @Test fun reconstructsIncomingOutgoingAndModuleCheckpointsWithoutDoubleCounting() {
        val incoming = transfer(1, other, safe, BigInteger.valueOf(500))
        val outgoing = transfer(3, safe, other, BigInteger.valueOf(200))
        val module = outgoing.copy(address = other, topics = listOf(ModuleEvents.PAID), data = "0x", logIndex = 1)
        val points = BalanceReconstruction.reconstruct(BigInteger.valueOf(300), header(5),
            listOf(incoming, outgoing, outgoing, module, module.copy(blockNumber = 2, blockHash = header(2).hash)),
            (1L..5L).associateWith(::header), asset, safe)
        assertEquals(listOf(10L, 20L, 30L, 50L), points.map { it.timestampSeconds })
        assertEquals(listOf(500, 500, 300, 300).map { it.toBigInteger() }, points.map { it.balanceRaw })
    }
    @Test fun selfTransfersCancelAndHugeUnitsStayExact() {
        val huge = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        val self = transfer(1, safe, safe, huge)
        assertEquals(BigInteger.ZERO, BalanceReconstruction.delta(self, asset, safe))
        val points = BalanceReconstruction.reconstruct(huge, header(2), listOf(self), mapOf(1L to header(1)), asset, safe)
        assertTrue(points.all { it.balanceRaw == huge })
    }
    @Test fun sameBlockTransfersAggregateAndBrokenHistoryIsRejected() {
        val logs = listOf(transfer(1, other, safe, BigInteger.TEN), transfer(1, safe, other, BigInteger.TWO, 1))
        assertEquals(2, BalanceReconstruction.reconstruct(BigInteger.TEN, header(2), logs, mapOf(1L to header(1)), asset, safe).size)
        assertThrows(IllegalArgumentException::class.java) {
            BalanceReconstruction.reconstruct(BigInteger.ONE, header(2), logs, mapOf(1L to header(1)), asset, safe)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BalanceReconstruction.reconstruct(BigInteger.TEN, header(2), logs, mapOf(1L to header(1).copy(hash = "wrong")), asset, safe)
        }
    }
}
