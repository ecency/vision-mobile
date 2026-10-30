package com.ecency.core.l10n

import com.ecency.core.app.Num
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The units of relative times, durations and ages. */
enum class TimeUnit(internal val key: String) {
    SECOND("second"), MINUTE("minute"), HOUR("hour"), DAY("day"), WEEK("week"), MONTH("month"), YEAR("year"),
}

/**
 * Display formatting in the app language ([L10n.language]): numbers, amounts for display, percent, compact numbers,
 * dates and times, relative times and short ages. Every call reads the current language, so text built after a
 * language switch follows it. [LocaleFormatter] does the same for one fixed language (tests, previews).
 *
 * Display only. Text that is broadcast, signed or sent to an API (operation amounts such as "1.000 HIVE", memos,
 * custom_json quantities, hive-uri) never goes through here: it comes from `Amounts`, `Num` and `Asset`, which are
 * POSIX by construction (ASCII digits, '.' decimal point, no grouping) and do not read the language; README
 * "Localization", formatting.
 */
object LocaleFormat {
    private val current: LocaleFormatter get() = LocaleFormatter.of(L10n.language.value)

    /** The formatter of the current language. */
    val formatter: LocaleFormatter get() = current

    fun decimal(value: Double, maxFraction: Int, minFraction: Int): String = current.decimal(value, maxFraction, minFraction)

    /** Up to 3 fraction digits, grouped (ICU and Intl defaults). */
    fun decimal(value: Double): String = current.decimal(value, 3, 0)

    fun fixed(value: Double, decimals: Int): String = current.fixed(value, decimals)

    fun integer(value: Long): String = current.integer(value)

    fun posix(text: String): String = current.posix(text)

    fun posix(text: String, grouping: Boolean): String = current.posix(text, grouping)

    fun toFixed(value: Double, decimals: Int): String = current.toFixed(value, decimals)

    fun assetText(text: String): String = current.assetText(text)

    fun amountsIn(text: String): String = current.amountsIn(text)

    fun percent(value: Double, maxFraction: Int, minFraction: Int): String = current.percent(value, maxFraction, minFraction)

    fun fiat(value: Double, symbol: String, decimals: Int): String = current.fiat(value, symbol, decimals)

    fun token(value: Double, decimals: Int, symbol: String): String = current.token(value, decimals, symbol)

    fun compact(value: Double, maxFraction: Int, power: Int): String = current.compact(value, maxFraction, power)

    fun compact(value: Double, maxFraction: Int): String = current.compact(value, maxFraction, 0)

    fun compact(value: Double, maxFraction: Int, power: Int, minFraction: Int): String = current.compact(value, maxFraction, power, minFraction)

    fun compact(value: Double, maxFraction: Int, power: Int, minFraction: Int, round: ((Double) -> Double)?): String =
        current.compact(value, maxFraction, power, minFraction, round)

    fun uses24Hour(): Boolean = current.uses24Hour

    fun dateShort(epochMillis: Long): String = current.format(LocaleFormatter.Pattern.DATE_SHORT, epochMillis)

    fun date(epochMillis: Long): String = current.format(LocaleFormatter.Pattern.DATE, epochMillis)

    fun dateLong(epochMillis: Long): String = current.format(LocaleFormatter.Pattern.DATE_LONG, epochMillis)

    fun time(epochMillis: Long): String = current.format(LocaleFormatter.Pattern.TIME, epochMillis)

    fun dateTime(epochMillis: Long): String = current.format(LocaleFormatter.Pattern.DATE_TIME, epochMillis)

    fun weekdayDateTime(epochMillis: Long): String = current.format(LocaleFormatter.Pattern.WEEKDAY_DATE_TIME, epochMillis)

    /** A calendar date as it is (no time zone): [month] 1 to 12. */
    fun calendarDate(year: Int, month: Int, day: Int, pattern: LocaleFormatter.Pattern): String = current.calendarDate(year, month, day, pattern)

