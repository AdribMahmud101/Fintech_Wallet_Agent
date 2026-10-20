package app.fusion.wallet.ui.agent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fusion.wallet.R
import app.fusion.wallet.data.*
import app.fusion.wallet.ui.common.startActivitySafe
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun screenNow(): Long {
    val now by produceState(System.currentTimeMillis() / 1_000) {
        while (true) { delay(1_000); value = System.currentTimeMillis() / 1_000 }
    }
    return now
}

/** A labelled static ring respects Remove animations; progress is derived in integer basis points. */
@Composable
internal fun EvidenceRing(basisPoints: Int?, label: String, modifier: Modifier = Modifier) {
    Box(modifier.size(100.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val stroke = 8.dp.toPx()
            val diameter = size.minDimension - stroke
            val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)
            drawArc(FusionColors.Border, -90f, 360f, false, origin, arcSize, style = Stroke(stroke))
            if (basisPoints != null) drawArc(FusionColors.Indigo, -90f, basisPoints.coerceIn(0, 10_000) * 360f / 10_000,
                false, origin, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(basisPoints?.let { "${it / 100}%" } ?: "—", style = FusionType.NumberSmall)
    }
}

/** Vertical nodes keep all labels readable on narrow phones and with large type. */
@Composable
internal fun ConnectedSteps(steps: List<Pair<String, String>>) {
    steps.forEachIndexed { index, (title, detail) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${index + 1}", style = FusionType.NumberSmall, color = FusionColors.Indigo)
            Column(Modifier.weight(1f)) {
                Text(title, style = FusionType.HeadingSmall)
                Text(detail, style = FusionType.BodySmall)
            }
        }
        if (index < steps.lastIndex) Canvas(Modifier.padding(start = 5.dp).width(16.dp).height(24.dp)) {
            val x = size.width / 2
            drawLine(FusionColors.Border, Offset(x, 0f), Offset(x, size.height - 3.dp.toPx()), 2.dp.toPx())
            drawLine(FusionColors.Indigo, Offset(x, size.height), Offset(x - 4.dp.toPx(), size.height - 5.dp.toPx()), 2.dp.toPx())
            drawLine(FusionColors.Indigo, Offset(x, size.height), Offset(x + 4.dp.toPx(), size.height - 5.dp.toPx()), 2.dp.toPx())
        }
    }
}

@Composable
internal fun SnapshotStatus(state: WalletState, onRefresh: () -> Unit) {
    StatusChip(when {
        state.source == DataSource.DEMO -> "DEMO · fixture data"
        state.isDisplayStale() -> "Cached / stale · refresh needed"
        else -> "Live · block ${state.blockNumber}"
    }, if (state.isDisplayStale() || state.source == DataSource.DEMO) FusionColors.Amber else FusionColors.Green)
    if (state.status == LoadStatus.ERROR) Text(state.error ?: stringResource(R.string.k24_network_error), style = FusionType.BodySmall, color = FusionColors.Red)
    TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp), enabled = !state.refreshing) {
        Text(stringResource(if (state.refreshing) R.string.k24_refreshing else R.string.refresh_button))
    }
}

@Composable
internal fun ExternalAction(label: String, url: String, onError: (String) -> Unit = {}) {
    val context = LocalContext.current
    val missingHandler = stringResource(R.string.k24_link_unavailable)
    OutlinedButton(onClick = {
        if (!context.startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) onError(missingHandler)
    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
internal fun GuardianStopControl(paused: Boolean?, requestPause: suspend () -> Result<String>, onResult: (String, String?) -> Unit, onStop: () -> Unit = {}) {
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val success = stringResource(R.string.k24_pause_success)
    val failure = stringResource(R.string.k24_pause_error)
    Button(onClick = { onStop(); confirm = true }, enabled = !busy && paused != true,
        colors = ButtonDefaults.buttonColors(containerColor = FusionColors.Red), modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(when { busy -> R.string.k24_stopping; paused == true -> R.string.k24_paused; else -> R.string.ag_stop_button }))
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!busy) confirm = false },
        title = { Text(stringResource(R.string.k24_stop_title)) },
        text = { Text(stringResource(R.string.k24_stop_explanation)) },
        confirmButton = { TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
            busy = true
            scope.launch {
                try {
                    requestPause().fold(onSuccess = { onResult(success, it) }, onFailure = { onResult(it.message ?: failure, null) })
                } finally { busy = false; confirm = false }
            }
        }) { Text(stringResource(R.string.ag_stop_button)) } },
        dismissButton = { TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = { confirm = false }) { Text(stringResource(R.string.k24_cancel)) } })
}

@Composable
internal fun rememberSafeUrl(safeAddress: String?): String? {
    val context = LocalContext.current
    return remember(safeAddress) {
        val address = safeAddress ?: runCatching {
            context.assets.open("deployments/84532.json").bufferedReader().use { Deployment.parse(it.readText()).safe }
        }.getOrNull()
        address?.let { "https://app.safe.global/home?safe=basesep:$it" }
    }
}
