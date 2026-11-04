package com.ecency.core.app

import com.ecency.core.l10n.L10n
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one daily reset (00:00 UTC) of the leaderboard, its quests check, daily quests and the free spin. */
class DailyResetTest {
    /** 2026-10-30T00:00:00Z. */
    private val midnight = 1_793_318_400_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    @AfterTest
    fun english() = L10n.setLanguage(L10n.SOURCE)

    @Test
    fun theNextResetIsTheNextUtcMidnight() {
        assertEquals(midnight, DailyReset.nextResetMillis(midnight - 1), "just before midnight")
        assertEquals(midnight + DailyReset.DAY_MILLIS, DailyReset.nextResetMillis(midnight), "exactly midnight starts a new day")
        assertEquals(midnight + DailyReset.DAY_MILLIS, DailyReset.nextResetMillis(midnight + 1), "just after")
        assertEquals(midnight, DailyReset.nextResetMillis(midnight - 5 * hour - 12 * minute))
        // a US DST change day (2026-11-01) and an EU one (2026-03-29): UTC days are always 24 h
        assertEquals(1_793_577_600_000L, DailyReset.nextResetMillis(1_793_534_400_000L))
        assertEquals(1_774_828_800_000L, DailyReset.nextResetMillis(1_774_747_800_000L))
    }

    @Test
    fun theTimeLeftIsHoursAndMinutesRoundedUp() {
        assertEquals("5h 12m", DailyReset.timeLeft(midnight - 5 * hour - 12 * minute))
        assertEquals("5h 13m", DailyReset.timeLeft(midnight - 5 * hour - 12 * minute - 1), "a started minute counts")
        assertEquals("1m", DailyReset.timeLeft(midnight - 1), "the last second never reads 0m")
        assertEquals("1m", DailyReset.timeLeft(midnight - 30_000))
        assertEquals("45m", DailyReset.timeLeft(midnight - 45 * minute))
        assertEquals("1h", DailyReset.timeLeft(midnight - hour))
        assertEquals("24h", DailyReset.timeLeft(midnight), "exactly midnight: a full day")
        assertEquals("24h", DailyReset.timeLeft(midnight + 1))
        assertEquals("23h 59m", DailyReset.timeLeft(midnight + minute))
        assertEquals(1L, DailyReset.minutesLeft(midnight - 1))
        assertEquals(1440L, DailyReset.minutesLeft(midnight))
    }

    @Test
    fun theLineReadsTheTimeLeft() {
        assertEquals("Today (UTC), resets in 5h 12m", DailyReset.todayLine(midnight - 5 * hour - 12 * minute))
        assertEquals("Today (UTC), resets in 24h", DailyReset.todayLine(midnight))
        assertEquals("Resets daily at 00:00 UTC", DailyReset.RESETS_DAILY)
    }

    @Test
    fun theTickerWakesWhenTheShownMinuteChanges() {
        val at = midnight - 5 * hour - 12 * minute
        assertEquals(minute, DailyReset.nextTickMillis(at))
        assertEquals(minute - 1, DailyReset.nextTickMillis(at + 1))
        assertEquals(1L, DailyReset.nextTickMillis(midnight - 1), "rolls over at midnight")
        assertEquals(minute, DailyReset.nextTickMillis(midnight))
        // after each wake the text has moved on
        var now = midnight - 3 * minute + 7_000
        val seen = mutableListOf<String>()
        repeat(4) {
            seen += DailyReset.timeLeft(now)
            now += DailyReset.nextTickMillis(now)
        }
        assertEquals(listOf("3m", "2m", "1m", "24h"), seen)
    }

    @Test
    fun theUnitsFollowTheAppLanguage() {
        L10n.setLanguage("de-DE")
        val de = DailyReset.timeLeft(midnight - 5 * hour - 12 * minute)
        assertNotEquals("5h 12m", de)
        assertTrue(de.contains("5") && de.contains("12"), de)
        L10n.setLanguage("ar-SA")
        val ar = DailyReset.timeLeft(midnight - 5 * hour - 12 * minute)
        assertTrue(ar.contains('٥') && ar.contains("١٢"), "Arabic-Indic digits: $ar")
    }

    @Test
    fun onlyTheDailyTabsShowTheLine() {
        val at = midnight - 5 * hour - 12 * minute
        assertEquals("Today (UTC), resets in 5h 12m", LeaderboardState(duration = LeaderboardDuration.DAY).resetLine(at))
        assertNull(LeaderboardState(duration = LeaderboardDuration.WEEK).resetLine(at))
        assertNull(LeaderboardState(duration = LeaderboardDuration.MONTH).resetLine(at))
        assertEquals("Today (UTC), resets in 5h 12m", PerksState(tier = QuestTier.DAILY).resetLine(at))
        assertNull(PerksState(tier = QuestTier.WEEKLY).resetLine(at))
        assertNull(PerksState(tier = QuestTier.MONTHLY).resetLine(at))
    }

    @Test
    fun theSpinScreenSaysWhenItResetsUnderTheCountdown() {
        assertEquals("Resets daily at 00:00 UTC", SpinState(loading = false, gameRight = 0).resetNote, "no spin left: the countdown shows")
        assertNull(SpinState(loading = false, gameRight = 1).resetNote, "a spin is left: no countdown")
        assertNull(SpinState(loading = true, gameRight = 0).resetNote)
    }
}
