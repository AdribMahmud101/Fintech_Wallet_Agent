package app.fusion.wallet.ui

import org.junit.Assert.*
import org.junit.Test

class FmtTest {
    @Test
    fun blockLabelFormatsWithoutDoubleHash() {
        val formatted = Fmt.blockLabel(48322201L)
        assertEquals("Block #48322201", formatted)
        assertFalse("Must never contain double hash", formatted.contains("##"))
    }

    @Test
    fun blockLabelFormatsNullAsUnconfirmed() {
        val formatted = Fmt.blockLabel(null)
        assertEquals("Block —", formatted)
        assertFalse(formatted.contains("#"))
    }

    @Test
    fun updatedAgoFormatsSecondsMinutesHours() {
        val now = 1_000_000_000L
        assertEquals("15s", Fmt.updatedAgo(now - 15_000, now))
        assertEquals("2 min", Fmt.updatedAgo(now - 120_000, now))
        assertEquals("3 h", Fmt.updatedAgo(now - 3 * 3600_000, now))
        assertEquals("0s", Fmt.updatedAgo(now, now))
    }
}
