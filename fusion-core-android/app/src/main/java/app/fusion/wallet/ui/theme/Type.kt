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

/**
 * Type scale from `styling.md` §2: Inter for UI copy, JetBrains Mono for anything the
 * engine produced or anything technical — hashes, addresses, FFI log lines.
 *
 * Both are variable fonts, so weight is selected through the `wght` axis on API 26+. Below
 * 26 the axis is unavailable and Android renders each family's default instance, which is
 * why the fallback branch lists the same resources again without variation settings.
 */
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
    displaySmall = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
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