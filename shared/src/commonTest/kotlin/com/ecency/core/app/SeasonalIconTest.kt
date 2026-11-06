package com.ecency.core.app

import com.ecency.core.app.LauncherIcon.BLACK_FRIDAY
import com.ecency.core.app.LauncherIcon.CHRISTMAS
import com.ecency.core.app.LauncherIcon.DEFAULT
import com.ecency.core.app.LauncherIcon.DIWALI
import com.ecency.core.app.LauncherIcon.EARTH_DAY
import com.ecency.core.app.LauncherIcon.FOOTBALL
import com.ecency.core.app.LauncherIcon.HALLOWEEN
import com.ecency.core.app.LauncherIcon.HIVE_DAY
import com.ecency.core.app.LauncherIcon.HOLI
import com.ecency.core.app.LauncherIcon.LUNAR_NEW_YEAR
import com.ecency.core.app.LauncherIcon.MID_AUTUMN
import com.ecency.core.app.LauncherIcon.NOWRUZ
import com.ecency.core.app.LauncherIcon.PIZZA_DAY
import com.ecency.core.app.LauncherIcon.PROGRAMMERS_DAY
import com.ecency.core.app.LauncherIcon.RAMADAN_EID
import com.ecency.core.app.LauncherIcon.SUMMER_GAMES
import com.ecency.core.app.LauncherIcon.VALENTINES
import com.ecency.core.app.LauncherIcon.YEAR_START
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Issue #3656: which launcher icon shows on a day, for a phone's region and language (README "App icons"). */
class SeasonalIconTest {
    private fun day(y: Int, m: Int, d: Int) = CalendarDay(y, m, d)

    private fun icon(date: String, region: String?, language: String?, overrides: List<SeasonalOverride> = emptyList()): LauncherIcon {
        val (y, m, d) = date.split('-').map { it.toInt() }
        return SeasonalIcons.pick(day(y, m, d), region, language, overrides)
    }

    private fun assertDays(region: String?, language: String?, expected: Map<String, LauncherIcon>, overrides: List<SeasonalOverride> = emptyList()) {
        for ((date, want) in expected) assertEquals(want, icon(date, region, language, overrides), "$date on a $language-$region phone")
    }

    @Test
    fun globalCelebrationsShowForEveryone() = assertDays(
        "DE", "de",
        mapOf(
            "2026-12-31" to YEAR_START, "2027-01-02" to YEAR_START, "2027-01-03" to DEFAULT,
            "2027-02-13" to VALENTINES, "2027-02-14" to VALENTINES, "2027-02-15" to DEFAULT,
            "2027-03-20" to HIVE_DAY, "2027-03-21" to DEFAULT,
            "2027-04-22" to EARTH_DAY, "2027-05-22" to PIZZA_DAY,
            // the 256th day: Sep 13, Sep 12 in a leap year
            "2027-09-13" to PROGRAMMERS_DAY, "2028-09-12" to PROGRAMMERS_DAY, "2028-09-13" to DEFAULT,
            "2027-07-01" to FOOTBALL, "2028-07-20" to SUMMER_GAMES, "2027-06-01" to DEFAULT,
            // regional ones follow the region: Christmas is kept in Germany, Halloween is not
            "2026-12-24" to CHRISTMAS, "2027-10-31" to DEFAULT,
        ),
    )

    @Test
    fun regionalCelebrationsFollowTheRegionAndTheLanguage() {
        assertEquals(HALLOWEEN, icon("2027-10-31", "US", "en"))
        assertEquals(DIWALI, icon("2027-10-29", "IN", "hi"))
        assertEquals(DIWALI, icon("2027-10-29", "US", "hi"), "a Hindi phone anywhere")
        assertEquals(DEFAULT, icon("2027-10-27", "US", "en"))
        assertEquals(MID_AUTUMN, icon("2027-09-15", "TW", "zh"))
        // Android's legacy code for Indonesian
        assertEquals(RAMADAN_EID, icon("2027-02-20", null, "in"))
        assertEquals(RAMADAN_EID, icon("2027-02-20", "ID", "id"))
        assertEquals(DEFAULT, icon("2027-02-20", null, null))
    }

