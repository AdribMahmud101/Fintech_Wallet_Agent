package app.fusion.wallet.ui.treasury

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fusion.wallet.data.ModuleState
import app.fusion.wallet.data.aiAllowance
import app.fusion.wallet.data.allowanceCopy
import app.fusion.wallet.ui.components.formatBigIntegerAmount
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType

/** Accurate labels for the instant-auto ceiling and the UTC-day cap, with an explanatory sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowanceLabels(module: ModuleState, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val copy = remember(module.policy, module.dailyUsed) {
        allowanceCopy(module.policy, module.dailyUsed) { formatBigIntegerAmount(it.raw) }
    }
    val allowance = remember(module.dailyUsed, module.policy) {
        aiAllowance(module.dailyUsed.raw, module.policy.dailyLimit.raw, module.policy.microMax.raw)
    }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Today's AI allowance: ${copy.remaining}", style = FusionType.BodySmall)
                Text(copy.dailyCap, style = FusionType.BodySmall)
                Text(copy.perMove, style = FusionType.BodySmall)
            }
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.semantics { contentDescription = "About AI allowance labels" },
            ) {
                Text("ⓘ", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            }
        }
        LinearProgressIndicator(
            progress = { allowance.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = FusionColors.Green,
        )
    }
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = FusionColors.SurfaceRaised,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AI allowance", style = FusionType.HeadingSmall)
            Text(copy.explanation, style = FusionType.BodyMedium)
            TextButton(onClick = { expanded = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Close")
            }
        }
    }
}
