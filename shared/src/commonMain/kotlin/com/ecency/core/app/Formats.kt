package com.ecency.core.app

import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.l10n.TimeUnit
import kotlin.math.abs
import kotlin.math.floor

/**
 * JS number formatting the RN screens use, always POSIX (ASCII digits, '.' decimal point, no grouping) whatever the
 * app language: this is the text that is signed, broadcast, sent to APIs and typed into amount fields. Text shown to
 * the user goes through [com.ecency.core.l10n.LocaleFormat] instead (README "Localization", formatting).
 */
object Num {
    /**
     * `Number.prototype.toFixed(decimals)`: rounds the exact binary value of [value] (12.345 is a bit above the
     * tie, so "12.35"; 1.005 a bit below, so "1.00"), ties away from zero, for any number of decimals (ICU's
     * HALF_UP on the exact value). "Infinity" as JS for an infinite value.
     */
    fun fixed(value: Double, decimals: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        require(decimals in 0..100) { "decimals $decimals" }
        val x = abs(value)
        val digits = (if (decimals <= 3 && x < 1e15) exactUnits(x, decimals).toString() else exactUnitDigits(x, decimals))
            .padStart(decimals + 1, '0')
        val whole = digits.dropLast(decimals).ifEmpty { "0" }
        val frac = digits.takeLast(decimals)
        val negative = value < 0 && digits.any { it != '0' }
        return (if (negative) "-" else "") + whole + if (decimals > 0) ".$frac" else ""
    }

    private fun parts(x: Double): Pair<Long, Int> {
        val bits = x.toRawBits()
        val exp = ((bits shr 52) and 0x7ff).toInt()
        val mantissa = if (exp == 0) (bits and 0xfffffffffffffL) else (bits and 0xfffffffffffffL) or (1L shl 52)
        return mantissa to (if (exp == 0) 1 else exp) - 1075
    }

    /** round(x * 10^f) half up on the exact value of the double x >= 0 (x = m * 2^e), f <= 3, x < 1e15. */
    private fun exactUnits(x: Double, f: Int): Long {
        if (x == 0.0) return 0
        val (mantissa, e) = parts(x)
        var scale = 1L
        repeat(f) { scale *= 10 }
        val num = mantissa * scale // < 2^53 * 1000 < 2^63
        if (e >= 0) return num shl e
        val shift = -e
        // below about 1e-3 the shift no longer fits a Long (0.0008 is still "0.001"): the digit path takes it
        if (shift >= 63) return exactUnitDigits(x, f).toLong()
        val n = num ushr shift
        val rem = num - (n shl shift)
        val half = 1L shl (shift - 1)
        return if (rem >= half) n + 1 else n
    }

    /**
     * The same for any x and f, in decimal digits (big endian): m * 10^f, then floor((m * 10^f + 2^(s-1)) / 2^s)
     * for x = m * 2^-s, computed as halving s - 1 times, adding one and halving once more.
     */
    private fun exactUnitDigits(x: Double, f: Int): String {
        if (x == 0.0) return "0"
        val (mantissa, e) = parts(x)
        var n = (mantissa.toString() + "0".repeat(f)).map { it - '0' }.toIntArray()
        if (e >= 0) {
            repeat(e) { n = times2(n) }
        } else {
            for (i in 0 until -e - 1) {
                n = half(n)
                if (n.size == 1 && n[0] == 0) return "0"
            }
            n = half(plusOne(n))
        }
        return n.joinToString("").trimStart('0').ifEmpty { "0" }
    }

    private fun half(n: IntArray): IntArray {
        val out = IntArray(n.size)
        var carry = 0
        for (i in n.indices) {
            val v = carry * 10 + n[i]
            out[i] = v / 2
            carry = v % 2
        }
        val first = out.indexOfFirst { it != 0 }
        return if (first <= 0) (if (first < 0) intArrayOf(0) else out) else out.copyOfRange(first, out.size)
    }

    private fun times2(n: IntArray): IntArray {
        val out = IntArray(n.size + 1)
        var carry = 0
        for (i in n.indices.reversed()) {
            val v = n[i] * 2 + carry
            out[i + 1] = v % 10
            carry = v / 10
        }
        out[0] = carry
        return if (carry == 0) out.copyOfRange(1, out.size) else out
    }

