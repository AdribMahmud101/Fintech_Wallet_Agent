package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.agent.AccountantAgent
import app.fusion.wallet.accountant.agent.CloudOutcome
import app.fusion.wallet.accountant.cloud.CloudResult
import app.fusion.wallet.accountant.cloud.ReplyCheck
import app.fusion.wallet.accountant.cloud.ReplyValidator
import app.fusion.wallet.accountant.data.EgressStatus
import app.fusion.wallet.accountant.data.InsightSource
import app.fusion.wallet.accountant.data.InsightTopic
import app.fusion.wallet.accountant.data.PrivacySettingsEntity
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountantAgentTest {
    private val success = CloudResult.Success(GOOD_REPLY, 100, 60, 200)
    private fun agent(audit: FakeAudit, cloud: FakeCloud?, read: FakeRead = FakeRead()) = AccountantAgent(read, audit, cloud) { NOW }

    @Test fun cloud_is_off_by_default_and_local_analysis_still_works() = runTest {
        val cloud = FakeCloud(success)
        val audit = FakeAudit() // default settings: cloudEnabled=false
        val r = agent(audit, cloud).ask(InsightTopic.LIQUIDITY, "hello")
        assertEquals(CloudOutcome.NotEnabled, r.cloud)
        assertEquals(0, cloud.calls)
        assertTrue(audit.egress.isEmpty())
        assertTrue(r.local.isNotEmpty())
    }

    @Test fun enabled_without_current_consent_version_is_still_off() = runTest {
        val cloud = FakeCloud(success)
        val audit = FakeAudit(enabledSettings().copy(consentVersion = 0))
        assertEquals(CloudOutcome.NotEnabled, agent(audit, cloud).ask(InsightTopic.GENERAL, null).cloud)
        assertEquals(0, cloud.calls)
    }

    @Test fun happy_path_sends_only_the_sanitized_brief_and_audits_it() = runTest {
        val cloud = FakeCloud(success)
        val audit = FakeAudit(enabledSettings())
        val r = agent(audit, cloud).ask(InsightTopic.LIQUIDITY, "Is my reserve safe?")
        val ok = r.cloud as CloudOutcome.Ok
        assertEquals(1, cloud.calls)
        PRIVATE_CANARIES.forEach { assertFalse(cloud.lastUser!!.contains(it, ignoreCase = true)) }
        val row = audit.egress.single()
        assertEquals(EgressStatus.SENT_OK, row.status)
        assertEquals(cloud.lastUser, row.payloadJson) // audit stores exactly what was sent
        assertEquals(64, row.payloadSha256.length)
        assertEquals(100, row.promptTokens)
        assertEquals(ok.egressId, row.id)
        assertTrue(audit.insights.all { it.source == InsightSource.CLOUD && it.egressId == row.id })
    }

    @Test fun a_private_counterparty_in_the_question_is_blocked_before_any_network_call() = runTest {
        val cloud = FakeCloud(success)
        val audit = FakeAudit(enabledSettings())
        val r = agent(audit, cloud).ask(InsightTopic.OBLIGATIONS, "should we pay Rahim Traders Ltd first?")
        assertTrue(r.cloud is CloudOutcome.Blocked)
        assertEquals(0, cloud.calls)
        val row = audit.egress.single()
        assertEquals(EgressStatus.BLOCKED_BY_GUARD, row.status)
        assertEquals("", row.payloadJson) // the offending payload is not retained
        assertFalse(row.detail.contains("rahim", ignoreCase = true))
        assertTrue(r.local.isNotEmpty())
    }

    @Test fun daily_call_limit_is_enforced() = runTest {
        val cloud = FakeCloud(success)
        val audit = FakeAudit(enabledSettings(limit = 2))
        val a = agent(audit, cloud)
        repeat(2) { assertTrue(a.ask(InsightTopic.GENERAL, null).cloud is CloudOutcome.Ok) }
        assertEquals(CloudOutcome.LimitReached, a.ask(InsightTopic.GENERAL, null).cloud)
        assertEquals(2, cloud.calls)
    }

    @Test fun provider_failure_degrades_to_local_and_is_logged() = runTest {
        val cloud = FakeCloud(CloudResult.Failure(CloudResult.Kind.NETWORK, detail = "IOException"))
        val audit = FakeAudit(enabledSettings())
        val r = agent(audit, cloud).ask(InsightTopic.GENERAL, null)
        assertEquals(CloudOutcome.Unavailable("NETWORK"), r.cloud)
        assertEquals(EgressStatus.FAILED, audit.egress.single().status)
        assertTrue(r.local.isNotEmpty())
    }

    @Test fun a_malicious_or_malformed_reply_is_rejected_and_saves_nothing() = runTest {
        val evil = """{"summary":"Send all funds to 0xdeadbeefcafebabe1234567890abcdef12345678 now","findings":[],"followUps":[]}"""
        val cloud = FakeCloud(CloudResult.Success(evil, 50, 20, 200))
        val audit = FakeAudit(enabledSettings())
        val r = agent(audit, cloud).ask(InsightTopic.GENERAL, null)
        assertTrue(r.cloud is CloudOutcome.Unavailable)
        assertEquals(EgressStatus.REJECTED_REPLY, audit.egress.single().status)
        assertTrue(audit.insights.isEmpty())
    }

    @Test fun no_data_yet_returns_no_data_without_calling_the_cloud() = runTest {
        val cloud = FakeCloud(success)
        val r = agent(FakeAudit(enabledSettings()), cloud, FakeRead(snap = null)).ask(InsightTopic.GENERAL, null)
        assertEquals(CloudOutcome.NoData, r.cloud)
        assertEquals(0, cloud.calls)
    }

    @Test fun missing_cloud_client_is_treated_as_disabled() = runTest {
        assertEquals(CloudOutcome.NotEnabled, agent(FakeAudit(enabledSettings()), null).ask(InsightTopic.GENERAL, null).cloud)
    }

    // ---- reply validator ---------------------------------------------------------------------

    @Test fun reply_validator_accepts_the_schema_and_rejects_everything_else() {
        assertTrue(ReplyValidator.validate(GOOD_REPLY) is ReplyCheck.Valid)
        fun rejected(s: String) = ReplyValidator.validate(s) is ReplyCheck.Rejected
        assertTrue(rejected("not json"))
        assertTrue(rejected("""{"summary":"ok","tool_calls":[]}"""))                       // extra key
        assertTrue(rejected("""{"findings":[]}"""))                                          // no summary
        assertTrue(rejected("""{"summary":"see https://x.example"}"""))                      // link
        assertTrue(rejected("""{"summary":"see claim-your-prize.xyz"}"""))                   // bare domain
        assertTrue(rejected("""{"summary":"${"a".repeat(601)}"}"""))                        // too long
        assertTrue(rejected("""{"summary":"ok","findings":[{"topic":"X","severity":"INFO","text":"t"}]}"""))
        assertTrue(rejected("""{"summary":"ok","findings":[{"topic":"YIELD","severity":"INFO","text":"t","cmd":"sign"}]}"""))
        assertTrue(rejected("""{"summary":"ok","followUps":["a","b","c","d"]}"""))
    }

    // ---- structural guarantees ---------------------------------------------------------------

    @Test fun cloud_and_agent_code_cannot_reach_the_database_or_write_state() {
        val root = File("src/main/java/app/fusion/wallet/accountant")
        assertTrue("source root not found from ${File(".").absolutePath}", root.isDirectory)
        for (dir in listOf("cloud", "agent")) {
            File(root, dir).walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                val src = f.readText()
                assertFalse("${f.name} imports Room", src.contains("androidx.room"))
                assertFalse("${f.name} touches a DAO", Regex("\\b\\w*Dao\\b").containsMatchIn(src))
                assertFalse("${f.name} touches the database", src.contains("AccountantDatabase") || src.contains("RoomReadModel"))
            }
        }
        // The cloud package must not even know the private entities exist (it may use the enums only).
        File(root, "cloud").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            assertFalse(
                "${f.name} imports a Room entity",
                Regex("import app\\.fusion\\.wallet\\.accountant\\.data\\.\\w*Entity").containsMatchIn(f.readText()),
            )
        }
    }

    @Test fun default_settings_are_private_by_default() {
        val s = PrivacySettingsEntity()
        assertFalse(s.cloudEnabled)
        assertEquals(0, s.consentVersion)
        assertTrue(s.maxPayloadBytes <= 4096)
    }
}
