package app.fusion.wallet.ui.advisor

import app.fusion.wallet.advisor.DecisionFacts
import app.fusion.wallet.agent.autopilot.AutopilotTick
import app.fusion.wallet.ui.agent.DecisionDetails

/** The latest decision on the AI Agent screen or the Preview sheet → public facts for "Why?". */
fun DecisionDetails.toFacts(): DecisionFacts {
    val chosen = candidates.firstOrNull { it.label == choice }
    val blocked = checks.any { it.status == "FAIL" }
    return DecisionFacts(
        choice = choice,
        chosenTitle = chosen?.title ?: if (choice == "H") "Hold" else "Unknown",
        reasonCode = reason,
        mode = when (dryRun) { true -> "DRY_RUN"; false -> "LIVE"; null -> "UNKNOWN" },
        outcome = when {
            choice == "H" -> "HOLD"
            blocked -> "BLOCKED"
            txHash != null -> "EXECUTED"
            dryRun == true -> "PREVIEW"
            else -> "UNCONFIRMED"
        },
        candidates = candidates.map { DecisionFacts.Candidate(it.label, it.title, it.reason, it.amount) },
        checks = checks.map { DecisionFacts.Check(it.name, it.status, it.message) },
    )
}

/** An Autopilot timeline row. Ticks keep only the chosen letter, so the candidate list is the choice alone. */
fun AutopilotTick.toFacts(): DecisionFacts = DecisionFacts(
    choice = letter,
    chosenTitle = title,
    reasonCode = reason,
    mode = if (outcome == "PREVIEW") "DRY_RUN" else if (outcome == "EXECUTED" || outcome == "UNCONFIRMED") "LIVE" else "UNKNOWN",
    outcome = outcome,
    candidates = listOf(DecisionFacts.Candidate(letter, title, reason, null)),
    checks = checks.map { DecisionFacts.Check(it.name, it.status, it.message) },
)
