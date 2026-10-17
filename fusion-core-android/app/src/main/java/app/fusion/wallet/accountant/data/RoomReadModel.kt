package app.fusion.wallet.accountant.data

import app.fusion.wallet.accountant.domain.AccountantReadModel
import app.fusion.wallet.accountant.domain.Aave
import app.fusion.wallet.accountant.domain.GasView
import app.fusion.wallet.accountant.domain.AssetBalanceView
import app.fusion.wallet.accountant.domain.FlowView
import app.fusion.wallet.accountant.domain.LendingView
import app.fusion.wallet.accountant.domain.ObligationView
import app.fusion.wallet.accountant.domain.PortfolioSnapshot
import app.fusion.wallet.accountant.domain.Raw
import app.fusion.wallet.accountant.domain.TreasuryUnit

/** Builds the private [PortfolioSnapshot] from Room using only the read DAO. */
class RoomReadModel(
    private val dao: PortfolioReadDao,
    private val unit: TreasuryUnit = TreasuryUnit("USDC", 6),
) : AccountantReadModel {

    override suspend fun snapshot(now: Long): PortfolioSnapshot? {
        val account = dao.primaryAccount() ?: return null
        val balances = dao.latestBalances(account.id)
            .filter { it.kind != AssetKind.ATOKEN && it.kind != AssetKind.VAULT_SHARE }
            .map { AssetBalanceView(it.symbol, it.decimals, it.isStable, Raw.parse(it.balanceRaw), isNative = it.kind == AssetKind.NATIVE) }
        val lending = dao.latestLending(account.id).map {
            LendingView(
                protocol = it.protocol,
                underlyingSymbol = it.underlyingSymbol,
                positionSymbol = it.positionSymbol,
                decimals = it.underlyingDecimals,
                isStable = it.underlyingIsStable,
                supplied = Raw.parse(it.suppliedRaw),
                borrowed = Raw.parse(it.borrowedRaw),
                supplyAprBps = Aave.aprBps(Raw.parse(it.liquidityRateRay)),
            )
        }
        val obligations = dao.activeObligations(account.id).map {
            ObligationView(Raw.parse(it.amountRaw), it.decimals, it.dueAt, it.categoryKind)
        }
        val since = now - THIRTY_DAYS_SEC
        val flows = dao.confirmedLedgerSince(account.id, since)
            .filter { it.kind == LedgerKind.PAYMENT || it.kind == LedgerKind.INCOME || it.kind == LedgerKind.FEE }
            .map {
                FlowView(
                    category = it.categoryKind ?: FlowCategory.OTHER,
                    isIncome = it.direction == Direction.IN,
                    amount = Raw.parse(it.amountRaw),
                    decimals = it.decimals,
                    at = it.occurredAt,
                )
            }
        return PortfolioSnapshot(
            asOf = now, unit = unit, balances = balances, lending = lending,
            obligations = obligations, flows30d = flows, buffer = Raw.parse(account.operatingBufferRaw),
            gas = balances.firstOrNull { it.isNative }?.let { GasView(it.raw, Raw.parse(account.estFeePerActionWei)) },
        )
    }

    override suspend fun privateStrings(): Set<String> =
        (dao.privateAccountStrings() + dao.privateObligationStrings() + dao.privateLedgerStrings() + dao.privateChainStrings() + dao.privateDecisionStrings())
            .map { it.trim() }
            .filter { it.length >= MIN_DENY_LEN }
            .toSet()

    private companion object {
        const val THIRTY_DAYS_SEC = 30L * 24 * 3600
        const val MIN_DENY_LEN = 4
    }
}
