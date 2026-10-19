package app.fusion.wallet.chain

import app.fusion.wallet.data.*
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AbiAndBucketsTest {
    private fun n(value: String) = BigInteger(value)
    private val capture = JSONObject(resource("live-snapshot.json"))
    private val manifest = JSONObject(resource("deployment.json"))
    private fun abi(selector: String): Abi {
        val calls = capture.getJSONArray("calls")
        val result = (0 until calls.length()).map { calls.getJSONObject(it) }.first {
            it.getString("method") == "eth_call" && it.getJSONArray("params").getJSONObject(0).getString("data") == selector
        }
        return Abi(result.getString("result"))
    }

    @Test fun decodesDeployedPolicyDynamicTupleAndVaultLimits() {
        val decoded = ContractDecoders.policy(abi(Views.POLICY))
        assertEquals(PermissionTier.AUTONOMOUS, decoded.policy.permissionTier)
        assertEquals("500000000", decoded.policy.operatingBuffer.rawUnits)
        assertEquals("4000000000", decoded.policy.dailyLimit.rawUnits)
        assertEquals(manifest.getJSONObject("roles").getString("guardian").lowercase(), decoded.policy.guardian)
        assertEquals(manifest.getLong("payeeDelaySeconds"), decoded.policy.payeeDelaySeconds)
        assertEquals(setOf(manifest.getString("vaultA").lowercase(), manifest.getString("vaultB").lowercase()), decoded.exposureLimits.keys)
    }

    @Test fun decodesAllModuleStateWordsAgainstManifestHandoff() {
        val raw = abi(Views.STATE).requireSize(17)
        val expected = manifest.getJSONObject("handoffSnapshot").getJSONObject("moduleState")
        val fields = listOf("policyEpoch" to 2, "reservedRaw" to 6, "obligationCount" to 7, "actionNonce" to 8,
            "dailyDayIndex" to 9, "dailyUsedRaw" to 10, "sessionStartEpoch" to 12, "sessionExpiresAt" to 14,
            "sessionLimitRaw" to 15, "sessionUsedRaw" to 16)
        fields.forEach { (name, index) -> assertEquals(name, expected.getString(name), raw.uint(index).toString()) }
        assertEquals(expected.getBoolean("configured"), raw.bool(0))
        assertEquals(expected.getBoolean("paused"), raw.bool(1))
        listOf("policyHash" to 3, "mandateHash" to 4, "obligationsRoot" to 5, "sessionId" to 11).forEach { (name, index) ->
            assertEquals(name, expected.getString(name), raw.bytes32(index))
        }
        assertEquals(expected.getString("sessionKey"), raw.address(13))
        val policy = ContractDecoders.policy(abi(Views.POLICY)).policy
        val state = ContractDecoders.module(raw, manifest.getString("module"), policy, capture.getJSONObject("summary").getLong("timestamp"))
        assertTrue(state.paused)
        assertFalse(state.sessionActive)
        assertEquals("1907000000", state.dailyRemaining.rawUnits)
        val nextDay = (raw.long(9) + 1) * 86400
        assertEquals(Money.ZERO, ContractDecoders.module(raw, "module", policy, nextDay).dailyUsed)
        assertEquals(policy.dailyLimit, ContractDecoders.module(raw, "module", policy, nextDay).dailyRemaining)
    }

    @Test fun rawExecutedEventMatchesVerifiedEvidence() {
        val rows = JSONObject(resource("evidence.json")).getJSONArray("rows")
        val calls = capture.getJSONArray("calls")
        val logs = (0 until calls.length()).map { calls.getJSONObject(it) }.filter { it.getString("method") == "eth_getLogs" }
            .flatMap { call -> val page = call.getJSONArray("result"); List(page.length()) { ChainLog.fromJson(page.getJSONObject(it)) } }
        var checked = 0
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val event = row.optJSONObject("expectedEvent") ?: continue
            if (event.getString("name") != "ActionExecuted") continue
            val log = logs.single { it.txHash == row.getString("txHash") && it.logIndex == event.getLong("logIndex") }
            val action = ModuleEvents.action(log, 123L, null)
            val fields = event.getJSONObject("fields")
            assertEquals(fields.getString("assets"), action.amount.rawUnits)
            assertEquals(fields.getInt("kind"), action.kind.ordinal)
            assertEquals(row.getString("decisionHash"), action.decisionHash)
            assertEquals(fields.getString("actionDigest"), action.id)
            assertEquals(row.getLong("block"), action.blockNumber)
            checked++
        }
        assertTrue("Expected verified execution evidence", checked >= 1)
    }

    @Test fun reserveMathMatchesSection79Reference() {
        val buckets = BucketMath.compute(n("5000000000"), n("1500000000"), n("500000000"), n("5000000000"))
        assertEquals("1500.000000", buckets.reserve.display)
        assertEquals("3500.000000", buckets.free.display)
        assertEquals("500.000000", buckets.buffer.display)
        assertEquals("3000.000000", buckets.surplus.display)
        assertEquals("10000.000000", buckets.total.display)
        assertEquals(Money.ZERO, buckets.shortfall)
    }

    @Test fun handoffBucketsAndShortfallRemainExact() {
        val handoff = manifest.getJSONObject("handoffSnapshot")
        val buckets = BucketMath.compute(n(handoff.getString("cashRaw")), n("50000000"), n("500000000"), BigInteger.ZERO)
        assertEquals("9467.001235", buckets.total.display)
        assertEquals("8917.001235", buckets.surplus.display)
        val shortfall = BucketMath.compute(n("1000000"), n("1500000"), n("500000"), n("1234567"))
        assertEquals("1.000000", shortfall.reserve.display)
        assertEquals("0.500000", shortfall.shortfall.display)
        assertEquals(Money.ZERO, shortfall.free)
        assertEquals(Money.ZERO, shortfall.buffer)
        assertEquals(Money.ZERO, shortfall.surplus)
        assertEquals("2.234567", shortfall.total.display)
    }

    @Test fun payingBillsLeavesFreeCashUnchanged() {
        val before = BucketMath.compute(n("5000000000"), n("1500000000"), n("500000000"), BigInteger.ZERO)
        val after = BucketMath.compute(n("4925000000"), n("1425000000"), n("500000000"), BigInteger.ZERO)
        assertEquals(before.free, after.free)
        assertEquals(before.surplus, after.surplus)
    }

    @Test fun maximumUint256AndSixDecimalDustNeverRound() {
        val maximum = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        assertEquals(maximum, Abi("0x" + Abi.uintWord(maximum)).uint(0))
        assertEquals("0.000001", Money(BigInteger.ONE).display)
        assertEquals(maximum.toString(), Money(maximum).rawUnits)
    }

    @Test fun queueReadinessAndExpiryUseChainTimeBoundaries() {
        val words = MutableList(19) { Abi.uintWord(BigInteger.ZERO) }
        fun set(index: Int, value: Long) { words[index] = Abi.uintWord(BigInteger.valueOf(value)) }
        set(0, 1); set(1, 123); set(3, 100); set(4, 200); set(5, 3); set(8, 75000000); set(15, 9); set(18, 456)
        words[13] = Abi.addressWord(manifest.getJSONObject("roles").getString("owner"))
        val queue = Abi("0x" + words.joinToString(""))
        assertEquals(QueueStatus.WAITING, ContractDecoders.queue(queue, 99).single().status)
        assertEquals(QueueStatus.READY, ContractDecoders.queue(queue, 100).single().status)
        assertEquals(QueueStatus.READY, ContractDecoders.queue(queue, 200).single().status)
        assertEquals(QueueStatus.EXPIRED, ContractDecoders.queue(queue, 201).single().status)
        val action = ContractDecoders.queue(queue, 100).single()
        assertEquals(ActionKind.PAY_OBLIGATION, action.kind)
        assertEquals("75.000000", action.amount.display)
        assertEquals(n("9"), action.expectedNonce)
        assertEquals(manifest.getJSONObject("roles").getString("owner").lowercase(), action.payee)
    }

    @Test fun payeeCoolingEndsExactlyAtActivationAndCancellationWins() {
        val address = manifest.getJSONObject("roles").getString("owner")
        val base = "0x" + Abi.addressWord(address) + "00".repeat(32) + Abi.uintWord(n("100"))
        val value = Abi(base + Abi.uintWord(BigInteger.ZERO))
        assertEquals(PayeeStatus.COOLING, ContractDecoders.payee(value, "id", 99, false).status)
        assertEquals(PayeeStatus.ACTIVE, ContractDecoders.payee(value, "id", 100, false).status)
        assertTrue(ContractDecoders.payee(value, "id", 100, true).sanctioned)
        val cancelled = Abi(base + Abi.uintWord(BigInteger.ONE))
        assertEquals(PayeeStatus.CANCELLED, ContractDecoders.payee(cancelled, "id", 99, false).status)
    }

    @Test fun sessionExpiresAtBoundaryAndPauseDisablesIt() {
        val original = abi(Views.STATE)
        val words = MutableList(original.size) { original.bytes32(it).drop(2) }
        words[1] = Abi.uintWord(BigInteger.ZERO)
        words[11] = Abi.uintWord(BigInteger.ONE)
        words[13] = Abi.addressWord(manifest.getJSONObject("roles").getString("session"))
        words[14] = Abi.uintWord(n("100"))
        val policy = ContractDecoders.policy(abi(Views.POLICY)).policy
        fun module(now: Long) = ContractDecoders.module(Abi("0x" + words.joinToString("")), "module", policy, now)
        assertTrue(module(99).sessionActive)
        assertFalse(module(100).sessionActive)
        words[1] = Abi.uintWord(BigInteger.ONE)
        assertFalse(module(99).sessionActive)
    }

    @Test fun snapshotFreshnessExpiresOnTimeOrObservedBlocksAndNeverExtendsOnRollback() {
        val state = WalletState(status = LoadStatus.READY, blockNumber = 100, capturedAtEpochMillis = 1000, observedHeadBlock = 104)
        assertFalse(state.isStale(30_999))
        assertTrue(state.isStale(31_000))
        assertTrue(state.isStale(2000, 105))
        assertTrue(state.isStale(999))
        assertTrue(state.isStale(2000, 99))
        assertTrue(state.copy(status = LoadStatus.ERROR).isStale(2000))
        assertEquals("DEMO", state.copy(source = DataSource.DEMO).dataLabel)
    }

    @Test fun malformedWordsOffsetsEnumsAndNegativeMoneyAreRejected() {
        val invalid = listOf<() -> Any>(
            { Abi("0x01") }, { Abi("0x" + "g".repeat(64)) }, { Abi("0x").uint(0) },
            { Abi("0x" + Abi.uintWord(BigInteger.TWO)).bool(0) },
            { Abi("0x" + Abi.uintWord(BigInteger.ONE)).offset(0) },
            { Abi("0x" + "1".repeat(64)).address(0) },
            { Abi.uintWord(BigInteger.ONE.shiftLeft(256)) }, { Money(n("-1")) },
            { ContractDecoders.actionKind(4) },
            { BucketMath.compute(n("-1"), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO) },
        )
        invalid.forEach { operation -> assertThrows(RuntimeException::class.java) { operation() } }
    }
}
