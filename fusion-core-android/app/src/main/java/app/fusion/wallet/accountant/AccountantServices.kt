package app.fusion.wallet.accountant

import android.content.Context
import app.fusion.wallet.BuildConfig
import app.fusion.wallet.accountant.agent.AccountantAgent
import app.fusion.wallet.accountant.cloud.ChainedKeyProvider
import app.fusion.wallet.accountant.cloud.DeepInfraClient
import app.fusion.wallet.accountant.cloud.HttpUrlConnectionTransport
import app.fusion.wallet.accountant.cloud.KeystoreApiKeyStore
import app.fusion.wallet.accountant.data.AccountantDatabase
import app.fusion.wallet.accountant.data.RoomAudit
import app.fusion.wallet.accountant.data.RoomReadModel

/** Process-wide wiring for the Accountant. Held by the Application/ViewModel layer. */
class AccountantServices private constructor(context: Context) {
    val db: AccountantDatabase = AccountantDatabase.get(context)
    val keyStore = KeystoreApiKeyStore(context)
    val readModel = RoomReadModel(db.readDao())
    val audit = RoomAudit(db.sinkDao())

    private val client = DeepInfraClient(HttpUrlConnectionTransport(), ChainedKeyProvider(keyStore, BuildConfig.DEEPINFRA_KEY_DEBUG))

    val agent = AccountantAgent(readModel, audit, client) { System.currentTimeMillis() / 1000 }

    companion object {
        @Volatile private var instance: AccountantServices? = null
        fun get(context: Context): AccountantServices =
            instance ?: synchronized(this) { instance ?: AccountantServices(context.applicationContext).also { instance = it } }
    }
}
