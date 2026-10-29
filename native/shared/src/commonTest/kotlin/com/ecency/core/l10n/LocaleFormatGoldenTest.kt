package com.ecency.core.l10n

import com.ecency.core.l10n.LocaleFormatter.Pattern as P
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per language goldens of [LocaleFormatter] (the host test CldrFormatsOracleTest checks all 39 languages against
 * ICU4J; these run on every target, the iOS simulator included, so both platforms are pinned to the same text).
 * [T] is 2024-03-05 15:07 UTC, a Tuesday; times are formatted in UTC. Invisible characters are written as escapes:
 * U+202F narrow no break space (en times), U+00A0 no break space, U+061C Arabic letter mark, U+200F right to left mark.
 */
class LocaleFormatGoldenTest {
    private val T = 1_709_651_220_000L

    @AfterTest
    fun english() = L10n.setLanguage(L10n.SOURCE)

    @Test
    fun enGoldens() = with(LocaleFormatter.of("en-US")) {
        assertEquals("1,234,567.891", decimal(1234567.891, 3, 0))
        assertEquals("0.50", fixed(0.5, 2))
        assertEquals("-1,234.50", decimal(-1234.5, 2, 2))
        assertEquals("1,234", integer(1234))
        assertEquals("\$1,234.50", fiat(1234.5, "$", 2))
        assertEquals("1,234.500 HIVE", token(1234.5, 3, "HIVE"))
        assertEquals("12.5%", percent(12.5, 1, 0))
        assertEquals("1.2K", compact(1234.0, 1, 3))
        assertEquals("1.5M", compact(1_500_000.0, 1, 6))
        assertEquals("0.1M", compact(123_456.0, 1, 6))
        assertEquals("3/5/2024", format(P.DATE_SHORT, T, 0))
        assertEquals("Mar 5, 2024", format(P.DATE, T, 0))
        assertEquals("March 5, 2024", format(P.DATE_LONG, T, 0))
        assertEquals("3:07\u202fPM", format(P.TIME, T, 0))
        assertEquals("Mar 5, 2024, 3:07\u202fPM", format(P.DATE_TIME, T, 0))
        assertEquals("Mar 05", format(P.MONTH_DAY, T, 0))
        assertEquals("Tue, Mar 5, 3:07\u202fPM", format(P.WEEKDAY_DATE_TIME, T, 0))
        assertEquals("3 hours ago", relative(3, TimeUnit.HOUR, future = false))
        assertEquals("in 2 days", relative(2, TimeUnit.DAY, future = true))
        assertEquals("1 minute ago", relative(1, TimeUnit.MINUTE, future = false))
        assertEquals("5 years ago", relative(5, TimeUnit.YEAR, future = false))
        assertEquals("now", now())
        assertEquals("3 years", duration(3, TimeUnit.YEAR))
        assertEquals("5m", age(5, TimeUnit.MINUTE))
        assertEquals("3h", age(3, TimeUnit.HOUR))
        assertEquals("2d", age(2, TimeUnit.DAY))
        assertEquals("4w", age(4, TimeUnit.WEEK))
        assertEquals("1y", age(1, TimeUnit.YEAR))
    }

