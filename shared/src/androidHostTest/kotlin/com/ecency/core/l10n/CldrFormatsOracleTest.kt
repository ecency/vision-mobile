package com.ecency.core.l10n

import com.ibm.icu.number.Notation
import com.ibm.icu.number.NumberFormatter
import com.ibm.icu.number.Precision
import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.MeasureFormat
import com.ibm.icu.text.MessageFormat
import com.ibm.icu.text.RelativeDateTimeFormatter
import com.ibm.icu.util.Measure
import com.ibm.icu.util.MeasureUnit
import com.ibm.icu.util.NoUnit
import com.ibm.icu.util.TimeZone
import java.io.File
import kotlin.math.pow
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ICU4J 75.1 (CLDR 45) as the offline oracle of [LocaleFormatter], test classpath only: the vendored
 * `l10n/cldr-formats.json` is exactly what [CldrFormatsExtract] prints, and for every one of the 39 app languages the
 * formatter agrees with ICU4J on numbers (grouping, fraction digits, digits, minus), percent, compact numbers, every
 * date pattern (three time zones), relative times, durations and ages, and numbers inside messages. Rounding is
 * compared as ICU's HALF_UP on the exact value (JS toFixed and Intl round half away from zero).
 *
 * One deliberate difference: ICU4J's older formatters (MessageFormat, RelativeDateTimeFormatter, MeasureFormat) group
 * every number of four digits or more, ignoring CLDR's minimum grouping digits, which ICU's NumberFormatter and Intl
 * (formatjs, so RN's messages) honour; the app honours it everywhere (bg-BG "1234 секунди", not "1 234"). Those
 * comparisons swap ICU's grouped number for NumberFormatter's.
 */
class CldrFormatsOracleTest {
    private val ids = L10nData.LOCALES

    @Test
    fun vendoredFormatsAreIcu4jsData() {
        val expected = CldrFormatsExtract.json(ids)
        val file = File("l10n/cldr-formats.json")
        val actual = if (file.exists()) file.readText() else ""
        if (actual != expected) File("build/cldr-formats.json").writeText(expected)
        assertEquals(expected, actual, "shared/l10n/cldr-formats.json differs from ICU4J; the fresh file is in shared/build/cldr-formats.json")
    }

    private val numbers = listOf(
        0.0, 1.0, 7.0, 12.0, 123.0, 999.0, 1000.0, 1234.0, 12_345.0, 123_456.0, 1_234_567.0, 12_345_678.0, 1_234_567_890.0,
        0.5, 0.25, 0.123, 1.5, 12.34, 99.999, 1234.567, 1_234_567.891, 0.04, 21.0, 101.0,
        -1.0, -12.5, -1234.5, -0.75, -1_234_567.891,
    )

    private fun icuNumber(id: String, v: Double, min: Int, max: Int): String =
        NumberFormatter.withLocale(CldrFormatsExtract.locale(id))
            .precision(Precision.minMaxFraction(min, max)).roundingMode(RoundingMode.HALF_UP)
            .format(BigDecimal(v)).toString()

    /** ICU keeps the sign of a negative value that rounds to zero ("-0"); the app never shows "-0". */
    private fun roundsToNegativeZero(v: Double, max: Int) = v < 0 && BigDecimal(v).setScale(max, RoundingMode.HALF_UP).signum() == 0

    @Test
    fun numbersMatchIcu4jForEveryLanguage() {
        for (id in ids) {
            val f = LocaleFormatter.of(id)
            for (v in numbers) for ((min, max) in listOf(0 to 3, 2 to 2, 3 to 3, 0 to 0, 0 to 2)) {
                if (roundsToNegativeZero(v, max)) continue
                assertEquals(icuNumber(id, v, min, max), f.decimal(v, max, min), "$id $v $min..$max")
            }
            for (v in listOf(0.35, 0.12345, 0.5, 0.98765)) assertEquals(icuNumber(id, v, 5, 5), f.fixed(v, 5), "$id $v fiat")
            // ties beyond 3 decimals round on the exact binary value, half up (the binary value sits just above each)
            for ((v, d) in listOf(0.000005 to 5, 0.000125 to 5, 0.00025 to 4, 1.0000005 to 6, 2.5e-7 to 6, 1e-10 to 9, 0.0000125 to 6)) {
                assertEquals(icuNumber(id, v, d, d), f.fixed(v, d), "$id $v at $d")
            }
            assertEquals(icuNumber(id, 1_234_567.0, 0, 0), f.integer(1_234_567), "$id integer")
            assertEquals(icuNumber(id, 1234.5, 3, 3), f.posix("1234.500"), "$id posix")
        }
    }

    /** The POSIX base of every number: half up on the exact binary value, any decimals (JS toFixed). */
    @Test
    fun numFixedIsHalfUpOnTheExactValue() {
        val r = java.util.Random(7)
        val values = (0 until 3000).map {
            when (it % 4) {
                0 -> r.nextDouble() * 10
                1 -> (r.nextInt(2_000_000) / 8.0) / 10.0.pow(r.nextInt(8)) // binary ties and near ties
                2 -> r.nextDouble() * 1e12
                else -> -r.nextDouble() * 1000
            }
        } + listOf(0.000005, 0.000125, 0.00025, 1.005, 12.345, 2.5, 0.5, 1e15 + 0.5, 4.35, 1e-300, 123456789.123456789)
        for (v in values) for (d in 0..10) {
            val exact = BigDecimal(v).setScale(d, RoundingMode.HALF_UP)
            if (exact.signum() == 0 && v < 0) continue // JS keeps "-0.00"; the app never shows "-0"
            assertEquals(exact.toPlainString(), com.ecency.core.app.Num.fixed(v, d), "$v at $d")
        }
    }

    @Test
    fun percentMatchesIcu4jForEveryLanguage() {
        for (id in ids) {
            val f = LocaleFormatter.of(id)
            for (v in listOf(0.0, 12.0, 12.5, 3.123, 100.0, 1234.5, -12.0, -0.5)) for ((min, max) in listOf(0 to 0, 0 to 2, 3 to 3)) {
                if (roundsToNegativeZero(v, max)) continue
                val icu = NumberFormatter.withLocale(CldrFormatsExtract.locale(id)).unit(NoUnit.PERCENT)
                    .precision(Precision.minMaxFraction(min, max)).roundingMode(RoundingMode.HALF_UP).format(BigDecimal(v)).toString()
                assertEquals(icu, f.percent(v, max, min), "$id $v% $min..$max")
            }
        }
    }

    @Test
    fun compactNumbersMatchIcu4jForEveryLanguage() {
        val values = listOf(
            0.0, 12.0, 999.0, 999.5, 999.94, 999.96, 1000.0, 1234.0, 1500.0, 9999.0, 12_345.0, 99_999.0, 123_456.0, 999_949.0, 999_999.0,
            1_000_000.0, 1_234_567.0, 12_345_678.0, 123_456_789.0, 1_500_000_000.0, 2.5e12, 3.4e14, -1234.0, -2_500_000.0,
        )
        for (id in ids) {
            val f = LocaleFormatter.of(id)
            for (v in values) for (max in listOf(1, 2)) {
                val icu = NumberFormatter.withLocale(CldrFormatsExtract.locale(id)).notation(Notation.compactShort())
                    .precision(Precision.maxFraction(max)).roundingMode(RoundingMode.HALF_UP).format(BigDecimal(v)).toString()
                assertEquals(icu, f.compact(v, max, 0), "$id compact $v max $max")
            }
        }
    }

    /** 2024-03-05 15:07 UTC (a Tuesday), 2026-12-31 23:55, 1999-01-10 00:05 (a Sunday), 2025-07-20 12:00, 2025-09-01 09:30. */
    private val instants = listOf(1_709_651_220_000L, 1_798_761_300_000L, 916_013_100_000L, 1_753_012_800_000L, 1_756_719_000_000L)

    @Test
    fun datesMatchIcu4jForEveryLanguageAndPattern() {
        val zones = listOf("GMT" to 0L, "GMT+05:30" to 19_800_000L, "GMT-08:00" to -28_800_000L)
        for (id in ids) {
            val f = LocaleFormatter.of(id)
            for (p in LocaleFormatter.Pattern.entries) {
                for ((zone, offset) in zones) {
                    val icu = DateFormat.getInstanceForSkeleton(p.skeleton, CldrFormatsExtract.gregorian(id)).apply { timeZone = TimeZone.getTimeZone(zone) }
                    for (t in instants) assertEquals(icu.format(java.util.Date(t)), f.format(p, t, offset), "$id ${p.skeleton} $zone $t")
                }
                val utc = DateFormat.getInstanceForSkeleton(p.skeleton, CldrFormatsExtract.gregorian(id)).apply { timeZone = TimeZone.getTimeZone("GMT") }
                assertEquals(utc.format(java.util.Date(1_582_934_400_000L)), f.calendarDate(2020, 2, 29, p), "$id ${p.skeleton} calendar date")
            }
        }
    }

    /** [text] from an older ICU formatter with its number of [n] written the NumberFormatter way (see class doc). */
    private fun minGrouping(id: String, text: String, n: Double): String {
        val l = CldrFormatsExtract.locale(id)
        val old = com.ibm.icu.text.NumberFormat.getInstance(l).format(n)
        val new = NumberFormatter.withLocale(l).format(BigDecimal(n)).toString()
        return if (old == new) text else text.replace(old, new)
    }

    @Test
    fun relativeTimesDurationsAndAgesMatchIcu4jForEveryLanguage() {
        val counts = (0L..30L) + listOf(45L, 59L, 100L, 101L, 111L, 1234L)
        val relUnits = mapOf(
            TimeUnit.SECOND to RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND,
            TimeUnit.MINUTE to RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE,
            TimeUnit.HOUR to RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR,
            TimeUnit.DAY to RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY,
            TimeUnit.WEEK to RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK,
            TimeUnit.MONTH to RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH,
            TimeUnit.YEAR to RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR,
        )
        val measures = mapOf(
            TimeUnit.SECOND to MeasureUnit.SECOND, TimeUnit.MINUTE to MeasureUnit.MINUTE, TimeUnit.HOUR to MeasureUnit.HOUR,
            TimeUnit.DAY to MeasureUnit.DAY, TimeUnit.WEEK to MeasureUnit.WEEK, TimeUnit.MONTH to MeasureUnit.MONTH,
            TimeUnit.YEAR to MeasureUnit.YEAR,
        )
        for (id in ids) {
            val l = CldrFormatsExtract.locale(id)
            val f = LocaleFormatter.of(id)
            val rel = RelativeDateTimeFormatter.getInstance(l)
            val wide = MeasureFormat.getInstance(l, MeasureFormat.FormatWidth.WIDE)
            val narrow = MeasureFormat.getInstance(l, MeasureFormat.FormatWidth.NARROW)
            assertEquals(rel.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW), f.now(), "$id now")
            for ((unit, icuUnit) in relUnits) for (n in counts) {
                if (n > 0) {
                    val d = n.toDouble()
                    assertEquals(minGrouping(id, rel.formatNumeric(d, icuUnit), d), f.relative(n, unit, future = true), "$id in $n $unit")
                    assertEquals(minGrouping(id, rel.formatNumeric(-d, icuUnit), d), f.relative(n, unit, future = false), "$id $n $unit ago")
                }
                val m = measures.getValue(unit)
                assertEquals(minGrouping(id, wide.format(Measure(n, m)), n.toDouble()), f.duration(n, unit), "$id $n $unit wide")
                assertEquals(minGrouping(id, narrow.format(Measure(n, m)), n.toDouble()), f.age(n, unit), "$id $n $unit narrow")
            }
        }
    }

    @Test
    fun numbersInMessagesMatchIcuMessageFormatForEveryLanguage() {
        val pattern = "{n, plural, one {# item} other {# items}} {n, number} {n}"
        for (id in ids) {
            val f = LocaleFormatter.of(id)
            for (v in listOf(1.0, 3.0, 1234.0, 1_234_567.0, 2.5, 1234.567)) {
                val icu = minGrouping(id, MessageFormat(pattern, CldrFormatsExtract.locale(id)).format(mapOf("n" to v)), v)
                val ours = IcuMessage.parse(pattern).format(mapOf("n" to v), { L10nPluralRules.select(id, it) }, { it }, f::messageNumber)
                assertEquals(icu, ours, "$id $v")
            }
        }
    }
}
