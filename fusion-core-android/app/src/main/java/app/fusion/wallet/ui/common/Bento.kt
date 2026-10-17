package app.fusion.wallet.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoStyle

/** Gap between tiles, in both directions, so the grid reads as one rhythm. */
val BentoGap = 10.dp
private val TileShape = RoundedCornerShape(22.dp)

/**
 * A bento tile: large radius, quiet border, and an optional tint that fades in from the top so a tile can
 * carry meaning (good / warning) without shouting. Pass `Modifier.fillMaxHeight()` inside a [BentoRow].
 */
@Composable
fun BentoTile(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surfaces = FusionTheme.surfaces
    val base = Modifier
        .clip(TileShape)
        .background(surfaces.level1)
        .let { if (tint != null) it.background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent))) else it }
        .border(FusionDims.hairline, surfaces.border, TileShape)
        .let { if (onClick != null) it.clickable(onClick = onClick).semantics { role = Role.Button } else it }
    Column(modifier.then(base).padding(padding), content = content)
}

/** Equal-height row of tiles. Give each child `Modifier.weight(w).fillMaxHeight()`. */
@Composable
fun BentoRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(BentoGap), content = content)
}

/** Small muted caption at the top of a tile. */
@Composable
fun TileLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Muted)
}

/** A count/value tile: big number, small caption. */
@Composable
fun StatTile(modifier: Modifier, label: String, value: String, color: Color, tint: Color? = null) {
    BentoTile(modifier, tint = tint) {
        Text(value, style = MonoStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold), color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp))
    }
}
