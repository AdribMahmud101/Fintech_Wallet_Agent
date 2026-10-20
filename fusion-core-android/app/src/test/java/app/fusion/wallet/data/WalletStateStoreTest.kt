package app.fusion.wallet.data

import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WalletStateStoreTest {

    private fun sampleState(): WalletState {
        val policy = Policy(
            permissionTier = PermissionTier.AUTONOMOUS,
            operatingBuffer = Money(BigInteger("500000000")),
            microMax = Money(BigInteger("10000000")),
            delayedMax = Money(BigInteger("50000000")),
            hardMax = Money(BigInteger("100000000")),
            dailyLimit = Money(BigInteger("1000000000")),
            delaySeconds = 3600L,
            maxShareSlippageBps = 50,
            guardian = "0x" + "1".repeat(40),
            payeeDelaySeconds = 86400L,
            payEarlyWindowSeconds = 1800L,
        )
        val module = ModuleState(
            address = "0x" + "2".repeat(40),
            configured = true,
            paused = false,
            sessionActive = true,
            policy = policy,
            policyEpoch = BigInteger("3"),
            policyHash = "0x" + "a".repeat(64),
            mandateHash = "0x" + "b".repeat(64),
            obligationsRoot = "0x" + "c".repeat(64),
            reserved = Money(BigInteger("50000000")),
            obligationCount = BigInteger("5"),
            actionNonce = BigInteger("12"),
            dailyUsed = Money(BigInteger("2000000")),
            dailyRemaining = Money(BigInteger("998000000")),
            sessionId = "0x" + "d".repeat(64),
            sessionKey = "0x" + "3".repeat(40),
            sessionExpiresAt = 1792500000L,
            sessionLimit = Money(BigInteger("500000000")),
            sessionUsed = Money(BigInteger("10000000")),
        )
        val treasury = Treasury(
            safeAddress = "0x" + "4".repeat(40),
            assetAddress = "0x" + "5".repeat(40),
            balance = Money(BigInteger("9467001235")),
            symbol = "USDC",
        )
        val buckets = Buckets(
            reserve = Money(BigInteger("50000000")),
            owed = Money(BigInteger("50000000")),
            buffer = Money(BigInteger("500000000")),
            surplus = Money(BigInteger("8917001235")),
            free = Money(BigInteger("9417001235")),
            shortfall = Money.ZERO,
            deployed = Money.ZERO,
            total = Money(BigInteger("9467001235")),
            bufferTarget = Money(BigInteger("500000000")),
        )
        val vaults = listOf(
            VaultPosition(
                id = "A",
                name = "Aave waUSDC",
                address = "0x" + "6".repeat(40),
                sharesRaw = BigInteger.ZERO,
                assets = Money.ZERO,
                maxWithdraw = Money.ZERO,
                exposureLimit = Money(BigInteger("5000000000")),
                simulatedYield = false,
                previewDepositSharesRaw = Money.SCALE,
                previewWithdrawSharesRaw = Money.SCALE,
            )
        )
        val queue = listOf(
            QueuedAction(
                id = "0x" + "e".repeat(64),
                kind = ActionKind.PAY_OBLIGATION,
                amount = Money(BigInteger("2000000")),
                readyAt = 1792400000L,
                expiresAt = 1792486400L,
                status = QueueStatus.READY,
                payee = "0x" + "7".repeat(40),
                decisionHash = "0x" + "f".repeat(64),
                expectedNonce = BigInteger("12"),
            )
        )
        val payees = listOf(
            Payee(
                id = "0x" + "8".repeat(64),
                address = "0x" + "9".repeat(40),
                label = "Acme Cloud",
                labelHash = "0x" + "0".repeat(64),
                status = PayeeStatus.ACTIVE,
                activeAt = 1792300000L,
                sanctioned = false,
            )
        )
        val actions = listOf(
            AgentAction(
                id = "0x" + "a".repeat(64),
                kind = ActionKind.PAY_OBLIGATION,
                amount = Money(BigInteger("2000000")),
                payee = "0x" + "9".repeat(40),
                txHash = "0x" + "b".repeat(64),
                decisionHash = "0x" + "c".repeat(64),
                blockNumber = 48320000L,
                blockTime = 1792400000L,
                source = "AGENT",
            )
        )
        val obligations = listOf(
            Obligation(
                id = "0x" + "d".repeat(64),
                payeeId = "0x" + "8".repeat(64),
                amount = Money(BigInteger("2000000")),
                dueAt = 1792500000L,
                paid = false,
            )
        )

        return WalletState(
            source = DataSource.LIVE,
            status = LoadStatus.READY,
            chainId = 84532L,
            blockNumber = 48320000L,
            blockHash = "0x" + "1".repeat(64),
            blockTime = 1792400000L,
            capturedAtEpochMillis = 1792400000000L,
            observedHeadBlock = 48320002L,
            agentSource = "AGENT",
            planner = "deterministic",
            accountingMatches = true,
            accountingError = null,
            treasury = treasury,
            buckets = buckets,
            vaults = vaults,
            module = module,
            queue = queue,
            payees = payees,
            recentActions = actions,
            evidence = emptyList(),
            obligations = obligations,
        )
    }

    @Test
    fun roundTripPreservesExactMoneyAndState() {
        val original = sampleState()
        val encoded = WalletStateStore.encode(original)
        assertNotNull("Encoding READY state must succeed", encoded)

        val decoded = WalletStateStore.decode(encoded)
        assertNotNull("Decoding valid snapshot must succeed", decoded)

        assertEquals(LoadStatus.READY, decoded!!.status)
        assertEquals(84532L, decoded.chainId)
        assertEquals(original.blockNumber, decoded.blockNumber)
        assertEquals(original.blockHash, decoded.blockHash)
        assertEquals(original.capturedAtEpochMillis, decoded.capturedAtEpochMillis)
        assertEquals(original.treasury?.balance?.raw, decoded.treasury?.balance?.raw)
        assertEquals("9467001235", decoded.treasury?.balance?.rawUnits)
        assertEquals(original.buckets?.surplus?.raw, decoded.buckets?.surplus?.raw)
        assertEquals(original.vaults.size, decoded.vaults.size)
        assertEquals(original.vaults[0].assets.raw, decoded.vaults[0].assets.raw)
        assertEquals(original.module?.reserved?.raw, decoded.module?.reserved?.raw)
        assertEquals(original.queue.size, decoded.queue.size)
        assertEquals(original.payees.size, decoded.payees.size)
        assertEquals(original.recentActions.size, decoded.recentActions.size)
        assertEquals(original.obligations.size, decoded.obligations.size)
        assertEquals(original.accountingMatches, decoded.accountingMatches)
    }

    @Test
    fun agentGasRoundTripPreservesExactWeiAndFractionalMedian() {
        val gas = AgentGas(BigInteger("123456789012345678901234567890"), "2.5".toBigDecimal(),
            BigInteger("49382715604938271560493827156"))
        val restored = WalletStateStore.decode(WalletStateStore.encode(sampleState().copy(agentGas = gas)))
        assertEquals(gas, restored?.agentGas)
    }

    @Test
    fun balanceOnlyGasRoundTripKeepsEstimatesUnknown() {
        listOf(BigInteger.ZERO, BigInteger("17641102153994847")).forEach { balance ->
            val gas = AgentGas(balance, null, null)
            val restored = WalletStateStore.decode(WalletStateStore.encode(sampleState().copy(agentGas = gas)))
            assertEquals(gas, restored?.agentGas)
        }
    }

    @Test
    fun oldCachesWithoutAgentGasStillRestore() {
        val original = sampleState()
        val legacy = JSONObject(WalletStateStore.encode(original)).apply { remove("agentGas") }
        val restored = WalletStateStore.decode(legacy.toString())
        assertNotNull(restored)
        assertNull(restored!!.agentGas)
        assertEquals(original.treasury, restored.treasury)
        assertEquals(original.module, restored.module)
    }

    @Test
    fun nonReadyStateReturnsNull() {
        val loading = sampleState().copy(status = LoadStatus.LOADING)
        assertNull(WalletStateStore.encode(loading))

        val error = sampleState().copy(status = LoadStatus.ERROR)
        assertNull(WalletStateStore.encode(error))
    }

    @Test
    fun malformedOrMissingJsonReturnsNull() {
        assertNull(WalletStateStore.decode(null))
        assertNull(WalletStateStore.decode(""))
        assertNull(WalletStateStore.decode("not valid json"))
        assertNull(WalletStateStore.decode("""{"v": 999}"""))
    }
}
