package app.fusion.wallet.ui

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Number formatting. Fixed US-style separators so amounts read the same in every UI language. */
object Fmt {
    fun money(raw: BigInteger, dec: Int): String =
        DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(BigDecimal(raw).movePointLeft(dec).setScale(2, RoundingMode.HALF_UP))

    /** Whole-number amount, e.g. caps: 2,500. */
    fun whole(raw: BigInteger, dec: Int): String =
        DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)).format(BigDecimal(raw).movePointLeft(dec).setScale(0, RoundingMode.HALF_UP))

    /** Native 18-decimal amount to 4 places, e.g. 0.0123. */
    fun native(raw: BigInteger): String = BigDecimal(raw).movePointLeft(18).setScale(4, RoundingMode.HALF_UP).toPlainString()

    fun short(hex: String): String = if (hex.length > 14) hex.take(8) + "…" + hex.takeLast(4) else hex

    /** 95 -> "1:35", 3700 -> "1h 1m". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "$m:${(s % 60).toString().padStart(2, '0')}"
    }
}
