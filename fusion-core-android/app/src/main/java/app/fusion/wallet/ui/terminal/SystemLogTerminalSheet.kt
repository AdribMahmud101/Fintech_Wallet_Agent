package app.fusion.wallet.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.components.FusionIcon
import app.fusion.wallet.ui.components.FusionIconRes
import app.fusion.wallet.ui.model.LogLine
import app.fusion.wallet.ui.model.LogTone
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.MonoTerminalStyle
import app.fusion.wallet.ui.theme.Obsidian


/**
 * `layout.md` View 3 — the expandable Rust FFI / MNN system terminal.
 *
 * Uses the full 11sp mono style rather than Material's default sheet typography: this is an
 * engineer-facing surface where column alignment matters more than comfortable reading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemLogTerminalSheet(
    sheetState: SheetState,
    visible: Boolean,
    logs: List<LogLine>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val surfaces = FusionTheme.surfaces
    val listState = rememberLazyListState()

    // Keep the sheet composed even while hidden: removing it is what previously killed the
    // container fill and scrim. Material draws nothing while the state is Hidden.
    if (sheetState.currentValue == SheetValue.Hidden && !visible) return

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    ModalBottomSheet(
        // The host drives visibility through `visible` and its own LaunchedEffect, so a
        // dismiss request only flips state; the sheet then animates itself out. Tearing the
        // sheet out of composition on dismiss instead cut the exit animation short.
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = surfaces.level1,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Material's default scrim measured as a no-op over this palette: the backdrop is
        // already #08090C, so a 32% black wash is invisible and the sheet read as floating
        // text with nothing behind it. A heavier, slightly warm scrim gives real
        // separation while keeping the Obsidian feel.
        scrimColor = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(
            topStart = FusionDims.radiusLarge,
            topEnd = FusionDims.radiusLarge,
        ),
        dragHandle = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.terminal_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accessible.TextMuted,
                )
                Spacer(Modifier.height(6.dp))
                FusionIcon(FusionIconRes.Terminal, contentDescription = null, size = 18.dp)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FusionDims.gutter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${logs.size} lines",
                style = MaterialTheme.typography.labelMedium,
                color = Accessible.TextMuted,
            )
            TextButton(onClick = onClear) {
                Text(
                    stringResource(R.string.terminal_clear),
                    style = MaterialTheme.typography.labelMedium,
                    color = Obsidian.AccentAiViolet,
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 520.dp)
                .padding(horizontal = FusionDims.gutter),
        ) {
            items(logs, key = { it.id }) { line ->
                LogLineRow(line)
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LogLineRow(line: LogLine) {
    val tone = when (line.tone) {
        LogTone.Default -> MaterialTheme.colorScheme.onSurface
        LogTone.Muted -> Accessible.TextMuted
        LogTone.Success -> Obsidian.StatusSuccess
        LogTone.Warn -> Obsidian.StatusWarning
        LogTone.Error -> Obsidian.StatusError
        LogTone.Engine -> Obsidian.AccentAiViolet
    }
    val bodyTone = tone.copy(alpha = if (line.tone == LogTone.Muted) 0.7f else 1f)

    // One flowing annotated line rather than three fixed columns. Measured on a 393dp
    // device: a fixed 150dp tag column left the body ~90dp, which wrapped every hash and
    // message character-by-character into a ragged block.
    val text = remember(line.id, line.body, tone) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(color = Accessible.TextMuted),
            ) { append(line.timestamp) }
            append("  ")
            withStyle(SpanStyle(color = tone)) { append(line.tag) }
            append("  ")
            withStyle(SpanStyle(color = bodyTone)) { append(line.body) }
        }
    }

    Text(
        text = text,
        style = MonoTerminalStyle,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** Compact inline terminal used by the chat feed when the sheet is closed. */
@Composable
fun TerminalTicker(logs: List<LogLine>, modifier: Modifier = Modifier) {
    val last = logs.lastOrNull() ?: return
    Row(
        modifier = modifier
            .background(
                app.fusion.wallet.ui.theme.FusionTheme.surfaces.level2,
                RoundedCornerShape(FusionDims.radiusSmall),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FusionIcon(FusionIconRes.Terminal, null, size = 12.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = last.body,
            style = MaterialTheme.typography.labelMedium,
            color = Accessible.TextMuted,
            maxLines = 1,
        )
    }
}