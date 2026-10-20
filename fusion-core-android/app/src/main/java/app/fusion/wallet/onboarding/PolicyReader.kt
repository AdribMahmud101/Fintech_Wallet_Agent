package app.fusion.wallet.onboarding

import app.fusion.wallet.chain.Abi
import app.fusion.wallet.chain.ChainLog
import app.fusion.wallet.chain.ChainReader
import app.fusion.wallet.chain.ContractDecoders
import app.fusion.wallet.chain.JsonRpcClient
import app.fusion.wallet.chain.ModuleEvents
import app.fusion.wallet.chain.RpcTransport
import app.fusion.wallet.chain.Views
import app.fusion.wallet.data.Deployment
import app.fusion.wallet.data.Money
import app.fusion.wallet.data.Payee
import app.fusion.wallet.data.PayeeStatus
import app.fusion.wallet.data.PermissionTier
import app.fusion.wallet.data.Policy
import app.fusion.wallet.data.WalletState
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PolicyTiers(
    val tier: PermissionTier,
    val microMax: Money,
    val delayedMax: Money,
    val hardMax: Money,
    val delaySeconds: Long,
)

data class PolicyLimits(
    val dailyLimit: Money,
    val dailyUsed: Money,
    val dailyRemaining: Money,
    val sessionLimit: Money,
    val sessionUsed: Money,
    val sessionRemaining: Money,
    val sessionActive: Boolean,
    val sessionExpiresAt: Long,
    val sessionKey: String,
)

data class ReserveBuffer(
    val operatingBuffer: Money,
    val reserved: Money,
    val totalRequired: Money,
)

data class VerifiedVendor(
    val id: String,
    val address: String,
    val name: String,
    val status: PayeeStatus,
    val activeAt: Long,
    val sanctioned: Boolean,
)

data class PolicySummary(
    val blockNumber: Long,
    val blockTimestamp: Long,
    val policyEpoch: Long,
    val tiers: PolicyTiers,
    val limits: PolicyLimits,
    val reserveBuffer: ReserveBuffer,
    val verifiedVendors: List<VerifiedVendor>,
    val guardianAddress: String,
    val safeAddress: String,
    val moduleAddress: String,
    val ruleNotice: String = "Changes need both owners (Safe 2-of-2)",
    /** False while the vendor list (a history scan) is still loading; totals above are already final. */
    val vendorsLoaded: Boolean = true,
)

