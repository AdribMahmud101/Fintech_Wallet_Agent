package app.fusion.wallet.ui.charts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fusion.wallet.ui.theme.*

/** Keep the chart face quiet, with all evidence and caveats available in one tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartTileHeader(title: String, explanation: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The glyph is small; its 48 dp hit target extends into the tile padding.
    Box(Modifier.fillMaxWidth().height(20.dp)) {
        Text(title, Modifier.fillMaxWidth().padding(end = 12.dp), style = FusionChart.Title,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.CenterEnd)
            .offset(x = 16.dp).requiredSize(48.dp)
            .semantics { contentDescription = "About $title" }) {
            Text("ⓘ", style = FusionType.BodySmall, color = FusionChart.Muted)
        }
    }
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = FusionColors.SurfaceRaised,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = FusionType.HeadingSmall)
            Text(explanation, style = FusionType.BodyMedium)
            TextButton(onClick = { expanded = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Close")
            }
        }
    }
}

/** The reference Home surface; fixed padding keeps both bento columns aligned. */
@Composable
fun ChartTile(
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val fill = if (hero) Brush.linearGradient(listOf(Color(0xFF0F2A22), Color(0xFF0E1512)))
        else Brush.linearGradient(listOf(FusionChart.Surface, FusionChart.Surface))
    Column(modifier.clip(shape).background(fill).border(1.dp, FusionChart.Border, shape)
        .padding(16.dp), content = content)
}

/** Currency follows the value on the same baseline, using the reference sans face. */
@Composable
fun ChartAmount(value: String, unit: String = "USDC", large: Boolean = true) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value, style = FusionChart.Value.copy(fontSize = if (large) 32.sp else 26.sp), maxLines = 1, modifier = Modifier.alignByBaseline())
        Text(unit, style = FusionType.BodyMedium.copy(fontSize = 16.sp), color = FusionChart.Muted,
            modifier = Modifier.alignByBaseline())
    }
}
