package app.fusion.wallet.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.accountant.data.Severity
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.Obsidian

val Muted: Color get() = Accessible.TextMuted

/** Meaning-bearing colours, one per idea, used the same way on every screen. Never the only signal. */
object Tone {
    val Cash = Color(0xFF10B981)
    val Bills = Color(0xFFF59E0B)
    val Earning = Color(0xFF38BDF8)
    val Good = Obsidian.StatusSuccess
    val Warn = Obsidian.StatusWarning
    val Bad = Obsidian.StatusError
}

fun severityColor(s: Severity): Color = when (s) {
    Severity.INFO -> Tone.Earning
    Severity.NOTICE -> Tone.Good
    Severity.WARNING -> Tone.Warn
    Severity.CRITICAL -> Tone.Bad
}

@Composable
fun ScreenHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
        if (trailing != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { trailing() }
    }
}

/** Sentence-case section title (not tiny shouting caps), with an optional quiet value on the right. */
@Composable
fun CardTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/**
 * Progressive disclosure: a row that opens/closes secondary content. The whole row is the touch
 * target (>= 48dp) and exposes a Button role for screen readers.
 */
@Composable
fun Expander(label: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Accessible.AccentText, fontWeight = FontWeight.Medium)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Muted)
        }
        AnimatedVisibility(visible = expanded) { Column { content() } }
    }
}

@Composable
fun Notice(text: String, onDismiss: () -> Unit) {
    ObsidianCard(level = 2) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
        }
    }
}
