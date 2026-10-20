package app.fusion.wallet.data

import java.math.BigInteger

/** Human labels for the two allowance numbers. Formatting is injected so tests can stay pure. */
data class AllowanceCopy(
    val perMove: String,
    val dailyCap: String,
    val remaining: String,
    val explanation: String,
)

/**
 * The per-move number is the instant (auto) tier ceiling, not an owner-approval threshold.
 * The daily number is the UTC-day gross cap. Above the instant ceiling the deployed contract
 * queues up to the delayed ceiling, then requires both Safe owners up to the hard ceiling.
 */
fun allowanceCopy(policy: Policy, dailyUsed: Money, format: (Money) -> String): AllowanceCopy {
    require(dailyUsed.raw.signum() >= 0)
    val remainingRaw = (policy.dailyLimit.raw - dailyUsed.raw).max(BigInteger.ZERO)
    val perMove = when (policy.permissionTier) {
        PermissionTier.READ_ONLY -> "Per move: agent execution disabled (read-only)"
        PermissionTier.OWNER_APPROVED ->
            "Per move: up to ${format(policy.microMax)} USDC · both Safe owners must approve"
        PermissionTier.AUTONOMOUS -> "Per move: up to ${format(policy.microMax)} USDC (auto)"
    }
    val dailyCap = "Daily cap: ${format(policy.dailyLimit)} USDC"
    val remaining = "${format(Money(remainingRaw))} of ${format(policy.dailyLimit)} USDC left"
    val explanation = buildString {
        append(perMove)
        append(". ")
        append(
            when (policy.permissionTier) {
                PermissionTier.READ_ONLY ->
                    "This policy is read-only, so the agent cannot execute a move."
                PermissionTier.OWNER_APPROVED ->
                    "This policy requires both Safe owners to approve every agent move."
                PermissionTier.AUTONOMOUS ->
                    "Moves at or below this amount can execute immediately with the agent session key."
            }
        )
        if (policy.permissionTier == PermissionTier.AUTONOMOUS) {
            append("\n\n")
            append(aboveAutoLimit(policy, format))
        }
        append("\n\n$dailyCap. ")
        append("The contract rejects an agent move that would make the UTC-day gross exceed this cap.")
    }
    return AllowanceCopy(perMove, dailyCap, remaining, explanation)
}

private fun aboveAutoLimit(policy: Policy, format: (Money) -> String): String = buildString {
    if (policy.delayedMax.raw > policy.microMax.raw) {
        append(
            "Above ${format(policy.microMax)} USDC and up to ${format(policy.delayedMax)} USDC, " +
                "the agent must queue the move for ${policy.delaySeconds} seconds before it can execute; " +
                "this tier is not owner-approved. "
        )
    }
    if (policy.hardMax.raw > policy.delayedMax.raw) {
        append(
            "Above ${format(policy.delayedMax)} USDC and up to ${format(policy.hardMax)} USDC, " +
                "both Safe owners must approve the move. "
        )
    }
    append("Above ${format(policy.hardMax)} USDC the contract rejects the move.")
}
