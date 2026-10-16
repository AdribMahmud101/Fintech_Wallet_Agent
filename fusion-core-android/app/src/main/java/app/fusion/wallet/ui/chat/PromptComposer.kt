package app.fusion.wallet.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.FusionDims
import app.fusion.wallet.ui.theme.FusionTheme
import app.fusion.wallet.ui.theme.Obsidian

/**
 * `layout.md` §2 Scaffold BottomBar — the prompt field and the voice affordance.
 *
 * The mic is present but inert in the demo: voice input has no implementation behind it, so
 * it is disabled rather than wired to a no-op that would swallow taps.
 */
@Composable
fun PromptComposer(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaces = FusionTheme.surfaces
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaces.canvas)
            .padding(horizontal = FusionDims.gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .background(surfaces.level1, RoundedCornerShape(FusionDims.radiusSmall))
                .border(FusionDims.hairline, surfaces.border, RoundedCornerShape(FusionDims.radiusSmall))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Accessible.ButtonFill),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (text.isNotBlank()) {
                            onSubmit(text.trim())
                            text = ""
                            keyboard?.hide()
                        }
                    },
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.prompt_input_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Accessible.TextMuted,
                        )
                    }
                    inner()
                },
            )
        }

        IconButton(
            onClick = { keyboard?.hide() },
            enabled = text.isNotBlank(),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Accessible.ButtonFill,
                contentColor = Obsidian.TextPrimary,
                disabledContainerColor = surfaces.level2,
                disabledContentColor = Accessible.TextMuted,
            ),
        ) {
            Icon(
                Icons.Filled.Send,
                contentDescription = stringResource(R.string.prompt_send),
                modifier = Modifier.size(18.dp),
            )
        }

        IconButton(
            onClick = { /* voice input is not implemented; see KOTLIN_INTEGRATION.md */ },
            enabled = false,
            colors = IconButtonDefaults.iconButtonColors(
                disabledContainerColor = surfaces.level2,
                disabledContentColor = Accessible.TextMuted,
            ),
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = stringResource(R.string.prompt_voice),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Compact engine strip shown under the composer so the log tail is visible without a sheet. */
@Composable
fun EngineStrip(mnnModel: String, latencyMs: Int, rustOk: Boolean, modifier: Modifier = Modifier) {
    val surfaces = FusionTheme.surfaces
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(surfaces.canvas)
            .padding(start = FusionDims.gutter, end = FusionDims.gutter, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$mnnModel · ${latencyMs}ms",
            style = MaterialTheme.typography.labelMedium,
            color = Obsidian.AccentAiViolet,
        )
        Text(
            text = if (rustOk) "GUARDRAILS ARMED" else "GUARDRAILS OFFLINE",
            style = MaterialTheme.typography.labelMedium,
            color = if (rustOk) Obsidian.StatusSuccess else Obsidian.StatusError,
        )
    }
    Spacer(Modifier.height(4.dp))
}
