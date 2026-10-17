package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.cloud.BriefBuilder
import app.fusion.wallet.accountant.cloud.EgressGuard
import app.fusion.wallet.accountant.cloud.GuardPolicy
import app.fusion.wallet.accountant.cloud.GuardResult
import app.fusion.wallet.accountant.data.InsightTopic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EgressGuardTest {
    private val policy = GuardPolicy(4096, PRIVATE_CANARIES)
    private fun brief(q: String? = null) = BriefBuilder.build(referenceSnapshot(), InsightTopic.LIQUIDITY, q).toJson()
    private fun blocked(json: String, p: GuardPolicy = policy) = (EgressGuard.check(json, p) as? GuardResult.Blocked)?.rules ?: emptyList()

    @Test fun valid_brief_passes() = assertEquals(GuardResult.Ok, EgressGuard.check(brief("How is our runway?"), policy))

    @Test fun unknown_key_anywhere_is_blocked() {
        val j = JSONObject(brief()).put("wallet", "main").toString()
        assertTrue("keys:unknown(root)" in blocked(j))
        val nested = JSONObject(brief()).also { it.getJSONObject("totals").put("note", "x") }.toString()
        assertTrue("keys:unknown(totals)" in blocked(nested))
    }

    @Test fun exact_balances_are_blocked_even_if_the_builder_had_a_bug() {
        val j = JSONObject(brief()).also { it.getJSONObject("totals").put("assets", "5000.123456") }.toString()
        assertTrue(blocked(j).any { it.startsWith("amount:not_coarse") })
        val j2 = JSONObject(brief()).also { it.getJSONObject("totals").put("assets", "10001") }.toString()
        assertTrue(blocked(j2).any { it.startsWith("amount:not_coarse") })
    }

    @Test fun addresses_hashes_urls_emails_and_keys_in_the_question_are_blocked() {
        assertTrue("pattern:eth_address" in blocked(brief("send to 0x9f8e7d6c5b4a3928")))
        assertTrue("pattern:long_hex" in blocked(brief("hash " + "ab".repeat(20))))
        assertTrue("pattern:url" in blocked(brief("see https://evil.example/x")))
        assertTrue("pattern:email" in blocked(brief("mail me a@b.co")))
        assertTrue("pattern:api_key" in blocked(brief("my key sk-abcdefghijklmnop")))
        assertTrue("pattern:api_key" in blocked(brief("Bearer abcdefghijklmnopqrst")))
    }

    @Test fun long_digit_runs_in_free_text_are_blocked_but_ordinary_numbers_pass() {
        assertTrue("question:long_digits" in blocked(brief("acct 123456789012")))
        assertEquals(GuardResult.Ok, EgressGuard.check(brief("What if payroll rises 20% to 4000 next month?"), policy))
    }

    @Test fun seed_phrase_shaped_text_is_blocked_but_a_long_ordinary_question_is_not() {
        val seed = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        assertTrue("pattern:seed_phrase" in blocked(brief(seed)))
        val q = "How much runway do we have if payroll doubles next month and one supplier invoice is paid a week late?"
        assertEquals(GuardResult.Ok, EgressGuard.check(brief(q), policy))
    }

    @Test fun known_private_strings_are_denied_case_insensitively() {
        assertTrue("deny:private_string" in blocked(brief("did we pay rahim traders ltd yet")))
        assertTrue("deny:private_string" in blocked(brief("status of inv-2026-00417")))
    }

    @Test fun size_cap_and_invalid_json() {
        assertTrue("size:over_limit" in blocked(brief(), GuardPolicy(100, PRIVATE_CANARIES)))
        assertEquals(listOf("json:invalid"), blocked("not json"))
        assertTrue(blocked(JSONObject(brief()).put("question", "x".repeat(201)).toString()).contains("field:question_len"))
    }

    @Test fun wrong_schema_version_and_out_of_range_numbers_are_blocked() {
        assertTrue("schema:version" in blocked(JSONObject(brief()).put("schema", "v0").toString()))
        val j = JSONObject(brief()).also { it.getJSONObject("totals").put("freePct", 101) }.toString()
        assertTrue("field:freePct" in blocked(j))
    }

    @Test fun guard_rule_ids_never_echo_the_offending_content() {
        val rules = blocked(brief("0x9f8e7d6c5b4a39281706f5e4d3c2b1a098765432 Rahim Traders Ltd"))
        assertTrue(rules.isNotEmpty())
        rules.forEach { r -> PRIVATE_CANARIES.forEach { assertFalse(r.contains(it, ignoreCase = true)) } }
    }

    @Test fun two_significant_figure_predicate() {
        listOf("0", "5", "12", "120", "1200", "1.2", "0.37", "10000").forEach { assertTrue(it, EgressGuard.isTwoSigFig(it)) }
        listOf("123", "1.23", "5000.1", "1e5", "-5", "", "12a", "1234.5").forEach { assertFalse(it, EgressGuard.isTwoSigFig(it)) }
        assertFalse(EgressGuard.isTwoSigFig(120))
    }
}