    @Test
    fun deGoldens() = with(LocaleFormatter.of("de-DE")) {
        assertEquals("1.234.567,891", decimal(1234567.891, 3, 0))
        assertEquals("0,50", fixed(0.5, 2))
        assertEquals("-1.234,50", decimal(-1234.5, 2, 2))
        assertEquals("1.234", integer(1234))
        assertEquals("\$1.234,50", fiat(1234.5, "$", 2))
        assertEquals("1.234,500 HIVE", token(1234.5, 3, "HIVE"))
        assertEquals("12,5\u00a0%", percent(12.5, 1, 0))
        assertEquals("1234", compact(1234.0, 1, 3))
        assertEquals("1,5\u00a0Mio.", compact(1_500_000.0, 1, 6))
        assertEquals("0,1\u00a0Mio.", compact(123_456.0, 1, 6))
        assertEquals("5.3.2024", format(P.DATE_SHORT, T, 0))
        assertEquals("5. März 2024", format(P.DATE, T, 0))
        assertEquals("5. März 2024", format(P.DATE_LONG, T, 0))
        assertEquals("15:07", format(P.TIME, T, 0))
        assertEquals("5. März 2024, 15:07", format(P.DATE_TIME, T, 0))
        assertEquals("05. März", format(P.MONTH_DAY, T, 0))
        assertEquals("Di., 5. März, 15:07", format(P.WEEKDAY_DATE_TIME, T, 0))
        assertEquals("vor 3 Stunden", relative(3, TimeUnit.HOUR, future = false))
        assertEquals("in 2 Tagen", relative(2, TimeUnit.DAY, future = true))
        assertEquals("vor 1 Minute", relative(1, TimeUnit.MINUTE, future = false))
        assertEquals("vor 5 Jahren", relative(5, TimeUnit.YEAR, future = false))
        assertEquals("jetzt", now())
        assertEquals("3 Jahre", duration(3, TimeUnit.YEAR))
        assertEquals("5 Min.", age(5, TimeUnit.MINUTE))
        assertEquals("3 Std.", age(3, TimeUnit.HOUR))
        assertEquals("2 T", age(2, TimeUnit.DAY))
        assertEquals("4 W", age(4, TimeUnit.WEEK))
        assertEquals("1 J", age(1, TimeUnit.YEAR))
    }

    @Test
    fun ruGoldens() = with(LocaleFormatter.of("ru-RU")) {
        assertEquals("1\u00a0234\u00a0567,891", decimal(1234567.891, 3, 0))
        assertEquals("0,50", fixed(0.5, 2))
        assertEquals("-1\u00a0234,50", decimal(-1234.5, 2, 2))
        assertEquals("1\u00a0234", integer(1234))
        assertEquals("\$1\u00a0234,50", fiat(1234.5, "$", 2))
        assertEquals("1\u00a0234,500 HIVE", token(1234.5, 3, "HIVE"))
        assertEquals("12,5\u00a0%", percent(12.5, 1, 0))
        assertEquals("1,2\u00a0тыс.", compact(1234.0, 1, 3))
        assertEquals("1,5\u00a0млн", compact(1_500_000.0, 1, 6))
        assertEquals("0,1\u00a0млн", compact(123_456.0, 1, 6))
        assertEquals("05.03.2024", format(P.DATE_SHORT, T, 0))
        assertEquals("5 мар. 2024\u202fг.", format(P.DATE, T, 0))
        assertEquals("5 марта 2024\u202fг.", format(P.DATE_LONG, T, 0))
        assertEquals("15:07", format(P.TIME, T, 0))
        assertEquals("5 мар. 2024\u202fг., 15:07", format(P.DATE_TIME, T, 0))
        assertEquals("05 мар.", format(P.MONTH_DAY, T, 0))
        assertEquals("вт, 5 мар., 15:07", format(P.WEEKDAY_DATE_TIME, T, 0))
        assertEquals("3 часа назад", relative(3, TimeUnit.HOUR, future = false))
        assertEquals("через 2 дня", relative(2, TimeUnit.DAY, future = true))
        assertEquals("1 минуту назад", relative(1, TimeUnit.MINUTE, future = false))
        assertEquals("5 лет назад", relative(5, TimeUnit.YEAR, future = false))
        assertEquals("сейчас", now())
        assertEquals("3 года", duration(3, TimeUnit.YEAR))
        assertEquals("5 мин", age(5, TimeUnit.MINUTE))
        assertEquals("3 ч", age(3, TimeUnit.HOUR))
        assertEquals("2 д.", age(2, TimeUnit.DAY))
        assertEquals("4 н.", age(4, TimeUnit.WEEK))
        assertEquals("1 г.", age(1, TimeUnit.YEAR))
    }