class PolicyReader(
    private val rpcTransport: RpcTransport? = null,
) {
    companion object {
        fun fromWalletState(state: WalletState, deployment: Deployment): PolicySummary? {
            val module = state.module ?: return null
            val policy = module.policy
            val tiers = PolicyTiers(
                tier = policy.permissionTier,
                microMax = policy.microMax,
                delayedMax = policy.delayedMax,
                hardMax = policy.hardMax,
                delaySeconds = policy.delaySeconds,
            )
            val sessionRemaining = Money((module.sessionLimit.raw - module.sessionUsed.raw).max(BigInteger.ZERO))
            val limits = PolicyLimits(
                dailyLimit = policy.dailyLimit,
                dailyUsed = module.dailyUsed,
                dailyRemaining = module.dailyRemaining,
                sessionLimit = module.sessionLimit,
                sessionUsed = module.sessionUsed,
                sessionRemaining = sessionRemaining,
                sessionActive = module.sessionActive,
                sessionExpiresAt = module.sessionExpiresAt,
                sessionKey = module.sessionKey,
            )
            val reserveBuffer = ReserveBuffer(
                operatingBuffer = policy.operatingBuffer,
                reserved = module.reserved,
                totalRequired = Money(policy.operatingBuffer.raw + module.reserved.raw),
            )
            val vendors = state.payees.map { p ->
                VerifiedVendor(
                    id = p.id,
                    address = p.address,
                    name = p.label,
                    status = p.status,
                    activeAt = p.activeAt,
                    sanctioned = p.sanctioned,
                )
            }
            return PolicySummary(
                blockNumber = state.blockNumber ?: 0L,
                blockTimestamp = state.blockTime ?: 0L,
                policyEpoch = module.policyEpoch.toLong(),
                tiers = tiers,
                limits = limits,
                reserveBuffer = reserveBuffer,
                verifiedVendors = vendors,
                guardianAddress = policy.guardian,
                safeAddress = deployment.safe,
                moduleAddress = deployment.module,
            )
        }
    }

    suspend fun read(manifestJson: String, includeVendors: Boolean = true): PolicySummary = withContext(Dispatchers.IO) {
        val deployment = Deployment.parse(manifestJson)
        val transport = rpcTransport ?: JsonRpcClient(deployment.rpcUrl)
        val reader = ChainReader(transport)

        val block = reader.block("latest")
        val calls = listOf(
            deployment.module to Views.POLICY,
            deployment.module to Views.STATE,
        )
        val batchResults = reader.batchCalls(calls, block.number)
        val decodedPolicy = ContractDecoders.policy(batchResults[0])
        val policy = decodedPolicy.policy
        val moduleState = ContractDecoders.module(batchResults[1], deployment.module, policy, block.timestamp)

        // Read payee events to find verified vendors. This scans history from the
        // deployment block, which is slow on a public RPC, so callers can skip it
        // and show the policy first.
        val logs = if (!includeVendors) emptyList() else try {
            reader.logs(deployment.module, deployment.deploymentBlock, block.number)
        } catch (e: Exception) {
            emptyList()
        }

        val payeeIds = logs.filter { it.topics.firstOrNull() == ModuleEvents.PAYEE_ADDED }.mapNotNull { log ->
            if (log.topics.size >= 2) {
                try { Abi(log.topics[1]).requireSize(1).bytes32(0) } catch (e: Exception) { null }
            } else null
        }.distinct()

        val vendors = payeeIds.mapNotNull { id ->
            try {
                val value = reader.call(deployment.module, Views.PAYEE + Abi.hashWord(id), block.number).requireSize(4)
                val sanctioned = reader.call(deployment.sanctionsList, Views.SANCTIONED + Abi.addressWord(value.address(0)), block.number).requireSize(1).bool(0)
                val payee = ContractDecoders.payee(value, id, block.timestamp, sanctioned)
                VerifiedVendor(
                    id = payee.id,
                    address = payee.address,
                    name = payee.label,
                    status = payee.status,
                    activeAt = payee.activeAt,
                    sanctioned = payee.sanctioned,
                )
            } catch (e: Exception) {
                null
            }
        }

        val tiers = PolicyTiers(
            tier = policy.permissionTier,
            microMax = policy.microMax,
            delayedMax = policy.delayedMax,
            hardMax = policy.hardMax,
            delaySeconds = policy.delaySeconds,
        )
        val sessionRemaining = Money((moduleState.sessionLimit.raw - moduleState.sessionUsed.raw).max(BigInteger.ZERO))
        val limits = PolicyLimits(
            dailyLimit = policy.dailyLimit,
            dailyUsed = moduleState.dailyUsed,
            dailyRemaining = moduleState.dailyRemaining,
            sessionLimit = moduleState.sessionLimit,
            sessionUsed = moduleState.sessionUsed,
            sessionRemaining = sessionRemaining,
            sessionActive = moduleState.sessionActive,
            sessionExpiresAt = moduleState.sessionExpiresAt,
            sessionKey = moduleState.sessionKey,
        )
        val reserveBuffer = ReserveBuffer(
            operatingBuffer = policy.operatingBuffer,
            reserved = moduleState.reserved,
            totalRequired = Money(policy.operatingBuffer.raw + moduleState.reserved.raw),
        )

        PolicySummary(
            blockNumber = block.number,
            blockTimestamp = block.timestamp,
            policyEpoch = moduleState.policyEpoch.toLong(),
            tiers = tiers,
            limits = limits,
            reserveBuffer = reserveBuffer,
            verifiedVendors = vendors,
            guardianAddress = policy.guardian,
            safeAddress = deployment.safe,
            moduleAddress = deployment.module,
            vendorsLoaded = includeVendors,
        )
    }
}
