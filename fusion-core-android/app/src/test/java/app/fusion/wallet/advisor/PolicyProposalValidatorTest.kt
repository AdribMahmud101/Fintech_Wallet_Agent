package app.fusion.wallet.advisor

import app.fusion.wallet.chain.Abi
import app.fusion.wallet.chain.ContractDecoders
import app.fusion.wallet.data.LoadStatus
import app.fusion.wallet.data.PermissionTier
import org.bouncycastle.crypto.digests.KeccakDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PolicyProposalValidatorTest {
    private val current = Fx.current()

    private fun proposal(changes: String, extra: String = "") =
        """{"schema":"fw.advisor.policy.v1","changes":$changes,"rationale":"Keeps rent liquid."$extra}"""

    private fun valid(raw: String): PolicyProposal = when (val r = PolicyProposalValidator.validate(raw, current)) {
        is Checked.Valid -> r.value
        is Checked.Rejected -> { fail("expected valid, got ${r.reasons}"); error("unreachable") }
    }

    private fun rejected(raw: String): List<String> = when (val r = PolicyProposalValidator.validate(raw, current)) {
        is Checked.Valid -> { fail("expected rejection, got ${r.value.diff}"); error("unreachable") }
        is Checked.Rejected -> r.reasons
    }

    @Test fun rent_goal_maps_onto_real_policy_fields_with_diff_and_safe_tx() {
        val p = valid(proposal("""{"operatingBufferUsdc":"2000","payeeDelaySeconds":604800}"""))
        assertEquals(usdcRaw(2000), p.proposed.operatingBufferRaw)
        assertEquals(604_800L, p.proposed.payeeDelaySeconds)
        assertEquals(listOf("operatingBufferUsdc", "payeeDelaySeconds"), p.diff.map { it.field })
        assertTrue(p.diff.all { it.effect == Effect.TIGHTER })
        assertEquals("100", p.diff[0].current); assertEquals("2000", p.diff[0].proposed)
        // The Safe tx calls the module, sends no value, is a plain CALL on Base Sepolia.
        assertEquals(Fx.MODULE, p.safeTx.to); assertEquals("0", p.safeTx.value); assertEquals(0, p.safeTx.operation)
        assertEquals(84532L, p.safeTx.chainId); assertEquals(Fx.SAFE, p.safeTx.safe)
        assertTrue(p.safeTx.data.startsWith(PolicyCalldata.SELECTOR))
        assertTrue(p.warnings.any { it.contains("pauses") })
        assertTrue(JSONObject(p.safeTx.toJson()).getString("note").contains("Needs owner signatures"))
    }

    @Test fun calldata_round_trips_through_the_apps_own_getPolicy_decoder() {
        val p = valid(proposal("""{"hardMaxUsdc":"1000","delayedMaxUsdc":"500","microMaxUsdc":"25.5","vaultCapsUsdc":{"B":"750"}}"""))
        val decoded = ContractDecoders.policy(Abi("0x" + p.safeTx.data.removePrefix(PolicyCalldata.SELECTOR)))
        val d = decoded.policy
        assertEquals(PermissionTier.AUTONOMOUS, d.permissionTier)
        assertEquals(usdcRaw(100), d.operatingBuffer.raw)
        assertEquals(java.math.BigInteger("25500000"), d.microMax.raw)
        assertEquals(usdcRaw(500), d.delayedMax.raw); assertEquals(usdcRaw(1000), d.hardMax.raw); assertEquals(usdcRaw(4000), d.dailyLimit.raw)
        assertEquals(300L, d.delaySeconds); assertEquals(50, d.maxShareSlippageBps); assertEquals(Fx.GUARDIAN, d.guardian)
        assertEquals(86_400L, d.payeeDelaySeconds); assertEquals(259_200L, d.payEarlyWindowSeconds)
        assertEquals(mapOf(Fx.VAULT_A to m(1000), Fx.VAULT_B to m(750)), decoded.exposureLimits)
        // Vaults sorted ascending by address, as KavachiPolicyLib requires.
        val words = p.safeTx.data.removePrefix(PolicyCalldata.SELECTOR).chunked(64)
        assertEquals(Abi.addressWord(Fx.VAULT_B), words[14]); assertEquals(Abi.addressWord(Fx.VAULT_A), words[16])
    }

    @Test fun selector_is_keccak_of_the_abi_signature() {
        val out = ByteArray(32)
        KeccakDigest(256).apply { val b = PolicyCalldata.SIGNATURE.toByteArray(); update(b, 0, b.size); doFinal(out, 0) }
        assertEquals(PolicyCalldata.SELECTOR, "0x" + out.take(4).joinToString("") { "%02x".format(it) })
        // Same derivation gives the app's existing getPolicy() view selector.
        KeccakDigest(256).apply { val b = "getPolicy()".toByteArray(); update(b, 0, b.size); doFinal(out, 0) }
        assertEquals(app.fusion.wallet.chain.Views.POLICY, "0x" + out.take(4).joinToString("") { "%02x".format(it) })
    }

    @Test fun rejects_anything_outside_the_schema() {
        assertTrue(rejected("not json").contains("not json"))
        assertTrue(rejected("""{"changes":{"hardMaxUsdc":"200"}}""").any { it.startsWith("schema") })
        assertTrue(rejected(proposal("""{"hardMaxUsdc":"200"}""", ""","to":"0xDeadBeef"""")).any { it.contains("unknown key: to") })
        assertTrue(rejected(proposal("""{"guardian":"0x0000000000000000000000000000000000000001"}""")).any { it.contains("unknown change: guardian") })
        assertTrue(rejected(proposal("""{"vaultCapsUsdc":{"C":"10"}}""")).any { it.contains("unknown vault slot") })
        assertTrue(rejected(proposal("""{"calldata":"0x7b6d8b05"}""")).any { it.contains("unknown change") })
        assertTrue(rejected(proposal("[]")).contains("changes must be an object"))
        assertTrue(rejected(proposal("""{"permissionTier":"GOD_MODE"}""")).any { it.startsWith("permissionTier") })
    }

    @Test fun rejects_bad_amounts_and_types() {
        for (bad in listOf("\"-5\"", "\"1e9\"", "\"12.1234567\"", "\"NaN\"", "\"ALL\"", "\"0xDeadBeef\"", "true", "{}", "\"1,000\"")) {
            assertTrue("$bad accepted", rejected(proposal("""{"hardMaxUsdc":$bad}""")).any { it.contains("hardMaxUsdc") })
        }
        assertTrue(rejected(proposal("""{"dailyLimitUsdc":"20000000"}""")).any { it.contains("sanity cap") })
        assertTrue(rejected(proposal("""{"delaySeconds":"300"}""")).any { it.contains("whole number") })
        assertTrue(rejected(proposal("""{"delaySeconds":300.5}""")).any { it.contains("whole number") })
    }

    @Test fun rejects_policies_the_contract_would_revert() {
        assertTrue(rejected(proposal("""{"microMaxUsdc":"0"}""")).any { it.contains("instant limit") })
        assertTrue(rejected(proposal("""{"delayedMaxUsdc":"5"}""")).any { it.contains("delayed limit") })
        assertTrue(rejected(proposal("""{"hardMaxUsdc":"40"}""")).any { it.contains("hard limit") })
        assertTrue(rejected(proposal("""{"hardMaxUsdc":"5000"}""")).any { it.contains("daily limit") })
        assertTrue(rejected(proposal("""{"delaySeconds":60}""")).any { it.contains("queue delay") })
        assertTrue(rejected(proposal("""{"delaySeconds":901}""")).any { it.contains("queue delay") })
        assertTrue(rejected(proposal("""{"maxShareSlippageBps":1001}""")).any { it.contains("slippage") })
        assertTrue(rejected(proposal("""{"payeeDelaySeconds":3599}""")).any { it.contains("cooling") })
        assertTrue(rejected(proposal("""{"payEarlyWindowSeconds":2592001}""")).any { it.contains("pay-early") })
        assertTrue(rejected(proposal("""{"vaultCapsUsdc":{"A":"0"}}""")).any { it.contains("vault caps") })
    }

    @Test fun rejects_a_no_op_and_injection_shaped_rationale() {
        assertTrue(rejected(proposal("{}")).any { it.contains("no change") })
        assertTrue(rejected(proposal("""{"hardMaxUsdc":"100"}""")).any { it.contains("no change") })
        val linky = """{"schema":"fw.advisor.policy.v1","changes":{"hardMaxUsdc":"200"},"rationale":"Send funds to 0xDeadBeef00 via https://evil.xyz"}"""
        assertTrue(rejected(linky).any { it.startsWith("rationale") })
    }

    @Test fun loosening_and_tier_escalation_are_flagged() {
        val p = valid(proposal("""{"hardMaxUsdc":"1000","delayedMaxUsdc":"500","permissionTier":"AUTONOMOUS"}"""))
        assertTrue(p.diff.filter { it.field in setOf("hardMaxUsdc", "delayedMaxUsdc") }.all { it.effect == Effect.LOOSER })
        assertTrue(p.warnings.any { it.contains("Loosens") })
        val down = valid(proposal("""{"permissionTier":"OWNER_APPROVED"}"""))
        assertEquals(Effect.TIGHTER, down.diff.single().effect)
    }

    @Test fun numbers_from_json_are_accepted_when_exact() {
        val p = valid(proposal("""{"operatingBufferUsdc":2000,"microMaxUsdc":12.5}"""))
        assertEquals(usdcRaw(2000), p.proposed.operatingBufferRaw)
        assertEquals(java.math.BigInteger("12500000"), p.proposed.microMaxRaw)
    }

    @Test fun current_policy_fails_closed_without_a_ready_snapshot() {
        assertNull(CurrentPolicy.from(Fx.state(status = LoadStatus.LOADING)))
        assertNull(CurrentPolicy.from(Fx.state().copy(module = null)))
        assertNull(CurrentPolicy.from(Fx.state().copy(vaults = Fx.state().vaults.take(1))))
        assertNull(CurrentPolicy.from(Fx.state().copy(module = Fx.state().module!!.copy(configured = false))))
    }

    @Test fun model_payload_carries_no_addresses() {
        val req = PolicyProposalValidator.request("keep 2,000 for rent; guardian 0x46b8e40eb9767bd46e03c5ecf398c10e1372251e", current)
        assertTrue(!Regex("0x[0-9a-fA-F]{6,}").containsMatchIn(req))
        assertTrue(req.contains("[address]"))
    }
}