    @Test
    fun arGoldens() = with(LocaleFormatter.of("ar-SA")) {
        assertEquals("١٬٢٣٤٬٥٦٧٫٨٩١", decimal(1234567.891, 3, 0))
        assertEquals("٠٫٥٠", fixed(0.5, 2))
        assertEquals("\u061c-١٬٢٣٤٫٥٠", decimal(-1234.5, 2, 2))
        assertEquals("١٬٢٣٤", integer(1234))
        assertEquals("\$١٬٢٣٤٫٥٠", fiat(1234.5, "$", 2))
        assertEquals("١٬٢٣٤٫٥٠٠ HIVE", token(1234.5, 3, "HIVE"))
        assertEquals("١٢٫٥٪\u061c", percent(12.5, 1, 0))
        assertEquals("١٫٢\u00a0ألف", compact(1234.0, 1, 3))
        assertEquals("١٫٥\u00a0مليون", compact(1_500_000.0, 1, 6))
        assertEquals("٠٫١\u00a0مليون", compact(123_456.0, 1, 6))
        assertEquals("٥\u200f/٣\u200f/٢٠٢٤", format(P.DATE_SHORT, T, 0))
        assertEquals("٥ مارس ٢٠٢٤", format(P.DATE, T, 0))
        assertEquals("٥ مارس ٢٠٢٤", format(P.DATE_LONG, T, 0))
        assertEquals("٣:٠٧ م", format(P.TIME, T, 0))
        assertEquals("٥ مارس ٢٠٢٤، ٣:٠٧ م", format(P.DATE_TIME, T, 0))
        assertEquals("٠٥ مارس", format(P.MONTH_DAY, T, 0))
        assertEquals("الثلاثاء، ٥ مارس، ٣:٠٧ م", format(P.WEEKDAY_DATE_TIME, T, 0))
        assertEquals("قبل ٣ ساعات", relative(3, TimeUnit.HOUR, future = false))
        assertEquals("خلال يومين", relative(2, TimeUnit.DAY, future = true))
        assertEquals("قبل دقيقة واحدة", relative(1, TimeUnit.MINUTE, future = false))
        assertEquals("قبل ٥ سنوات", relative(5, TimeUnit.YEAR, future = false))
        assertEquals("الآن", now())
        assertEquals("٣ سنوات", duration(3, TimeUnit.YEAR))
        assertEquals("٥ د", age(5, TimeUnit.MINUTE))
        assertEquals("٣ س", age(3, TimeUnit.HOUR))
        assertEquals("٢ ي", age(2, TimeUnit.DAY))
        assertEquals("٤ أ", age(4, TimeUnit.WEEK))
        assertEquals("سنة", age(1, TimeUnit.YEAR))
    }

    @Test
    fun jaGoldens() = with(LocaleFormatter.of("ja-JP")) {
        assertEquals("1,234,567.891", decimal(1234567.891, 3, 0))
        assertEquals("0.50", fixed(0.5, 2))
        assertEquals("-1,234.50", decimal(-1234.5, 2, 2))
        assertEquals("1,234", integer(1234))
        assertEquals("\$1,234.50", fiat(1234.5, "$", 2))
        assertEquals("1,234.500 HIVE", token(1234.5, 3, "HIVE"))
        assertEquals("12.5%", percent(12.5, 1, 0))
        assertEquals("1234", compact(1234.0, 1, 3))
        assertEquals("150万", compact(1_500_000.0, 1, 6))
        assertEquals("12.3万", compact(123_456.0, 1, 6))
        assertEquals("2024/3/5", format(P.DATE_SHORT, T, 0))
        assertEquals("2024年3月5日", format(P.DATE, T, 0))
        assertEquals("2024年3月5日", format(P.DATE_LONG, T, 0))
        assertEquals("15:07", format(P.TIME, T, 0))
        assertEquals("2024年3月5日 15:07", format(P.DATE_TIME, T, 0))
        assertEquals("3月05日", format(P.MONTH_DAY, T, 0))
        assertEquals("3月5日(火) 15:07", format(P.WEEKDAY_DATE_TIME, T, 0))
        assertEquals("3 時間前", relative(3, TimeUnit.HOUR, future = false))
        assertEquals("2 日後", relative(2, TimeUnit.DAY, future = true))
        assertEquals("1 分前", relative(1, TimeUnit.MINUTE, future = false))
        assertEquals("5 年前", relative(5, TimeUnit.YEAR, future = false))
        assertEquals("今", now())
        assertEquals("3 年", duration(3, TimeUnit.YEAR))
        assertEquals("5m", age(5, TimeUnit.MINUTE))
        assertEquals("3h", age(3, TimeUnit.HOUR))
        assertEquals("2d", age(2, TimeUnit.DAY))
        assertEquals("4w", age(4, TimeUnit.WEEK))
        assertEquals("1y", age(1, TimeUnit.YEAR))
    }

