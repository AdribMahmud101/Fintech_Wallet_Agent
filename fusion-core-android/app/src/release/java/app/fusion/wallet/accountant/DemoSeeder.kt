package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.data.AccountantDatabase

/** Release builds carry no fixture data (spec: demo data must not be linked into a TESTNET release). */
@Suppress("UNUSED_PARAMETER")
object DemoSeeder {
    suspend fun seedIfEmpty(db: AccountantDatabase): Boolean = false
}
