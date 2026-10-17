package app.fusion.wallet.accountant.data

import app.fusion.wallet.accountant.agent.AccountantAudit

/** Adapts the sink DAO (the only tables the Accountant may write) to [AccountantAudit]. */
class RoomAudit(private val dao: AccountantSinkDao) : AccountantAudit {
    override suspend fun settings(): PrivacySettingsEntity = dao.settings() ?: PrivacySettingsEntity()
    override suspend fun callsSince(since: Long): Int = dao.callsSince(since)
    override suspend fun logEgress(e: EgressLogEntity): Long = dao.insertEgress(e)
    override suspend fun updateEgress(e: EgressLogEntity) = dao.updateEgress(e)
    override suspend fun saveInsight(i: InsightEntity): Long = dao.insertInsight(i)
}
