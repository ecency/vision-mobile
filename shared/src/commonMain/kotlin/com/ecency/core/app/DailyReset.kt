package com.ecency.core.app

import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.l10n.TimeUnit

/**
 * The one daily reset at 00:00 UTC shared by the daily leaderboard, its quests check, the daily quests and the free
 * spin (issue #3548). Always UTC: the device's zone and its DST changes play no part.
 */
object DailyReset {
    const val DAY_MILLIS = 86_400_000L
    private const val MINUTE_MILLIS = 60_000L

    /** The next 00:00 UTC; exactly midnight already belongs to the new day. */
    fun nextResetMillis(nowMillis: Long): Long = (nowMillis.floorDiv(DAY_MILLIS) + 1) * DAY_MILLIS

    /** Whole minutes to the reset, rounded up, so the last seconds read 1m and never 0m. */
    fun minutesLeft(nowMillis: Long): Long = (nextResetMillis(nowMillis) - nowMillis + MINUTE_MILLIS - 1) / MINUTE_MILLIS

    /** "5h 12m", "45m", "24h": CLDR narrow units in the app language. */
    fun timeLeft(nowMillis: Long): String {
        val total = minutesLeft(nowMillis)
        val hours = total / 60
        val minutes = total % 60
        return when {
            hours == 0L -> LocaleFormat.age(minutes, TimeUnit.MINUTE)
            minutes == 0L -> LocaleFormat.age(hours, TimeUnit.HOUR)
            else -> L.native.dailyReset.hoursMinutes(
                hours = LocaleFormat.age(hours, TimeUnit.HOUR),
                minutes = LocaleFormat.age(minutes, TimeUnit.MINUTE),
            )
        }
    }

    /** "Today (UTC), resets in 5h 12m": the daily leaderboard and the daily quests. */
    fun todayLine(nowMillis: Long): String = L.native.dailyReset.todayResetsIn(time = timeLeft(nowMillis))

    /** Millis until [timeLeft] reads differently: a minute ticker on the shown value, which rolls over at midnight. */
    fun nextTickMillis(nowMillis: Long): Long =
        (nextResetMillis(nowMillis) - nowMillis) - (minutesLeft(nowMillis) - 1) * MINUTE_MILLIS

    /** Under the spin countdown. */
    val RESETS_DAILY: String get() = L.native.dailyReset.resetsDaily
}
