package app.fusion.wallet.data

import java.math.BigInteger
import kotlinx.coroutines.flow.StateFlow

/** Exact USDC units. Formatting always retains all six asset decimals. */
data class Money(val raw: BigInteger) {
    init { require(raw.signum() >= 0) { "Negative asset amount" } }
    val rawUnits: String get() = raw.toString()
    val display: String get() = raw.divide(SCALE).toString() + "." + raw.mod(SCALE).toString().padStart(6, '0')
    companion object {
        val SCALE: BigInteger = BigInteger.TEN.pow(6)
        val ZERO = Money(BigInteger.ZERO)
        fun fromRaw(raw: String) = Money(BigInteger(raw))
    }
}

enum class DataSource { LIVE, DEMO }
enum class LoadStatus { LOADING, READY, ERROR }
enum class PermissionTier { READ_ONLY, OWNER_APPROVED, AUTONOMOUS }
enum class ActionKind { DEPOSIT, WITHDRAW, REBALANCE, PAY_OBLIGATION }
enum class QueueStatus { WAITING, READY, EXPIRED }
enum class PayeeStatus { ACTIVE, COOLING, CANCELLED }

/** Null snapshot fields mean unavailable; zero is reserved for a successful on-chain read. */
data class WalletState(
    val source: DataSource = DataSource.LIVE,
    val status: LoadStatus = LoadStatus.LOADING,
    val chainId: Long = 84532,
    val blockNumber: Long? = null,
    val blockHash: String? = null,
    val blockTime: Long? = null,
    val treasury: Treasury? = null,
    val buckets: Buckets? = null,
    val vaults: List<VaultPosition> = emptyList(),
    val module: ModuleState? = null,
    val queue: List<QueuedAction> = emptyList(),
    val payees: List<Payee> = emptyList(),
    val recentActions: List<AgentAction> = emptyList(),
    val evidence: List<EvidenceRow> = emptyList(),
    val error: String? = null,
    val refreshing: Boolean = false,
    val agentSource: String = "SCRIPT",
    val planner: String = "deterministic",
    val obligations: List<Obligation> = emptyList(),
    val accountingMatches: Boolean? = null,
    val accountingError: String? = null,
    val capturedAtEpochMillis: Long? = null,
    val observedHeadBlock: Long? = null,
) {
    val dataLabel: String get() = if (source == DataSource.DEMO) "DEMO" else "Base Sepolia · LIVE"

    /** Monitor freshness only; consumers must never use this read-only repository to sign. */
    fun isStale(nowMillis: Long = System.currentTimeMillis(), headBlock: Long? = observedHeadBlock): Boolean {
        val captured = capturedAtEpochMillis ?: return true
        val block = blockNumber ?: return true
        return status != LoadStatus.READY || nowMillis < captured || nowMillis - captured >= 30_000 ||
            (headBlock != null && (headBlock < block || headBlock - block >= 5))
    }
}

data class Treasury(val safeAddress: String, val assetAddress: String, val balance: Money, val symbol: String = "USDC")

/** §7.9: reserve=min(cash,owed), free=max(cash-owed,0), buffer=min(free,target). */
data class Buckets(
    val reserve: Money,
    val owed: Money,
    val buffer: Money,
    val surplus: Money,
    val free: Money,
    val shortfall: Money,
    val deployed: Money,
    val total: Money,
    val bufferTarget: Money,
)

data class VaultPosition(
    val id: String,
    val name: String,
    val address: String,
    val sharesRaw: BigInteger,
    val assets: Money,
    val maxWithdraw: Money,
    val exposureLimit: Money,
    val simulatedYield: Boolean = false,
    val previewDepositSharesRaw: BigInteger? = null,
    val previewWithdrawSharesRaw: BigInteger? = null,
)

data class Policy(
    val permissionTier: PermissionTier,
    val operatingBuffer: Money,
    val microMax: Money,
    val delayedMax: Money,
    val hardMax: Money,
    val dailyLimit: Money,
    val delaySeconds: Long,
    val maxShareSlippageBps: Int,
    val guardian: String,
    val payeeDelaySeconds: Long,
    val payEarlyWindowSeconds: Long,
)

data class ModuleState(
    val address: String,
    val configured: Boolean,
    val paused: Boolean,
    val sessionActive: Boolean,
    val policy: Policy,
    val policyEpoch: BigInteger,
    val policyHash: String,
    val mandateHash: String,
    val obligationsRoot: String,
    val reserved: Money,
    val obligationCount: BigInteger,
    val actionNonce: BigInteger,
    val dailyUsed: Money,
    val dailyRemaining: Money,
    val sessionId: String,
    val sessionKey: String,
    val sessionExpiresAt: Long,
    val sessionLimit: Money,
    val sessionUsed: Money,
)

data class QueuedAction(
    val id: String,
    val kind: ActionKind,
    val amount: Money,
    val readyAt: Long,
    val expiresAt: Long,
    val status: QueueStatus,
    val payee: String,
    val decisionHash: String,
    val expectedNonce: BigInteger,
)

data class Payee(
    val id: String,
    val address: String,
    val label: String,
    val labelHash: String,
    val status: PayeeStatus,
    val activeAt: Long,
    val sanctioned: Boolean,
)

data class AgentAction(
    val id: String,
    val kind: ActionKind,
    val amount: Money,
    val payee: String?,
    val txHash: String,
    val decisionHash: String,
    val blockNumber: Long,
    val blockTime: Long,
    val source: String = "SCRIPT",
)

/** Evidence is backed by observed logs; this list never substitutes for live balances. */
data class EvidenceRow(
    val id: String,
    val kind: String,
    val txHash: String,
    val blockNumber: Long,
    val blockTime: Long,
    val decisionHash: String?,
    val source: String = "SCRIPT",
    val explorerUrl: String,
)

interface WalletRepository {
    val state: StateFlow<WalletState>
    suspend fun refresh()
}

data class Obligation(
    val id: String,
    val payeeId: String,
    val amount: Money,
    val dueAt: Long,
    val paid: Boolean,
)
