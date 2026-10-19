package app.fusion.wallet.ui.agent

import app.fusion.wallet.data.*
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class ScreenMathTest {
    @Test fun gaugeKeepsPrecisionForUint256Amounts() {
        val limit = BigInteger.ONE.shiftLeft(255)
        assertEquals(2_500, gaugeBasisPoints(limit / BigInteger.valueOf(4), limit))
        assertEquals(10_000, gaugeBasisPoints(limit * BigInteger.TWO, limit))
        assertEquals(0, gaugeBasisPoints(BigInteger.valueOf(-1), limit))
        assertNull(gaugeBasisPoints(BigInteger.ONE, BigInteger.ZERO))
        assertNull(gaugeBasisPoints(BigInteger.ONE, BigInteger.valueOf(-1)))
        assertEquals(3_333, gaugeBasisPoints(BigInteger.ONE, BigInteger.valueOf(3)))
    }

    @Test fun countdownClampsAndDoesNotOverflow() {
        assertEquals(0L, remainingSeconds(100, 101))
        assertEquals(0L, remainingSeconds(100, 100))
        assertEquals(1L, remainingSeconds(101, 100))
        assertEquals(Long.MAX_VALUE, remainingSeconds(Long.MAX_VALUE, Long.MIN_VALUE))
        assertEquals("0:00", countdownText(100, 100))
        assertEquals("2:31", countdownText(251, 100))
        assertEquals("1:01:01", countdownText(3_661, 0))
        assertEquals("1d 1h 1m", countdownText(90_061, 0))
    }

    @Test fun queueReadinessAndExpiryMatchContractBoundaries() {
        val action = QueuedAction("id", ActionKind.DEPOSIT, Money.ZERO, 100, 200, QueueStatus.WAITING, "payee", "hash", BigInteger.ZERO)
        assertEquals("Queued · 0:01", queueStatusText(action, 99))
        assertEquals("Ready · awaiting execution", queueStatusText(action, 100))
        assertEquals("Ready · awaiting execution", queueStatusText(action, 200))
        assertEquals("Expired", queueStatusText(action, 201))
        assertEquals("Expired", queueStatusText(action.copy(status = QueueStatus.EXPIRED), 100))
    }
}
