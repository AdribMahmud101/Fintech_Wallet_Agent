package app.fusion.wallet.ui.safety

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.R
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SafetyScreenState(
    val arena: ArenaEvidence? = null, val heldOut: EvidenceRatio? = null,
    val refreshing: Boolean = false, val loaded: Boolean = false, val source: String = "Bundled evidence snapshot",
    val error: String? = null,
)

class SafetyScreenModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(SafetyScreenState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            mutable.value = withContext(Dispatchers.IO) {
                runCatching {
                    SafetyScreenState(ArenaEvidenceParser.parse(app.getString(R.string.k24_arena_snapshot)),
                        ArenaEvidenceParser.heldOutAsr(app.getString(R.string.k24_heldout_snapshot)), loaded = true)
                }.getOrElse { SafetyScreenState(loaded = true, error = "Bundled evidence unavailable: ${it.message}") }
            }
            refresh()
        }
    }

    fun refresh() {
        if (mutable.value.refreshing) return
        mutable.value = mutable.value.copy(refreshing = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    coroutineScope {
                        val arena = async { ArenaEvidenceParser.parse(download(ARENA_PATH)) }
                        val heldOut = async { ArenaEvidenceParser.heldOutAsr(download(HELDOUT_PATH)) }
                        arena.await() to heldOut.await()
                    }
                }
            }
            mutable.value = result.fold(onSuccess = { (arena, heldOut) ->
                SafetyScreenState(arena, heldOut, loaded = true, source = "GitHub evidence · refreshed")
            }, onFailure = { mutable.value.copy(refreshing = false, loaded = true,
                error = "Refresh unavailable; showing the last evidence snapshot. ${it.message?.take(160).orEmpty()}") })
        }
    }

    private fun download(path: String): String {
        val connection = URL(EVIDENCE_RAW + path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            check(connection.responseCode in 200..299) { "Evidence HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { it.readBytesWithLimit(512 * 1024) }
            bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }
}

private fun java.io.InputStream.readBytesWithLimit(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        check(out.size() + size <= limit) { "Evidence exceeds size limit" }
        out.write(buffer, 0, size)
    }
    return out.toByteArray()
}
