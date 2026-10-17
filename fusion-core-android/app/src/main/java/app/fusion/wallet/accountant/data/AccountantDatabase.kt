package app.fusion.wallet.accountant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        AssetEntity::class,
        BalanceSnapshotEntity::class,
        LendingPositionEntity::class,
        CategoryEntity::class,
        ObligationEntity::class,
        LedgerEntryEntity::class,
        BucketSnapshotEntity::class,
        InsightEntity::class,
        EgressLogEntity::class,
        PrivacySettingsEntity::class,
        MandateEntity::class,
        AgentDecisionEntity::class,
        ArenaResultEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AccountantDatabase : RoomDatabase() {
    abstract fun writeDao(): PortfolioWriteDao
    abstract fun readDao(): PortfolioReadDao
    abstract fun sinkDao(): AccountantSinkDao

    companion object {
        @Volatile private var instance: AccountantDatabase? = null

        fun get(context: Context): AccountantDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AccountantDatabase::class.java,
                    "accountant.db",
                )
                    // No destructive fallback: a corrupt/future schema must fail loudly, never
                    // silently reset financial history (spec: fail closed, never restore zero).
                    .build()
                    .also { instance = it }
            }
    }
}