    fun relative(count: Long, unit: TimeUnit, future: Boolean): String = current.relative(count, unit, future)

    fun now(): String = current.now()

    fun duration(count: Long, unit: TimeUnit): String = current.duration(count, unit)

    fun age(count: Long, unit: TimeUnit): String = current.age(count, unit)
}

/**
 * Formatting for one app language ([languageId], one of [L10n.supported]; anything else formats as en-US) from the
 * CLDR data vendored in `shared/l10n/cldr-formats.json` (ICU4J 75.1, CLDR 45, checked against ICU4J by the host test
 * `CldrFormatsOracleTest` for all 39 languages). Pure Kotlin, so both platforms and the tests format alike.
 *
 * - Digits: the language's CLDR default numbering system, as Android and iOS use for that locale: Arabic-Indic
 *   digits for ar-SA, Persian digits for fa-IR, Bengali digits for bn-BD, ASCII digits for every other language
 *   (hi-IN included, whose CLDR default is Latin). Dates and relative times use them too.
 * - Numbers: grouping as CLDR (Indian 3;2 for hi-IN and bn-BD; minimum grouping digits, so es-ES, pl-PL, pt-PT and
 *   a few more write 1234 but 12 345); rounding half away from zero on the exact binary value (JS toFixed, Intl's
 *   halfExpand); no "-0".
 * - Dates: Gregorian in every language (CLDR would default ar-SA to the Islamic, fa-IR to the Persian and th-TH to
 *   the Buddhist calendar; chain times and RN show Gregorian); 12 or 24 hours as the language's CLDR default.
 */
@OptIn(ExperimentalAtomicApi::class)
class LocaleFormatter private constructor(val languageId: String, private val d: LocaleData) {
    /** The date and time patterns, by the CLDR skeleton they come from. */
    enum class Pattern(internal val skeleton: String) {
        /** "3/5/2024" (RN toLocaleDateString). */
        DATE_SHORT("yMd"),

        /** "Mar 5, 2024". */
        DATE("yMMMd"),

        /** "March 5, 2024" (moment 'LL'). */
        DATE_LONG("yMMMMd"),

        /** "3:07 PM" or "15:07" as the language does. */
        TIME("jmm"),

        /** "Mar 5, 2024, 3:07 PM". */
        DATE_TIME("yMMMdjmm"),

        /** "Mar 05" (RN balance analytics labels). */
        MONTH_DAY("MMMdd"),

        /** "Tue, Mar 5, 3:07 PM" (editor schedule). */
        WEEKDAY_DATE_TIME("MMMEdjmm"),
    }

    // ---- numbers

    /** [value] with at most [maxFraction] and at least [minFraction] fraction digits, grouped. */
    fun decimal(value: Double, maxFraction: Int, minFraction: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return (if (value < 0) d.minus else "") + "∞"
        return posixToLocal(Num.fixed(value, maxFraction), minFraction.coerceAtMost(maxFraction))
    }

    /** Exactly [decimals] fraction digits (toFixed), grouped. */
    fun fixed(value: Double, decimals: Int): String = decimal(value, decimals, decimals)

    fun integer(value: Long): String = posixToLocal(value.toString(), 0)

    /**
     * JS `toFixed(decimals)` in this language: its digits, decimal separator and minus, and like toFixed no grouping
     * ("1234.50" is "1234,50" in de-DE). For the numbers RN prints with toFixed, so en-US stays exactly RN's text.
     */
    fun toFixed(value: Double, decimals: Int): String =
        if (!value.isFinite()) decimal(value, decimals, decimals) else posixToLocal(Num.fixed(value, decimals), decimals, grouping = false)

    /**
     * A plain decimal text that is already rounded ("1234.500", "-0.5") in this language's digits and separators,
     * every fraction digit kept, grouped when [grouping]; any other text is returned unchanged.
     */
    fun posix(text: String, grouping: Boolean): String = if (POSIX.matches(text)) posixToLocal(text, Int.MAX_VALUE, grouping = grouping) else text

