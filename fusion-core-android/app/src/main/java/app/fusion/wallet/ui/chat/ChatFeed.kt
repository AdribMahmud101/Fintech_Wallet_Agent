package app.fusion.wallet.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.components.FusionIcon
import app.fusion.wallet.ui.components.FusionIconRes
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.model.ChatMessage
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian
import app.fusion.wallet.ui.shorten

/**
 * `layout.md` View 1 — the intent execution stream.
 *
 * Five renderings of one list, so the feed and the engine's state machine stay in step:
 * Idle / Parsing / Guardrail / Executed / Blocked.
 */
@Composable
fun ChatFeed(
    messages: List<ChatMessage>,
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (messages.isEmpty()) {
        EmptyChat(suggestions, onSuggestion, modifier)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = FusionDims.gutter,
            end = FusionDims.gutter,
            top = 12.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            when (message) {
                is ChatMessage.Prompt -> UserBubble(message)
                is ChatMessage.Parsing -> ParsingIndicator("Parsing Intent…")
                is ChatMessage.Guardrail -> PreFlightGuardrailCard(
                    report = message.report,
                    onConfirm = onConfirm,
                    onCancel = onCancel,
                )
                is ChatMessage.Executed -> ExecutedRow(message)
                is ChatMessage.Blocked -> BlockedRow(message)
            }
        }
    }
}

/** `layout.md` View 1, Idle state. */
@Composable
private fun EmptyChat(
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FusionDims.gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FusionIcon(
            resId = FusionIconRes.EmptyChat,
            contentDescription = null,
            size = 160.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.empty_chat_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Accessible.TextMuted,
        )
        Spacer(Modifier.height(24.dp))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { text ->
                ObsidianCard(level = 2, onClick = { onSuggestion(text) }) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage.Prompt) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .padding(start = 48.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(FusionDims.radiusMedium),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ExecutedRow(message: ChatMessage.Executed) {
    ObsidianCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .background(Obsidian.StatusSuccess, RoundedCornerShape(FusionDims.radiusSmall)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "BROADCAST",
                    style = MaterialTheme.typography.labelSmall,
                    color = Obsidian.StatusSuccess,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = shorten(message.hash),
                    style = MonoStyle,
                    color = Accessible.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun BlockedRow(message: ChatMessage.Blocked) {
    ObsidianCard(Modifier.fillMaxWidth(), level = 1) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            FusionIcon(
                resId = FusionIconRes.ShieldBlock,
                contentDescription = null,
                size = 20.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "[${message.code.uppercase()}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = Obsidian.StatusError,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}