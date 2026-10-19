package app.fusion.wallet.ui.theme

import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import app.fusion.wallet.R

/** Variable-font helpers retained for legacy mono text styles. */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(
    resId: Int,
    weights: List<Pair<FontWeight, Int>>,
): FontFamily = if (Build.VERSION.SDK_INT >= 26) {
    FontFamily(
        weights.map { (weight, axisValue) ->
            Font(
                resId = resId,
                weight = weight,
                variationSettings = FontVariation.Settings(FontVariation.weight(axisValue)),
            )
        }
    )
} else {
    FontFamily(weights.map { (weight, _) -> Font(resId = resId, weight = weight) })
}

val InterFamily: FontFamily = variableFamily(
    resId = R.font.inter,
    weights = listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
    ),
)

val JetBrainsMonoFamily: FontFamily = variableFamily(
    resId = R.font.jetbrains_mono,
    weights = listOf(
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
    ),
)

/** Tabular figures keep hashes and balances from jittering as digits change. */
private val TabularNumbers = TextStyle(
    fontFeatureSettings = "tnum",
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

private val TrimmedLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val FusionTypography = Typography(
    displaySmall = FusionType.HeadingLarge.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    headlineMedium = FusionType.HeadingMedium.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    titleLarge = FusionType.HeadingSmall.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    titleMedium = FusionType.HeadingSmall.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    titleSmall = FusionType.BodyMedium.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    bodyLarge = FusionType.BodyMedium.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    bodyMedium = FusionType.BodyMedium.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    bodySmall = FusionType.BodySmall.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    labelLarge = FusionType.BodyMedium.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    labelMedium = FusionType.BodySmall.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    labelSmall = FusionType.BodySmall.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
)

/** Any hash, address, nonce or FFI log line. Tabular figures, mono, never wraps mid-token. */
val MonoStyle = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
).merge(TabularNumbers)

/** The 11sp mono style `layout.md` View 3 specifies for the terminal sheet. */
val MonoTerminalStyle = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.sp,
).merge(TabularNumbers)

internal val lineHeightStyle = TrimmedLineHeight