    fun posix(text: String): String = posix(text, grouping = true)

    /**
     * An asset text from the chain or an API ("1.000 HIVE", "-0.5 HBD") with its number in this language and the
     * symbol kept, no grouping (as the chain writes it); any other text unchanged. Display only.
     */
    fun assetText(text: String): String {
        val m = ASSET.matchEntire(text.trim()) ?: return text
        return posixToLocal(m.groupValues[1], Int.MAX_VALUE, grouping = false) + " " + m.groupValues[2]
    }

    /** Every amount in [text] ("1.000 HBD 2.500 HIVE", "0.1 HIVE = 0.3 HBD") written as [assetText] does. Display only. */
    fun amountsIn(text: String): String = AMOUNT_IN_TEXT.replace(text) { posixToLocal(it.value, Int.MAX_VALUE, grouping = false) }

    /** [value] in percent units (12.5 is "12.5%", "12,5 %" in de-DE). */
    fun percent(value: Double, maxFraction: Int, minFraction: Int): String {
        val n = decimal(abs(value), maxFraction, minFraction)
        val negative = value < 0 && Num.fixed(value, maxFraction).startsWith("-")
        return (if (negative) d.minus else "") + d.percentPrefix + n + d.percentSuffix
    }

    /** RN formatAmount with a currency symbol: the symbol in front (RN does so in every language), then the number. */
    fun fiat(value: Double, symbol: String, decimals: Int): String = symbol + fixed(value, decimals)

    /** A token amount for display: the number, a space and the ticker (never translated): "1.234,500 HIVE". */
    fun token(value: Double, decimals: Int, symbol: String): String = fixed(value, decimals) + " " + symbol

    /**
     * A short compact number ("1.2K", "1,2 Mio.", "12万"), at most [maxFraction] fraction digits. [power] is the
     * magnitude RN's own rule chose (3 thousands, 6 millions, 9 billions; 0 lets CLDR choose): when this language
     * abbreviates exactly that magnitude with one integer digit (en "0K", ru "0 тыс."), [value] is shown at it,
     * keeping RN's thresholds (RN's 100K is "0.1M"); otherwise CLDR's own magnitude is used (ja has no thousands
     * and writes 12,345 or 1.2万; de abbreviates from millions).
     */
    fun compact(value: Double, maxFraction: Int, power: Int): String = compact(value, maxFraction, power, 0)

    /** [compact] with at least [minFraction] fraction digits ("2.00B"). */
    fun compact(value: Double, maxFraction: Int, power: Int, minFraction: Int): String = compact(value, maxFraction, power, minFraction, null)

    /**
     * [compact] where [round] rounds the value scaled to RN's [power] first, for RN rules that round their own way
     * (makeCountFriendly's `Math.round(v * 10) / 10`, which makes 1450 "1.5K").
     */
    fun compact(value: Double, maxFraction: Int, power: Int, minFraction: Int, round: ((Double) -> Double)?): String {
        if (!value.isFinite()) return decimal(value, maxFraction, 0)
        if (power in 3..14) {
            val byCat = d.compact[power - 3]
            if (byCat.values.all { zeros(it) == 1 && it.trim() != "0" }) {
                val scaled = value / 10.0.pow(power)
                return compactAt(round?.invoke(scaled) ?: scaled, maxFraction, minFraction, byCat)
            }
        }
        // the magnitude of the value as it would show unabbreviated (999.5 stays "999.5"; 999.96 at 1 decimal is 1K)
        val a = abs(Num.fixed(value, maxFraction).toDouble())
        if (a < 1000) return decimal(value, maxFraction, minFraction)
        var p = minOf(14, floor(log10(a)).toInt())
        while (true) {
            val byCat = d.compact[p - 3]
            val z = zeros(byCat.getValue("other"))
            // not abbreviated at this magnitude: the whole number, grouped from five digits as ICU's compact notation
            if (byCat.getValue("other").trim() == "0") return posixToLocal(Num.fixed(value, 0), 0, minGrouping = maxOf(2, d.minGrouping))
            val scaled = value / 10.0.pow(p - z + 1)
            // rounding can reach the next power (999.96K is 1M)
            if (p < 14 && abs(Num.fixed(scaled, maxFraction).toDouble()) >= 10.0.pow(z)) { p++; continue }
            return compactAt(scaled, maxFraction, minFraction, byCat)
        }
    }

