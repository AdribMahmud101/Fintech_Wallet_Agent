package app.fusion.wallet.agent.autopilot

import app.fusion.wallet.agent.AgentTickResult
import app.fusion.wallet.data.WalletState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One foreground worker, fixed delay after completion, no background catch-up or overlapping ticks.
 * All controls are called on the UI dispatcher; dependencies also make scheduling testable. */
class AutopilotController(
    private val scope: CoroutineScope,
    private val store: AutopilotStore,
    private val now: () -> Long,
    private val online: () -> Boolean,
    private val snapshot: suspend () -> WalletState,
    private val executionMode: suspend () -> ExecutionMode,
    private val run: suspend (dryRun: Boolean) -> Result<AgentTickResult>,
    initialMode: String = "Checking device execution mode at next tick",
) {
    private val mutableState = MutableStateFlow(try { store.load().copy(mode = initialMode) } catch (_: Exception) {
        AutopilotState(mode = initialMode, storageError = "Autopilot storage unavailable; automatic execution is off")
    })
    val state = mutableState.asStateFlow()
    private var worker: Job? = null
    private var canAct = false
    private var guardianStopped = false
    private var closed = false
    private var sequence = mutableState.value.timeline.maxOfOrNull { it.id } ?: 0

    fun setAccess(allowed: Boolean) {
        canAct = allowed
        if (!allowed && state.value.running) worker?.cancel(CancellationException("App access changed"))
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled == state.value.enabled) {
            if (!enabled) cancelWorker("Autopilot stopped")
            return
        }
        if (enabled) guardianStopped = false // Explicit user restart; fresh on-chain pause/session checks still apply.
        update(state.value.copy(enabled = enabled), persist = true)
        if (enabled) schedule(immediate = true) else cancelWorker("Autopilot stopped")
    }

    fun setInterval(minutes: Int) {
        require(minutes in setOf(1, 5, 15))
        update(state.value.copy(intervalMinutes = minutes), persist = true)
        if (!state.value.running) schedule(immediate = false)
    }

    fun setForeground(foreground: Boolean) {
        if (foreground == state.value.foreground) return
        update(state.value.copy(foreground = foreground))
        if (foreground) schedule(immediate = true) else cancelWorker("App left foreground")
    }

    /** Available even with the periodic toggle off. Uses exactly the same guards and mode probe. */
    fun runNow() {
        if (closed || !state.value.foreground || state.value.running || state.value.storageError != null) return
        schedule(immediate = true, once = true)
    }

    fun stopForGuardian() {
        guardianStopped = true
        update(state.value.copy(enabled = false), persist = true)
        cancelWorker("Guardian stop requested")
        if (!state.value.running) record(AutopilotTick(++sequence, now(), reason = "Guardian stop requested; Autopilot off",
            checks = listOf(TickCheck("Guardian stop", "FAIL", "Guardian stop requested"))))
    }

    fun close() { closed = true; cancelWorker("Autopilot controller closed") }

    private fun cancelWorker(reason: String) {
        worker?.cancel(CancellationException(reason))
        update(state.value.copy(nextTickAtMillis = null))
    }

    private fun schedule(immediate: Boolean, once: Boolean = false) {
        if (closed || !state.value.foreground || (!state.value.enabled && !once) || state.value.running || state.value.storageError != null) return
        val previous = worker
        worker = null // Detach before cancellation: Main.immediate may run the old finally inline.
        previous?.cancel()
        val wait = if (immediate) 0L else state.value.intervalMinutes * 60_000L
        update(state.value.copy(nextTickAtMillis = now() + wait))
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(wait)
                tick()
            } finally {
                if (worker === currentCoroutineContext()[Job]) {
                    worker = null
                    update(state.value.copy(running = false, nextTickAtMillis = null))
                    schedule(immediate = false)
                }
            }
        }
        worker = next // Assign before starting: an offline HOLD can complete without suspending.
        next.start()
    }

    private suspend fun tick() {
        val id = ++sequence
        val time = now()
        var checks = emptyList<TickCheck>()
        var mode = ExecutionMode(true, state.value.mode)
        var runnerStarted = false
        update(state.value.copy(running = true, nextTickAtMillis = null))
        try {
            record(AutopilotTick(id, time, letter = "—", title = "Checking guards", reason = "Tick in progress", outcome = "RUNNING"))
            if (storageBlocked(id, time)) return
            val connected = online()
            if (!connected || !canAct || guardianStopped) {
                checks = listOf(
                    TickCheck("Network", if (connected) "PASS" else "FAIL", if (connected) "Network available" else "No network"),
                    TickCheck("App access", if (canAct) "PASS" else "FAIL", if (canAct) "Agent controls available" else "Read-only role, signed out, or setup incomplete"),
                    TickCheck("Guardian stop", if (guardianStopped) "FAIL" else "PASS", if (guardianStopped) "Guardian stop requested" else "No local guardian stop"),
                )
            } else {
                val fresh = withTimeout(60_000) { snapshot() }
                checks = AutopilotGuards.checks(fresh, now(), online(), canAct, guardianStopped)
            }
            if (checks.any { it.status == "FAIL" }) {
                record(AutopilotTick(id, time, reason = checks.filter { it.status == "FAIL" }.joinToString("; ") { it.message }, checks = checks))
                return
            }
            mode = executionMode()
            currentCoroutineContext().ensureActive()
            update(state.value.copy(mode = mode.description))
            // Persist the mode before entering runtime. Interrupted live attempts must never look like a HOLD.
            record(AutopilotTick(id, time, letter = "—", title = "Deciding", reason = "On-device planner running", outcome = "RUNNING", mode = mode.description, checks = checks))
            if (storageBlocked(id, time)) return
            runnerStarted = true
            val result = withTimeout(300_000) { run(mode.dryRun).getOrThrow() }
            record(tickResult(id, time, mode, checks, result))
        } catch (e: CancellationException) {
            record(interrupted(id, time, mode, checks, runnerStarted, e.message ?: "Tick cancelled"))
            if (e !is TimeoutCancellationException) throw e
        } catch (e: Exception) {
            record(interrupted(id, time, mode, checks + TickCheck("Runtime", "FAIL", safeError(e)), runnerStarted, safeError(e)))
        } catch (_: LinkageError) {
            record(interrupted(id, time, mode, checks + TickCheck("On-device planner", "FAIL", "Native planner unavailable"), runnerStarted,
                "On-device planner unavailable; check model setup"))
        }
    }

    private fun interrupted(id: Long, time: Long, mode: ExecutionMode, checks: List<TickCheck>, started: Boolean, reason: String): AutopilotTick {
        val uncertain = started && !mode.dryRun
        return AutopilotTick(id, time, if (uncertain) "—" else "H", if (uncertain) "Execution not confirmed" else "Hold",
            if (uncertain) "$reason. A submitted transaction may still confirm; check Activity." else reason,
            if (uncertain) "UNCONFIRMED" else "HOLD", if (started) mode.description else "Guards only · no transaction requested", checks)
    }

    // Exception messages can contain transport config or RPC payloads. Keep only a public error class/code.
    private fun safeError(error: Exception): String {
        val code = Regex("\\b[A-Z][A-Z_]{3,64}\\b").find(error.message.orEmpty())?.value
        return code?.let { "Tick held: $it" } ?: "Tick held: ${error.javaClass.simpleName}; refresh network and model setup"
    }

    private fun record(tick: AutopilotTick) = update(state.value.copy(timeline =
        (listOf(tick) + state.value.timeline.filterNot { it.id == tick.id }).take(50)), persist = true)

    private fun storageBlocked(id: Long, time: Long): Boolean {
        val error = state.value.storageError ?: return false
        val held = AutopilotTick(id, time, reason = error, checks = listOf(TickCheck("Timeline storage", "FAIL", error)))
        update(state.value.copy(timeline = (listOf(held) + state.value.timeline.filterNot { it.id == id }).take(50)))
        return true
    }

    private fun update(value: AutopilotState, persist: Boolean = false) {
        mutableState.value = value
        if (persist) try { store.save(value) } catch (_: Exception) {
            mutableState.value = value.copy(enabled = false, nextTickAtMillis = null,
                storageError = "Autopilot storage unavailable; automatic execution is off")
            worker?.cancel(CancellationException("Autopilot storage unavailable"))
        }
    }
}
