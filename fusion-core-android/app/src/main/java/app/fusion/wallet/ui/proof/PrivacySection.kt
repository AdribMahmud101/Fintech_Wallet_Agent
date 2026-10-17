package app.fusion.wallet.ui.proof

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.accountant.data.EgressLogEntity
import app.fusion.wallet.accountant.data.EgressStatus
import app.fusion.wallet.ui.common.CardTitle
import app.fusion.wallet.ui.common.Muted
import app.fusion.wallet.ui.components.ObsidianCard
import app.fusion.wallet.ui.components.StatusPill
import app.fusion.wallet.ui.theme.MonoStyle
import app.fusion.wallet.ui.theme.Obsidian
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivacyCard(
    enabled: Boolean, hasKey: Boolean, model: String, dailyLimit: Int,
    onToggle: (Boolean) -> Unit, onSaveKey: (String) -> Unit, onClearKey: () -> Unit, onModel: (String) -> Unit, onLimit: (Int) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var modelText by rememberSaveable(model) { mutableStateOf(model) }
    var limitText by rememberSaveable(dailyLimit) { mutableStateOf(dailyLimit.toString()) }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.priv_title))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.priv_toggle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.priv_toggle_note), style = MaterialTheme.typography.labelSmall, color = Muted)
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(if (hasKey) R.string.priv_key_stored else R.string.priv_key)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSaveKey(key); key = "" }, enabled = key.isNotBlank()) { Text(stringResource(R.string.priv_save_key)) }
                TextButton(onClick = onClearKey, enabled = hasKey) { Text(stringResource(R.string.priv_remove_key)) }
            }
            OutlinedTextField(
                value = modelText, onValueChange = { modelText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.priv_model)) },
                supportingText = { Text(stringResource(R.string.priv_model_note)) },
            )
            TextButton(onClick = { onModel(modelText) }, enabled = modelText.trim() != model) { Text(stringResource(R.string.priv_apply_model)) }
            OutlinedTextField(
                value = limitText, onValueChange = { limitText = it.filter(Char::isDigit).take(3) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.priv_limit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(
                onClick = { limitText.toIntOrNull()?.let(onLimit) },
                enabled = limitText.toIntOrNull() != null && limitText.toIntOrNull() != dailyLimit,
            ) { Text(stringResource(R.string.priv_apply_limit)) }
        }
    }
}

@Composable
fun PreviewCard(json: String, allowed: Boolean, rules: List<String>) {
    var open by rememberSaveable { mutableStateOf(false) }
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CardTitle(stringResource(R.string.pv_title))
                StatusPill(
                    label = stringResource(if (allowed) R.string.pv_ok else R.string.pv_block),
                    tone = if (allowed) Obsidian.StatusSuccess else Obsidian.StatusError,
                )
            }
            Text(stringResource(R.string.pv_desc, json.toByteArray(Charsets.UTF_8).size.toString()), style = MaterialTheme.typography.labelSmall, color = Muted)
            if (!allowed) Text(stringResource(R.string.pv_rules, rules.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = Obsidian.StatusError)
            TextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.pv_hide else R.string.pv_show)) }
            if (open) Text(json, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun EgressCard(rows: List<EgressLogEntity>) {
    ObsidianCard {
        Column(Modifier.padding(16.dp)) {
            CardTitle(stringResource(R.string.eg_title), stringResource(R.string.eg_recent, rows.size.toString()))
            if (rows.isEmpty()) Text(stringResource(R.string.eg_none), style = MaterialTheme.typography.bodySmall, color = Obsidian.StatusSuccess)
            rows.take(10).forEach { EgressRow(it) }
        }
    }
}

@Composable
private fun EgressRow(r: EgressLogEntity) {
    var open by remember { mutableStateOf(false) }
    val tone = when (r.status) {
        EgressStatus.SENT_OK -> Obsidian.StatusSuccess
        EgressStatus.BLOCKED_BY_GUARD, EgressStatus.REJECTED_REPLY -> Obsidian.StatusWarning
        EgressStatus.FAILED -> Obsidian.StatusError
        else -> Muted
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(r.createdAt * 1000)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${r.model.substringAfterLast('/')} · ${r.payloadBytes} B" + (r.promptTokens?.let { " · $it→${r.completionTokens ?: 0} tok" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                )
            }
            StatusPill(label = r.status.name, tone = tone)
        }
        if (r.detail.isNotEmpty()) Text(r.detail, style = MaterialTheme.typography.labelSmall, color = Muted)
        if (r.payloadJson.isNotEmpty()) {
            TextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.eg_hide else R.string.eg_show)) }
            if (open) Text(r.payloadJson, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun ConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cs_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cs_sent_h), style = MaterialTheme.typography.labelSmall, color = Obsidian.StatusSuccess)
                Text(stringResource(R.string.cs_sent_b), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cs_never_h), style = MaterialTheme.typography.labelSmall, color = Obsidian.StatusError)
                Text(stringResource(R.string.cs_never_b), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cs_foot), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.cs_accept)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cs_decline)) } },
    )
}
