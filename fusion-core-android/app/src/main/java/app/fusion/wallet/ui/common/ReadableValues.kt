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
fun usdc(raw: String): String = raw.toBigIntegerOrNull()?.let {
    java.text.DecimalFormat("#,##0.######", java.text.DecimalFormatSymbols(Locale.US)).format(it.toBigDecimal().movePointLeft(6))
} ?: "Not measured"

fun readableCheckMessage(message: String): String {
    Regex("assets=(\\d+), hard_max=(\\d+)").matchEntire(message)?.let {
        return "Amount ${usdc(it.groupValues[1])} USDC · max per action ${usdc(it.groupValues[2])} USDC"
    }
    Regex("daily_used=(\\d+)/(\\d+), session_used=(\\d+)/(\\d+)").matchEntire(message)?.let {
        val g = it.groupValues
        return "Used today ${usdc(g[1])} / ${usdc(g[2])} USDC · this session ${usdc(g[3])} / ${usdc(g[4])} USDC"
    }
    if (message.contains("nonce_ok=")) {
        val failed = Regex("(nonce|epoch|deadline|decision_hash|hash)_ok=false").findAll(message).map { it.groupValues[1].replace('_', ' ') }.toList()
        return if (failed.isEmpty() && !message.contains("false")) "Nonce, epoch, deadline and decision hash all valid"
        else if (failed.isNotEmpty()) "Invalid: ${failed.joinToString(", ")}"
        else "Transaction metadata validation failed"
    }
    Regex("paused=(true|false), tier=(\\w+), session_live=(true|false)").matchEntire(message)?.let {
        return "Module ${if (it.groupValues[1] == "true") "live" else "paused"} · ${readableCode(it.groupValues[2])} permission · session ${if (it.groupValues[3] == "true") "active" else "inactive or expired"}"
    }
    Regex("tier=(\\w+), route=(\\w+)").matchEntire(message)?.let {
        return "${readableCode(it.groupValues[1])} tier · ${readableCode(it.groupValues[2])} route"
    }
    return message.replace(Regex("\\bpaused=(true|false)", RegexOption.IGNORE_CASE)) {
        if (it.groupValues[1].equals("true", true)) "module live" else "module paused"
    }
}

/** The report carries the explanation; the terse validator code is a last resort. */
fun decisionReason(report: org.json.JSONObject, fallback: String): String {
    report.optString("routerReason").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
    report.optString("decisionExplanation").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
    val chosen = report.optJSONObject("chosenCandidate")
    report.optJSONObject("preview")?.optJSONArray("checks")?.let { checks ->
        (0 until checks.length()).map { checks.getJSONObject(it) }.firstOrNull { it.optString("status") == "FAIL" }
            ?.optString("message")?.takeIf { it.isNotBlank() }?.let { return readableCheckMessage(it) }
    }
    chosen?.optString("explanation")?.takeIf { it.isNotBlank() }?.let { return it }
    chosen?.optString("reason")?.takeIf { it.isNotBlank() && it != "HOLD" }?.let { return readableCode(it) }
    return readableCode(fallback)
}

fun decisionTimings(load: java.math.BigDecimal?, inference: java.math.BigDecimal?, decode: java.math.BigDecimal?): String =
    listOf("Load" to load, "Inference" to inference, "Decode" to decode).mapNotNull { (label, ms) ->
        ms?.let { "$label ${it.movePointLeft(3).setScale(2, java.math.RoundingMode.HALF_UP)} s" }
    }.joinToString(" · ")

/** Presentation labels only; stored evidence and enum identifiers remain unchanged. */
fun readableCode(value: String?, fallback: String = "Not recorded"): String {
    if (value.isNullOrBlank()) return fallback
    if (value.contains(' ')) return value.replaceFirstChar { it.titlecase(locale = Locale.getDefault()) }
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
