package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.agent.AccountantAudit
import app.fusion.wallet.accountant.cloud.CloudClient
import app.fusion.wallet.accountant.cloud.CloudResult
import app.fusion.wallet.accountant.data.EgressLogEntity
import app.fusion.wallet.accountant.data.FlowCategory
import app.fusion.wallet.accountant.data.InsightEntity
import app.fusion.wallet.accountant.data.LendingProtocol
import app.fusion.wallet.accountant.data.PrivacySettingsEntity
import app.fusion.wallet.accountant.domain.AccountantReadModel
import app.fusion.wallet.accountant.domain.AssetBalanceView
import app.fusion.wallet.accountant.domain.FlowView
import app.fusion.wallet.accountant.domain.LendingView
import app.fusion.wallet.accountant.domain.ObligationView
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.accountant.domain.TreasuryUnit
import java.math.BigInteger

const val NOW = 1_800_000_000L
const val DAY = 86_400L

/** DemoUSD raw units (6 decimals). */
fun usd(whole: Long): BigInteger = BigInteger.valueOf(whole).multiply(BigInteger.valueOf(1_000_000))

/** Spec reference state: C=5,000  R=1,500  B=500  V=3,000 (Aave) + 2,000 (ERC-4626 demo). */
fun referenceSnapshot(): PortfolioSnapshot = PortfolioSnapshot(
    asOf = NOW,
    unit = TreasuryUnit("DemoUSD", 6),
    balances = listOf(
        AssetBalanceView("DemoUSD", 6, true, usd(5_000)),
        AssetBalanceView("ETH", 18, false, BigInteger("12345678901234567")),
    ),
    lending = listOf(
        LendingView(LendingProtocol.AAVE_V3, "DemoUSD", "aDemoUSD", 6, true, usd(3_000), BigInteger.ZERO, 420),
        LendingView(LendingProtocol.ERC4626_DEMO, "DemoUSD", "vDemoUSD", 6, true, usd(2_000), BigInteger.ZERO, 310),
    ),
    obligations = listOf(
        ObligationView(usd(1_000), 6, NOW + 3 * DAY, FlowCategory.PAYROLL),
        ObligationView(usd(500), 6, NOW + 20 * DAY, FlowCategory.SUPPLIERS),
    ),
    flows30d = listOf(
        FlowView(FlowCategory.PAYROLL, false, usd(3_200), 6, NOW - 5 * DAY),
        FlowView(FlowCategory.PAYROLL, false, usd(3_100), 6, NOW - 20 * DAY),
        FlowView(FlowCategory.SUPPLIERS, false, usd(800), 6, NOW - 9 * DAY),
        FlowView(FlowCategory.YIELD, true, usd(41), 6, NOW - 2 * DAY),
    ),
    buffer = usd(500),
)

/** Strings that exist on the device and must never be sent (they act as canaries). */
val PRIVATE_CANARIES = setOf(
    "0x9f8e7d6c5b4a39281706f5e4d3c2b1a098765432",
    "Rahim Traders Ltd",
    "INV-2026-00417",
    "0xdeadbeefcafebabe1234567890abcdef12345678",
)

class FakeRead(
    private val snap: PortfolioSnapshot? = referenceSnapshot(),
    private val priv: Set<String> = PRIVATE_CANARIES,
) : AccountantReadModel {
    override suspend fun snapshot(now: Long) = snap
    override suspend fun privateStrings() = priv
}

class FakeAudit(var settings: PrivacySettingsEntity = PrivacySettingsEntity()) : AccountantAudit {
    val egress = mutableListOf<EgressLogEntity>()
    val insights = mutableListOf<InsightEntity>()
    private var seq = 0L
    override suspend fun settings() = settings
    override suspend fun callsSince(since: Long) =
        egress.count { it.createdAt >= since && it.status.name in setOf("SENT_OK", "FAILED", "REJECTED_REPLY") }
    override suspend fun logEgress(e: EgressLogEntity): Long { val id = ++seq; egress += e.copy(id = id); return id }
    override suspend fun updateEgress(e: EgressLogEntity) { val i = egress.indexOfFirst { it.id == e.id }; egress[i] = e }
    override suspend fun saveInsight(i: InsightEntity): Long { insights += i; return insights.size.toLong() }
}

class FakeCloud(var result: CloudResult) : CloudClient {
    override val provider = "deepinfra"
    var calls = 0
    var lastUser: String? = null
    override suspend fun complete(model: String, system: String, userJson: String): CloudResult {
        calls++
        lastUser = userJson
        return result
    }
}

const val GOOD_REPLY =
    """{"summary":"About half of funds are deployed and reserves are covered.","findings":[{"topic":"LIQUIDITY","severity":"INFO","text":"Surplus is available above the reserve and buffer."}],"followUps":["Should I rebalance between positions?"]}"""

fun enabledSettings(limit: Int = 20) = PrivacySettingsEntity(cloudEnabled = true, consentVersion = 1, consentedAt = NOW, dailyCallLimit = limit)
