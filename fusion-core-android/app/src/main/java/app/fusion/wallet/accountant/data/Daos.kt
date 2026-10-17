package app.fusion.wallet.accountant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A balance joined with its asset, latest block per asset. */
data class LatestBalance(
    val assetId: Long,
    val symbol: String,
    val decimals: Int,
    val kind: AssetKind,
    val isStable: Boolean,
    val balanceRaw: String,
    val blockNumber: Long,
)

/** A lending position joined with its underlying + position assets, latest block per position. */
data class LatestLending(
    val positionId: Long,
    val protocol: LendingProtocol,
    val underlyingSymbol: String,
    val underlyingDecimals: Int,
    val underlyingIsStable: Boolean,
    val positionSymbol: String,
    val suppliedRaw: String,
    val borrowedRaw: String,
    val supplyApyBps: Int,
    val blockNumber: Long,
)

data class ObligationRow(
    val amountRaw: String,
    val dueAt: Long,
    val status: ObligationStatus,
    val decimals: Int,
    val categoryKind: FlowCategory?,
)

data class LedgerRow(
    val kind: LedgerKind,
    val direction: Direction,
    val amountRaw: String,
    val decimals: Int,
    val categoryKind: FlowCategory?,
    val occurredAt: Long,
    val status: TxStatus,
)

/** Everything the app WRITES: sync, seeding, owner actions. The Accountant never receives this. */
@Dao
interface PortfolioWriteDao {
    @Insert suspend fun insertAccount(a: AccountEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAsset(a: AssetEntity): Long
    @Insert suspend fun insertBalance(b: BalanceSnapshotEntity): Long
    @Insert suspend fun insertLending(p: LendingPositionEntity): Long
    @Insert suspend fun insertCategory(c: CategoryEntity): Long
    @Insert suspend fun insertObligation(o: ObligationEntity): Long
    @Update suspend fun updateObligation(o: ObligationEntity)
    @Insert suspend fun insertLedger(l: LedgerEntryEntity): Long
    @Insert suspend fun insertBuckets(b: BucketSnapshotEntity): Long
    @Query("DELETE FROM account") suspend fun wipeAll()
}

/** Read-only queries behind the Accountant's `AccountantReadModel`. */
@Dao
interface PortfolioReadDao {
    @Query("SELECT * FROM account ORDER BY id LIMIT 1")
    suspend fun primaryAccount(): AccountEntity?

    @Query("SELECT * FROM account ORDER BY id LIMIT 1")
    fun observePrimaryAccount(): Flow<AccountEntity?>

    @Query(
        """
        SELECT a.id AS assetId, a.symbol AS symbol, a.decimals AS decimals, a.kind AS kind,
               a.isStable AS isStable, b.balanceRaw AS balanceRaw, b.blockNumber AS blockNumber
        FROM balance_snapshot b
        JOIN asset a ON a.id = b.assetId
        WHERE b.accountId = :accountId
          AND b.blockNumber = (SELECT MAX(b2.blockNumber) FROM balance_snapshot b2
                               WHERE b2.accountId = b.accountId AND b2.assetId = b.assetId)
        GROUP BY b.assetId
        ORDER BY a.symbol
        """,
    )
    suspend fun latestBalances(accountId: Long): List<LatestBalance>

    @Query(
        """
        SELECT p.id AS positionId, p.protocol AS protocol,
               u.symbol AS underlyingSymbol, u.decimals AS underlyingDecimals, u.isStable AS underlyingIsStable,
               t.symbol AS positionSymbol, p.suppliedRaw AS suppliedRaw, p.borrowedRaw AS borrowedRaw,
               p.supplyApyBps AS supplyApyBps, p.blockNumber AS blockNumber
        FROM lending_position p
        JOIN asset u ON u.id = p.underlyingAssetId
        JOIN asset t ON t.id = p.positionAssetId
        WHERE p.accountId = :accountId
          AND p.blockNumber = (SELECT MAX(p2.blockNumber) FROM lending_position p2
                               WHERE p2.accountId = p.accountId AND p2.protocol = p.protocol
                                 AND p2.marketAddress = p.marketAddress AND p2.underlyingAssetId = p.underlyingAssetId)
        GROUP BY p.protocol, p.marketAddress, p.underlyingAssetId
        ORDER BY u.symbol
        """,
    )
    suspend fun latestLending(accountId: Long): List<LatestLending>