    @Test
    fun february2027LunarNewYearRamadanAndValentines() {
        // China: Lunar New Year (Feb 6, eve to the Lantern Festival) covers Valentine's Day
        assertDays("CN", "zh", mapOf("2027-02-05" to LUNAR_NEW_YEAR, "2027-02-14" to LUNAR_NEW_YEAR, "2027-02-20" to LUNAR_NEW_YEAR, "2027-02-21" to DEFAULT))
        // Malaysia qualifies for both: Ramadan and Eid come first there, Lunar New Year (overlapping) does not show
        assertDays(
            "MY", "ms",
            mapOf("2027-02-05" to DEFAULT, "2027-02-06" to DEFAULT, "2027-02-07" to RAMADAN_EID, "2027-02-14" to RAMADAN_EID, "2027-03-11" to RAMADAN_EID, "2027-03-12" to DEFAULT),
        )
        // Singapore's order puts Lunar New Year first
        assertDays("SG", "ms", mapOf("2027-02-06" to LUNAR_NEW_YEAR, "2027-02-14" to LUNAR_NEW_YEAR, "2027-03-01" to DEFAULT))
        // elsewhere Valentine's Day
        assertDays("US", "en", mapOf("2027-02-06" to DEFAULT, "2027-02-14" to VALENTINES))
    }

    @Test
    fun march20To23Of2027HiveDayNowruzAndHoli() {
        assertDays("DE", "de", mapOf("2027-03-20" to HIVE_DAY, "2027-03-21" to DEFAULT, "2027-03-22" to DEFAULT))
        // Holi (Holika Dahan Mar 21, Holi Mar 22) wins over Hive Day the day before
        assertDays("IN", "hi", mapOf("2027-03-20" to DEFAULT, "2027-03-21" to HOLI, "2027-03-22" to HOLI, "2027-03-23" to DEFAULT))
        for ((region, language) in listOf("UZ" to "uz", "KZ" to "kk", "IR" to "fa", "AZ" to "az", "AF" to "ps", "TJ" to "tg", "KG" to "ky", "TM" to "tk")) {
            assertDays(region, language, mapOf("2027-03-19" to DEFAULT, "2027-03-20" to NOWRUZ, "2027-03-23" to NOWRUZ, "2027-03-24" to DEFAULT))
        }
        assertEquals(NOWRUZ, icon("2027-03-21", "DE", "fa"), "a Persian phone anywhere")
    }

    @Test
    fun twoCelebrationsLessThanThreeDaysApartShowOnlyOne() {
        // Programmers' Day (Sep 13) and Mid-Autumn (Sep 14 to 16, 2027) on a Chinese phone: the regional one
        assertDays("CN", "zh", mapOf("2027-09-13" to DEFAULT, "2027-09-14" to MID_AUTUMN))
        // two global ones two days apart: the earlier
        val earthLate = listOf(SeasonalOverride(EARTH_DAY, 2027, DayRange(day(2027, 5, 20), day(2027, 5, 20))))
        assertDays("DE", "de", mapOf("2027-05-20" to EARTH_DAY, "2027-05-22" to DEFAULT), earthLate)
        // three plain days between them is enough: Christmas (to Dec 26) and New Year (from Dec 31) both show
        val apart = listOf(SeasonalOverride(CHRISTMAS, 2026, DayRange(day(2026, 12, 20), day(2026, 12, 27))))
        assertDays("DE", "de", mapOf("2026-12-27" to CHRISTMAS, "2026-12-31" to YEAR_START), apart)
        val tooClose = listOf(SeasonalOverride(CHRISTMAS, 2026, DayRange(day(2026, 12, 20), day(2026, 12, 28))))
        assertDays("DE", "de", mapOf("2026-12-24" to CHRISTMAS, "2026-12-31" to DEFAULT), tooClose)
    }

    @Test
    fun theServerCanStopOrShiftACelebration() {
        val off2027 = listOf(SeasonalOverride(HIVE_DAY, 2027, null))
        assertDays("DE", "de", mapOf("2027-03-20" to DEFAULT, "2028-03-20" to HIVE_DAY), off2027)
        val offEveryYear = listOf(SeasonalOverride(CHRISTMAS, null, null))
        assertDays("DE", "de", mapOf("2026-12-24" to DEFAULT, "2027-12-24" to DEFAULT), offEveryYear)
        val shifted = listOf(SeasonalOverride(DIWALI, 2027, DayRange(day(2027, 10, 30), day(2027, 11, 3))))
        assertDays("IN", "hi", mapOf("2027-10-28" to DEFAULT, "2027-11-02" to DIWALI, "2027-11-04" to DEFAULT), shifted)
    }

    @Test
    fun blackFridayShowsOnlyWhenTheServerGivesItDays() {
        assertEquals(DEFAULT, icon("2026-11-27", "US", "en"), "no Points sale known: no Black Friday icon")
        val sale = listOf(SeasonalOverride(BLACK_FRIDAY, 2026, DayRange(day(2026, 11, 27), day(2026, 11, 30))))
        assertEquals(BLACK_FRIDAY, icon("2026-11-27", "US", "en", sale))
        assertEquals(DEFAULT, icon("2026-11-27", "JP", "ja", sale))
    }

