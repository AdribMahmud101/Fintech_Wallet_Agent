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
}