    @Test
    fun hiGoldens() = with(LocaleFormatter.of("hi-IN")) {
        assertEquals("12,34,567.891", decimal(1234567.891, 3, 0))
        assertEquals("0.50", fixed(0.5, 2))
        assertEquals("-1,234.50", decimal(-1234.5, 2, 2))
        assertEquals("1,234", integer(1234))
        assertEquals("\$1,234.50", fiat(1234.5, "$", 2))
        assertEquals("1,234.500 HIVE", token(1234.5, 3, "HIVE"))
        assertEquals("12.5%", percent(12.5, 1, 0))
        assertEquals("1.2\u00a0हज़ार", compact(1234.0, 1, 3))
        assertEquals("15\u00a0लाख", compact(1_500_000.0, 1, 6))
        assertEquals("1.2\u00a0लाख", compact(123_456.0, 1, 6))
        assertEquals("5/3/2024", format(P.DATE_SHORT, T, 0))
        assertEquals("5 मार्च 2024", format(P.DATE, T, 0))
        assertEquals("5 मार्च 2024", format(P.DATE_LONG, T, 0))
        assertEquals("3:07 pm", format(P.TIME, T, 0))
        assertEquals("5 मार्च 2024, 3:07 pm", format(P.DATE_TIME, T, 0))
        assertEquals("05 मार्च", format(P.MONTH_DAY, T, 0))
        assertEquals("मंगल, 5 मार्च, 3:07 pm", format(P.WEEKDAY_DATE_TIME, T, 0))
        assertEquals("3 घंटे पहले", relative(3, TimeUnit.HOUR, future = false))
        assertEquals("2 दिन में", relative(2, TimeUnit.DAY, future = true))
        assertEquals("1 मिनट पहले", relative(1, TimeUnit.MINUTE, future = false))
        assertEquals("5 वर्ष पहले", relative(5, TimeUnit.YEAR, future = false))
        assertEquals("अब", now())
        assertEquals("3 वर्ष", duration(3, TimeUnit.YEAR))
        assertEquals("5 मि", age(5, TimeUnit.MINUTE))
        assertEquals("3 घं", age(3, TimeUnit.HOUR))
        assertEquals("2 दि", age(2, TimeUnit.DAY))
        assertEquals("4 सप्ताह", age(4, TimeUnit.WEEK))
        assertEquals("1 व", age(1, TimeUnit.YEAR))
    }

