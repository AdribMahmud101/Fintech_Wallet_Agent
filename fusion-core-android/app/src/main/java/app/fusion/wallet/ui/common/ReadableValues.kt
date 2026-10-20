package app.fusion.wallet.ui.common

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** API 24 compatible; fractional ISO seconds and source offsets never leak into screen labels. */
fun formatTimestamp(value: String?, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
    val raw = value?.trim().orEmpty()
    if (Regex("[0-9]{10}|[0-9]{13}").matches(raw)) {
        val number = raw.toLongOrNull() ?: return "Time unavailable"
        return formatEpochMillis(if (raw.length == 13) number else number * 1000, zone, locale)
    }
    val match = Regex("(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})").matchEntire(raw)
        ?: return "Time unavailable"
    val (date, time, fraction, offset) = match.destructured
    val normalized = "${date}T$time.${fraction.padEnd(3, '0').take(3)}${if (offset == "Z") "+0000" else offset.replace(":", "")}"
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply { isLenient = false }
    val position = ParsePosition(0)
    val parsed = parser.parse(normalized, position)?.takeIf { position.index == normalized.length } ?: return "Time unavailable"
    return formatEpochMillis(parsed.time, zone, locale)
}

fun formatEpochSeconds(value: Long?, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String {
    if (value == null || value < 0 || value > Long.MAX_VALUE / 1000) return "Time unavailable"
    return formatEpochMillis(value * 1000, zone, locale)
}

private fun formatEpochMillis(value: Long, zone: TimeZone, locale: Locale): String =
    SimpleDateFormat("d MMM, HH:mm z", locale).apply { timeZone = zone }.format(Date(value))

/**
 * fusion-core's lifecycle check prints `paused={not_paused}`, so `paused=true` means the module is live.
 * Core output stays as-is because committed evidence carries it; only the display is corrected.
 */
fun readableCheckMessage(message: String): String =
    message.replace(Regex("\\bpaused=(true|false)", RegexOption.IGNORE_CASE)) {
        if (it.groupValues[1].equals("true", true)) "module live" else "module paused"
    }

/** Presentation labels only; stored evidence and enum identifiers remain unchanged. */
fun readableCode(value: String?, fallback: String = "Not recorded"): String {
    if (value.isNullOrBlank()) return fallback
    return when (value.lowercase(Locale.ROOT)) {
        "mnn+ahem-g@android" -> "On-device MNN · AHEM-G"
        "deterministic" -> "Deterministic planner"
        "pass" -> "Passed"
        "fail" -> "Failed"
        "unknown", "unmeasured" -> "Not measured"
        else -> value.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('_', ' ').replace('-', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(locale = Locale.getDefault()) }
    }
}
