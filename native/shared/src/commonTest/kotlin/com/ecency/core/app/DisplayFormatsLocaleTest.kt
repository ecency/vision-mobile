package com.ecency.core.app

import com.ecency.core.api.ecency.NotificationTime
import com.ecency.core.api.wallet.CoinActivity
import com.ecency.core.l10n.L10n
import com.ecency.core.l10n.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared display formats follow the app language (owner decision 10-22: ages, dates and numbers), while en-US
 * keeps RN's exact text (the other suites pin that). Invisible characters as escapes: U+00A0 no break space.
 */
class DisplayFormatsLocaleTest {
    private val now = 1_800_000_000_000L

    @AfterTest
    fun english() = L10n.setLanguage(L10n.SOURCE)

    private fun at(language: String, block: () -> Unit) {
        L10n.setLanguage(language)
        block()
    }

    @Test
    fun cardAgesUseCldrNarrowUnits() {
        assertEquals("now", TimeAgo.short(now - 5_000, now))
        assertEquals("3h", TimeAgo.short(now - 3 * 3_600_000, now))
        at("de-DE") {
            assertEquals("jetzt", TimeAgo.short(now - 5_000, now))
            assertEquals("5 Min.", TimeAgo.short(now - 5 * 60_000, now))
            assertEquals("3 Std.", TimeAgo.short(now - 3 * 3_600_000, now))
            assertEquals("2 W", TimeAgo.short(now - 14 * 86_400_000L, now))
        }
        at("ru-RU") { assertEquals("3 ч", TimeAgo.short(now - 3 * 3_600_000, now)) }
        at("ar-SA") { assertEquals("٣ س", TimeAgo.short(now - 3 * 3_600_000, now)) }
        at("ja-JP") { assertEquals("3h", TimeAgo.short(now - 3 * 3_600_000, now)) }
    }

    @Test
    fun fromNowKeepsMomentsBucketsAndEnglishWording() {
        assertEquals("a few seconds ago", FromNow.format(now - 10_000, now))
        assertEquals("an hour ago", FromNow.format(now - 3_600_000, now))
        assertEquals("in 3 days", FromNow.format(now + 3 * 86_400_000L, now))
        assertEquals("2 hours", FromNow.humanize(now - 2 * 3_600_000, now))
        at("de-DE") {
            assertEquals("jetzt", FromNow.format(now - 10_000, now))
            assertEquals("vor 1 Stunde", FromNow.format(now - 3_600_000, now))
            assertEquals("in 3 Tagen", FromNow.format(now + 3 * 86_400_000L, now))
            assertEquals("vor 2 Monaten", FromNow.format(now - 60 * 86_400_000L, now))
            assertEquals("2 Stunden", FromNow.humanize(now - 2 * 3_600_000, now))
        }
        at("ru-RU") { assertEquals("5 минут назад", FromNow.format(now - 5 * 60_000, now)) }
        at("ar-SA") { assertEquals("خلال ٣ أيام", FromNow.format(now + 3 * 86_400_000L, now)) }
        at("hi-IN") { assertEquals("3 दिन में", FromNow.format(now + 3 * 86_400_000L, now)) }
    }

    @Test
    fun notificationAndJoinedTimes() {
        assertEquals("5 minutes ago", NotificationTime.relative(now - 5 * 60_000, now))
        assertEquals("1 day ago", NotificationTime.relative(now - 86_400_000, now))
        assertEquals("1 years", QuickProfileModel.joinedAgo(now - 365L * 86_400_000, now), "RN's own plural")
        at("de-DE") {
            assertEquals("vor 5 Minuten", NotificationTime.relative(now - 5 * 60_000, now))
            assertEquals("vor 1 Tag", NotificationTime.relative(now - 86_400_000, now))
            assertEquals("1 Jahr", QuickProfileModel.joinedAgo(now - 365L * 86_400_000, now))
            assertEquals("2 Wochen", QuickProfileModel.joinedAgo(now - 14L * 86_400_000, now))
        }
        at("ru-RU") { assertEquals("5 минут назад", NotificationTime.relative(now - 5 * 60_000, now)) }
    }

