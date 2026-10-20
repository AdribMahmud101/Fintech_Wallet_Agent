package app.fusion.wallet.data

import app.fusion.wallet.chain.*
import java.math.BigInteger

data class BalanceSample(val timestampSeconds: Long, val balanceRaw: BigInteger)

/** ERC-20 amounts are exact asset units. Self-transfers cancel, and module events never debit twice. */
object BalanceReconstruction {
    const val TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

    fun delta(log: ChainLog, asset: String, safe: String): BigInteger {
        if (!log.address.equals(asset, true) || log.topics.firstOrNull() != TRANSFER || log.removed) return BigInteger.ZERO
        require(log.topics.size == 3) { "Invalid USDC Transfer topics" }
        val from = Abi(log.topics[1]).requireSize(1).address(0)
        val to = Abi(log.topics[2]).requireSize(1).address(0)
        val amount = Abi(log.data).requireSize(1).uint(0)
        return (if (to.equals(safe, true)) amount else BigInteger.ZERO) -
            (if (from.equals(safe, true)) amount else BigInteger.ZERO)
    }

    fun reconstruct(current: BigInteger, head: BlockHeader, logs: List<ChainLog>,
        headers: Map<Long, BlockHeader>, asset: String, safe: String): List<BalanceSample> {
        require(current.signum() >= 0)
        val observed = logs.filter { !it.removed && it.blockNumber <= head.number }
            .distinctBy { it.blockHash to it.logIndex }
        var balance = current
        val samples = mutableListOf(BalanceSample(head.timestamp, balance))
        observed.groupBy { it.blockNumber }.toSortedMap(reverseOrder()).forEach { (number, events) ->
            val header = headers[number] ?: error("Missing history header $number")
            require(events.all { it.blockHash.equals(header.hash, true) }) { "History block reorganized" }
            require(header.timestamp <= head.timestamp)
            if (number != head.number) samples += BalanceSample(header.timestamp, balance)
            balance -= events.fold(BigInteger.ZERO) { sum, log -> sum + delta(log, asset, safe) }
            require(balance.signum() >= 0) { "Incomplete or inconsistent USDC transfer history" }
        }
        return samples.reversed().distinctBy { it.timestampSeconds }
    }
}
