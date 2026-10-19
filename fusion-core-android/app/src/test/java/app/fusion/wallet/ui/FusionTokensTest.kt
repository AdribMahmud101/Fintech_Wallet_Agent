package app.fusion.wallet.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fusion.wallet.ui.components.formatBigIntegerAmount
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionShape
import app.fusion.wallet.ui.theme.FusionSpacing
import app.fusion.wallet.ui.theme.FusionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class FusionTokensTest {

    @Test
    fun tokensMatchUxPlanSpec() {
        assertEquals(Color(0xFF0B0F14), FusionColors.Bg)
        assertEquals(Color(0xFF141B22), FusionColors.Surface)
        assertEquals(Color(0xFF1A232C), FusionColors.SurfaceRaised)
        assertEquals(Color(0xFF26313B), FusionColors.Border)
        assertEquals(Color(0xFFF3F1EA), FusionColors.Text)
        assertEquals(Color(0xFF8C9A94), FusionColors.TextMuted)
        assertEquals(Color(0xFF19C37D), FusionColors.Green)
        assertEquals(Color(0xFF7C83F7), FusionColors.Indigo)
        assertEquals(Color(0xFFE8A33D), FusionColors.Amber)
        assertEquals(Color(0xFFE5484D), FusionColors.Red)
    }

    @Test
    fun shapeAndSpacingMatchSpec() {
        assertEquals(RoundedCornerShape(20.dp), FusionShape.tile)
        assertEquals(16.dp, FusionSpacing.gutter)
        assertEquals(20.dp, FusionSpacing.tilePadding)
    }

    @Test
    fun typographyScalesMatchSpec() {
        assertEquals(32.sp, FusionType.HeadingLarge.fontSize)
        assertEquals(24.sp, FusionType.HeadingMedium.fontSize)
        assertEquals(18.sp, FusionType.HeadingSmall.fontSize)
        assertEquals(15.sp, FusionType.BodyMedium.fontSize)
        assertEquals(13.sp, FusionType.BodySmall.fontSize)
    }

    @Test
    fun bigIntegerAmountFormattingIsExactAndNeverUsesFloats() {
        // 2,153 USDC (6 decimals)
        val raw2153 = BigInteger("2153000000")
        assertEquals("2,153.00", formatBigIntegerAmount(raw2153, 6, exact = false))
        assertEquals("2,153.000000", formatBigIntegerAmount(raw2153, 6, exact = true))

        // Exact fractional value
        val rawFraction = BigInteger("1234567890")
        assertEquals("1,234.57", formatBigIntegerAmount(rawFraction, 6, exact = false))
        assertEquals("1,234.567890", formatBigIntegerAmount(rawFraction, 6, exact = true))

        // Zero value
        val rawZero = BigInteger.ZERO
        assertEquals("0.00", formatBigIntegerAmount(rawZero, 6, exact = false))
        assertEquals("0.000000", formatBigIntegerAmount(rawZero, 6, exact = true))

        // Large balance with comma groupings
        val largeRaw = BigInteger("10000000000000") // 10,000,000 USDC
        assertEquals("10,000,000.00", formatBigIntegerAmount(largeRaw, 6, exact = false))
        assertEquals("10,000,000.000000", formatBigIntegerAmount(largeRaw, 6, exact = true))
    }
}
