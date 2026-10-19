package app.fusion.wallet.chain

import app.fusion.wallet.data.*
import java.math.BigInteger

data class DecodedPolicy(val policy: Policy, val exposureLimits: Map<String, Money>)

object ContractDecoders {
    fun policy(abi: Abi): DecodedPolicy {
        require(abi.offset(0) == 1) { "Unexpected policy tuple offset" }
        val root = 1
        val array = root + abi.offset(root + 9)
        require(array >= root + 12 && array < abi.size) { "Invalid vault array offset" }
        val count = abi.int(array)
        require(count == 2 && abi.size == array + 1 + count * 2) { "Expected two vault limits" }
        val limits = (0 until count).associate { i ->
            abi.address(array + 1 + i * 2) to Money(abi.uint(array + 2 + i * 2))
        }
        require(limits.size == 2) { "Duplicate vault limits" }
        val tier = PermissionTier.entries.getOrNull(abi.int(root)) ?: error("Invalid permission tier")
        return DecodedPolicy(Policy(
            tier, Money(abi.uint(2)), Money(abi.uint(3)), Money(abi.uint(4)), Money(abi.uint(5)), Money(abi.uint(6)),
            abi.long(7), abi.int(8), abi.address(9), abi.long(11), abi.long(12),
        ), limits)
    }

    fun module(abi: Abi, address: String, policy: Policy, now: Long): ModuleState {
        abi.requireSize(17)
        val paused = abi.bool(1)
        val sessionId = abi.bytes32(11)
        val sessionKey = abi.address(13)
        val expiresAt = abi.long(14)
        val dailyUsed = if (abi.uint(9) == BigInteger.valueOf(now / 86400)) abi.uint(10) else BigInteger.ZERO
        val active = abi.bool(0) && !paused && sessionId != ZERO_HASH && sessionKey != ZERO_ADDRESS && expiresAt > now
        // Session start epoch identifies the session; policyEpoch can increase while it stays alive.
        return ModuleState(
            address, abi.bool(0), paused, active, policy, abi.uint(2), abi.bytes32(3), abi.bytes32(4), abi.bytes32(5),
            Money(abi.uint(6)), abi.uint(7), abi.uint(8), Money(dailyUsed),
            Money((policy.dailyLimit.raw - dailyUsed).max(BigInteger.ZERO)), sessionId, sessionKey,
            expiresAt, Money(abi.uint(15)), Money(abi.uint(16)),
        )
    }

    fun queue(abi: Abi, now: Long): List<QueuedAction> {
        abi.requireSize(19)
        if (!abi.bool(0)) return emptyList()
        val ready = abi.long(3)
        val expires = abi.long(4)
        val kind = actionKind(abi.int(5))
        return listOf(QueuedAction(
            abi.bytes32(1), kind, Money(abi.uint(8)), ready, expires,
            when { now > expires -> QueueStatus.EXPIRED; now >= ready -> QueueStatus.READY; else -> QueueStatus.WAITING },
            abi.address(13), abi.bytes32(18), abi.uint(15),
        ))
    }

    fun payee(abi: Abi, id: String, now: Long, sanctioned: Boolean): Payee {
        abi.requireSize(4)
        val activeAt = abi.long(2)
        require(abi.address(0) != ZERO_ADDRESS) { "Unknown payee $id" }
        return Payee(
            id, abi.address(0), "Payee ${id.take(10)}", abi.bytes32(1),
            when { abi.bool(3) -> PayeeStatus.CANCELLED; now < activeAt -> PayeeStatus.COOLING; else -> PayeeStatus.ACTIVE },
            activeAt, sanctioned,
        )
    }

    fun actionKind(value: Int): ActionKind = ActionKind.entries.getOrNull(value) ?: error("Invalid action kind")
    const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
    const val ZERO_HASH = "0x0000000000000000000000000000000000000000000000000000000000000000"
}

object ModuleEvents {
    const val PAYEE_ADDED = "0xe711ab354836ceffbda6c5b3c0d49f63c3f32ac451c0dac7cf57b7dbd57cce77"
    const val EXECUTED = "0xec3c5fb2a9f98228404a340d65a7da26d4bb846738b0d1f39b0de18f0a0154e8"
    const val PAID = "0x55b6c2047d256a41d48c1a2e922b59254cf977232846a54a6e2edf5f64d0c8a0"
    const val QUEUED = "0x97c854f499aef695661e30437a03bb608a96556ee775d57648e7217d96c449cd"
    const val PAUSED = "0x07132b2418d1ecb2df85ff8d532ce93647aca2015b8534584835fe449819c206"
    const val UNWIND = "0x1000f6bd1e72a87b9c03a3e183615200d3706dc37ddf713d023f6dd4359de9bc"

    fun action(log: ChainLog, blockTime: Long, recipient: String?): AgentAction {
        require(log.topics.size == 4 && log.topics[0] == EXECUTED)
        val abi = Abi(log.data).requireSize(8)
        return AgentAction(
            log.topics[1], ContractDecoders.actionKind(abi.int(0)), Money(abi.uint(1)), recipient,
            log.txHash, log.topics[3], log.blockNumber, blockTime,
        )
    }
    fun paidRecipient(log: ChainLog): String {
        require(log.topics.size == 4 && log.topics[0] == PAID)
        Abi(log.data).requireSize(3).bool(2)
        return Abi(log.topics[3]).address(0)
    }
    fun evidence(log: ChainLog, blockTime: Long, explorer: String): EvidenceRow {
        val kind = when (log.topics.firstOrNull()) {
            EXECUTED -> "ActionExecuted"; PAID -> "ObligationPaid"; QUEUED -> "ActionQueued"
            PAUSED -> "Paused"; UNWIND -> "EmergencyUnwind"; PAYEE_ADDED -> "PayeeAdded"
            else -> "Module event ${log.topics.firstOrNull()?.take(10).orEmpty()}"
        }
        val decisionHash = when (log.topics.firstOrNull()) {
            EXECUTED -> log.topics[3]
            QUEUED -> Abi(log.data).requireSize(3).bytes32(2)
            else -> null
        }
        return EvidenceRow("${log.txHash}:${log.logIndex}", kind, log.txHash, log.blockNumber, blockTime, decisionHash,
            explorerUrl = "$explorer/tx/${log.txHash}")
    }
}
