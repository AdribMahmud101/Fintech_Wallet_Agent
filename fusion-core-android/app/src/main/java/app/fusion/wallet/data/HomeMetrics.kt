package app.fusion.wallet.data

import java.math.BigDecimal
import java.math.BigInteger

/** ContractDecoders normalizes dailyUsed to the snapshot's UTC day. */
data class AiAllowance(val remaining: BigInteger, val limit: BigInteger, val perMove: BigInteger) {
    val fraction: Float get() = if (limit.signum() == 0) 0f else remaining.toBigDecimal().divide(limit.toBigDecimal(), 8, java.math.RoundingMode.DOWN).toFloat()
}
fun aiAllowance(used: BigInteger, limit: BigInteger, instantMax: BigInteger): AiAllowance {
    require(listOf(used, limit, instantMax).all { it.signum() >= 0 })
    return AiAllowance((limit - used).max(BigInteger.ZERO), limit, instantMax)
}
fun approvalBannerVisible(queue: List<QueuedAction>): Boolean = queue.isNotEmpty()
fun billsStayCovered(buckets: Buckets?): Boolean = buckets != null && buckets.shortfall.raw.signum() == 0 && buckets.buffer.raw >= buckets.bufferTarget.raw

data class AgentGas(val balanceWei: BigInteger, val medianCostWei: BigDecimal?, val estimatedMoves: BigInteger?)
fun gasRunway(balanceWei: BigInteger, recentCostsWei: List<BigInteger>): AgentGas {
    require(balanceWei.signum() >= 0 && recentCostsWei.all { it.signum() >= 0 })
    val costs = recentCostsWei.filter { it.signum() > 0 }.sorted()
    val median = if (costs.isEmpty()) null else if (costs.size % 2 == 1) costs[costs.size / 2].toBigDecimal()
        else (costs[costs.size / 2 - 1] + costs[costs.size / 2]).toBigDecimal().divide(2.toBigDecimal())
    return AgentGas(balanceWei, median, median?.let { balanceWei.toBigDecimal().divideToIntegralValue(it).toBigInteger() })
}
