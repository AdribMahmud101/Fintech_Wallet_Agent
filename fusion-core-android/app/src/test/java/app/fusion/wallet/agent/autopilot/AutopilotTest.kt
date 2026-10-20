package app.fusion.wallet.agent.autopilot

import app.fusion.wallet.agent.AgentTickResult
import app.fusion.wallet.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AutopilotTest {
    @get:Rule val temp = TemporaryFolder()

    private class MemoryStore : AutopilotStore {
        var saved = AutopilotState()
        override fun load() = saved
        override fun save(state: AutopilotState) { saved = state }
    }

    private class Harness(val scope: TestScope, val store: AutopilotStore = MemoryStore()) {
        fun now() = 1_800_000_000_000L + scope.testScheduler.currentTime
        var wallet = healthy(now())
        var online = true
        var calls = 0
        var refreshes = 0
        var mode = ExecutionMode(true, "Preview only on this device: no session key")
        var receivedDryRun: Boolean? = null
        var action: suspend (Boolean) -> AgentTickResult = { result(it) }
        val controller = AutopilotController(scope.backgroundScope, store, ::now, { online }, {
            refreshes++
            wallet.copy(capturedAtEpochMillis = now())
        }, { mode }, { dryRun -> calls++; receivedDryRun = dryRun; Result.success(action(dryRun)) })
        init { controller.setAccess(true); controller.setForeground(true) }
        val state get() = controller.state.value
    }

    @Test fun defaultFiveMinuteScheduleWaitsAfterCompletionAndDoesNotOverlap() = runTest {
        val h = Harness(this)
        h.action = { delay(10_000); result(it) }
        h.controller.setEnabled(true)
        runCurrent()
        assertTrue(h.state.running)
        h.controller.runNow()
        h.controller.runNow()
        assertEquals(1, h.calls)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(h.now() + 300_000, h.state.nextTickAtMillis)
        advanceTimeBy(299_999); runCurrent()
        assertEquals(1, h.calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, h.calls)
        h.controller.close()
    }

    @Test fun intervalChoicesResetTheCountdownAndStopDisablesScheduling() = runTest {
        val h = Harness(this)
        h.controller.setEnabled(true); runCurrent()
        for (minutes in listOf(1, 5, 15)) {
            h.controller.setInterval(minutes)
            assertEquals(h.now() + minutes * 60_000, h.state.nextTickAtMillis)
            val calls = h.calls
            advanceTimeBy(minutes * 60_000L); runCurrent()
            assertEquals(calls + 1, h.calls)
        }
        h.controller.setEnabled(false); runCurrent()
        advanceTimeBy(1_000_000); runCurrent()
        assertFalse(h.state.enabled)
        assertNull(h.state.nextTickAtMillis)
        assertEquals(4, h.calls)
        h.controller.close()
    }

    @Test fun immediateDispatcherHandlesSynchronousHoldsAndReplacesOnlyOneTimer() = runTest {
        val job = SupervisorJob()
        val uiScope = CoroutineScope(job + UnconfinedTestDispatcher(testScheduler))
        var attempts = 0
        val controller = AutopilotController(uiScope, MemoryStore(), { testScheduler.currentTime }, { attempts++; false },
            { error("offline must not refresh") }, { error("offline must not probe lease") }, { error("offline must not run") })
        controller.setAccess(true)
        controller.setForeground(true)
        controller.setEnabled(true)
        assertFalse(controller.state.value.running)
        assertEquals("HOLD", controller.state.value.timeline.single().outcome)
        controller.setInterval(1)
        controller.setInterval(15)
        controller.setInterval(1)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, attempts)
        assertFalse(controller.state.value.running)
        assertEquals(120_000L, controller.state.value.nextTickAtMillis)
        controller.close()
        job.cancel()
    }

    @Test fun foregroundOnlyResumesOnceWithoutBackgroundCatchup() = runTest {
        val h = Harness(this)
        h.controller.setEnabled(true); runCurrent()
        h.controller.setForeground(false); runCurrent()
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(1, h.calls)
        assertNull(h.state.nextTickAtMillis)
        h.controller.setForeground(true); runCurrent()
        assertEquals(2, h.calls)
        h.controller.close()
    }

    @Test fun runNowWhileOffIsOneShotAndCanBeStoppedImmediately() = runTest {
        val h = Harness(this)
        h.action = { delay(10_000); result(it) }
        h.controller.runNow(); runCurrent()
        assertTrue(h.state.running)
        h.controller.setEnabled(false); runCurrent()
        assertFalse(h.state.running)
        assertEquals("HOLD", h.state.timeline.single().outcome)
        advanceTimeBy(1_000_000); runCurrent()
        assertEquals(1, h.calls)
        h.controller.close()
    }

    @Test fun failedGuardsRecordHoldWithoutInvokingThePlanner() = runTest {
        val h = Harness(this)
        val module = h.wallet.module!!
        val variants = listOf(
            h.wallet.copy(module = module.copy(paused = true)),
            h.wallet.copy(module = module.copy(sessionExpiresAt = h.now() / 1000)),
            h.wallet.copy(module = module.copy(sessionActive = false)),
            h.wallet.copy(module = module.copy(policy = module.policy.copy(permissionTier = PermissionTier.READ_ONLY))),
            h.wallet.copy(module = null),
            h.wallet.copy(accountingMatches = false),
            h.wallet.copy(status = LoadStatus.ERROR),
            h.wallet.copy(source = DataSource.DEMO),
        )
        for (wallet in variants) {
            h.wallet = wallet
            h.controller.runNow(); runCurrent()
            assertEquals("H", h.state.timeline.first().letter)
            assertTrue(h.state.timeline.first().checks.any { it.status == "FAIL" })
        }
        assertEquals(0, h.calls)
        h.wallet = healthy(h.now())
        h.online = false
        val refreshes = h.refreshes
        h.controller.runNow(); runCurrent()
        assertEquals(refreshes, h.refreshes)
        assertTrue(h.state.timeline.first().reason.contains("No network"))
        h.online = true
        h.controller.setAccess(false)
        h.controller.runNow(); runCurrent()
        assertTrue(h.state.timeline.first().reason.contains("Read-only"))
        h.controller.close()
    }

    @Test fun guardianStopDisablesAutopilotAndStaleDataFailsClosed() = runTest {
        val h = Harness(this)
        h.controller.setEnabled(true); runCurrent()
        h.controller.stopForGuardian(); runCurrent()
        assertFalse(h.state.enabled)
        assertTrue(h.state.timeline.first().reason.contains("Guardian stop"))
        h.controller.runNow(); runCurrent()
        assertEquals(1, h.calls)
        assertTrue(h.state.timeline.first().checks.any { it.name == "Guardian stop" && it.status == "FAIL" })
        assertTrue(AutopilotGuards.checks(h.wallet.copy(capturedAtEpochMillis = h.now() - 30_000), h.now(), true, true, false)
            .any { it.name == "Live snapshot" && it.status == "FAIL" })
        h.controller.close()
    }

    @Test fun missingKeyOrLeaseUsesPreviewAndOnlyVerifiedHashIsExecuted() = runTest {
        val h = Harness(this)
        h.controller.runNow(); runCurrent()
        assertEquals(true, h.receivedDryRun)
        assertEquals("PREVIEW", h.state.timeline.first().outcome)
        assertNull(h.state.timeline.first().txHash)
        assertEquals("A", h.state.timeline.first().letter)
        assertEquals("Invest surplus", h.state.timeline.first().title)
        assertEquals("PASS", h.state.timeline.first().checks.last().status)
        h.mode = ExecutionMode(true, "Preview only on this device: broadcast lease unavailable")
        h.controller.runNow(); runCurrent()
        assertEquals(true, h.receivedDryRun)
        h.mode = ExecutionMode(false, "Live execution · session key and broadcast lease available")
        h.action = { result(it).copy(txHash = "0x" + "a".repeat(64)) }
        h.controller.runNow(); runCurrent()
        assertEquals(false, h.receivedDryRun)
        assertEquals("EXECUTED", h.state.timeline.first().outcome)
        assertNotNull(h.state.timeline.first().txHash)
        h.action = { result(it) }
        h.controller.runNow(); runCurrent()
        assertEquals("UNCONFIRMED", h.state.timeline.first().outcome)
        assertNull(h.state.timeline.first().txHash)
        h.action = { result(it).copy(previewPassed = false) }
        h.controller.runNow(); runCurrent()
        assertEquals("HOLD", h.state.timeline.first().outcome)
        assertEquals("H", h.state.timeline.first().letter)
        h.controller.close()
    }

    @Test fun cancelledLiveTickNeverClaimsHoldOrExecutionAndDoesNotRestart() = runTest {
        val h = Harness(this)
        h.mode = ExecutionMode(false, "Live execution")
        h.action = { delay(10_000); result(it) }
        h.controller.setEnabled(true); runCurrent()
        h.controller.setEnabled(false); runCurrent()
        assertEquals("UNCONFIRMED", h.state.timeline.single().outcome)
        assertNull(h.state.timeline.single().txHash)
        assertFalse(h.state.enabled)
        assertFalse(h.state.running)
        h.controller.close()
    }

    @Test fun nativeFailureAndRuntimeTimeoutAreRecordedAndDoNotKillTheScheduler() = runTest {
        val h = Harness(this)
        h.action = { throw UnsatisfiedLinkError("planner") }
        h.controller.setEnabled(true); runCurrent()
        assertEquals("HOLD", h.state.timeline.single().outcome)
        assertTrue(h.state.timeline.first().reason.contains("planner unavailable"))
        h.action = { delay(400_000); result(it) }
        advanceTimeBy(300_000); runCurrent()
        advanceTimeBy(300_000); runCurrent()
        assertFalse(h.state.running)
        assertEquals("HOLD", h.state.timeline.first().outcome)
        assertNotNull(h.state.nextTickAtMillis)
        h.controller.close()
    }

    @Test fun brokenJournalPreventsExecutionBeforePlanning() = runTest {
        val h = Harness(this, object : AutopilotStore {
            override fun load() = AutopilotState()
            override fun save(state: AutopilotState) { error("disk full") }
        })
        h.controller.setEnabled(true); runCurrent()
        assertFalse(h.state.enabled)
        assertNotNull(h.state.storageError)
        assertEquals(0, h.calls)
        h.controller.close()
    }

    @Test fun storageFailureDuringGuardRecordingHoldsAndDisablesExecution() = runTest {
        var writes = 0
        val h = Harness(this, object : AutopilotStore {
            override fun load() = AutopilotState()
            override fun save(state: AutopilotState) { if (++writes > 1) error("disk full") }
        })
        h.controller.setEnabled(true); runCurrent()
        assertFalse(h.state.enabled)
        assertFalse(h.state.running)
        assertEquals("HOLD", h.state.timeline.single().outcome)
        assertEquals("Timeline storage", h.state.timeline.single().checks.single().name)
        assertEquals(0, h.calls)
        h.controller.close()
    }

    @Test fun timelinePersistsLastFiftyTicksChecksModeAndHashAcrossNewStore() {
        val file = File(temp.root, "autopilot/state.json")
        val checks = listOf(TickCheck("Lifecycle", "PASS", "module live"), TickCheck("Limits", "FAIL", "limit reached"))
        val hash = "0x" + "a".repeat(64)
        val ticks = (60L downTo 1L).map { AutopilotTick(it, 1_800_000_000_000 + it, "A", "Invest surplus", "Surplus available", "EXECUTED", "Live execution", checks, hash) }
        FileAutopilotStore(file).save(AutopilotState(enabled = true, intervalMinutes = 15, running = true, timeline = ticks))
        val restored = FileAutopilotStore(file).load()
        assertTrue(restored.enabled)
        assertEquals(15, restored.intervalMinutes)
        assertFalse(restored.foreground)
        assertFalse(restored.running)
        assertNull(restored.nextTickAtMillis)
        assertEquals(ticks.take(50), restored.timeline)
        FileAutopilotStore(file).save(restored.copy(timeline = listOf(ticks.first().copy(outcome = "RUNNING", txHash = null))))
        assertEquals("UNCONFIRMED", FileAutopilotStore(file).load().timeline.single().outcome)
        file.writeText("broken json")
        assertTrue(runCatching { FileAutopilotStore(file).load() }.isFailure)
    }

    companion object {
        private fun result(dryRun: Boolean) = AgentTickResult("A", "SURPLUS_AVAILABLE", "INSTANT", "hash", "{}", 123, true, 1.0, 0, dryRun,
            """{"chosenCandidate":{"kind":0},"preview":{"checks":[{"name":"Recorded check","status":"PASS","message":"Recorded success"}]}}""")
        private fun healthy(now: Long): WalletState {
            val policy = Policy(PermissionTier.AUTONOMOUS, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, 0, 0, "guardian", 0, 0)
            val module = ModuleState("module", true, false, true, policy, 1.toBigInteger(), "policy", "mandate", "root", Money.ZERO,
                0.toBigInteger(), 0.toBigInteger(), Money.ZERO, Money.ZERO, "session", "key", now / 1000 + 86400, Money.ZERO, Money.ZERO)
            return WalletState(status = LoadStatus.READY, blockNumber = 123, blockTime = now / 1000, module = module,
                accountingMatches = true, capturedAtEpochMillis = now)
        }
    }
}
