package app.fusion.wallet.ui.agent

import java.math.BigInteger

/** Integer basis points; only the final drawing coordinate becomes a Float. */
fun gaugeBasisPoints(used: BigInteger, limit: BigInteger): Int? {
    if (limit.signum() <= 0) return null
    return used.max(BigInteger.ZERO).min(limit).multiply(BigInteger.valueOf(10_000))
        .divide(limit).toInt()
}

fun remainingSeconds(deadline: Long, now: Long): Long =
    BigInteger.valueOf(deadline).subtract(BigInteger.valueOf(now)).max(BigInteger.ZERO)
        .min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()

fun countdownText(deadline: Long, now: Long): String {
    val seconds = remainingSeconds(deadline, now)
    val days = seconds / 86_400
    val hours = seconds / 3_600 % 24
    val minutes = seconds / 60 % 60
    val rest = seconds % 60
    return if (days > 0) "${days}d ${hours}h ${minutes}m" else if (seconds >= 3_600) {
        "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3_600, minutes, rest)
    } else "%d:%02d".format(java.util.Locale.ROOT, minutes, rest)
}
