package app.fusion.wallet.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.accountant.data.Severity
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.theme.Accessible
import app.fusion.wallet.ui.theme.Obsidian

val Muted: Color get() = Accessible.TextMuted

fun severityColor(s: Severity): Color = when (s) {
    Severity.INFO -> Obsidian.AccentPrimary
    Severity.NOTICE -> Obsidian.StatusSuccess
    Severity.WARNING -> Obsidian.StatusWarning
    Severity.CRITICAL -> Obsidian.StatusError
}

@Composable
fun ScreenHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        trailing?.invoke()
    }
}

@Composable
fun CardTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Muted)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = Muted)
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
