package app.fusion.wallet.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Compatibility aliases for screens that predate the shared UX design tokens. */
object Obsidian {
    val CanvasBackground = FusionColors.Bg
    val SurfaceLevel1 = FusionColors.Surface
    val SurfaceLevel2 = FusionColors.SurfaceRaised
    val SurfaceLevel3 = FusionColors.SurfaceRaised

    val BorderSubtle = FusionColors.Border
    val BorderFocused = Color(0xFF374151)

    val TextPrimary = FusionColors.Text
    val TextSecondary = FusionColors.TextMuted
    val TextMuted = FusionColors.TextMuted

    val AccentPrimary = FusionColors.Green
    val AccentAiViolet = FusionColors.Indigo

    val StatusSuccess = FusionColors.Green
    val StatusWarning = FusionColors.Amber
    val StatusError = FusionColors.Red
}

/** Readable text and action colors used by existing screens. */
object Accessible {
    val TextMuted = FusionColors.TextMuted

    val ButtonFill = FusionColors.Green
    val ButtonFillPressed = Color(0xFF4F52D6)

    val AccentText = FusionColors.Indigo

    /** 3.15:1 on Surface1 — clears WCAG 1.4.11 for focus rings and control outlines. */
    val BorderFocused = Color(0xFF5A6478)
}

/**
 * Four surface levels. Material 3's ColorScheme has room for a canvas and one surface, but
 * this design nests cards inside chips inside sheets, so the remaining levels are supplied
 * through a CompositionLocal rather than by bending `surfaceVariant` out of shape.
 */
data class FusionSurfaces(
    val canvas: Color = Obsidian.CanvasBackground,
    val level1: Color = Obsidian.SurfaceLevel1,
    val level2: Color = Obsidian.SurfaceLevel2,
    val level3: Color = Obsidian.SurfaceLevel3,
    val border: Color = Obsidian.BorderSubtle,
    val borderFocused: Color = Accessible.BorderFocused,
)

val LocalFusionSurfaces = staticCompositionLocalOf { FusionSurfaces() }

object FusionDims {
    val radiusSmall = 6.dp
    val radiusMedium = 12.dp
    val radiusLarge = 20.dp
    val hairline = 1.dp
    val gutter = 16.dp
}