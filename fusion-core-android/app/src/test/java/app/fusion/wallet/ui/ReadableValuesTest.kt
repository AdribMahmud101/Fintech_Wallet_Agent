package app.fusion.wallet.ui

import app.fusion.wallet.ui.common.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ReadableValuesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    @Test fun isoOffsetsMicrosecondsAndEpochsUseLocalReadableDate() {
        val expected = "19 Oct, 19:15 UTC"
        assertEquals(expected, formatTimestamp("2026-10-19T19:15:51.279410+00:00", utc, Locale.UK))
        assertEquals(expected, formatTimestamp("2026-10-19T21:15:51+02:00", utc, Locale.UK))
        assertEquals(expected, formatTimestamp("2026-10-19T19:15:51Z", utc, Locale.UK))
        assertEquals(expected, formatTimestamp("1792437351", utc, Locale.UK))
        assertEquals(expected, formatTimestamp("1792437351279", utc, Locale.UK))
        assertEquals("20 Oct, 00:45 GMT+05:30", formatTimestamp("2026-10-19T19:15:51Z", TimeZone.getTimeZone("GMT+05:30"), Locale.UK))
    }
    @Test fun invalidOrMissingTimeNeverShowsRawInput() {
        listOf(null, "", "bad", "2026-02-30T19:15:51Z", "2026-10-19T19:15:51").forEach {
            assertEquals("Time unavailable", formatTimestamp(it, utc, Locale.UK))
        }
        assertEquals("Time unavailable", formatEpochSeconds(Long.MAX_VALUE, utc, Locale.UK))
    }
    @Test fun enumAndPlannerLabelsAreReadable() {
        assertEquals("Owner approved", readableCode("OWNER_APPROVED"))
        assertEquals("Needs approval", readableCode("NEEDS_APPROVAL"))
        assertEquals("Policy configured", readableCode("PolicyConfigured"))
        assertEquals("On-device MNN · AHEM-G", readableCode("mnn+ahem-g@android"))
        assertEquals("Passed", readableCode("PASS"))
        assertEquals("Not recorded", readableCode(null))
    }
}
