package app.fusion.wallet.advisor

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class AdvisorPiecesTest {
    // ---- key storage ----------------------------------------------------------------------------

    private class MemStore : ByteStore {
        val map = mutableMapOf<String, ByteArray>()
        override fun get(key: String) = map[key]?.copyOf()
        override fun put(values: Map<String, ByteArray>) { values.forEach { (k, v) -> map[k] = v.copyOf() } }
        override fun clear() = map.clear()
        override fun contains(key: String) = key in map
    }

    /** JVM stand-in for the Android Keystore box: same AES-256-GCM transform. */
    private class JvmBox : SecretBox {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv to c.doFinal(plain)
        }
        override fun open(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(ciphertext)
    }

    @Test fun key_round_trips_encrypted_and_never_rests_in_plaintext() {
        val mem = MemStore()
        val store = EncryptedSecretStore(mem, JvmBox())
        assertFalse(store.has()); assertNull(store.read())
        store.save("di-SECRET-0123456789abcdef")
        assertTrue(store.has())
        assertEquals("di-SECRET-0123456789abcdef", store.read())
        val atRest = mem.map.values.joinToString("|") { String(it, Charsets.ISO_8859_1) }
        assertFalse(atRest.contains("di-SECRET"))
        store.clear()
        assertFalse(store.has()); assertNull(store.read())
    }

    @Test fun corrupt_or_foreign_ciphertext_reads_as_no_key() {
        val mem = MemStore()
        EncryptedSecretStore(mem, JvmBox()).save("di-SECRET-0123456789abcdef")
        assertNull(EncryptedSecretStore(mem, JvmBox()).read()) // a different Keystore key (e.g. after reset)
        val store = EncryptedSecretStore(mem, JvmBox())
        store.save("di-SECRET-0123456789abcdef")
        mem.map[EncryptedSecretStore.K_CT]!![0] = (mem.map[EncryptedSecretStore.K_CT]!![0].toInt() xor 1).toByte()
        assertNull(store.read())
    }

    @Test fun api_key_format() {
        assertEquals("abcDEF0123456789xyz", ApiKeyFormat.normalize("  abcDEF0123456789xyz \n"))
        assertEquals("abcDEF0123456789xyz", ApiKeyFormat.normalize("DEEPINFRA_API_KEY=abcDEF0123456789xyz"))
        assertEquals("abcDEF0123456789xyz", ApiKeyFormat.normalize("# deepinfra\nexport DEEPINFRA_API_KEY=\"abcDEF0123456789xyz\"\n"))
        assertNull(ApiKeyFormat.normalize(""))
        assertNull(ApiKeyFormat.normalize("short"))
        assertNull(ApiKeyFormat.normalize("has spaces in the middle of it"))
        assertNull(ApiKeyFormat.normalize("abc\u0000DEF0123456789xyz"))
    }

    // ---- egress -----------------------------------------------------------------------------------

    @Test fun redaction_and_egress_rules() {
        val addr = "0x46b8e40eb9767bd46e03c5ecf398c10e1372251e"
        assertEquals("pay [address] now", AdvisorEgress.redact("pay $addr now"))
        assertEquals("tx [hex]", AdvisorEgress.redact("tx 0x" + "ab".repeat(32)))
        assertEquals("key [hex]", AdvisorEgress.redact("key " + "cd".repeat(32)))
        assertEquals("0xDeadBeef stays", AdvisorEgress.redact("0xDeadBeef stays"))
        assertEquals(EgressCheck.Ok, AdvisorEgress.check("""{"q":"How much surplus do we have this week?"}""", "di-KEY-0123456789abcdef"))
        assertTrue(AdvisorEgress.check("x".repeat(AdvisorConfig.MAX_PAYLOAD_BYTES + 1), null) is EgressCheck.Blocked)
        assertTrue(AdvisorEgress.check("""{"q":"di-KEY-0123456789abcdef"}""", "di-KEY-0123456789abcdef") is EgressCheck.Blocked)
        assertTrue(AdvisorEgress.check("""{"q":"Bearer abcdefghijklmnop"}""", null) is EgressCheck.Blocked)
        assertTrue(AdvisorEgress.check("""{"q":"${"ef".repeat(32)}"}""", null) is EgressCheck.Blocked)
    }

    @Test fun json_keys_alone_do_not_trip_the_seed_phrase_heuristic() {
        val payload = WalletFacts.build(Fx.state(), Fx.NOW)!!.put("question", "How much surplus?").toString()
        assertEquals(EgressCheck.Ok, AdvisorEgress.check(payload, "di-KEY-0123456789abcdef", "How much surplus?"))
    }

    // ---- wallet facts -----------------------------------------------------------------------------

    @Test fun wallet_facts_are_public_and_complete() {
        val json = WalletFacts.build(Fx.state(), Fx.NOW)!!
        val text = json.toString()
        assertFalse("no addresses or hashes", Regex("0x[0-9a-fA-F]{6,}").containsMatchIn(text))
        assertFalse(text.contains("payee-rent"))
        val f = WalletFacts.flatten(json)
        assertEquals("2500", f["facts.cash"]); assertEquals("2342", f["facts.surplus"]); assertEquals("58", f["facts.reserve"])
        assertEquals("2", f["facts.unpaidBillsCount"]); assertEquals("58", f["facts.unpaidBillsTotal"])
        assertEquals("300", f["vaults[0].assets"]); assertEquals("1000", f["vaults[0].exposureLimit"])
        assertEquals("2", f["unpaidBills[0].dueInDays"]); assertEquals("50", f["unpaidBills[0].amount"])
        assertEquals("PAY_OBLIGATION", f["recentActivity[1].kind"])
        // 150 USDC paid in 30 days → 5/day → 2500 / 5 = 500 days.
        assertEquals("500", f["facts.runwayDaysAtRecentPayRate"])
        assertEquals("false", f["facts.stale"])
    }

    @Test fun runway_is_not_measured_without_payments_and_stale_is_reported() {
        val noPays = Fx.state().copy(recentActions = emptyList())
        assertEquals(WalletFacts.NOT_MEASURED, WalletFacts.flatten(WalletFacts.build(noPays, Fx.NOW)!!)["facts.runwayDaysAtRecentPayRate"])
        val old = Fx.state(capturedAt = (Fx.NOW - 600) * 1000)
        assertEquals("true", WalletFacts.flatten(WalletFacts.build(old, Fx.NOW)!!)["facts.stale"])
        assertNull(WalletFacts.build(Fx.state().copy(buckets = null), Fx.NOW))
    }

    // ---- explanation -----------------------------------------------------------------------------

    @Test fun explanation_validator() {
        assertTrue(ExplanationValidator.validate("""{"explanation":"Held: queue busy.","points":["a","b"]}""") is Checked.Valid)
        assertTrue(ExplanationValidator.validate("""{"explanation":"ok","execute":true}""") is Checked.Rejected)
        assertTrue(ExplanationValidator.validate("""{"explanation":"pay 0xDeadBeef00 now"}""") is Checked.Rejected)
        assertTrue(ExplanationValidator.validate("""{"explanation":"see www.evil.io"}""") is Checked.Rejected)
        assertTrue(ExplanationValidator.validate("""{"explanation":"${"x".repeat(701)}"}""") is Checked.Rejected)
        assertTrue(ExplanationValidator.validate("""{"explanation":"ok","points":["1","2","3","4","5"]}""") is Checked.Rejected)
        assertTrue(ExplanationValidator.validate("""{"points":[]}""") is Checked.Rejected)
        assertTrue(ExplanationValidator.validate("plain text") is Checked.Rejected)
        val v = ExplanationValidator.validate(JSONObject().put("explanation", "Held.\u0007 Bell").toString()) as Checked.Valid
        assertEquals("Held. Bell", v.value.text)
    }

    @Test fun decision_facts_json_caps_lists() {
        val many = DecisionFacts("A", "Invest", "R", "LIVE", "EXECUTED",
            (1..20).map { DecisionFacts.Candidate("$it", "t", "r", null) }, (1..50).map { DecisionFacts.Check("c$it", "PASS", "") })
        val o = JSONObject(many.toJson())
        assertEquals(DecisionFacts.MAX_CANDIDATES, o.getJSONArray("candidates").length())
        assertEquals(DecisionFacts.MAX_CHECKS, o.getJSONArray("checks").length())
        assertEquals("none", o.getJSONArray("candidates").getJSONObject(0).getString("amountUsdc"))
        assertArrayEquals(arrayOf("12.5", "0", "1"), arrayOf(usdc(java.math.BigInteger("12500000")), usdc(java.math.BigInteger.ZERO), usdc(java.math.BigInteger("1000000"))))
    }
}
