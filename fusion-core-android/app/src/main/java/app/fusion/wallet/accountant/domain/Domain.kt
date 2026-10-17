package app.fusion.wallet.accountant.domain

import app.fusion.wallet.accountant.data.FlowCategory
import app.fusion.wallet.accountant.data.LendingProtocol
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/** Exact integer money helpers. Raw amounts are base-10 strings of integer units. */
object Raw {
    fun parse(s: String): BigInteger {
        require(s.isNotEmpty() && s.all { it in '0'..'9' }) { "invalid raw amount" }
        return BigInteger(s)
    }

    /** Rescales a raw amount between decimal bases (truncating toward zero when reducing). */
    fun rescale(raw: BigInteger, fromDecimals: Int, toDecimals: Int): BigInteger = when {
        fromDecimals == toDecimals -> raw
        fromDecimals < toDecimals -> raw.multiply(BigInteger.TEN.pow(toDecimals - fromDecimals))
        else -> raw.divide(BigInteger.TEN.pow(fromDecimals - toDecimals))
    }

    /** Plain decimal string with no exponent, e.g. 1500000000 @6 -> "1500". Exact. */
    fun format(raw: BigInteger, decimals: Int): String =
        BigDecimal(raw).movePointLeft(decimals).stripTrailingZeros().toPlainString()

    /** Reader-friendly amount with thousands separators and at most 2 decimals: 1000 -> "1,000", 1234.5 -> "1,234.5". */
    fun pretty(raw: BigInteger, decimals: Int): String =
        java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols(java.util.Locale.US)).format(BigDecimal(raw).movePointLeft(decimals))

    /** Two-significant-figure rounding used for anything shown to the cloud. 1234567 @6 -> "1.2". */
    fun coarse(raw: BigInteger, decimals: Int): String {
        if (raw.signum() == 0) return "0"
        val v = BigDecimal(raw).movePointLeft(decimals).round(MathContext(2, RoundingMode.HALF_UP))
        return v.stripTrailingZeros().toPlainString()
    }

    /** Integer percentage (floor-biased half up) of part/total; 0 when total is 0. */
    fun pct(part: BigInteger, total: BigInteger): Int {
        if (total.signum() <= 0) return 0
        return part.multiply(BigInteger.valueOf(100)).add(total.shiftRight(1)).divide(total).toInt().coerceIn(0, 100)
    }
}

/** Aave rate math. Aave reports an APR in ray (1e27 = 100%); never a float. */
object Aave {
    private val RAY_PER_BP = BigInteger.TEN.pow(23) // 1 bp = 0.01% = 1e-4 of a ray-denominated rate

    /** APR in basis points, rounded half up. 2.0748e24 ray (0.2075%) -> 21 bps. */
    fun aprBps(rateRay: BigInteger): Int =
        rateRay.add(RAY_PER_BP.shiftRight(1)).divide(RAY_PER_BP).min(BigInteger.valueOf(1_000_000)).toInt()

    /** "0.21%" from basis points. */
    fun pct(bps: Int): String = "${bps / 100}.${(bps % 100).toString().padStart(2, '0')}%"
}

/** Treasury unit (USDC, 6 decimals in the base scope). All bucket math is in this unit. */
data class TreasuryUnit(val symbol: String, val decimals: Int)

/** Spec §3 buckets. All values are raw units of the treasury unit. */
data class Buckets(
    val cash: BigInteger,
    val reserved: BigInteger,
    val obligationTarget: BigInteger,
    val free: BigInteger,
    val deployed: BigInteger,
    val sweepable: BigInteger,
    val shortfall: BigInteger,
    val buffer: BigInteger,
) {
    val totalAssets: BigInteger get() = cash.add(deployed)
}