    private fun compactAt(scaled: Double, maxFraction: Int, minFraction: Int, byCat: Map<String, String>): String {
        val rounded = Num.fixed(scaled, maxFraction)
        var shown = rounded
        if ('.' in shown) {
            val keep = rounded.indexOf('.') + 1 + minFraction
            while (shown.length > keep && shown.endsWith('0')) shown = shown.dropLast(1)
            shown = shown.removeSuffix(".")
        }
        val pattern = byCat[plural(abs(shown.toDouble()))] ?: byCat.getValue("other")
        // compact notation groups from five integer digits (ICU's MIN2), "1234.6万"
        val n = posixToLocal(shown.removePrefix("-"), Int.MAX_VALUE, minGrouping = maxOf(2, d.minGrouping))
        val body = pattern.replace(ZEROS, Regex.escapeReplacement(n))
        return if (shown.startsWith("-") && shown.trim('-', '0', '.').isNotEmpty()) d.minus + body else body
    }

    private fun zeros(pattern: String): Int = ZEROS.find(pattern)?.value?.length ?: 0

    /** The number in a message (`#`, `{n, number}`, a number in `{n}`): grouped, at most 3 fraction digits. */
    internal fun messageNumber(value: Double): String = decimal(value, 3, 0)

    private fun posixToLocal(posix: String, minFraction: Int, minGrouping: Int = d.minGrouping, grouping: Boolean = true): String {
        val negative = posix.startsWith('-')
        val body = if (negative) posix.substring(1) else posix
        val whole = body.substringBefore('.')
        var frac = body.substringAfter('.', "")
        if (minFraction != Int.MAX_VALUE) {
            var end = frac.length
            while (end > minFraction && frac[end - 1] == '0') end--
            frac = frac.substring(0, end)
        }
        val b = StringBuilder()
        if (negative && (whole.any { it != '0' } || frac.any { it != '0' })) b.append(d.minus)
        appendGrouped(b, whole, if (grouping) minGrouping else Int.MAX_VALUE / 2)
        if (frac.isNotEmpty()) {
            b.append(d.decimal)
            for (c in frac) b.append(digit(c))
        }
        return b.toString()
    }

    private fun appendGrouped(b: StringBuilder, whole: String, minGrouping: Int) {
        val n = whole.length
        val grouped = n >= d.primary + minGrouping
        for (i in 0 until n) {
            b.append(digit(whole[i]))
            val left = n - 1 - i // digits after this one
            if (!grouped || left == 0) continue
            val separate = left == d.primary || (left > d.primary && (left - d.primary) % d.secondary == 0)
            if (separate) b.append(d.group)
        }
    }

    private fun digit(c: Char): Char = if (c in '0'..'9') d.digits[c - '0'] else c

    private fun digits(n: Int, width: Int): String {
        val s = n.toString().padStart(width, '0')
        val b = StringBuilder(s.length)
        for (c in s) b.append(digit(c))
        return b.toString()
    }

    private fun plural(n: Double): String = L10nPluralRules.select(languageId, PluralOperands.of(n))

    // ---- dates

    /** The language writes times with a 24 hour clock (CLDR's time pattern uses H), for time pickers. */
    val uses24Hour: Boolean by lazy { d.patterns[Pattern.TIME.ordinal].replace(Regex("'[^']*'"), "").contains('H') }

    /** [epochMillis] in the device's time zone. */
    fun format(pattern: Pattern, epochMillis: Long): String = format(pattern, epochMillis, localOffsetMillis(epochMillis))

