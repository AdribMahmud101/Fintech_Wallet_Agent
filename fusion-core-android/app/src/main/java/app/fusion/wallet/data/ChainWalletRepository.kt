package app.fusion.wallet.data

import android.content.Context
import app.fusion.wallet.chain.*
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A complete refresh is published atomically. A failed read preserves the last snapshot with ERROR. */
class ChainWalletRepository(
    manifestJson: String,
    private val obligationsJson: String? = null,
    rpc: RpcTransport? = null,
    private val obligationsResolver: ObligationsResolver = ObligationsResolver(),
) : WalletRepository {
    private val deployment = Deployment.parse(manifestJson)
    private val reader = ChainReader(rpc ?: JsonRpcClient(deployment.rpcUrl))
    private val mutableState = MutableStateFlow(WalletState(chainId = deployment.chainId))
    override val state = mutableState.asStateFlow()
    private val refreshLock = Mutex()
    private var history: List<ChainLog> = emptyList()
    private var historyThrough: Long? = null
    private val historicalHeaders = mutableMapOf<String, BlockHeader>()

    override suspend fun refresh() = refreshLock.withLock {
        mutableState.value = mutableState.value.copy(refreshing = true)
        try {
            val snapshot = withContext(Dispatchers.IO) { readSnapshot() }
            mutableState.value = snapshot
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(refreshing = false)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(status = LoadStatus.ERROR, refreshing = false,
                error = error.message ?: "Chain snapshot unavailable")
        }
    }

    private fun readSnapshot(): WalletState {
        val capturedAt = System.currentTimeMillis()
        require(reader.chainId() == deployment.chainId) { "RPC chain does not match deployment" }
        val block = reader.block("latest")
        require(block.number >= deployment.deploymentBlock) { "RPC precedes deployment" }
        fun call(address: String, data: String) = reader.call(address, data, block.number)
        fun uint(address: String, data: String): BigInteger = call(address, data).requireSize(1).uint(0)
        val decodedPolicy = ContractDecoders.policy(call(deployment.module, Views.POLICY))
        val policy = decodedPolicy.policy
        val module = ContractDecoders.module(call(deployment.module, Views.STATE), deployment.module, policy, block.timestamp)
        val queue = ContractDecoders.queue(call(deployment.module, Views.QUEUE), block.timestamp)
        val owner = Abi.addressWord(deployment.safe)
        val cash = uint(deployment.asset, Views.BALANCE + owner)
        val vaults = listOf(deployment.vaultA, deployment.vaultB).mapIndexed { index, address ->
            val shares = uint(address, Views.BALANCE + owner)
            VaultPosition(
                id = if (index == 0) "A" else "B", name = if (index == 0) "Aave waUSDC" else "Demo vault · SIMULATED yield",
                address = address, sharesRaw = shares,
                assets = Money(uint(address, Views.CONVERT_TO_ASSETS + Abi.uintWord(shares))),
                maxWithdraw = Money(uint(address, Views.MAX_WITHDRAW + owner)),
                exposureLimit = decodedPolicy.exposureLimits[address] ?: error("Missing vault exposure limit"),
                simulatedYield = index == 1,
                previewDepositSharesRaw = uint(address, Views.PREVIEW_DEPOSIT + Abi.uintWord(Money.SCALE)),
                previewWithdrawSharesRaw = uint(address, Views.PREVIEW_WITHDRAW + Abi.uintWord(Money.SCALE)),
            )
        }
        // Re-read the most recent 64 blocks on each refresh so shallow reorgs remove old logs.
        val from = historyThrough?.takeIf { it <= block.number }?.let {
            maxOf(deployment.deploymentBlock, it - 63)
        } ?: deployment.deploymentBlock
        val logs = history.filter { it.blockNumber < from } + reader.logs(deployment.module, from, block.number)
        val blockCache = mutableMapOf(block.number to block)
        fun header(log: ChainLog): BlockHeader {
            val header = blockCache.getOrPut(log.blockNumber) {
                historicalHeaders[log.blockHash] ?: reader.block(ChainReader.tag(log.blockNumber))
            }
            require(header.hash.equals(log.blockHash, true)) { "Log block was reorganized" }
            historicalHeaders[log.blockHash] = header
            return header
        }
        val payeeIds = logs.filter { it.topics.firstOrNull() == ModuleEvents.PAYEE_ADDED }.map {
            require(it.topics.size == 3)
            Abi(it.topics[1]).requireSize(1).bytes32(0)
        }.distinct()
        val payees = payeeIds.map { id ->
            val value = call(deployment.module, Views.PAYEE + Abi.hashWord(id)).requireSize(4)
            val sanctioned = call(deployment.sanctionsList, Views.SANCTIONED + Abi.addressWord(value.address(0))).requireSize(1).bool(0)
            ContractDecoders.payee(value, id, block.timestamp, sanctioned)
        }
        val paidLogs = logs.filter { it.topics.firstOrNull() == ModuleEvents.PAID }
        val actions = logs.filter { it.topics.firstOrNull() == ModuleEvents.EXECUTED }.takeLast(50).map { log ->
            // A payment emits ObligationPaid before ActionExecuted. Match the closest preceding payment
            // in that transaction; deposits and withdrawals have no payee.
            val kind = ContractDecoders.actionKind(Abi(log.data).int(0))
            val payment = if (kind == ActionKind.PAY_OBLIGATION) paidLogs.lastOrNull {
                it.txHash == log.txHash && it.logIndex < log.logIndex
            } else null
            if (kind == ActionKind.PAY_OBLIGATION) require(payment != null) { "Missing payment event" }
            ModuleEvents.action(log, header(log).timestamp, payment?.let(ModuleEvents::paidRecipient))
        }.reversed()
        val evidence = logs.takeLast(100).map { ModuleEvents.evidence(it, header(it).timestamp, deployment.explorerBaseUrl) }.reversed()
        val (obligations, accountingError) = readObligations(module) { data -> call(deployment.module, data) }
        val end = reader.block(ChainReader.tag(block.number))
        require(end.hash.equals(block.hash, true)) { "Snapshot block was reorganized; refresh again" }
        val head = reader.block("latest")
        require(head.number >= block.number && head.timestamp >= block.timestamp) { "Chain time or height rolled back" }
        history = logs
        historyThrough = block.number
        return WalletState(
            status = LoadStatus.READY, chainId = deployment.chainId, blockNumber = block.number,
            blockHash = block.hash, blockTime = block.timestamp,
            treasury = Treasury(deployment.safe, deployment.asset, Money(cash)),
            buckets = BucketMath.compute(cash, module.reserved.raw, policy.operatingBuffer.raw,
                vaults.fold(BigInteger.ZERO) { sum, position -> sum + position.assets.raw }),
            vaults = vaults, module = module, queue = queue, payees = payees, recentActions = actions,
            evidence = evidence, obligations = obligations, accountingMatches = accountingError == null,
            accountingError = accountingError, capturedAtEpochMillis = capturedAt, observedHeadBlock = head.number,
        )
    }

    private fun readObligations(module: ModuleState, call: (String) -> Abi): Pair<List<Obligation>, String?> {
        val resolved = obligationsResolver.resolve(module.obligationsRoot, obligationsJson)
            ?: return emptyList<Obligation>() to "ACCOUNTING_MISMATCH: on-chain commitment differs from bundled bills"
        val local = JSONObject(resolved)
        val bills = local.getJSONArray("bills")
        val obligations = List(bills.length()) { index ->
            val bill = bills.getJSONObject(index)
            val id = bill.getString("obligationIdHash")
            Obligation(id, bill.getString("payeeIdHash"), Money.fromRaw(bill.getString("assets")),
                BigInteger(bill.getString("dueAt")).longValueExact(), call(Views.PAID + Abi.hashWord(id)).requireSize(1).bool(0))
        }
        val unpaid = obligations.filterNot { it.paid }.fold(BigInteger.ZERO) { sum, bill -> sum + bill.amount.raw }
        val matches = unpaid == module.reserved.raw && module.obligationCount == BigInteger.valueOf(obligations.size.toLong())
        return obligations to if (matches) null else "ACCOUNTING_MISMATCH: local unpaid total/count differs from module"
    }

    companion object {
        fun fromAssets(context: Context, rpcUrl: String? = null): ChainWalletRepository {
            fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
            return ChainWalletRepository(asset("deployments/84532.json"), asset("deployments/obligations-84532.json"),
                rpcUrl?.let(::JsonRpcClient))
        }
    }
}