    @Test
    fun walletNumbersFollowTheLanguageLikeRnsIntlNumberFormat() {
        assertEquals("1,234,567.891", WalletFormat.amount(1_234_567.891))
        assertEquals("$0.35000", WalletFormat.fiat(0.35, WalletCurrency.USD))
        assertEquals("APR 3.123%", WalletFormat.apr(3.1234))
        at("de-DE") {
            assertEquals("1.234.567,891", WalletFormat.amount(1_234_567.891))
            assertEquals("€1.234,50", WalletFormat.fiat(1234.5, WalletCurrency("eur", "€")))
            assertEquals("APR 3,123 %", WalletFormat.apr(3.1234))
        }
        at("hi-IN") { assertEquals("12,34,567.891", WalletFormat.amount(1_234_567.891)) }
        at("ar-SA") { assertEquals("١٬٢٣٤٫٥٠٠", WalletFormat.amount(1234.5)) }
    }

    @Test
    fun compactCountsKeepRnsThresholds() {
        assertEquals("1.2K", PostStatsModel.abbreviated(1234))
        assertEquals("0.1M", PostStatsModel.abbreviated(123_456))
        assertEquals("1.5M", ProfileFormat.count(1_500_000))
        assertEquals("999", ProfileFormat.count(999))
        assertEquals("1.5K", NotificationFormat.rcFormat(1500.0))
        at("ru-RU") {
            assertEquals("1,2 тыс.", PostStatsModel.abbreviated(1234))
            assertEquals("0,1 млн", PostStatsModel.abbreviated(123_456))
        }
        at("de-DE") {
            assertEquals("1234", ProfileFormat.count(1234), "de abbreviates no thousands: the whole number")
            assertEquals("1,5 Mio.", ProfileFormat.count(1_500_000))
        }
        at("ja-JP") { assertEquals("12.3万", PostStatsModel.abbreviated(123_456)) }
    }

    @Test
    fun dataStaysPosixWhileItsDisplayTextLocalizes() {
        fun row() = VoterRow("bob", "", "1234.568", "33.33", false, null, 1, 3333, 0)
        fun activity() = CoinActivity(textKey = "author_reward", icon = AppIcon.TICKET, value = " 1.500 HBD 2.000 HIVE  ", created = "", trxIndex = 1)
        // the display texts are fixed when the row is built (in the language of that moment)
        var row = row()
        var activity = activity()
        assertEquals("1234.568", row.rewardText)
        assertEquals("33.33%", row.percentText)
        assertEquals("1.500 HBD 2.000 HIVE", activity.displayValue)
        at("de-DE") {
            assertEquals("1234.568", row.rewardText, "built in en-US")
            row = row()
            activity = activity()
            assertEquals("1234,568", row.rewardText)
            assertEquals("33,33 %", row.percentText)
            assertEquals("1,500 HBD 2,000 HIVE", activity.displayValue)
            assertEquals(" 1.500 HBD 2.000 HIVE  ", activity.value, "a repeated transfer reads this")
            assertEquals("1234.568", row.reward, "the voter sort reads this")
        }
        at("ar-SA") { assertEquals("١٫٥٠٠ HBD ٢٫٠٠٠ HIVE", activity().displayValue) }
    }

    @Test
    fun durationsAreTheCatalogsTimeUnits() {
        at("ru-RU") { assertEquals("3 года", com.ecency.core.l10n.LocaleFormat.duration(3, TimeUnit.YEAR)) }
    }

    @Test
    fun stakeFieldTextStaysPosixWhileItsDisplayLocalizes() {
        at("de-DE") {
            assertEquals("1234.500", StakeMath.hpText(1234.5), "field text and the signed quote")
            assertEquals("1234,500", StakeMath.hpDisplay(1234.5))
        }
        at("ar-SA") { assertEquals("١٢٣٤٫٥٠٠", StakeMath.hpDisplay(1234.5)) }
    }

    @Test
    fun timePickersFollowTheLanguagesClock() {
        assertEquals(false, com.ecency.core.l10n.LocaleFormat.uses24Hour())
        at("de-DE") { assertEquals(true, com.ecency.core.l10n.LocaleFormat.uses24Hour()) }
        at("hi-IN") { assertEquals(false, com.ecency.core.l10n.LocaleFormat.uses24Hour()) }
        at("ja-JP") { assertEquals(true, com.ecency.core.l10n.LocaleFormat.uses24Hour()) }
        at("ko-KR") { assertEquals(false, com.ecency.core.l10n.LocaleFormat.uses24Hour()) }
    }
}
