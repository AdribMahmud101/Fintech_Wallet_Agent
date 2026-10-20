package app.fusion.wallet.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.fusion.wallet.ui.settings.SettingsViewModel
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType
import kotlinx.coroutines.delay

/**
 * Shown under the top bar on every screen while the on-device AI model is missing (for example a judge who skipped
 * setup). Preview, Run now and Autopilot need it; everything else works without it. Download starts it in place.
 */
@Composable
fun ModelMissingBar(vm: SettingsViewModel = viewModel()) {
    val model by vm.model.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        vm.refreshModel()
        while (true) { delay(5_000); if (!vm.model.value.downloading) vm.refreshModel() }
    }
    val status = model.status ?: return
    if (status.ready) return
    var info by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF3A2A0A)).padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = { info = true }) {
            Icon(Icons.Outlined.Info, contentDescription = "About the AI model", tint = FusionColors.Amber, modifier = Modifier.size(22.dp))
        }
        Text(
            if (model.downloading) "Downloading the on-device AI model…" else "AI model not downloaded: AI previews won’t run",
            style = FusionType.BodySmall, color = Color(0xFFF5D28A), modifier = Modifier.weight(1f), maxLines = 2,
        )
        if (!model.downloading) TextButton(onClick = vm::redownload) { Text("Download", color = FusionColors.Amber) }
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false },
        confirmButton = { TextButton(onClick = { info = false; if (!model.downloading) vm.redownload() }) { Text(if (model.downloading) "OK" else "Download now") } },
        dismissButton = { TextButton(onClick = { info = false }) { Text("Later") } },
        title = { Text("On-device AI model") },
        text = { Text("The AI agent runs fully on this phone. Download its model once (about 128 MB, Wi-Fi recommended). Until then you can browse balances, proof and security, but Preview, Run now and Autopilot can’t decide.") },
    )
}
