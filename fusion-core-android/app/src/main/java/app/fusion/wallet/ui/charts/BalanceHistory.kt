package app.fusion.wallet.ui.charts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.chain.Abi
import app.fusion.wallet.chain.ChainReader
import app.fusion.wallet.chain.JsonRpcClient
import app.fusion.wallet.chain.Views
import app.fusion.wallet.data.Deployment
import app.fusion.wallet.data.WalletState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger

data class BalanceHistoryState(
    val points: List<BalancePoint> = emptyList(),
    val loading: Boolean = false,
    val note: String? = null,
)

/** Background archive reads at actual module-event blocks, never interpolated money. */
class BalanceHistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(BalanceHistoryState())
    val state = mutable.asStateFlow()
    private var requested: List<Long> = emptyList()

    fun load(wallet: WalletState, retry: Boolean = false) {
        val cutoff = System.currentTimeMillis() / 1000 - 30 * 86400L
        val blocks = (wallet.evidence.map { it.blockNumber to it.blockTime } +
            wallet.recentActions.map { it.blockNumber to it.blockTime })
            .filter { it.second >= cutoff && it.first > 0 }.map { it.first }.distinct().sorted()
        if (mutable.value.loading || (!retry && requested == blocks)) return
        requested = blocks
        viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, note = null)
            try {
                val points = withContext(Dispatchers.IO) {
                    val deployment = Deployment.parse(getApplication<Application>().assets
                        .open("deployments/84532.json").bufferedReader().use { it.readText() })
                    val reader = ChainReader(JsonRpcClient(deployment.rpcUrl))
                    require(reader.chainId() == deployment.chainId)
                    // Bound archive work; equally spaced samples preserve the whole available window.
                    val sampled = if (blocks.size <= 30) blocks else (0 until 30).map {
                        blocks[it * (blocks.size - 1) / 29]
                    }
                    sampled.map { block ->
                        val header = reader.block(ChainReader.tag(block))
                        val owner = Abi.addressWord(deployment.safe)
                        val vaults = listOf(deployment.vaultA, deployment.vaultB)
                        val balances = reader.batchCalls((listOf(deployment.asset) + vaults)
                            .map { it to Views.BALANCE + owner }, block).map { it.requireSize(1).uint(0) }
                        val assets = reader.batchCalls(vaults.mapIndexed { index, vault ->
                            vault to Views.CONVERT_TO_ASSETS + Abi.uintWord(balances[index + 1])
                        }, block).map { it.requireSize(1).uint(0) }
                        require(reader.block(ChainReader.tag(block)).hash == header.hash) { "History block reorganized" }
                        BalancePoint(header.timestamp, assets.fold(balances.first(), BigInteger::add))
                    }
                }
                mutable.value = BalanceHistoryState(points)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                mutable.value = BalanceHistoryState(note = "Historical balances unavailable from the archive RPC. Refresh to retry.")
            }
        }
    }
}