    private fun plusOne(n: IntArray): IntArray {
        val out = n.copyOf()
        for (i in out.indices.reversed()) {
            if (out[i] < 9) { out[i]++; return out }
            out[i] = 0
        }
        return intArrayOf(1) + out
    }

    /** `String(n)` for the values RN shows (integers without ".0", up to 10 decimals otherwise). */
    fun plain(value: Double): String {
        if (value == floor(value) && abs(value) < 1e15) return value.toLong().toString()
        return fixed(value, 10).trimEnd('0').trimEnd('.')
    }
}

/**
 * moment's `fromNow()` with its default thresholds ("a few seconds ago", "2 hours ago", "in 3 days"), in the app
 * language: en-US keeps moment's English exactly (RN parity, "a minute ago", "an hour ago"); the other languages
 * write the same bucket with CLDR's relative time ("vor 2 Stunden", "через 3 дня"), "now" for moment's few seconds.
 */
object FromNow {
    /** [format] for a chain ("YYYY-MM-DDTHH:MM:SS") or ISO time; empty when unreadable. */
    fun ofTime(value: String, nowMillis: Long): String {
        val millis = PostCard.epochMillis(value.take(19))
        return if (millis <= 0) "" else format(millis, nowMillis)
    }

    fun format(epochMillis: Long, nowMillis: Long): String =
        phrase(bucket(abs(epochMillis - nowMillis) / 1000.0), future = epochMillis > nowMillis)

    /** moment's `duration(...).humanize()` without a suffix ("a few seconds", "2 hours"), the same thresholds. */
    fun humanize(epochMillis: Long, nowMillis: Long): String {
        val b = bucket(abs(epochMillis - nowMillis) / 1000.0)
        if (english()) return b.english
        return LocaleFormat.duration(if (b.few) maxOf(1L, abs(epochMillis - nowMillis) / 1000) else b.count, b.unit)
    }

    /**
     * A moment humanize bucket: [unit] and [count], [few] for "a few seconds", [english] moment's text. [monthDays]
     * and [yearDays] are the lengths moment divides by; [minTwo] is moment's "at least 2" for the plural buckets.
     */
    internal class Bucket(val unit: TimeUnit, val count: Long, val few: Boolean, val english: String)

    internal fun bucket(seconds: Double, monthDays: Double = 30.4375, yearDays: Double = 365.25, minTwo: Boolean = true): Bucket {
        val s = seconds
        fun jsRound(x: Double) = floor(x + 0.5).toLong() // Math.round, as moment
        val minutes = jsRound(s / 60)
        val hours = jsRound(s / 3600)
        val days = jsRound(s / 86400)
        val months = jsRound(s / (86400 * monthDays)).let { if (minTwo) it.coerceAtLeast(2) else it }
        val years = jsRound(s / (86400 * yearDays)).let { if (minTwo) it.coerceAtLeast(2) else it }
        return when {
            s < 45 -> Bucket(TimeUnit.SECOND, 0, true, "a few seconds")
            s < 90 -> Bucket(TimeUnit.MINUTE, 1, false, "a minute")
            minutes < 45 -> Bucket(TimeUnit.MINUTE, minutes, false, "$minutes minutes")
            minutes < 90 -> Bucket(TimeUnit.HOUR, 1, false, "an hour")
            hours < 22 -> Bucket(TimeUnit.HOUR, hours, false, "$hours hours")
            hours < 36 -> Bucket(TimeUnit.DAY, 1, false, "a day")
            days < 26 -> Bucket(TimeUnit.DAY, days, false, "$days days")
            days < 46 -> Bucket(TimeUnit.MONTH, 1, false, "a month")
            days < 320 -> Bucket(TimeUnit.MONTH, months, false, "$months months")
            days < 548 -> Bucket(TimeUnit.YEAR, 1, false, "a year")
            else -> Bucket(TimeUnit.YEAR, years, false, "$years years")
        }
    }

    /** "in 3 days" or "3 days ago" for a bucket, in the app language. */
    internal fun phrase(b: Bucket, future: Boolean): String = when {
        english() -> if (future) "in ${b.english}" else "${b.english} ago"
        b.few -> LocaleFormat.now()
        else -> LocaleFormat.relative(b.count, b.unit, future)
    }

    private fun english() = L10n.language.value == L10n.SOURCE
}
