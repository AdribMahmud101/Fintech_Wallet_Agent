package app.fusion.wallet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fusion.wallet.R

/**
 * Shared Design System Tokens for Fusion Wallet (UX Plan §1).
 *
 * Dark obsidian bento grid:
 * - 20 dp corner radius on tiles, 1 dp border
 * - 16 dp gutters, 20 dp tile padding
 * - Numbers big (JetBrains Mono), headings clear (Space Grotesk), body legible (IBM Plex Sans)
 */
object FusionColors {
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF141B22)
    val SurfaceRaised = Color(0xFF1A232C)
    val Border = Color(0xFF26313B)
    val Text = Color(0xFFF3F1EA)
    val TextMuted = Color(0xFF8C9A94)
    val Green = Color(0xFF19C37D)
    val Indigo = Color(0xFF7C83F7)
    val Amber = Color(0xFFE8A33D)
    val Red = Color(0xFFE5484D)
}

object FusionType {
    val SpaceGrotesk = FontFamily(
        Font(R.font.space_grotesk, FontWeight.Normal),
        Font(R.font.space_grotesk, FontWeight.Medium),
        Font(R.font.space_grotesk, FontWeight.SemiBold),
        Font(R.font.space_grotesk, FontWeight.Bold),
    )
    val PlexSans = FontFamily(
        Font(R.font.ibm_plex_sans, FontWeight.Normal),
        Font(R.font.ibm_plex_sans, FontWeight.Medium),
        Font(R.font.ibm_plex_sans, FontWeight.SemiBold),
    )
    val JetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono, FontWeight.Normal),
        Font(R.font.jetbrains_mono, FontWeight.Medium),
        Font(R.font.jetbrains_mono, FontWeight.SemiBold),
    )

    // Type scale: 32 / 24 / 18 / 15 / 13 sp
    val HeadingLarge = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        color = FusionColors.Text,
    )
    val HeadingMedium = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        color = FusionColors.Text,
    )
    val HeadingSmall = TextStyle(
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = FusionColors.Text,
    )
    val BodyMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = FusionColors.Text,
    )
    val BodySmall = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = FusionColors.TextMuted,
    )
    val NumberLarge = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        color = FusionColors.Text,
    )
    val NumberMedium = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        color = FusionColors.Text,
    )
    val NumberSmall = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = FusionColors.Text,
    )
    val NumberBody = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = FusionColors.Text,
    )
    val NumberCaption = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        color = FusionColors.TextMuted,
    )
}

object FusionShape {
    val tile = RoundedCornerShape(20.dp)
}

object FusionSpacing {
    val gutter: Dp = 16.dp
    val tilePadding: Dp = 20.dp
}
