package app.fusion.wallet.ui.components

import app.fusion.wallet.ui.common.startActivitySafe
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionShape
import app.fusion.wallet.ui.theme.FusionSpacing
import app.fusion.wallet.ui.theme.FusionType
import java.math.BigInteger
import java.util.Locale

/**
 * Bento Tile: 20 dp radius, 1 dp border, dark obsidian background.
 * Optional top gradient tint for subtle status coloring.
 */
@Composable
fun BentoTile(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: Dp = FusionSpacing.tilePadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = Modifier
        .clip(FusionShape.tile)
        .background(FusionColors.Surface)
        .let {
            if (tint != null) {
                it.background(
                    Brush.verticalGradient(
                        listOf(tint.copy(alpha = 0.16f), Color.Transparent)
                    )
                )
            } else it
        }
        .border(1.dp, FusionColors.Border, FusionShape.tile)
        .let {
            if (onClick != null) {
                it.clickable(onClick = onClick).semantics { role = Role.Button }
            } else it
        }
    // Even vertical rhythm inside every tile so rows never touch.
    Column(modifier.then(base).padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/**
 * Status Chip: always text + color dot (never color alone, per UX plan §1).
 */
@Composable
fun StatusChip(
    text: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(FusionColors.SurfaceRaised)
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tone)
        )
        Text(
            text = text,
            style = FusionType.BodySmall.copy(fontWeight = FontWeight.Medium),
            color = FusionColors.Text,
            maxLines = 1,
        )
    }
}

/**
 * Pure integer-based USDC/crypto amount formatter.
 * No Float or Double money anywhere in the calculations.
 */
fun formatBigIntegerAmount(raw: BigInteger, decimals: Int = 6, exact: Boolean = false): String {
    require(decimals >= 0)
    val value = java.math.BigDecimal(raw, decimals).setScale(
        if (exact) decimals else 2, java.math.RoundingMode.HALF_UP
    )
    return java.text.DecimalFormat(if (exact && decimals == 0) "#,##0" else
        "#,##0." + "0".repeat(if (exact) decimals else 2),
        java.text.DecimalFormatSymbols(Locale.US)).format(value)
}

/**
 * BigInteger amount text: "2,153.00 USDC" or exact 6-dp value "2,153.000000 USDC".
 */
@Composable
fun AmountText(
    raw: BigInteger,
    decimals: Int = 6,
    exact: Boolean = false,
    symbol: String = "USDC",
    modifier: Modifier = Modifier,
    style: TextStyle = FusionType.NumberMedium,
    color: Color = FusionColors.Text,
) {
    val formatted = remember(raw, decimals, exact) {
        formatBigIntegerAmount(raw, decimals, exact)
    }
    Text(
        text = if (symbol.isNotBlank()) "$formatted $symbol" else formatted,
        style = style,
        color = color,
        modifier = modifier,
        maxLines = 1,
    )
}

enum class Provenance { Agent, Owner, App, Script }

/**
 * Provenance Chip: Agent (Indigo), Owner (Green), App (Amber), Script (Muted).
 */
@Composable
fun ProvenanceChip(
    provenance: Provenance,
    modifier: Modifier = Modifier,
) {
    val (tone, label) = when (provenance) {
        Provenance.Agent -> FusionColors.Indigo to "Agent"
        Provenance.Owner -> FusionColors.Green to "Owner"
        Provenance.App -> FusionColors.Amber to "App"
        Provenance.Script -> FusionColors.TextMuted to "Script"
    }
    StatusChip(text = label, tone = tone, modifier = modifier)
}

@Composable
fun ProvenanceChip(
    provenance: String,
    modifier: Modifier = Modifier,
) {
    val p = when (provenance.trim().uppercase()) {
        "AGENT" -> Provenance.Agent
        "OWNER" -> Provenance.Owner
        "APP" -> Provenance.App
        else -> Provenance.Script
    }
    ProvenanceChip(provenance = p, modifier = modifier)
}

/**
 * Transaction link: shortened hash with explorer tap target.
 */
@Composable
fun TxLink(
    hash: String,
    modifier: Modifier = Modifier,
    explorerUrl: String? = null,
    label: String? = null,
) {
    val context = LocalContext.current
    val url = explorerUrl ?: "https://sepolia.basescan.org/tx/$hash"
    val short = if (hash.length > 12) "${hash.take(6)}…${hash.takeLast(4)}" else hash

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivitySafe(intent) }
            }
            .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 2.dp)
            .semantics {
                role = Role.Button
                contentDescription = "View transaction $hash on block explorer"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label ?: short,
            style = FusionType.NumberCaption,
            color = FusionColors.Indigo,
        )
        Icon(
            painter = painterResource(R.drawable.ic_open_in_new),
            contentDescription = null,
            tint = FusionColors.Indigo,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * Stable loading placeholder tile that matches tile geometry to prevent layout jumps.
 */
@Composable
fun SkeletonTile(
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
) {
    // Static placeholder also respects the system's Remove animations setting.
    val alpha = 0.6f

    BentoTile(
        modifier = modifier.fillMaxWidth().height(height),
        padding = FusionSpacing.tilePadding,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(FusionColors.SurfaceRaised.copy(alpha = alpha))
        )
    }
}