    @Test
    fun theDateTableCoversTheNextThreeYearsOfEveryLunarAndLunisolarCelebration() {
        assertTrue(SeasonalCalendar.LAST_YEAR >= 2029)
        for (year in 2026..SeasonalCalendar.LAST_YEAR) {
            val windows = SeasonalCalendar.windows(year)
            for (icon in listOf(LUNAR_NEW_YEAR, RAMADAN_EID, HOLI, MID_AUTUMN, DIWALI)) {
                val w = assertNotNull(windows.firstOrNull { it.icon == icon }, "$icon in $year")
                assertTrue(w.range.start <= w.range.end, "$icon $year")
            }
            // every celebration but Black Friday (no sale signal) has days every year; the tournaments only some
            for (icon in LauncherIcon.entries - setOf(DEFAULT, BLACK_FRIDAY, FOOTBALL, SUMMER_GAMES)) {
                assertTrue(windows.any { it.icon == icon }, "$icon in $year")
            }
        }
        // the table's spot checks (published dates, see SeasonalCalendar)
        assertEquals(DayRange(day(2027, 2, 7), day(2027, 3, 11)), SeasonalCalendar.windows(2027).first { it.icon == RAMADAN_EID }.range)
        assertEquals(DayRange(day(2027, 2, 5), day(2027, 2, 20)), SeasonalCalendar.windows(2027).first { it.icon == LUNAR_NEW_YEAR }.range)
        assertEquals(DayRange(day(2026, 11, 6), day(2026, 11, 10)), SeasonalCalendar.windows(2026).first { it.icon == DIWALI }.range)
        assertEquals(DayRange(day(2028, 3, 10), day(2028, 3, 11)), SeasonalCalendar.windows(2028).first { it.icon == HOLI }.range)
        assertEquals(DayRange(day(2029, 9, 21), day(2029, 9, 23)), SeasonalCalendar.windows(2029).first { it.icon == MID_AUTUMN }.range)
        // past the table nothing lunar shows rather than a wrong day
        assertFalse(SeasonalCalendar.windows(SeasonalCalendar.LAST_YEAR + 1).any { it.icon == RAMADAN_EID })
    }

    // ---- the model: settings, consent ----

    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)

    private fun model(prefs: Prefs, region: String = "DE", language: String = "de", asksFirst: Boolean = false) =
        LauncherIconModel({ prefs }, dispatchers).apply { configure(region, language, asksFirst) }

    @Test
    fun seasonalIconsOffShowsThePickedIcon() {
        val prefs = InMemoryPrefs()
        val m = model(prefs)
        m.refresh(day(2027, 3, 20))
        assertTrue(m.current.seasonal, "on by default where nothing asks first (Android)")
        assertEquals(HIVE_DAY, m.current.current)
        m.setSeasonal(false)
        assertEquals(DEFAULT, m.current.current)
        m.pick(CHRISTMAS)
        assertFalse(m.current.seasonal)
        assertEquals(CHRISTMAS, m.current.current)
        // saved
        val again = model(prefs).apply { refresh(day(2027, 3, 20)) }
        assertEquals(CHRISTMAS, again.current.current)
        again.setSeasonal(true)
        assertEquals(HIVE_DAY, again.current.current)
        again.refresh(day(2027, 3, 21))
        assertEquals(DEFAULT, again.current.current)
    }

    @Test
    fun whereTheAppAsksFirstNothingSwitchesBeforeTheAnswer() {
        val prefs = InMemoryPrefs()
        val m = model(prefs, asksFirst = true)
        m.refresh(day(2027, 6, 1))
        assertFalse(m.current.askConsent, "nothing to celebrate: no question yet")
        m.refresh(day(2027, 3, 20))
        assertTrue(m.current.askConsent)
        assertEquals(DEFAULT, m.current.current, "no automatic switch before the answer")
        m.answerConsent(true)
        assertFalse(m.current.askConsent)
        assertEquals(HIVE_DAY, m.current.current)
        assertFalse(model(prefs, asksFirst = true).apply { refresh(day(2027, 3, 20)) }.current.askConsent, "asked once")

        val declined = InMemoryPrefs()
        val n = model(declined, asksFirst = true)
        n.refresh(day(2027, 3, 20))
        n.answerConsent(false)
        assertEquals(DEFAULT, n.current.current)
        assertFalse(model(declined, asksFirst = true).apply { refresh(day(2027, 4, 22)) }.current.askConsent, "never asked again")
        // the Settings picker works either way
        n.pick(HOLI)
        assertEquals(HOLI, n.current.current)
    }

    @Test
    fun everyIconHasAName() {
        for (icon in LauncherIcon.entries) assertTrue(icon.title.isNotBlank(), "$icon")
        assertEquals(LauncherIcon.entries.size, LauncherIcon.entries.map { it.key }.toSet().size)
    }
}
