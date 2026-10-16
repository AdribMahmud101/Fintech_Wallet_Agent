package app.fusion.wallet.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.model.RuleState
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian

/**
 * The flat card the whole design is built from: level-1 surface, 1dp [Obsidian.BorderSubtle]
 * outline, zero elevation (`styling.md`: "Zero shadows. Flat surfaces only.").
 */
@Composable
fun ObsidianCard(
    modifier: Modifier = Modifier,
    level: Int = 1,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surfaces = FusionTheme.surfaces
    val shape = RoundedCornerShape(FusionDims.radiusMedium)
    val interactive = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Column(
        modifier = modifier
            .clip(shape)
            .background(
                when (level) {
                    1 -> surfaces.level1
                    2 -> surfaces.level2
                    else -> surfaces.level3
                },
            )
            .border(FusionDims.hairline, surfaces.border, shape)
            .then(interactive),
        content = content,
    )
}

/**
 * Flat status pill: level-2 background, hairline outline, 6dp solid dot, label.
 *
 * [compact] drops the text and keeps only the icon and dot. Measured on a 411dp device,
 * the two labelled pills consume enough of the app bar to squeeze the wallet address down
 * to a couple of characters -- and the engine strip under the composer already spells out
 * "MNN 1.5B" and "GUARDRAILS ARMED" in full, so the labels were redundant here anyway.
 */
@Composable
fun StatusPill(
    label: String,
    tone: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val surfaces = FusionTheme.surfaces
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(FusionDims.radiusSmall))
            .background(surfaces.level2)
            .border(FusionDims.hairline, surfaces.border, RoundedCornerShape(FusionDims.radiusSmall))
            .padding(
                horizontal = if (compact) 6.dp else 6.dp,
                vertical = if (compact) 5.dp else 4.dp,
            )
            .semantics { contentDescription = label }
            .then(Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp),
    ) {
        leading?.invoke()
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tone),
        )
        if (!compact) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Rule row shared by the Pre-Flight card and the Ledger view. */
@Composable
fun RuleRow(
    label: String,
    detail: String,
    state: RuleState,
    modifier: Modifier = Modifier,
) {
    val (tone, tag) = when (state) {
        RuleState.Pass -> Obsidian.StatusSuccess to "PASS"
        RuleState.Warn -> Obsidian.StatusWarning to "WARN"
        RuleState.Block -> Obsidian.StatusError to "BLOCK"
    }
    val surfaces = FusionTheme.surfaces

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MonoStyle,
                color = Accessible.TextMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(FusionDims.radiusSmall))
                .border(FusionDims.hairline, tone.copy(alpha = 0.5f), RoundedCornerShape(FusionDims.radiusSmall))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = tone,
            )
        }
    }
}

/**
 * Daily velocity meter. The bar is level-3 with an accessible-colour fill; once past 50%
 * it turns amber, matching the `WARN` state the engine reports on the same threshold.
 */
@Composable
fun VelocityMeter(
    spentUsd: Double,
    capUsd: Double,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    height: Dp = 4.dp,
) {
    val surfaces = FusionTheme.surfaces
    val fraction = if (capUsd <= 0.0) 0f else (spentUsd / capUsd).toFloat().coerceIn(0f, 1f)
    val tone = when {
        fraction >= 1f -> Obsidian.StatusError
        fraction >= 0.5f -> Obsidian.StatusWarning
        else -> Obsidian.StatusSuccess
    }

    Column(modifier) {
        if (showLabel) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "DAILY VELOCITY",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accessible.TextMuted,
                )
                Text(
                    text = "$" + "%,.2f".format(spentUsd) + " / $" + "%,.2f".format(capUsd),
                    style = MonoStyle,
                    color = tone,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .background(surfaces.level3),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(height)
                        .clip(CircleShape)
                        .background(tone),
                )
            }
        }
    }
}

/**
 * Token monogram for assets with no branded glyph of their own.
 *
 * ETH has none in this set, and borrowing another product's icon for it (an n8n cloud was
 * the first attempt) is worse than admitting there is no mark.
 */
@Composable
fun TokenMonogram(symbol: String, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(FusionTheme.surfaces.level3)
            .border(FusionDims.hairline, FusionTheme.surfaces.borderFocused, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol.take(1),
            style = MaterialTheme.typography.labelMedium,
            color = Accessible.TextMuted,
        )
    }
}

/** Brand or status glyph. Never tinted — the drawables are already glyph-only and coloured. */
@Composable
fun FusionIcon(
    resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

/**
 * Aspect ratio of the trimmed brand lockup (`ic_fusion_logo.png`, 878x312).
 *
 * The generated constant rather than an intrinsic-size lookup so the composable does not
 * have to be laid out before it can be measured, which matters inside a `TopAppBar` slot
 * that is already being measured against a fixed height.
 */
private const val BRAND_LOCKUP_ASPECT = 878f / 312f

/**
 * The FUSION lockup -- hexagonal glyph beside the wordmark -- drawn at its true aspect.
 *
 * [FusionIcon] forces a square box, which is right for the square badges and wrong for
 * this: the lockup is 2.8:1, so a square box letterboxes the mark down to a fraction of
 * its own slot and shrinks the wordmark with it. That is why the brand used to render
 * roughly 9dp tall inside a 30dp box. Sizing by height and deriving width from the
 * artwork makes [height] mean what it says.
 */
@Composable
fun FusionBrandLockup(
    height: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(FusionIconRes.Logo),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .height(height)
            .aspectRatio(BRAND_LOCKUP_ASPECT),
    )
}

/** Keeps `R.drawable` references in one place so a rename is a single edit. */
object FusionIconRes {
    val Logo = R.drawable.ic_fusion_logo
    val LauncherForeground = R.drawable.ic_launcher_foreground
    val Mnn = R.drawable.ic_badge_mnn
    val Rust = R.drawable.ic_badge_rust
    val ShieldBlock = R.drawable.ic_block_shield
    val Send = R.drawable.ic_action_send
    val Terminal = R.drawable.ic_action_terminal
    val N8n = R.drawable.ic_badge_n8n
    val Usdc = R.drawable.ic_asset_usdc
    val Weth = R.drawable.ic_asset_weth
    val EmptyChat = R.drawable.img_empty_chat
}