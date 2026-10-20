package app.fusion.wallet.agent.autopilot

import app.fusion.wallet.agent.AgentTickResult
import app.fusion.wallet.data.*
import org.json.JSONObject

data class TickCheck(val name: String, val status: String, val message: String = "")

/** EXECUTED is reserved for a receipt verified by the existing session signer. */
data class AutopilotTick(
    val id: Long,
    val timeMillis: Long,
    val letter: String = "H",
    val title: String = "Hold",
    val reason: String,
    val outcome: String = "HOLD",
    val mode: String = "Guards only · no transaction requested",
    val checks: List<TickCheck> = emptyList(),
    val txHash: String? = null,
)

data class AutopilotState(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 5,
    val foreground: Boolean = false,
    val running: Boolean = false,
    val nextTickAtMillis: Long? = null,
    val mode: String = "Checking device execution mode at next tick",
    val timeline: List<AutopilotTick> = emptyList(),
    val storageError: String? = null,
)

data class ExecutionMode(val dryRun: Boolean, val description: String)

/** Pure guard evaluation; unavailable or stale data never authorizes planning or signing. */
object AutopilotGuards {
    fun checks(state: WalletState, nowMillis: Long, online: Boolean, canAct: Boolean, guardianStopped: Boolean): List<TickCheck> {
        val module = state.module
        fun check(name: String, passed: Boolean, message: String, success: String = message) = TickCheck(name, if (passed) "PASS" else "FAIL", if (passed) success else message)
        return listOf(
            check("Network", online, if (online) "Network available" else "No network"),
            check("App access", canAct, if (canAct) "Signed in with agent controls" else "Read-only role, signed out, or setup incomplete"),
            check("Guardian stop", !guardianStopped, if (guardianStopped) "Guardian stop requested" else "No local guardian stop"),
            check("Live snapshot", state.source == DataSource.LIVE && !state.isStale(nowMillis), "A fresh live snapshot is required", "Fresh live snapshot"),
            check("Module configured", module?.configured == true, "Module must be configured", "Module configured"),
            check("Module live", module != null && !module.paused, if (module?.paused == true) "Module paused by guardian or owners" else "Module must be unpaused"),
            check("Permission tier", module != null && module.policy.permissionTier != PermissionTier.READ_ONLY, "Read-only tier cannot act", "Permission tier allows agent requests"),
            check("Session", module?.sessionActive == true && module.sessionExpiresAt > maxOf(nowMillis / 1000, state.blockTime ?: 0), "Session expired or inactive", "Active session within expiry"),
            check("Bill accounting", state.accountingMatches == true, "Bill metadata must match the on-chain commitment", "Bills match the on-chain commitment"),
        )
    }
}

internal fun tickResult(id: Long, time: Long, mode: ExecutionMode, guards: List<TickCheck>, result: AgentTickResult): AutopilotTick {
    val report = JSONObject(result.reportJson)
    val checks = report.optJSONObject("preview")?.optJSONArray("checks")
    val policy = if (checks == null) emptyList() else (0 until checks.length()).map { index ->
        val c = checks.getJSONObject(index)
        TickCheck(c.optString("name", c.optString("ruleId")), c.optString("status", "UNKNOWN"), c.optString("message"))
    }
    val blocked = !result.previewPassed || policy.any { it.status == "FAIL" }
    val held = result.choiceLabel == "H" || blocked
    val hash = result.txHash?.takeIf { !mode.dryRun && !result.dryRun && !held && Regex("0x[0-9a-fA-F]{64}").matches(it) }
    val kind = report.optJSONObject("chosenCandidate")?.optInt("kind", -1)
    val title = if (held) "Hold" else when (kind) {
        0 -> "Invest surplus"
        1 -> "Return funds to treasury"
        2 -> "Rebalance vaults"
        3 -> "Pay bill"
        else -> "Agent decision"
    }
    return AutopilotTick(id, time, if (held) "H" else result.choiceLabel, title,
        if (blocked) "Policy preview blocked execution" else result.reason,
        when { held -> "HOLD"; hash != null -> "EXECUTED"; mode.dryRun || result.dryRun -> "PREVIEW"; else -> "UNCONFIRMED" },
        mode.description, guards + policy + listOfNotNull(report.optJSONObject("quant")?.let { TickCheck("Measured economics", "INFO", quantSummary(it)) }), hash)
}

fun autopilotStatus(state: AutopilotState, nowMillis: Long): String = when {
    state.running -> if (state.enabled) "Autopilot on · deciding now" else "Running now · periodic ticks off"
    !state.enabled -> "Autopilot off"
    !state.foreground -> "Autopilot on · waiting for foreground"
    state.nextTickAtMillis == null -> "Autopilot on · ready"
    else -> {
        val seconds = ((state.nextTickAtMillis - nowMillis).coerceAtLeast(0) + 999) / 1000
        "Autopilot on · next in " + if (seconds >= 60) "${(seconds + 59) / 60}m" else "${seconds}s"
    }
}
