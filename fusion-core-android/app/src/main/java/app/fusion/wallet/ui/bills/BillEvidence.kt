package app.fusion.wallet.ui.bills

import android.content.Context
import app.fusion.wallet.agent.AgentNetwork
import app.fusion.wallet.chain.*
import app.fusion.wallet.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Additional read-only joins by indexed bill/payee IDs, never guessed from vendor + amount. */
data class BillEvidence(
    val blockHash: String? = null,
    val paymentTxByBill: Map<String, String> = emptyMap(),
    val registrationTxByPayee: Map<String, String> = emptyMap(),
    val queuedBillId: String? = null,
    val error: String? = null,
)

internal class BillEvidenceReader {
    private val lock = Mutex()

    suspend fun load(context: Context, state: WalletState): BillEvidence = lock.withLock {
        val block = requireNotNull(state.blockNumber)
        val deployment = context.assets.open("deployments/84532.json").bufferedReader().use { Deployment.parse(it.readText()) }
        val reader = ChainReader(AgentNetwork.readOnly(context, deployment.rpcUrl))
        check(reader.chainId() == deployment.chainId) { "Unexpected evidence chain" }
        check(reader.block(ChainReader.tag(block)).hash.equals(state.blockHash, true)) { "Snapshot changed; refresh required" }
        val indexed = SharedEventIndexes.forContext(context, deployment).sync(reader,
            BlockHeader(block, requireNotNull(state.blockHash), requireNotNull(state.blockTime)))
        val logs = indexed.logs.filter { it.address.equals(deployment.module, true) }
        val queue = if (state.queue.isEmpty()) null else reader.call(deployment.module, Views.QUEUE, block).requireSize(19)
        check(reader.block(ChainReader.tag(block)).hash.equals(state.blockHash, true)) { "Evidence block reorganized; refresh required" }
        return fromLogs(state.blockHash, logs, queue)
    }

    companion object {
        fun fromLogs(blockHash: String?, logs: List<ChainLog>, queue: Abi?): BillEvidence {
            val payments = mutableMapOf<String, String>()
            val registrations = mutableMapOf<String, String>()
            logs.filter { !it.removed }.sortedWith(compareBy({ it.blockNumber }, { it.logIndex })).forEach { log ->
                when (log.topics.firstOrNull()) {
                    ModuleEvents.PAID -> {
                        require(log.topics.size == 4)
                        Abi(log.data).requireSize(3).bool(2)
                        payments[log.topics[1].lowercase()] = log.txHash
                    }
                    ModuleEvents.PAYEE_ADDED -> {
                        require(log.topics.size == 3)
                        registrations[log.topics[1].lowercase()] = log.txHash
                    }
                }
            }
            val queuedId = if (queue != null && queue.bool(0) && queue.int(5) == ActionKind.PAY_OBLIGATION.ordinal) queue.bytes32(11) else null
            return BillEvidence(blockHash, payments, registrations, queuedId)
        }
    }
}
