package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.data.AccountEntity
import app.fusion.wallet.accountant.data.AccountRole
import app.fusion.wallet.accountant.data.AccountantDatabase
import app.fusion.wallet.accountant.data.AssetEntity
import app.fusion.wallet.accountant.data.AssetKind
import app.fusion.wallet.accountant.data.BalanceSnapshotEntity
import app.fusion.wallet.accountant.data.CategoryEntity
import app.fusion.wallet.accountant.data.Direction
import app.fusion.wallet.accountant.data.FlowCategory
import app.fusion.wallet.accountant.data.LedgerEntryEntity
import app.fusion.wallet.accountant.data.LedgerKind
import app.fusion.wallet.accountant.data.LendingPositionEntity
import app.fusion.wallet.accountant.data.LendingProtocol
import app.fusion.wallet.accountant.data.ObligationEntity
import app.fusion.wallet.accountant.data.ObligationStatus
import app.fusion.wallet.accountant.data.TxStatus

/**
 * DEBUG-ONLY fixture data (the spec forbids fixtures in release builds; a no-op twin lives in the
 * release source set). Mirrors the spec's reference state: C=5,000  R=1,500  B=500  V=3,000+2,000.
 * Addresses and names are obviously synthetic.
 */
object DemoSeeder {
    suspend fun seedIfEmpty(db: AccountantDatabase) {
        val w = db.writeDao()
        if (db.readDao().primaryAccount() != null) return
        val now = System.currentTimeMillis() / 1000
        val day = 86_400L
        fun usd(whole: Long) = (whole * 1_000_000L).toString()
        val chain = 84532L

        val acct = w.insertAccount(
            AccountEntity(
                label = "Demo treasury", role = AccountRole.OWNER_TREASURY, chainId = chain,
                address = "0x00000000000000000000000000000000000d3a01", operatingBufferRaw = usd(500), createdAt = now,
            ),
        )
        val demoUsd = w.insertAsset(AssetEntity(chainId = chain, address = "0x00000000000000000000000000000000000de301", symbol = "DemoUSD", decimals = 6, kind = AssetKind.ERC20, isStable = true))
        val eth = w.insertAsset(AssetEntity(chainId = chain, address = "native", symbol = "ETH", decimals = 18, kind = AssetKind.NATIVE, isStable = false))
        val aToken = w.insertAsset(AssetEntity(chainId = chain, address = "0x00000000000000000000000000000000000a0001", symbol = "aDemoUSD", decimals = 6, kind = AssetKind.ATOKEN, isStable = true, underlyingAssetId = demoUsd))
        val vShare = w.insertAsset(AssetEntity(chainId = chain, address = "0x00000000000000000000000000000000000b0002", symbol = "vDemoUSD", decimals = 6, kind = AssetKind.VAULT_SHARE, isStable = true, underlyingAssetId = demoUsd))

        w.insertBalance(BalanceSnapshotEntity(accountId = acct, assetId = demoUsd, blockNumber = 1000, blockHash = "0x" + "11".repeat(32), balanceRaw = usd(5_000), observedAt = now))
        w.insertBalance(BalanceSnapshotEntity(accountId = acct, assetId = eth, blockNumber = 1000, blockHash = "0x" + "11".repeat(32), balanceRaw = "42000000000000000", observedAt = now))

        w.insertLending(LendingPositionEntity(accountId = acct, protocol = LendingProtocol.AAVE_V3, marketAddress = "0x00000000000000000000000000000000000a4e01", underlyingAssetId = demoUsd, positionAssetId = aToken, suppliedRaw = usd(3_000), scaledBalanceRaw = "2985000000", liquidityIndexRay = "1005025125628140703517587", supplyApyBps = 420, blockNumber = 1000, observedAt = now))
        w.insertLending(LendingPositionEntity(accountId = acct, protocol = LendingProtocol.ERC4626_DEMO, marketAddress = "0x00000000000000000000000000000000000b4e02", underlyingAssetId = demoUsd, positionAssetId = vShare, suppliedRaw = usd(2_000), supplyApyBps = 310, blockNumber = 1000, observedAt = now))

        val payroll = w.insertCategory(CategoryEntity(label = "Payroll (demo)", kind = FlowCategory.PAYROLL))
        val suppliers = w.insertCategory(CategoryEntity(label = "Suppliers (demo)", kind = FlowCategory.SUPPLIERS))
        val yieldCat = w.insertCategory(CategoryEntity(label = "Yield (demo)", kind = FlowCategory.YIELD))

        w.insertObligation(ObligationEntity(accountId = acct, idHash = "0x" + "a1".repeat(32), counterparty = "Demo payroll run", assetId = demoUsd, amountRaw = usd(1_000), dueAt = now + 3 * day, categoryId = payroll, status = ObligationStatus.ACTIVE, createdAt = now, updatedAt = now))
        w.insertObligation(ObligationEntity(accountId = acct, idHash = "0x" + "b2".repeat(32), counterparty = "Demo supplier invoice", assetId = demoUsd, amountRaw = usd(500), dueAt = now + 20 * day, categoryId = suppliers, status = ObligationStatus.ACTIVE, createdAt = now, updatedAt = now))

        fun ledger(kind: LedgerKind, dir: Direction, whole: Long, cat: Long?, daysAgo: Long, memo: String) =
            LedgerEntryEntity(accountId = acct, assetId = demoUsd, kind = kind, direction = dir, amountRaw = usd(whole), categoryId = cat, status = TxStatus.RECONCILED, txHash = null, memo = memo, blockNumber = 900, occurredAt = now - daysAgo * day)
        w.insertLedger(ledger(LedgerKind.PAYMENT, Direction.OUT, 3_200, payroll, 5, "demo payroll"))
        w.insertLedger(ledger(LedgerKind.PAYMENT, Direction.OUT, 3_100, payroll, 20, "demo payroll"))
        w.insertLedger(ledger(LedgerKind.PAYMENT, Direction.OUT, 800, suppliers, 9, "demo supplier"))
        w.insertLedger(ledger(LedgerKind.INCOME, Direction.IN, 41, yieldCat, 2, "demo yield"))
    }
}
