package app.fusion.wallet.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * "Obsidian Institutional" tokens, verbatim from `styling.md`.
 *
 * Kept as the record of what the spec asked for. Components do not read from this object
 * directly — they read [Accessible], which differs where the spec's hex values fail
 * contrast.
 */
object Obsidian {
    val CanvasBackground = Color(0xFF08090C)
    val SurfaceLevel1 = Color(0xFF101217)
    val SurfaceLevel2 = Color(0xFF181B22)
    val SurfaceLevel3 = Color(0xFF232733)

    val BorderSubtle = Color(0xFF232733)
    val BorderFocused = Color(0xFF374151)

    val TextPrimary = Color(0xFFF9FAFB)
    val TextSecondary = Color(0xFF9CA3AF)
    val TextMuted = Color(0xFF6B7280)

    val AccentPrimary = Color(0xFF6366F1)
    val AccentAiViolet = Color(0xFF8B5CF6)

    val StatusSuccess = Color(0xFF10B981)
    val StatusWarning = Color(0xFFF59E0B)
    val StatusError = Color(0xFFEF4444)
}

/**
 * The tokens the UI actually paints with.
 *
 * Three substitutions against [Obsidian], each measured rather than eyeballed:
 *
 * | token | spec | used | why |
 * |---|---|---|---|
 * | muted text | `#6B7280` | `#7A8194` | 3.88:1 on Surface1 — fails AA 4.5:1 |
 * | button fill | `#6366F1` | `#5F62EE` | white label is 4.47:1 — misses AA by 0.03 |
 * | focus border | `#374151` | `#5A6478` | 1.82:1 — fails WCAG 1.4.11 non-text 3:1 |
 *
 * `Obsidian.AccentPrimary` remains the brand colour for large text and indicator fills,
 * where the 3:1 large-text threshold applies.
 */
object Accessible {
    /** 5.12:1 on canvas, 4.81:1 on Surface1. */
    val TextMuted = Color(0xFF7A8194)

    /** White label measures 4.70:1. */
    val ButtonFill = Color(0xFF5F62EE)
    val ButtonFillPressed = Color(0xFF4F52D6)

    /** Large text and indicator fills: 3.72:1 on Surface1 clears AA-large. */
    val AccentText = Color(0xFF5A5DE8)

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
    val radiusLarge = 16.dp
    val hairline = 1.dp
    val gutter = 16.dp
}