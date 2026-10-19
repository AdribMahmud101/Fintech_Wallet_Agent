package app.fusion.wallet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.isSystemInDarkTheme

/** Dark obsidian scheme; the shared Fusion tokens also style legacy screens. */
private val ObsidianColorScheme = darkColorScheme(
    primary = Accessible.ButtonFill,
    onPrimary = FusionColors.Bg,
    primaryContainer = Obsidian.SurfaceLevel3,
    onPrimaryContainer = Obsidian.TextPrimary,

    secondary = Obsidian.AccentAiViolet,
    onSecondary = Obsidian.TextPrimary,
    secondaryContainer = Obsidian.SurfaceLevel3,
    onSecondaryContainer = Obsidian.TextPrimary,

    tertiary = Obsidian.AccentAiViolet,
    onTertiary = Obsidian.TextPrimary,

    error = Obsidian.StatusError,
    onError = Obsidian.TextPrimary,
    errorContainer = Obsidian.SurfaceLevel3,
    onErrorContainer = Obsidian.StatusError,

    background = Obsidian.CanvasBackground,
    onBackground = Obsidian.TextPrimary,

    surface = Obsidian.SurfaceLevel1,
    onSurface = Obsidian.TextPrimary,
    surfaceVariant = Obsidian.SurfaceLevel2,
    onSurfaceVariant = Obsidian.TextSecondary,

    surfaceContainerLowest = Obsidian.CanvasBackground,
    surfaceContainerLow = Obsidian.SurfaceLevel1,
    surfaceContainer = Obsidian.SurfaceLevel1,
    surfaceContainerHigh = Obsidian.SurfaceLevel2,
    surfaceContainerHighest = Obsidian.SurfaceLevel3,

    outline = Obsidian.BorderFocused,
    outlineVariant = Obsidian.BorderSubtle,
    scrim = Obsidian.CanvasBackground,
)

val FusionShapes = Shapes(
    extraSmall = RoundedCornerShape(FusionDims.radiusSmall),
    small = RoundedCornerShape(FusionDims.radiusSmall),
    medium = RoundedCornerShape(FusionDims.radiusMedium),
    large = RoundedCornerShape(FusionDims.radiusLarge),
    extraLarge = RoundedCornerShape(FusionDims.radiusLarge),
)

@Composable
fun FusionWalletTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // read once so lint does not flag the import as unused on a dark-only app

    CompositionLocalProvider(LocalFusionSurfaces provides FusionSurfaces()) {
        MaterialTheme(
            colorScheme = ObsidianColorScheme,
            typography = FusionTypography,
            shapes = FusionShapes,
            content = content,
        )
    }
}

/** Convenience accessor: `FusionTheme.surfaces.level1`. */
object FusionTheme {
    val surfaces: FusionSurfaces
        @Composable @ReadOnlyComposable get() = LocalFusionSurfaces.current
}