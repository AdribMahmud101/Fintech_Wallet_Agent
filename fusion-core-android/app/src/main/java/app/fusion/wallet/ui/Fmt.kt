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

    /**
     * Product-grade USDC amount for lists and tiles: grouped thousands, exactly two decimals,
     * e.g. 2153000000 (6-dp units) -> "2,153.00". The exact six-decimal value stays available on
     * detail views via [app.fusion.wallet.data.Money.display]; this is the everyday reading form.
     */
    fun usdc(money: app.fusion.wallet.data.Money): String = money(money.raw, 6)

    /** Whole-number amount, e.g. caps: 2,500. */
    fun whole(raw: BigInteger, dec: Int): String =
        DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US)).format(BigDecimal(raw).movePointLeft(dec).setScale(0, RoundingMode.HALF_UP))

    /** Native 18-decimal amount to 4 places, e.g. 0.0123. */
    fun native(raw: BigInteger): String = BigDecimal(raw).movePointLeft(18).setScale(4, RoundingMode.HALF_UP).toPlainString()

    /** Short form for tight spaces: 10,000 -> "10K", 1,250 -> "1.3K", 950 -> "950". */
    fun compact(raw: BigInteger, dec: Int): String {
        val v = BigDecimal(raw).movePointLeft(dec)
        val k = BigDecimal(1000)
        return if (v.abs() >= k) {
            val s = v.divide(k).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            s + "K"
        } else v.setScale(0, RoundingMode.HALF_UP).toPlainString()
    }

    fun short(hex: String): String = if (hex.length > 14) hex.take(8) + "…" + hex.takeLast(4) else hex

    /** 95 -> "1:35", 3700 -> "1h 1m". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "$m:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Formats a block header without double '#', e.g. "Block #48322201" or "Block —". */
    fun blockLabel(number: Long?): String = if (number != null) "Block #$number" else "Block —"
}
