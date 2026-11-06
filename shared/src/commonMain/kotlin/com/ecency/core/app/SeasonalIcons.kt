package com.ecency.core.app

import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormatter

/** A calendar day in the device's own time zone (the platform reads today; tests pass any day). */
data class CalendarDay(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDay> {
    /** Days since 1970-01-01. */
    val epochDay: Long get() = LocaleFormatter.daysFromCivil(year.toLong(), month, day)

    fun plusDays(days: Int): CalendarDay = of(epochDay + days)

    override fun compareTo(other: CalendarDay): Int = epochDay.compareTo(other.epochDay)

    companion object {
        fun of(epochDay: Long): CalendarDay = LocaleFormatter.civil(epochDay).let { (y, m, d) -> CalendarDay(y.toInt(), m, d) }
    }
}

/** The days [start] to [end], both included. */
data class DayRange(val start: CalendarDay, val end: CalendarDay) {
    operator fun contains(day: CalendarDay): Boolean = day >= start && day <= end
}

/**
 * The launcher icons the app ships (README "App icons"): the everyday icon and one per celebration. [key] names the
 * iOS alternate icon (`AppIcon-<key>`, none for [DEFAULT]) and the Android launcher alias (`Launcher<key>`); never
 * rename one, a saved choice and the platforms' assets use it. [regional] celebrations show only where they are
 * widely kept ([IconAudiences]); the others show for everyone.
 */
enum class LauncherIcon(val key: String, val regional: Boolean) {
    DEFAULT("Default", false),
    // not NEW_YEAR: Swift would see `newYear`, an Objective-C method family name
    YEAR_START("NewYear", false),
    LUNAR_NEW_YEAR("LunarNewYear", true),
    VALENTINES("Valentines", false),
    RAMADAN_EID("RamadanEid", true),
    HIVE_DAY("HiveDay", false),
    NOWRUZ("Nowruz", true),
    HOLI("Holi", true),
    EARTH_DAY("EarthDay", false),
    PIZZA_DAY("PizzaDay", false),
    FOOTBALL("Football", false),
    SUMMER_GAMES("SummerGames", false),
    PROGRAMMERS_DAY("ProgrammersDay", false),
    MID_AUTUMN("MidAutumn", true),
    DIWALI("Diwali", true),
    HALLOWEEN("Halloween", true),
    BLACK_FRIDAY("BlackFriday", true),
    CHRISTMAS("Christmas", true),
    ;

    /** The name the Settings picker shows. */
    val title: String get() = when (this) {
        DEFAULT -> L.native.appIcon.nameDefault
        YEAR_START -> L.native.appIcon.nameNewYear
        LUNAR_NEW_YEAR -> L.native.appIcon.nameLunarNewYear
        VALENTINES -> L.native.appIcon.nameValentines
        RAMADAN_EID -> L.native.appIcon.nameRamadanEid
        HIVE_DAY -> L.native.appIcon.nameHiveDay
        NOWRUZ -> L.native.appIcon.nameNowruz
        HOLI -> L.native.appIcon.nameHoli
        EARTH_DAY -> L.native.appIcon.nameEarthDay
        PIZZA_DAY -> L.native.appIcon.namePizzaDay
        FOOTBALL -> L.native.appIcon.nameFootball
        SUMMER_GAMES -> L.native.appIcon.nameSummerGames
        PROGRAMMERS_DAY -> L.native.appIcon.nameProgrammersDay
        MID_AUTUMN -> L.native.appIcon.nameMidAutumn
        DIWALI -> L.native.appIcon.nameDiwali
        HALLOWEEN -> L.native.appIcon.nameHalloween
        BLACK_FRIDAY -> L.native.appIcon.nameBlackFriday
        CHRISTMAS -> L.native.appIcon.nameChristmas
    }

    companion object {
        fun ofKey(key: String?): LauncherIcon? = entries.firstOrNull { it.key == key }
    }
}

/** The Settings row's texts (README "App icons"). */
object LauncherIconCopy {
    val TITLE: String get() = L.native.appIcon.title
    val SEASONAL: String get() = L.native.appIcon.seasonal
    val SEASONAL_HINT: String get() = L.native.appIcon.seasonalHint
}

/** One showing of a celebration's icon: [icon] over [range], for the celebration of [year]. */
data class SeasonalWindow(val icon: LauncherIcon, val year: Int, val range: DayRange)

/**
 * The server's word on a celebration: for [year] (every year when null), [range] null stops it, else it shows over
 * [range] instead of the table's days. No endpoint sends these yet (README "App icons"); the model takes them through
 * [LauncherIconModel.setOverrides].
 */
data class SeasonalOverride(val icon: LauncherIcon, val year: Int?, val range: DayRange?)

/**
 * When each celebration shows: the one place its days are decided (README "App icons"). A celebration of [year] may
 * start in the year before (New Year from Dec 31).
 *
 * Fixed: New Year Dec 31 to Jan 2; Valentine's Day Feb 13 to 14; Hive Day Mar 20 (Hive's launch in 2020); Nowruz
 * Mar 20 to 23 (the equinox falls on Mar 20 or 21; Kazakhstan's Nauryz holiday runs Mar 21 to 23); Earth Day Apr 22;
 * Bitcoin Pizza Day May 22; Programmers' Day, the 256th day (Sep 13, Sep 12 in a leap year); Halloween Oct 29 to 31;
 * Christmas Dec 20 to 26.
 *
 * From the tables below (published dates, [FIRST_YEAR] to [LAST_YEAR]; past them a lunar celebration does not show
 * rather than on a wrong day, so extend the tables before [LAST_YEAR]):
 * - Lunar New Year: the eve to the Lantern Festival (14 days after).
 * - Ramadan and Eid: the evening before the first fast (moon sighting can start it a day later too) to the third day
 *   of Eid al-Fitr where moon sighting puts Eid a day after the Umm al-Qura date (Eid + 3).
 * - Holi: Holika Dahan (the day before) and Holi.
 * - Mid-Autumn Festival: the day before to the day after.
 * - Diwali: Dhanteras (two days before Lakshmi Puja) to Bhai Dooj (two days after).
 * - The football tournament and the summer games: their match days.
 * - Black Friday: no days of its own; it shows only while a Points sale runs, which the app cannot see yet, so only
 *   a [SeasonalOverride] with days turns it on.
 */
object SeasonalCalendar {
    const val FIRST_YEAR = 2026
    const val LAST_YEAR = 2029

    private fun d(y: Int, m: Int, day: Int) = CalendarDay(y, m, day)

    /** Lunar New Year's day (Spring Festival). Source: Royal Observatory Greenwich, "Lunar New Year dates"; China's State Council holiday notices (2026 to 2028). */
    private val LUNAR_NEW_YEAR = mapOf(2026 to d(2026, 2, 17), 2027 to d(2027, 2, 6), 2028 to d(2028, 1, 26), 2029 to d(2029, 2, 13))

    /** Mid-Autumn Festival (15th day of the 8th lunar month). Source: "The Chinese Lunar Calendar 1900-2100" (taiwan-database.net), China's State Council holiday notices. */
    private val MID_AUTUMN = mapOf(2026 to d(2026, 9, 25), 2027 to d(2027, 9, 15), 2028 to d(2028, 10, 3), 2029 to d(2029, 9, 22))

    /**
     * 1 Ramadan and 1 Shawwal (Eid al-Fitr) of the Umm al-Qura calendar. Source: R.H. van Gent, "The Umm al-Qura
     * Calendar of Saudi Arabia" (Utrecht University), agreeing with the Fiqh Council of North America's calendar.
     */
    private val RAMADAN = mapOf(
        2026 to (d(2026, 2, 18) to d(2026, 3, 20)),
        2027 to (d(2027, 2, 8) to d(2027, 3, 9)),
        2028 to (d(2028, 1, 28) to d(2028, 2, 26)),
        2029 to (d(2029, 1, 16) to d(2029, 2, 14)),
    )

    /** Holi (Dhulandi, the day of colours). Source: USC Office of Religious and Spiritual Life, Drik Panchang. */
    private val HOLI = mapOf(2026 to d(2026, 3, 4), 2027 to d(2027, 3, 22), 2028 to d(2028, 3, 11), 2029 to d(2029, 3, 1))

    /** Diwali (Lakshmi Puja). Source: Drik Panchang (2026), USC Office of Religious and Spiritual Life (2026 to 2028); 2029 from the Hindu lunisolar calendar (Kartika Amavasya), to confirm in the community review. */
    private val DIWALI = mapOf(2026 to d(2026, 11, 8), 2027 to d(2027, 10, 29), 2028 to d(2028, 10, 17), 2029 to d(2029, 11, 5))

    /** Men's and women's world and continental football tournaments, opening to final match. */
    private val FOOTBALL = mapOf(
        2026 to DayRange(d(2026, 6, 11), d(2026, 7, 19)),
        2027 to DayRange(d(2027, 6, 24), d(2027, 7, 25)),
        2028 to DayRange(d(2028, 6, 9), d(2028, 7, 9)),
    )

    /** The summer games, opening to closing ceremony. */
    private val SUMMER_GAMES = mapOf(2028 to DayRange(d(2028, 7, 14), d(2028, 7, 30)))

    /** Every celebration of [year] with the days the table gives it, before any server override. */
    fun windows(year: Int): List<SeasonalWindow> {
        val out = mutableListOf<SeasonalWindow>()
        fun add(icon: LauncherIcon, start: CalendarDay, end: CalendarDay = start) { out += SeasonalWindow(icon, year, DayRange(start, end)) }
        add(LauncherIcon.YEAR_START, d(year - 1, 12, 31), d(year, 1, 2))
        LUNAR_NEW_YEAR[year]?.let { add(LauncherIcon.LUNAR_NEW_YEAR, it.plusDays(-1), it.plusDays(14)) }
        add(LauncherIcon.VALENTINES, d(year, 2, 13), d(year, 2, 14))
        RAMADAN[year]?.let { (first, eid) -> add(LauncherIcon.RAMADAN_EID, first.plusDays(-1), eid.plusDays(3)) }
        add(LauncherIcon.HIVE_DAY, d(year, 3, 20))
        add(LauncherIcon.NOWRUZ, d(year, 3, 20), d(year, 3, 23))
        HOLI[year]?.let { add(LauncherIcon.HOLI, it.plusDays(-1), it) }
        add(LauncherIcon.EARTH_DAY, d(year, 4, 22))
        add(LauncherIcon.PIZZA_DAY, d(year, 5, 22))
        FOOTBALL[year]?.let { add(LauncherIcon.FOOTBALL, it.start, it.end) }
        SUMMER_GAMES[year]?.let { add(LauncherIcon.SUMMER_GAMES, it.start, it.end) }
        add(LauncherIcon.PROGRAMMERS_DAY, d(year, 1, 1).plusDays(255))
        MID_AUTUMN[year]?.let { add(LauncherIcon.MID_AUTUMN, it.plusDays(-1), it.plusDays(1)) }
        DIWALI[year]?.let { add(LauncherIcon.DIWALI, it.plusDays(-2), it.plusDays(2)) }
        add(LauncherIcon.HALLOWEEN, d(year, 10, 29), d(year, 10, 31))
        add(LauncherIcon.CHRISTMAS, d(year, 12, 20), d(year, 12, 26))
        return out
    }
}

/**
 * Who sees a regional celebration: the phone's own region and language settings, never its location (README "App
 * icons"). Regions are ISO 3166 codes, languages ISO 639 codes.
 */
object IconAudiences {
    private fun set(codes: String) = codes.split(' ').filter { it.isNotEmpty() }.toSet()

    private val REGIONS: Map<LauncherIcon, Set<String>> = mapOf(
        LauncherIcon.LUNAR_NEW_YEAR to set("CN TW HK MO SG MY VN KR"),
        LauncherIcon.MID_AUTUMN to set("CN TW HK MO SG MY VN"),
        // Muslim-majority countries
        LauncherIcon.RAMADAN_EID to set(
            "SA AE QA KW BH OM YE JO IQ SY PS EG LY TN DZ MA MR SD SO DJ KM TR AZ IR AF PK BD MV MY ID BN UZ KZ KG TJ TM " +
                "SN ML NE GN GM BF TD XK AL",
        ),
        LauncherIcon.NOWRUZ to set("IR AF AZ UZ KZ KG TJ TM AL XK"),
        LauncherIcon.HOLI to set("IN NP"),
        LauncherIcon.DIWALI to set("IN NP LK SG MY MU FJ"),
        LauncherIcon.HALLOWEEN to set("US CA GB IE AU NZ"),
        LauncherIcon.BLACK_FRIDAY to set("US CA GB IE AU NZ BR MX DE FR ES IT NL PL"),
        // where Christmas on Dec 25 is widely kept (Orthodox Jan 7 countries left out)
        LauncherIcon.CHRISTMAS to set(
            "US CA MX GT HN SV NI CR PA CU DO HT JM PR TT BS BB BZ GY SR CO VE EC PE BO PY CL AR UY BR " +
                "GB IE FR DE NL BE LU CH AT IT ES PT MT AD MC SM VA LI DK NO SE FI IS EE LV LT PL CZ SK HU SI HR BA RO BG GR CY UA MD " +
                "AU NZ PG FJ WS TO PH TL ZA NA BW ZM ZW MW AO CD CG CM RW BI UG KE TZ GH LS SZ MG",
        ),
    )

    private val LANGUAGES: Map<LauncherIcon, Set<String>> = mapOf(
        LauncherIcon.LUNAR_NEW_YEAR to set("zh vi ko"),
        LauncherIcon.MID_AUTUMN to set("zh vi"),
        LauncherIcon.RAMADAN_EID to set("ar ms id tr ur"),
        LauncherIcon.NOWRUZ to set("fa tg uz kk ky tk az ku ckb"),
        LauncherIcon.HOLI to set("hi ne"),
        LauncherIcon.DIWALI to set("hi ne ta gu mr te kn"),
    )

    /** The order regional celebrations win in where two fall together; [order] changes it for a few regions. */
    private val DEFAULT_ORDER = listOf(
        LauncherIcon.RAMADAN_EID, LauncherIcon.DIWALI, LauncherIcon.HOLI, LauncherIcon.NOWRUZ, LauncherIcon.LUNAR_NEW_YEAR,
        LauncherIcon.MID_AUTUMN, LauncherIcon.CHRISTMAS, LauncherIcon.HALLOWEEN, LauncherIcon.BLACK_FRIDAY,
    )

    /**
     * Singapore's majority keeps Lunar New Year first. In Iran, Afghanistan and Tajikistan Nowruz is the main holiday
     * of the year and wins where Ramadan or Eid fall with it (2026: Eid al-Fitr is Mar 20).
     */
    private val REGION_ORDER: Map<String, List<LauncherIcon>> = mapOf(
        "SG" to listOf(LauncherIcon.LUNAR_NEW_YEAR, LauncherIcon.MID_AUTUMN),
        "IR" to listOf(LauncherIcon.NOWRUZ),
        "AF" to listOf(LauncherIcon.NOWRUZ),
        "TJ" to listOf(LauncherIcon.NOWRUZ),
    )

    /** Android still reports some languages by their old codes. */
    private val LEGACY_LANGUAGES = mapOf("in" to "id", "iw" to "he", "ji" to "yi")

    fun language(code: String?): String? = code?.substringBefore('-')?.substringBefore('_')?.lowercase()?.let { LEGACY_LANGUAGES[it] ?: it }?.takeIf { it.isNotEmpty() }

    fun region(code: String?): String? = code?.uppercase()?.takeIf { it.length == 2 }

    /** Whether a phone set to [region] and [language] sees [icon]: every global one, a regional one where it is kept. */
    fun reaches(icon: LauncherIcon, region: String?, language: String?): Boolean {
        if (icon == LauncherIcon.DEFAULT) return false
        if (!icon.regional) return true
        val r = region(region)
        val l = language(language)
        return (r != null && r in REGIONS[icon].orEmpty()) || (l != null && l in LANGUAGES[icon].orEmpty())
    }

    /** Regional celebrations in the order they win for [region]. */
    fun order(region: String?): List<LauncherIcon> {
        val first = REGION_ORDER[region(region)].orEmpty()
        return first + (DEFAULT_ORDER - first.toSet())
    }
}

/**
 * The rule both apps use (README "App icons"): which icon shows on a day. Of the celebrations that reach the phone,
 * regional ones go before global ones, regional ones in their region's order, then the earlier; one is kept unless a
 * kept one is less than [MIN_GAP_DAYS] plain days away from it (so a phone never changes icon twice within a few
 * days, which keeps iOS to two system notices per celebration). The same answer on every launch.
 */
object SeasonalIcons {
    const val MIN_GAP_DAYS = 3

    fun pick(today: CalendarDay, region: String?, language: String?, overrides: List<SeasonalOverride>): LauncherIcon =
        shown(today.year - 1..today.year + 1, region, language, overrides).firstOrNull { today in it.range }?.icon ?: LauncherIcon.DEFAULT

    /** The windows that show over [years] for this phone, after the server's [overrides] and the spacing rule. */
    internal fun shown(years: IntRange, region: String?, language: String?, overrides: List<SeasonalOverride>): List<SeasonalWindow> {
        val order = IconAudiences.order(region)
        val candidates = years.flatMap { year -> withOverrides(year, SeasonalCalendar.windows(year), overrides) }
            .filter { IconAudiences.reaches(it.icon, region, language) && it.range.start <= it.range.end }
            .sortedWith(
                compareBy<SeasonalWindow>({ if (it.icon.regional) 0 else 1 }, { order.indexOf(it.icon) }, { it.range.start }, { it.icon.ordinal }),
            )
        val kept = mutableListOf<SeasonalWindow>()
        for (w in candidates) if (kept.none { tooClose(it.range, w.range) }) kept += w
        return kept.sortedBy { it.range.start }
    }

    /** Fewer than [MIN_GAP_DAYS] plain days between [a] and [b] (or they overlap). */
    private fun tooClose(a: DayRange, b: DayRange): Boolean =
        b.start.epochDay - a.end.epochDay - 1 < MIN_GAP_DAYS && a.start.epochDay - b.end.epochDay - 1 < MIN_GAP_DAYS

    private fun withOverrides(year: Int, table: List<SeasonalWindow>, overrides: List<SeasonalOverride>): List<SeasonalWindow> {
        val mine = overrides.filter { it.year == null || it.year == year }
        if (mine.isEmpty()) return table
        // a year's own override beats an every-year one
        val byIcon = mine.sortedBy { if (it.year == null) 0 else 1 }.associateBy { it.icon }
        return table.filter { it.icon !in byIcon } +
            byIcon.values.mapNotNull { o -> o.range?.let { SeasonalWindow(o.icon, year, it) } }
    }
}

/**
 * The launcher icon state both apps draw (README "App icons"): [seasonal] switches are on, [picked] is the icon chosen
 * by hand (shown while seasonal is off), [current] the icon the launcher should show today, [askConsent] the one
 * question iOS asks before its first automatic switch.
 */
data class LauncherIconState(
    val seasonal: Boolean = false,
    val picked: LauncherIcon = LauncherIcon.DEFAULT,
    val current: LauncherIcon = LauncherIcon.DEFAULT,
    val askConsent: Boolean = false,
)

/**
 * The app icon setting and today's icon. One per app ([AppEnvironment.launcherIcons]). The platform calls [configure]
 * at launch with the phone's region and language and whether it asks before switching (iOS shows a system notice on
 * every change), then [refresh] with today whenever the app comes to the foreground; it applies [LauncherIconState.current]
 * (iOS while in the foreground, Android when the app goes to the background).
 */
class LauncherIconModel internal constructor(private val prefs: () -> Prefs, dispatchers: AppDispatchers) :
    StateModel<LauncherIconState>(LauncherIconState(), dispatchers) {
    private var region: String? = null
    private var language: String? = null
    private var asksFirst = false
    private var overrides: List<SeasonalOverride> = emptyList()
    private var today: CalendarDay? = null

    init {
        update()
    }

    fun configure(region: String?, language: String?, asksFirst: Boolean) {
        this.region = region
        this.language = language
        this.asksFirst = asksFirst
        update()
    }

    /** The server's switch: stops or moves celebrations without a release. */
    fun setOverrides(overrides: List<SeasonalOverride>) {
        this.overrides = overrides
        update()
    }

    fun refresh(today: CalendarDay) {
        this.today = today
        update()
    }

    /** The celebration's icon the rule picks for today, whatever the setting. */
    fun seasonalToday(): LauncherIcon = today?.let { SeasonalIcons.pick(it, region, language, overrides) } ?: LauncherIcon.DEFAULT

    /** The one question: yes turns automatic switches on, no keeps them off; it is not asked again either way. */
    fun answerConsent(agree: Boolean) = setSeasonal(agree)

    fun setSeasonal(on: Boolean) {
        prefs().putString(KEY_SEASONAL, if (on) "1" else "0")
        update()
    }

    /** An icon chosen by hand (the everyday one included): automatic switches stop. */
    fun pick(icon: LauncherIcon) {
        prefs().putString(KEY_PICKED, icon.key)
        prefs().putString(KEY_SEASONAL, "0")
        update()
    }

    private fun update() {
        val answered = prefs().getString(KEY_SEASONAL)
        val seasonal = answered?.let { it == "1" } ?: !asksFirst
        val picked = LauncherIcon.ofKey(prefs().getString(KEY_PICKED)) ?: LauncherIcon.DEFAULT
        val celebration = seasonalToday()
        setState {
            LauncherIconState(
                seasonal = seasonal,
                picked = picked,
                current = if (seasonal) celebration else picked,
                askConsent = asksFirst && answered == null && celebration != LauncherIcon.DEFAULT,
            )
        }
    }

    /** The app's one model: screens do not own it. */
    override fun close() {}

    companion object {
        const val KEY_SEASONAL = "launcherIcon.seasonal"
        const val KEY_PICKED = "launcherIcon.picked"
    }
}
