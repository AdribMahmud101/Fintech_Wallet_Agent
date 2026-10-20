package app.fusion.wallet.advisor

import app.fusion.wallet.accountant.cloud.CloudClient
import app.fusion.wallet.accountant.cloud.CloudResult
import app.fusion.wallet.data.ActionKind
import app.fusion.wallet.data.AgentAction
import app.fusion.wallet.data.Buckets
import app.fusion.wallet.data.LoadStatus
import app.fusion.wallet.data.ModuleState
import app.fusion.wallet.data.Money
import app.fusion.wallet.data.Obligation
import app.fusion.wallet.data.PermissionTier
import app.fusion.wallet.data.Policy
import app.fusion.wallet.data.Treasury
import app.fusion.wallet.data.VaultPosition
import app.fusion.wallet.data.WalletState
import java.math.BigInteger

internal fun usdcRaw(whole: Long): BigInteger = BigInteger.valueOf(whole).multiply(BigInteger.TEN.pow(6))
internal fun m(whole: Long) = Money(usdcRaw(whole))

internal object Fx {
    const val NOW = 1_760_000_000L
    const val SAFE = "0x5afe00000000000000000000000000000000cafe"
    const val MODULE = "0xa560ec10259657e9badd297e68286d2838cd5a8f"
    const val GUARDIAN = "0x46b8e40eb9767bd46e03c5ecf398c10e1372251e"
    // Deliberately listed out of address order: the encoder must sort.
    const val VAULT_A = "0xaaaa000000000000000000000000000000000001"
    const val VAULT_B = "0x1111000000000000000000000000000000000002"
    const val SESSION_KEY = "0x5e55100000000000000000000000000000000001"

    val policy = Policy(
        permissionTier = PermissionTier.AUTONOMOUS, operatingBuffer = m(100), microMax = m(10), delayedMax = m(50),
        hardMax = m(100), dailyLimit = m(4000), delaySeconds = 300, maxShareSlippageBps = 50, guardian = GUARDIAN,
        payeeDelaySeconds = 86_400, payEarlyWindowSeconds = 259_200,
    )

    fun state(status: LoadStatus = LoadStatus.READY, capturedAt: Long = NOW * 1000): WalletState = WalletState(
        status = status,
        blockNumber = 30_000_000, blockTime = NOW, capturedAtEpochMillis = capturedAt, observedHeadBlock = 30_000_000,
        treasury = Treasury(SAFE, "0x036cbd53842c5426634e7929541ec2318f3dcf7e", m(2500)),
        buckets = Buckets(reserve = m(58), owed = m(58), buffer = m(100), surplus = m(2342), free = m(2442), shortfall = Money.ZERO,
            deployed = m(400), total = m(2900), bufferTarget = m(100)),
        vaults = listOf(
            VaultPosition("vaultA", "Aave vault", VAULT_A, BigInteger.ONE, m(300), m(300), m(1000)),
            VaultPosition("vaultB", "Demo vault", VAULT_B, BigInteger.ONE, m(100), m(100), m(500)),
        ),
        module = ModuleState(
            address = MODULE, configured = true, paused = false, sessionActive = true, policy = policy,
            policyEpoch = BigInteger.valueOf(7), policyHash = "0x" + "11".repeat(32), mandateHash = "0x" + "22".repeat(32),
            obligationsRoot = "0x" + "33".repeat(32), reserved = m(58), obligationCount = BigInteger.TWO, actionNonce = BigInteger.TEN,
            dailyUsed = m(20), dailyRemaining = m(3980), sessionId = "0x" + "44".repeat(32), sessionKey = SESSION_KEY,
            sessionExpiresAt = NOW + 3600, sessionLimit = m(8000), sessionUsed = m(20),
        ),
        obligations = listOf(
            Obligation("0x" + "aa".repeat(32), "payee-rent", m(50), NOW + 2 * 86_400, paid = false),
            Obligation("0x" + "bb".repeat(32), "payee-net", m(8), NOW + 5 * 86_400, paid = false),
            Obligation("0x" + "cc".repeat(32), "payee-old", m(12), NOW - 10 * 86_400, paid = true),
        ),
        recentActions = listOf(
            AgentAction("a1", ActionKind.PAY_OBLIGATION, m(150), "0x9999000000000000000000000000000000000009", "0x" + "dd".repeat(32), "0x" + "ee".repeat(32), 1, NOW - 3 * 86_400, "AGENT"),
            AgentAction("a2", ActionKind.DEPOSIT, m(100), null, "0x" + "ab".repeat(32), "0x" + "cd".repeat(32), 2, NOW - 1 * 86_400, "AGENT"),
        ),
    )

    fun current(): CurrentPolicy = CurrentPolicy.from(state())!!
}

/** Scripted cloud: records every request, replies from [replies] in order (last one repeats). */
internal class FakeCloud(private vararg val replies: (model: String) -> CloudResult) : CloudClient {
    data class Call(val model: String, val system: String, val user: String)
    val calls = mutableListOf<Call>()
    override val provider = "fake"
    override suspend fun complete(model: String, system: String, userJson: String): CloudResult {
        calls += Call(model, system, userJson)
        return replies[minOf(calls.size - 1, replies.lastIndex)](model)
    }
}

internal fun reply(content: String): (String) -> CloudResult = { CloudResult.Success(content, 10, 10, 200) }
internal fun failure(kind: CloudResult.Kind): (String) -> CloudResult = { CloudResult.Failure(kind, 500, kind.name) }
