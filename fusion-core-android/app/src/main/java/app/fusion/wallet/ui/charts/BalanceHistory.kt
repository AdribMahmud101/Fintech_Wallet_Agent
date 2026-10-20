package app.fusion.wallet.ui.charts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.fusion.wallet.data.WalletState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BalanceHistoryState(
    val points: List<BalancePoint> = emptyList(),
    val loading: Boolean = false,
    val note: String? = null,
)

/** History is published by the shared event index together with the pinned wallet snapshot. */
class BalanceHistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(BalanceHistoryState())
    val state = mutable.asStateFlow()
    fun load(wallet: WalletState, retry: Boolean = false) {
        mutable.value = BalanceHistoryState(wallet.balanceHistory.map { BalancePoint(it.timestampSeconds, it.balanceRaw) },
            wallet.refreshing, wallet.historyError)
    }
}