    @Query(
        """
        SELECT o.amountRaw AS amountRaw, o.dueAt AS dueAt, o.status AS status, a.decimals AS decimals,
               c.kind AS categoryKind
        FROM obligation o
        JOIN asset a ON a.id = o.assetId
        LEFT JOIN category c ON c.id = o.categoryId
        WHERE o.accountId = :accountId AND o.status = 'ACTIVE'
        ORDER BY o.dueAt
        """,
    )
    suspend fun activeObligations(accountId: Long): List<ObligationRow>

    @Query(
        """
        SELECT l.kind AS kind, l.direction AS direction, l.amountRaw AS amountRaw, a.decimals AS decimals,
               c.kind AS categoryKind, l.occurredAt AS occurredAt, l.status AS status
        FROM ledger_entry l
        JOIN asset a ON a.id = l.assetId
        LEFT JOIN category c ON c.id = l.categoryId
        WHERE l.accountId = :accountId AND l.occurredAt >= :since
          AND l.status IN ('CONFIRMED','RECONCILED')
        ORDER BY l.occurredAt DESC
        """,
    )
    suspend fun confirmedLedgerSince(accountId: Long, since: Long): List<LedgerRow>

    /**
     * Strings that must NEVER appear in an outbound payload (addresses, tx hashes, counterparties,
     * memos, labels). Feeds the egress guard's deny-list as defense in depth.
     */
    @Query("SELECT address FROM account UNION SELECT label FROM account")
    suspend fun privateAccountStrings(): List<String>

    @Query("SELECT counterparty FROM obligation UNION SELECT idHash FROM obligation")
    suspend fun privateObligationStrings(): List<String>

    @Query("SELECT txHash FROM ledger_entry WHERE txHash IS NOT NULL UNION SELECT memo FROM ledger_entry WHERE memo IS NOT NULL")
    suspend fun privateLedgerStrings(): List<String>

    @Query("SELECT address FROM asset WHERE address != 'native' UNION SELECT marketAddress FROM lending_position UNION SELECT label FROM category")
    suspend fun privateChainStrings(): List<String>

    @Query("SELECT * FROM insight ORDER BY createdAt DESC LIMIT :limit")
    fun observeInsights(limit: Int): Flow<List<InsightEntity>>

    @Query("SELECT * FROM egress_log ORDER BY createdAt DESC LIMIT :limit")
    fun observeEgress(limit: Int): Flow<List<EgressLogEntity>>

    @Query("SELECT * FROM bucket_snapshot WHERE accountId = :accountId ORDER BY observedAt DESC LIMIT 1")
    fun observeLatestBuckets(accountId: Long): Flow<BucketSnapshotEntity?>
}

/** The only tables the Accountant itself may append to: its own output and the audit trail. */
@Dao
interface AccountantSinkDao {
    @Insert suspend fun insertInsight(i: InsightEntity): Long
    @Insert suspend fun insertEgress(e: EgressLogEntity): Long
    @Update suspend fun updateEgress(e: EgressLogEntity)

    /** Calls that actually reached the network (success or not). Blocked/limited/disabled rows don't count. */
    @Query("SELECT COUNT(*) FROM egress_log WHERE status IN ('SENT_OK','FAILED','REJECTED_REPLY') AND createdAt >= :since")
    suspend fun callsSince(since: Long): Int

    @Query("SELECT * FROM privacy_settings WHERE id = 1")
    suspend fun settings(): PrivacySettingsEntity?

    @Query("SELECT * FROM privacy_settings WHERE id = 1")
    fun observeSettings(): Flow<PrivacySettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(s: PrivacySettingsEntity)
}
