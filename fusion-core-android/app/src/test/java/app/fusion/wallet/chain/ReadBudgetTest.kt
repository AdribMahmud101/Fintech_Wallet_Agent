package app.fusion.wallet.chain

import org.junit.Assert.*
import org.junit.Test

class ReadBudgetTest {
    @Test fun smallBatchFollowedByLargeBatchCannotBurstAboveBudget() {
        var now = 0L
        val budget = ReadBudget(nanoTime = { now }, sleep = { now += it })
        val sent = mutableListOf<Pair<Long, Int>>()
        // Old spacing sent 10 then 20 only 500ms apart, exceeding the provider's 25/s.
        for (count in listOf(10, 20, 17, 3, 1, 20)) {
            budget.acquire("https://sepolia.base.org", count)
            sent += now to count
        }
        assertEquals(listOf(0L, 1L, 2L, 2L, 3L, 4L), sent.map { it.first / 1_000_000_000L })
        for ((at, _) in sent) {
            assertTrue(sent.filter { it.first > at - 1_000_000_000L && it.first <= at }.sumOf { it.second } <= 20)
        }
    }

    @Test fun concurrentReservationsShareBudgetButDifferentEndpointsDoNot() {
        val waits = mutableListOf<Long>()
        // Keep the clock fixed to model callers reserving while earlier callers sleep.
        val budget = ReadBudget(nanoTime = { 0L }, sleep = { waits += it })
        repeat(3) { budget.acquire("https://sepolia.base.org", 20) }
        budget.acquire("https://other.example", 20)
        assertEquals(listOf(1_000_000_000L, 2_000_000_000L), waits)
    }
}