    @Test
    fun pluralMessagesWriteTheNumberInTheAppLanguageAndPickTheMessagesOwnCategory() {
        val ru = IcuMessage.parse("{n, plural, one {# голос} few {# голоса} many {# голосов} other {# голоса}}")
        fun ru(n: Double) = ru.format(mapOf("n" to n), { L10nPluralRules.select("ru-RU", it) }, { it }, LocaleFormatter.of("ru-RU")::messageNumber)
        assertEquals("1\u00a0234 голоса", ru(1234.0))
        assertEquals("21 голос", ru(21.0))
        assertEquals("1\u00a0000\u00a0000 голосов", ru(1_000_000.0))
        assertEquals("1,5 голоса", ru(1.5))
        val ar = IcuMessage.parse("{n, plural, zero {لا شيء} one {واحد} two {اثنان} few {# قليلة} many {# كثيرة} other {# أخرى}}")
        fun ar(n: Double) = ar.format(mapOf("n" to n), { L10nPluralRules.select("ar-SA", it) }, { it }, LocaleFormatter.of("ar-SA")::messageNumber)
        assertEquals("١٠٣ قليلة", ar(103.0))
        assertEquals("١١ كثيرة", ar(11.0))
        assertEquals("١٬١٠٠ أخرى", ar(1100.0))

        // the catalog: these plural messages are still English in every translation, so English picks the form
        // and the app language writes the number
        assertEquals("1,234 votes", L.post.a11yVotes(count = 1234))
        L10n.setLanguage("de-DE")
        assertEquals("1.234 votes", L.post.a11yVotes(count = 1234))
        assertEquals("1 vote", L.post.a11yVotes(count = 1))
        L10n.setLanguage("hi-IN")
        assertEquals("12,34,567 votes", L.post.a11yVotes(count = 1_234_567))
        L10n.setLanguage("ar-SA")
        assertEquals("١٢ votes", L.post.a11yVotes(count = 12))
    }

    @Test
    fun theFacadeFollowsTheAppLanguage() {
        assertEquals("1,234.5", LocaleFormat.decimal(1234.5))
        L10n.setLanguage("de-DE")
        assertEquals("1.234,5", LocaleFormat.decimal(1234.5))
        assertEquals("vor 3 Stunden", LocaleFormat.relative(3, TimeUnit.HOUR, future = false))
        L10n.setLanguage("ja-JP")
        assertEquals("3 時間前", LocaleFormat.relative(3, TimeUnit.HOUR, future = false))
        L10n.setLanguage("xx-YY")
        assertEquals("1,234.5", LocaleFormat.decimal(1234.5))
        // the device time zone (platform actual): the local date is the UTC date give or take a day
        val local = LocaleFormat.dateShort(T)
        assertTrue(local in setOf("3/4/2024", "3/5/2024", "3/6/2024"), local)
    }

    @Test
    fun edgeCases() = with(LocaleFormatter.of("de-DE")) {
        assertEquals("0", decimal(-0.0001, 2, 0)) // never "-0"
        assertEquals("0,00", fixed(-0.001, 2))
        assertEquals("1.234,5", posix("1234.5"))
        assertEquals("1.234,500", posix("1234.500"))
        assertEquals("abc", posix("abc"))
        assertEquals("NaN", decimal(Double.NaN, 2, 0))
        assertEquals("-1,2\u00a0Mio.", compact(-1_234_567.0, 1, 0))
        assertEquals("999", compact(999.0, 1, 3))
        // de abbreviates no thousands, so RN's "1.2K" is the whole number, grouped from five digits
        assertEquals("1234", compact(1234.0, 1, 3))
        assertEquals("12.345", compact(12_345.0, 1, 3))
        assertEquals("29.2.2020", calendarDate(2020, 2, 29, P.DATE_SHORT))
        // the time zone offset moves the day
        assertEquals("6.3.2024", format(P.DATE_SHORT, T, 9 * 3_600_000L))
        assertEquals("00:07", format(P.TIME, T, 9 * 3_600_000L))
    }

    @Test
    fun unknownLanguagesFormatAsEnglishAndEveryLanguageLoads() {
        assertEquals("1,234.5", LocaleFormatter.of("xx-YY").decimal(1234.5, 1, 0))
        assertEquals("1.234,5", LocaleFormatter.of("de").decimal(1234.5, 1, 0))
        for (id in L10n.supported) {
            val f = LocaleFormatter.of(id)
            assertTrue(f.format(P.DATE_TIME, T, 0).isNotEmpty(), id)
            assertTrue(f.relative(2, TimeUnit.DAY, future = false).isNotEmpty(), id)
        }
    }
}