object BucketMath {
    /**
     * C = wallet cash, R = owner-approved active obligations, B = operating buffer, V = deployed.
     *  C >= R : free = C-R (includes buffer), reserved = R, sweepable = max(C-R-B, 0)
     *  C <  R : backed reserve = C, free = 0, shortfall = R-C. The liability R is never reduced.
     */
    fun compute(cash: BigInteger, obligations: BigInteger, buffer: BigInteger, deployed: BigInteger): Buckets {
        val backed = cash.min(obligations)
        val free = (cash - obligations).max(BigInteger.ZERO)
        val sweepable = (cash - obligations - buffer).max(BigInteger.ZERO)
        val shortfall = (obligations - cash).max(BigInteger.ZERO)
        return Buckets(
            cash = cash, reserved = backed, obligationTarget = obligations, free = free,
            deployed = deployed, sweepable = sweepable, shortfall = shortfall, buffer = buffer,
        )
    }
}

// ---- Read model ---------------------------------------------------------------------------

data class AssetBalanceView(val symbol: String, val decimals: Int, val isStable: Boolean, val raw: BigInteger, val isNative: Boolean = false)

data class LendingView(
    val protocol: LendingProtocol,
    val underlyingSymbol: String,
    val positionSymbol: String,
    val decimals: Int,
    val isStable: Boolean,
    val supplied: BigInteger,
    val borrowed: BigInteger,
    /** Supply APR in basis points (Aave `currentLiquidityRate`, rounded). NOT an APY. */
    val supplyAprBps: Int,
)

/** Native gas fuel (ETH on Base Sepolia). NOT an ERC-20 and NOT part of treasury value. */
data class GasView(val balanceWei: BigInteger, val estFeePerActionWei: BigInteger) {
    /** How many agent actions the fuel covers at the estimated fee; capped for display. */
    val runwayActions: Int
        get() = if (estFeePerActionWei.signum() <= 0) 0 else balanceWei.divide(estFeePerActionWei).min(BigInteger.valueOf(9_999)).toInt()
}

data class ObligationView(val amount: BigInteger, val decimals: Int, val dueAt: Long, val category: FlowCategory?)

data class FlowView(val category: FlowCategory, val isIncome: Boolean, val amount: BigInteger, val decimals: Int, val at: Long)

/**
 * Complete PRIVATE picture of the treasury at an instant. It exists only on the device. The cloud
 * never receives this type; `BriefBuilder` is the one-way, lossy projection that may leave.
 */
data class PortfolioSnapshot(
    val asOf: Long,
    val unit: TreasuryUnit,
    val balances: List<AssetBalanceView>,
    val lending: List<LendingView>,
    val obligations: List<ObligationView>,
    val flows30d: List<FlowView>,
    val buffer: BigInteger,
    val gas: GasView? = null,
) {
    /** Cash C: stable wallet balances normalised to the treasury unit. */
    val cash: BigInteger
        get() = balances.filter { it.isStable }.fold(BigInteger.ZERO) { a, b -> a + Raw.rescale(b.raw, b.decimals, unit.decimals) }

    /** Deployed V: stable lending principal normalised to the unit (aToken balance includes accrued interest). */
    val deployed: BigInteger
        get() = lending.filter { it.isStable }.fold(BigInteger.ZERO) { a, l -> a + Raw.rescale(l.supplied, l.decimals, unit.decimals) }

    val reserved: BigInteger
        get() = obligations.fold(BigInteger.ZERO) { a, o -> a + Raw.rescale(o.amount, o.decimals, unit.decimals) }

    val buckets: Buckets get() = BucketMath.compute(cash, reserved, buffer, deployed)
}

/**
 * READ-ONLY window onto the data. The Accountant agent is constructed with this interface and
 * nothing else that can read the database, so it cannot write financial state by construction.
 */
interface AccountantReadModel {
    suspend fun snapshot(now: Long): PortfolioSnapshot?

    /** Literal private strings (addresses, hashes, counterparties, memos) for the egress deny-list. */
    suspend fun privateStrings(): Set<String>
}
