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
    private val extraObligationsJson: List<String> = emptyList(),
    private val snapshotCache: SnapshotCache? = null,
) : WalletRepository {
    private val deployment = Deployment.parse(manifestJson)
    private val reader = ChainReader(rpc ?: JsonRpcClient(deployment.rpcUrl))
    private val mutableState = MutableStateFlow(WalletState(chainId = deployment.chainId))
    override val state = mutableState.asStateFlow()
    private val refreshLock = Mutex()
    private var history: List<ChainLog> = emptyList()
    private var historyThrough: Long? = null
    private var cachedBlockNumber: Long? = null
    private val historicalHeaders = mutableMapOf<String, BlockHeader>()

    init {
        val cached = snapshotCache?.read()?.let { WalletStateStore.decode(it) }
        if (cached != null) {
            mutableState.value = cached.copy(refreshing = true)
            cachedBlockNumber = cached.blockNumber
            PerfTracker.logFirstPaint(isCached = true)
        }
    }

    override suspend fun refresh(): Unit = refreshLock.withLock {
        val previous = mutableState.value
        mutableState.value = previous.copy(refreshing = true)
        val startTime = System.currentTimeMillis()
        try {
            // Phase 1: balances + module state + policy + queue (batch eth_calls into ONE JSON-RPC batch at one pinned block)
            val p1 = withContext(Dispatchers.IO) { readPhase1() }
            mutableState.value = p1.toWalletState(previous)
            PerfTracker.logFirstPaint(isCached = false)

            try {
                // Phase 2: event history (incremental eth_getLogs from last cached block) + GitHub evidence/bills
                val fullState = withContext(Dispatchers.IO) { readPhase2(p1, previous) }
                mutableState.value = fullState
                PerfTracker.logLiveSnapshot(System.currentTimeMillis() - startTime)
                snapshotCache?.write(WalletStateStore.encode(fullState) ?: "")
            } catch (p2Error: Exception) {
                if (p2Error is CancellationException) throw p2Error
                if (p2Error.message?.contains("reorganized") == true || p2Error.message?.contains("rolled back") == true) {
                    throw p2Error
                }
                mutableState.value = mutableState.value.copy(refreshing = false)
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = previous.copy(refreshing = false)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value = previous.copy(
                status = LoadStatus.ERROR,
                refreshing = false,
                error = error.message ?: "Chain snapshot unavailable"
            )
        }
    }

    private class Phase1Result(
        val block: BlockHeader,
        val cash: BigInteger,
        val policy: Policy,
        val module: ModuleState,
        val queue: List<QueuedAction>,
        val vaults: List<VaultPosition>,
        val buckets: Buckets,
        val capturedAt: Long,
        val deploymentChainId: Long,
        val safeAddress: String,
        val assetAddress: String,
    ) {
        fun toWalletState(previous: WalletState): WalletState = WalletState(
            status = LoadStatus.READY,
            source = DataSource.LIVE,
            chainId = deploymentChainId,
            blockNumber = block.number,
            blockHash = block.hash,
            blockTime = block.timestamp,
            treasury = Treasury(safeAddress, assetAddress, Money(cash)),
            buckets = buckets,
            vaults = vaults,
            module = module,
            queue = queue,
            payees = previous.payees,
            recentActions = previous.recentActions,
            evidence = previous.evidence,
            obligations = previous.obligations,
            accountingMatches = previous.accountingMatches,
            accountingError = previous.accountingError,
            capturedAtEpochMillis = capturedAt,
            refreshing = true,
        )
    }

    private fun readPhase1(): Phase1Result {
        val capturedAt = System.currentTimeMillis()
        require(reader.chainId() == deployment.chainId) { "RPC chain does not match deployment" }
        val block = reader.block("latest")
        require(block.number >= deployment.deploymentBlock) { "RPC precedes deployment" }
        val owner = Abi.addressWord(deployment.safe)
        val scaleWord = Abi.uintWord(Money.SCALE)

        // Batch the eth_calls into ONE JSON-RPC batch request at one pinned block
        val phase1Calls = listOf(
            deployment.module to Views.POLICY,
            deployment.module to Views.STATE,
            deployment.module to Views.QUEUE,
            deployment.asset to Views.BALANCE + owner,
            deployment.vaultA to Views.BALANCE + owner,
            deployment.vaultA to Views.MAX_WITHDRAW + owner,
            deployment.vaultA to Views.PREVIEW_DEPOSIT + scaleWord,
            deployment.vaultA to Views.PREVIEW_WITHDRAW + scaleWord,
            deployment.vaultB to Views.BALANCE + owner,
            deployment.vaultB to Views.MAX_WITHDRAW + owner,
            deployment.vaultB to Views.PREVIEW_DEPOSIT + scaleWord,
            deployment.vaultB to Views.PREVIEW_WITHDRAW + scaleWord,
        )
        val batchResults = reader.batchCalls(phase1Calls, block.number)
        val decodedPolicy = ContractDecoders.policy(batchResults[0])
        val policy = decodedPolicy.policy
        val module = ContractDecoders.module(batchResults[1], deployment.module, policy, block.timestamp)
        val queue = ContractDecoders.queue(batchResults[2], block.timestamp)
        val cash = batchResults[3].requireSize(1).uint(0)

        fun uint(address: String, data: String): BigInteger = reader.call(address, data, block.number).requireSize(1).uint(0)

        // Vault A
        val vAShares = batchResults[4].requireSize(1).uint(0)
        val vAMaxWithdraw = batchResults[5].requireSize(1).uint(0)
        val vAPreviewDeposit = batchResults[6].requireSize(1).uint(0)
        val vAPreviewWithdraw = batchResults[7].requireSize(1).uint(0)
        val vAAssets = uint(deployment.vaultA, Views.CONVERT_TO_ASSETS + Abi.uintWord(vAShares))

        // Vault B
        val vBShares = batchResults[8].requireSize(1).uint(0)
        val vBMaxWithdraw = batchResults[9].requireSize(1).uint(0)
        val vBPreviewDeposit = batchResults[10].requireSize(1).uint(0)
        val vBPreviewWithdraw = batchResults[11].requireSize(1).uint(0)
        val vBAssets = uint(deployment.vaultB, Views.CONVERT_TO_ASSETS + Abi.uintWord(vBShares))

        val vaults = listOf(
            VaultPosition(
                id = "A", name = "Aave waUSDC", address = deployment.vaultA, sharesRaw = vAShares,
                assets = Money(vAAssets), maxWithdraw = Money(vAMaxWithdraw),
                exposureLimit = decodedPolicy.exposureLimits[deployment.vaultA] ?: error("Missing vault exposure limit"),
                simulatedYield = false,
                previewDepositSharesRaw = vAPreviewDeposit,
                previewWithdrawSharesRaw = vAPreviewWithdraw,
            ),
            VaultPosition(
                id = "B", name = "Demo vault · SIMULATED yield", address = deployment.vaultB, sharesRaw = vBShares,
                assets = Money(vBAssets), maxWithdraw = Money(vBMaxWithdraw),
                exposureLimit = decodedPolicy.exposureLimits[deployment.vaultB] ?: error("Missing vault exposure limit"),
                simulatedYield = true,
                previewDepositSharesRaw = vBPreviewDeposit,
                previewWithdrawSharesRaw = vBPreviewWithdraw,
            )
        )
        val buckets = BucketMath.compute(
            cash, module.reserved.raw, policy.operatingBuffer.raw,
            vaults.fold(BigInteger.ZERO) { sum, position -> sum + position.assets.raw }
        )
        val end = reader.block(ChainReader.tag(block.number))
        require(end.hash.equals(block.hash, true)) { "Snapshot block was reorganized; refresh again" }
        val head = reader.block("latest")
        require(head.number >= block.number && head.timestamp >= block.timestamp) { "Chain time or height rolled back" }
        return Phase1Result(block, cash, policy, module, queue, vaults, buckets, capturedAt, deployment.chainId, deployment.safe, deployment.asset)
    }

    private fun readPhase2(p1: Phase1Result, previous: WalletState): WalletState {
        fun call(address: String, data: String) = reader.call(address, data, p1.block.number)
        val block = p1.block

        // Re-read the most recent 64 blocks on each refresh so shallow reorgs remove old logs.
        val lastKnownBlock = historyThrough ?: cachedBlockNumber
        val from = lastKnownBlock?.takeIf { it <= block.number }?.let {
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
            val kind = ContractDecoders.actionKind(Abi(log.data).int(0))
            val payment = if (kind == ActionKind.PAY_OBLIGATION) paidLogs.lastOrNull {
                it.txHash == log.txHash && it.logIndex < log.logIndex
            } else null
            if (kind == ActionKind.PAY_OBLIGATION) require(payment != null) { "Missing payment event" }
            ModuleEvents.action(log, header(log).timestamp, payment?.let(ModuleEvents::paidRecipient))
        }.reversed()
        val evidence = logs.takeLast(100).map { ModuleEvents.evidence(it, header(it).timestamp, deployment.explorerBaseUrl) }.reversed()
        val (obligations, accountingError) = readObligations(p1.module) { data -> call(deployment.module, data) }
        val end = reader.block(ChainReader.tag(block.number))
        require(end.hash.equals(block.hash, true)) { "Snapshot block was reorganized; refresh again" }
        val head = reader.block("latest")
        require(head.number >= block.number && head.timestamp >= block.timestamp) { "Chain time or height rolled back" }
        history = logs
        historyThrough = block.number
        return WalletState(
            status = LoadStatus.READY, chainId = deployment.chainId, blockNumber = block.number,
            blockHash = block.hash, blockTime = block.timestamp,
            treasury = Treasury(deployment.safe, deployment.asset, Money(p1.cash)),
            buckets = p1.buckets,
            vaults = p1.vaults, module = p1.module, queue = p1.queue, payees = payees, recentActions = actions,
            evidence = evidence, obligations = obligations, accountingMatches = accountingError == null,
            accountingError = accountingError, capturedAtEpochMillis = p1.capturedAt, observedHeadBlock = head.number,
            refreshing = false,
        )
    }

    private fun readObligations(module: ModuleState, call: (String) -> Abi): Pair<List<Obligation>, String?> {
        val resolved = obligationsResolver.resolve(module.obligationsRoot, listOf(obligationsJson) + extraObligationsJson)
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
            fun assetOrNull(path: String) = try { asset(path) } catch (_: Exception) { null }
            // Bundle every known bill set (K2 primary + K9 agent demo + K19 live demo); the on-chain root moves when
            // the agent recommits, and GitHub may be slow or offline, so the match must work from disk.
            val extra = listOfNotNull(
                assetOrNull("deployments/obligations-agent-84532.json"),
                assetOrNull("deployments/obligations-demo-84532.json"),
            )
            val cache = FileSnapshotCache.forContext(context)
            return ChainWalletRepository(asset("deployments/84532.json"), asset("deployments/obligations-84532.json"),
                rpcUrl?.let(::JsonRpcClient), extraObligationsJson = extra, snapshotCache = cache)
        }
    }
}
