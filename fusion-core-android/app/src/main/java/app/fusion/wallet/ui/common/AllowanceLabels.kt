package app.fusion.wallet.ui.common

import app.fusion.wallet.data.ModuleState

/** One snapshot, one set of labels for Home, Agent and Safety. Hard max is not the instant tier. */
fun allowanceLabels(module: ModuleState, blockTime: Long?): List<String> = listOf(
    "${usdc(module.dailyRemaining.rawUnits)} of ${usdc(module.policy.dailyLimit.rawUnits)} USDC left today",
    "Instant up to ${usdc(module.policy.microMax.rawUnits)} USDC · max per action ${usdc(module.policy.hardMax.rawUnits)} USDC",
    blockTime?.let { "Resets at ${formatEpochSeconds((it / 86400 + 1) * 86400)}" } ?: "Reset time unavailable",
)

/** Historical checks describe their recorded day, never today's allowance. */
fun historicalCheckMessage(message: String, recordedTime: Long?, currentBlockTime: Long?): String {
    if (message.startsWith("daily_used=") && recordedTime != null && currentBlockTime != null && recordedTime / 86400 < currentBlockTime / 86400)
        return "Daily counter has reset since this decision · resets at ${formatEpochSeconds((currentBlockTime / 86400 + 1) * 86400)}"
    return readableCheckMessage(message)
}