    /** [epochMillis] shifted by [offsetMillis] (the time zone's offset from UTC at that instant). */
    fun format(pattern: Pattern, epochMillis: Long, offsetMillis: Long): String {
        val local = epochMillis + offsetMillis
        val days = local.floorDiv(DAY_MILLIS)
        val ms = local.mod(DAY_MILLIS)
        return render(d.patterns[pattern.ordinal], days, (ms / 3_600_000).toInt(), ((ms / 60_000) % 60).toInt())
    }

    /** A calendar date as it is ([month] 1 to 12), time fields at midnight. */
    fun calendarDate(year: Int, month: Int, day: Int, pattern: Pattern): String =
        render(d.patterns[pattern.ordinal], daysFromCivil(year.toLong(), month, day), 0, 0)

    private fun render(pattern: String, days: Long, hour: Int, minute: Int): String {
        val (y, mo, dd) = civil(days)
        val weekday = (days + 4).mod(7L).toInt() // 1970-01-01 was a Thursday; 0 is Sunday
        val b = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                if (pattern.getOrNull(i + 1) == '\'') { b.append('\''); i += 2; continue }
                i++
                while (i < pattern.length) {
                    if (pattern[i] == '\'') {
                        if (pattern.getOrNull(i + 1) == '\'') { b.append('\''); i += 2; continue }
                        break
                    }
                    b.append(pattern[i]); i++
                }
                i++
                continue
            }
            if (c !in 'a'..'z' && c !in 'A'..'Z') { b.append(c); i++; continue }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            when (c) {
                'y' -> b.append(if (n == 2) digits((y % 100).toInt(), 2) else digits(y.toInt(), 1))
                'M' -> b.append(when (n) { 1, 2 -> digits(mo, n); 3 -> d.monthsShort[mo - 1]; else -> d.monthsWide[mo - 1] })
                'L' -> b.append(if (n <= 2) digits(mo, n) else d.monthsStandalone[mo - 1])
                'd' -> b.append(digits(dd, n))
                'E' -> b.append(d.weekdays[weekday])
                'c' -> b.append(d.weekdaysStandalone[weekday])
                'a' -> b.append(d.ampm[if (hour < 12) 0 else 1])
                'h' -> b.append(digits(if (hour % 12 == 0) 12 else hour % 12, n))
                'H' -> b.append(digits(hour, n))
                'm' -> b.append(digits(minute, n))
                // the generator refuses any other field, so this is unreachable with the vendored data
                else -> b.append(pattern, i, i + n)
            }
            i += n
        }
        return b.toString()
    }

    // ---- relative times

    /** "3 hours ago" or "in 3 hours" (CLDR numeric relative time). */
    fun relative(count: Long, unit: TimeUnit, future: Boolean): String = unitText(if (future) d.future else d.past, count, unit)

    /** "now" (CLDR's relative time for this second). */
    fun now(): String = d.now

    /** "3 hours" (CLDR wide duration). */
    fun duration(count: Long, unit: TimeUnit): String = unitText(d.wide, count, unit)

    /** "3h" (CLDR narrow duration), the cards' ages. */
    fun age(count: Long, unit: TimeUnit): String = unitText(d.narrow, count, unit)

    private fun unitText(table: Map<String, Map<String, String>>, count: Long, unit: TimeUnit): String {
        val byCat = table.getValue(unit.key)
        val pattern = byCat[plural(abs(count.toDouble()))] ?: byCat.getValue("other")
        return pattern.replace("{0}", integer(count))
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private val ZEROS = Regex("0+")
        private val POSIX = Regex("^-?[0-9]+(\\.[0-9]+)?$")
        private val AMOUNT_IN_TEXT = Regex("(?<![0-9A-Za-z.])-?[0-9]+(?:\\.[0-9]+)?(?= [A-Z])")
        private val ASSET = Regex("^(-?[0-9]+(?:\\.[0-9]+)?) ([A-Z][A-Z0-9.]*)$")
        private val cache = AtomicReference<Map<String, LocaleFormatter>>(emptyMap())

        /** The formatter of [languageId] (matched like [L10n.setLanguage]; unknown is en-US), built once. */
        fun of(languageId: String): LocaleFormatter {
            cache.load()[languageId]?.let { return it }
            val id = LocaleMatch.registered(languageId) ?: L10n.SOURCE
            val f = cache.load()[id] ?: LocaleFormatter(id, LocaleData.parse(requireNotNull(L10nFormatData.json(id) ?: L10nFormatData.json(L10n.SOURCE))))
            while (true) {
                val old = cache.load()
                if (cache.compareAndSet(old, old + (languageId to f) + (id to f))) return f
            }
        }

        /** Days since 1970-01-01 to (year, month, day), Howard Hinnant's civil_from_days. */
        internal fun civil(z0: Long): Triple<Long, Int, Int> {
            val z = z0 + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
        }

        /** (year, month, day) to days since 1970-01-01, days_from_civil. */
        internal fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = y.floorDiv(400L)
            val yoe = y - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }
    }
}

