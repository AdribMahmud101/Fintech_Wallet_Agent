package app.fusion.wallet.ui.activity

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.fusion.wallet.data.TxIndex
import app.fusion.wallet.data.TxIndexCache
import app.fusion.wallet.ui.components.Provenance
import app.fusion.wallet.ui.components.ProvenanceChip
import app.fusion.wallet.ui.components.StatusChip
import app.fusion.wallet.ui.theme.FusionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun rememberTxIndex(): TxIndex {
    val context = LocalContext.current
    val cache = remember(context) { TxIndexCache(File(context.filesDir, "evidence/tx_index.json")) }
    val index by produceState(TxIndex.Empty, cache) {
        value = withContext(Dispatchers.IO) { cache.cached() }
        value = withContext(Dispatchers.IO) { cache.refresh() }
    }
    return index
}

@Composable
fun RecordedProvenanceChip(provenance: Provenance?) {
    if (provenance == null) StatusChip("Source not recorded", FusionColors.TextMuted)
    else ProvenanceChip(provenance)
}
