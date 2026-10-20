package app.fusion.wallet.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fusion.wallet.data.WalletState
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.theme.FusionColors
import app.fusion.wallet.ui.theme.FusionType

/** Legal-safe custody statement; never offers Send or Swap. */
private fun custodyBody(ratio: String?): String =
    "Owner keys are not in this app. " +
        (ratio?.let { "This Safe is $it. " } ?: "The Safe threshold is unavailable. ") +
        "This app can Receive, preview moves, and run the limited agent. " +
        "Sending and admin changes need the Safe owners."

fun custodyRatio(threshold: Int?, owners: Int?): String? =
    if (threshold != null && owners != null && threshold > 0 && owners > 0) "$threshold-of-$owners" else null

/** Compact Home pill beside the hero. */
@Composable
fun CustodySplitPill(state: WalletState, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val ratio = custodyRatio(state.safeThreshold, state.safeOwnerCount)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FusionColors.SurfaceRaised)
            .border(1.dp, FusionColors.Border, RoundedCornerShape(12.dp))
            .clickable { open = true }
            .semantics { role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusChip(ratio?.let { "$it Safe" } ?: "Safe custody", FusionColors.Indigo)
        Text(
            "Owner keys stay outside this app",
            style = FusionType.BodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("View", style = FusionType.BodySmall, color = FusionColors.Indigo)
    }
    if (open) CustodySplitSheet(state, onDismiss = { open = false })
}

/** Full Safety-screen custody split card. */
@Composable
fun CustodySplitCard(state: WalletState, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val ratio = custodyRatio(state.safeThreshold, state.safeOwnerCount)
    BentoTile(modifier.fillMaxWidth(), onClick = { open = true }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Custody split", style = FusionType.HeadingMedium, modifier = Modifier.weight(1f))
            StatusChip(ratio?.let { "$it Safe" } ?: "Threshold unavailable", FusionColors.Indigo)
        }
        Text(custodyBody(ratio), style = FusionType.BodyMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Tap for the Safe address and threshold read from chain.",
            style = FusionType.BodySmall,
            color = FusionColors.TextMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (open) CustodySplitSheet(state, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustodySplitSheet(state: WalletState, onDismiss: () -> Unit) {
    val address = state.treasury?.safeAddress
    val ratio = custodyRatio(state.safeThreshold, state.safeOwnerCount)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = FusionColors.SurfaceRaised,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Custody split", style = FusionType.HeadingSmall)
            Text(custodyBody(ratio), style = FusionType.BodyMedium)
            Text("Safe address", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            if (address != null) {
                SelectionContainer { Text(address, style = FusionType.NumberCaption) }
            } else {
                Text("Safe address unavailable until the chain snapshot loads", style = FusionType.BodySmall)
            }
            Text("Threshold", style = FusionType.BodySmall, color = FusionColors.TextMuted)
            Text(
                ratio?.let { "$it (read from the Safe at the snapshot block)" }
                    ?: "Threshold unavailable until the chain snapshot loads",
                style = FusionType.BodyMedium,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
        }
    }
}