/** One language's CLDR data, decoded from the generated JSON text ([L10nFormatData]). */
internal class LocaleData(
    val digits: CharArray,
    val decimal: String,
    val group: String,
    val minus: String,
    val primary: Int,
    val secondary: Int,
    val minGrouping: Int,
    val percentPrefix: String,
    val percentSuffix: String,
    /** Index 0 is 10^3; plural category to pattern. */
    val compact: List<Map<String, String>>,
    /** By [LocaleFormatter.Pattern] ordinal. */
    val patterns: List<String>,
    val monthsShort: List<String>,
    val monthsWide: List<String>,
    val monthsStandalone: List<String>,
    val weekdays: List<String>,
    val weekdaysStandalone: List<String>,
    val ampm: List<String>,
    val now: String,
    val past: Map<String, Map<String, String>>,
    val future: Map<String, Map<String, String>>,
    val wide: Map<String, Map<String, String>>,
    val narrow: Map<String, Map<String, String>>,
) {
    companion object {
        fun parse(json: String): LocaleData {
            val o = Json.parseToJsonElement(json).jsonObject
            fun s(k: String) = o.getValue(k).jsonPrimitive.content
            fun list(k: String) = o.getValue(k).jsonArray.map { it.jsonPrimitive.content }
            fun table(e: JsonObject) = e.mapValues { (_, v) -> v.jsonObject.mapValues { (_, p) -> p.jsonPrimitive.content } }
            val grouping = o.getValue("grouping").jsonArray.map { it.jsonPrimitive.int }
            val percent = list("percent")
            val compact = o.getValue("compact").jsonObject
            val dates = o.getValue("dates").jsonObject
            return LocaleData(
                digits = s("digits").toCharArray(),
                decimal = s("decimal"), group = s("group"), minus = s("minus"),
                primary = grouping[0], secondary = grouping[1], minGrouping = grouping[2],
                percentPrefix = percent[0], percentSuffix = percent[1],
                compact = (3..14).map { p -> compact.getValue(p.toString()).jsonObject.mapValues { it.value.jsonPrimitive.content } },
                patterns = LocaleFormatter.Pattern.entries.map { dates.getValue(it.skeleton).jsonPrimitive.content },
                monthsShort = list("MMM"), monthsWide = list("MMMM"), monthsStandalone = list("LLL"),
                weekdays = list("EEE"), weekdaysStandalone = list("ccc"), ampm = list("ampm"),
                now = s("now"),
                past = table(o.getValue("past").jsonObject), future = table(o.getValue("future").jsonObject),
                wide = table(o.getValue("wide").jsonObject), narrow = table(o.getValue("narrow").jsonObject),
            )
        }
    }
}

/** The device time zone's offset from UTC at [epochMillis] (daylight saving included). */
internal expect fun localOffsetMillis(epochMillis: Long): Long
