package com.ecency.core.app

import com.ecency.core.api.games.AdventApi
import com.ecency.core.api.games.AdventDoor
import com.ecency.core.api.games.AdventDoorKind
import com.ecency.core.api.games.AdventDoorStatus
import com.ecency.core.api.games.AdventMeDoor
import com.ecency.core.api.games.AdventParticipation
import com.ecency.core.api.games.AdventPayoutStatus
import com.ecency.core.api.games.AdventPrize
import com.ecency.core.api.games.AdventReward
import com.ecency.core.api.games.AdventRewardKind
import com.ecency.core.api.games.GameSession
import com.ecency.core.api.games.GamesApiException
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.hive.PrivateKey
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.cancellation.CancellationException

/** Where the season is (web `adventPhase`). */
enum class AdventPhase { HIDDEN, TEASER, SEASON }

/**
 * The Ecency Advent Calendar's dates, the same rule as ecency.com/advent (web `features/advent/season.ts`), all UTC:
 * shown from 25 Nov, doors 1 to 24 from 1 Dec (one a day at 00:00), gone from 1 Jan. The menu item, the feed banner and
 * the screen all read it, so the calendar shows and hides itself with no release and no flag.
 */
object AdventSeason {
    const val SEASON = 2026

    /** 2026-11-25T00:00:00Z */
    const val SHOW_FROM = 1_795_564_800_000L

    /** 2026-12-01T00:00:00Z */
    const val FIRST_DOOR = 1_796_083_200_000L

    /** 2027-01-01T00:00:00Z */
    const val HIDE_AT = 1_798_761_600_000L

    const val DOORS = 24
    const val DAY_MILLIS = 86_400_000L

    fun phase(nowMillis: Long): AdventPhase = when {
        nowMillis < SHOW_FROM || nowMillis >= HIDE_AT -> AdventPhase.HIDDEN
        nowMillis < FIRST_DOOR -> AdventPhase.TEASER
        else -> AdventPhase.SEASON
    }

    fun isVisible(nowMillis: Long): Boolean = phase(nowMillis) != AdventPhase.HIDDEN

    /** Today's door (1..24), or 0 when none is open today (before 1 Dec, from 25 Dec). */
    fun todayDoor(nowMillis: Long): Int {
        if (nowMillis < FIRST_DOOR) return 0
        val day = ((nowMillis - FIRST_DOOR) / DAY_MILLIS + 1).toInt()
        return if (day in 1..DOORS) day else 0
    }

    /** The UTC calendar day of [nowMillis] as `YYYY-MM-DD` (the banner's once-a-day rule). */
    fun utcDay(nowMillis: Long): String {
        val days = floorDiv(nowMillis, DAY_MILLIS)
        // civil from days (Howard Hinnant's algorithm)
        val z = days + 719_468
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    private fun floorDiv(a: Long, b: Long): Long = if (a >= 0) a / b else -((-a + b - 1) / b)
}

/** How a door tile looks (web `door-view.ts`). */
enum class AdventDoorLook { LOCKED, TODAY, WAITING, ANSWERED, WON, MISSED, CLOSED, RESULTS }

/** One door of the calendar as drawn: [tag] is its short line ("Open me", "+50 Points", "#1"...). */
data class AdventTile(
    val day: Int,
    val look: AdventDoorLook,
    val late: Boolean,
    val prize: AdventPrize?,
    val tag: String,
    /** What VoiceOver and TalkBack read: the door and its state. */
    val label: String,
    /** A tile that opens something (a door to answer, a result, an answer). */
    val tappable: Boolean,
)

object AdventDoors {
    /**
     * Web `doorView`: [door] as the calendar shows it to [me] ([signedIn] false: nobody). In the [teaser] every door
     * is wrapped.
     */
    fun look(door: AdventDoor, me: AdventMeDoor?, signedIn: Boolean, teaser: Boolean): AdventDoorLook = when {
        teaser || door.status == AdventDoorStatus.LOCKED -> AdventDoorLook.LOCKED
        me?.answered == true && me.late -> AdventDoorLook.ANSWERED
        door.status == AdventDoorStatus.OPEN -> if (me?.answered == true) AdventDoorLook.WAITING else AdventDoorLook.TODAY
        door.status == AdventDoorStatus.CLOSED -> when {
            me?.answered == true -> AdventDoorLook.WAITING
            signedIn -> AdventDoorLook.MISSED
            else -> AdventDoorLook.CLOSED
        }
        me?.prize != null && me.prize.status != AdventPayoutStatus.CANCELLED -> AdventDoorLook.WON
        me?.answered == true -> AdventDoorLook.ANSWERED
        signedIn -> AdventDoorLook.MISSED
        else -> AdventDoorLook.RESULTS
    }

    fun tile(door: AdventDoor, me: AdventMeDoor?, signedIn: Boolean, teaser: Boolean): AdventTile {
        val look = look(door, me, signedIn, teaser)
        val late = look == AdventDoorLook.ANSWERED && me?.late == true
        val prize = me?.prize?.takeIf { look == AdventDoorLook.WON }
        val tag = when (look) {
            AdventDoorLook.LOCKED -> ""
            AdventDoorLook.TODAY -> L.native.advent.openMe
            AdventDoorLook.WAITING -> L.native.advent.waitingDraw
            AdventDoorLook.ANSWERED -> when {
                late -> L.native.advent.late
                me?.participation != null -> L.native.advent.plusPoints(n = AdventCopy.amount(me.participation.amount))
                else -> L.native.advent.answered
            }
            AdventDoorLook.WON -> L.native.advent.place(n = LocaleFormat.integer((prize?.place ?: 0).toLong()))
            AdventDoorLook.MISSED -> L.native.advent.missed
            AdventDoorLook.CLOSED -> L.native.advent.closed
            AdventDoorLook.RESULTS -> L.native.advent.results
        }
        val state = if (look == AdventDoorLook.LOCKED) L.native.advent.locked else tag
        return AdventTile(
            day = door.day, look = look, late = late, prize = prize, tag = tag,
            label = L.native.advent.a11yDoor(day = LocaleFormat.integer(door.day.toLong()), state = state),
            tappable = look != AdventDoorLook.LOCKED,
        )
    }
}

/** The calendar's copy that is more than one key. */
object AdventCopy {
    val TITLE: String get() = L.native.advent.title

    /** A reward amount, grouped in the app language, at most 3 decimals. */
    fun amount(value: Double): String = LocaleFormat.decimal(value, 3, 0)

    /** The time left to the first door, whole minutes rounded up ("5 days 14 h 0 min"). */
    fun countdown(leftMillis: Long): String {
        val minutes = (leftMillis.coerceAtLeast(0) + 59_999) / 60_000
        return L.native.advent.countdown(
            days = (minutes / 1_440).toInt(),
            hours = LocaleFormat.integer(minutes % 1_440 / 60),
            minutes = LocaleFormat.integer(minutes % 60),
        )
    }

    /** At most [max] UTF-16 units (the limit both editors count), never cutting a surrogate pair in two. */
    fun limit(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, end)
    }

    /** Under the answer field: "2,000 characters at most", the number in the app language. */
    val MAX_CHARS: String get() = L.native.advent.maxChars(n = LocaleFormat.integer(AdventModel.MAX_ANSWER.toLong()))

    /** Web `rewardText`: "500 / 300 / 200 Points"; a spotlight reads "Promotion worth 1,000 Points". */
    fun reward(reward: AdventReward): String {
        val amounts = reward.amounts.joinToString(" / ") { amount(it) }
        return if (reward.kind == AdventRewardKind.SPOTLIGHT) L.native.advent.spotlightAmount(n = amounts) else "$amounts ${rewardLabel(reward.kind)}"
    }

    /** One prize: "100 HIVE", or a spotlight's promotion. */
    fun prize(kind: AdventRewardKind, amount: Double): String = reward(AdventReward(kind, listOf(amount)))

    fun rewardLabel(kind: AdventRewardKind): String = when (kind) {
        AdventRewardKind.POINTS -> L.native.advent.rewardPoints
        AdventRewardKind.HIVE -> L.native.advent.rewardHive
        AdventRewardKind.HP -> L.native.advent.rewardHp
        AdventRewardKind.DELEGATION -> L.native.advent.rewardDelegation
        AdventRewardKind.SPOTLIGHT -> L.native.advent.rewardSpotlight
    }

    /** Web status pills: Paid, On its way, Delayed for any other status. */
    fun status(status: AdventPayoutStatus): String = when (status) {
        AdventPayoutStatus.PAID -> L.native.advent.statusPaid
        AdventPayoutStatus.PENDING -> L.native.advent.statusPending
        else -> L.native.advent.statusDelayed
    }

    fun kindLabel(kind: AdventDoorKind): String = if (kind == AdventDoorKind.OPEN) L.native.advent.labelWrite else L.native.advent.labelPick
}

/** The Wave that shares an answer (web `wave-text.ts`). */
object AdventWave {
    const val TAG = "ecencyadvent"
    const val URL = "https://ecency.com/advent"

    /**
     * `#ecencyadvent I opened door N ...`, then the written answer and the calendar's link. A quiz or poll Wave never
     * says the choice.
     */
    fun text(day: Int, kind: AdventDoorKind, answer: String?): String {
        val head = "#$TAG ${L.native.advent.waveHead(day = day.toString())}"
        if (kind != AdventDoorKind.OPEN) return "$head\n\n$URL"
        return "$head\n\n${answer.orEmpty().trim()}\n\n$URL"
    }
}

/** What the feed banner says (web `advent-banner.tsx`). */
enum class AdventBannerKind { TEASER, DOOR, RESULTS }

data class AdventBanner(val kind: AdventBannerKind, val day: Int, val title: String, val text: String, val action: String)

/** A Wave being posted for a door: its container and body, so a retry signs the very same reply (one Wave, never two). */
internal data class AdventPendingWave(val containerAuthor: String, val containerPermlink: String, val tags: List<String>, val body: String)

/**
 * The calendar's app-wide parts: the game sessions (one per account, in memory only: a new one is signed when the app
 * starts again), the feed banner's once-a-day rule and the Waves still being posted. Main thread.
 */
class AdventService internal constructor(private val env: AppEnvironment) {
    /** Replaceable in tests. */
    var api: AdventApi = AdventApi(env.auth.http)

    private val sessions = mutableMapOf<String, GameSession>()
    private val signing = mutableMapOf<String, Deferred<GameSession>>()

    /** Bumped when accounts leave: a sign-in that started before is not kept. */
    private var generation = 0

    /**
     * A game session token for [username], signed in when there is none (or [fresh]): a posting key signs the web's
     * login proof; any other login asks ecency.com to vouch with the account's Ecency token (renewed once if refused).
     * @throws GamesApiException when the games API or ecency.com refused or could not be reached.
     */
    @Throws(Exception::class)
    suspend fun token(username: String, fresh: Boolean): String {
        if (fresh) sessions.remove(username)
        sessions[username]?.let { if (it.expiresAtMillis == 0L || it.expiresAtMillis - env.nowMillis() > EXPIRY_MARGIN_MILLIS) return it.token }
        signing[username]?.let { return it.await().token }
        val started = generation
        // in the app's scope, not the caller's: a caller that goes away (a closed door, a refresh) leaves the sign-in
        // to the others waiting on it
        val pending = env.background.async {
            val session = signIn(username)
            if (session.account != username) throw GamesApiException(401, "unauthorized", "Wrong account")
            // a session already "expired" by the phone's clock (a clock ahead of the server's) is kept with no expiry:
            // the games API refusing it (401) is what replaces it then, not a new sign-in for every call
            val kept = if (session.expiresAtMillis - env.nowMillis() > EXPIRY_MARGIN_MILLIS) session else session.copy(expiresAtMillis = 0L)
            if (started == generation) sessions[username] = kept
            session
        }
        signing[username] = pending
        pending.invokeOnCompletion { if (signing[username] === pending) signing.remove(username) }
        return pending.await().token
    }

    /** The session of [username] was refused (401): it is not used again. True when it was still the one kept. */
    internal fun dropped(username: String, token: String): Boolean {
        if (sessions[username]?.token != token) return false
        sessions.remove(username)
        return true
    }

    private suspend fun signIn(username: String): GameSession {
        val wif = env.accounts.vaultAccount(username)?.postingKey?.takeIf { it.isNotEmpty() }
        val key = wif?.let { runCatching { PrivateKey.fromWif(it) }.getOrNull() }
        if (key != null) {
            try {
                return api.session(HiveSignerMessages.gameLoginProof(username, key, env.nowMillis()))
            } catch (e: GamesApiException) { // not reported: rethrown, or the vouch below is tried
                // a refused proof is most often a phone clock far off; the vouch does not depend on the phone's clock
                if (e.status != 401 || env.accounts.code(username) == null) throw e
            }
        }
        val code = env.accounts.code(username) ?: throw GamesApiException(401, "unauthorized", "No Ecency token")
        return try {
            api.vouched(username, code)
        } catch (e: GamesApiException) {
            if (e.status != 401) throw e
            // an expired or refused token: renewed once, as chat does
            val renewed = env.sessionRenewals.renew(username) ?: throw e
            api.vouched(username, renewed)
        }
    }

    /** Accounts left the device (null: all): their sessions end, here and on the server, and their pending Waves go. */
    internal fun forget(accounts: List<String>?) {
        generation++
        val leaving = if (accounts == null) sessions.keys.toList() else accounts.filter { it in sessions }
        val tokens = leaving.mapNotNull { sessions.remove(it)?.token }
        val prefix = PENDING_PREFIX
        for (key in env.prefs.keys().filter { it.startsWith(prefix) }) {
            val owner = key.removePrefix(prefix).substringBeforeLast('_')
            if (accounts == null || owner in accounts) env.prefs.putString(key, null)
        }
        if (tokens.isEmpty()) return
        env.background.launch {
            for (token in tokens) {
                try {
                    api.endSession(token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // not reported: the session expires by itself on the server
                }
            }
        }
    }

    // ---- the feed banner (web advent-banner.tsx): by date, gone for the UTC day once closed or answered ----

    fun banner(nowMillis: Long): AdventBanner? {
        val phase = AdventSeason.phase(nowMillis)
        if (phase == AdventPhase.HIDDEN) return null
        val today = AdventSeason.utcDay(nowMillis)
        if (env.prefs.getString(BANNER_CLOSED) == today || env.prefs.getString(ANSWERED_ON) == today) return null
        val day = AdventSeason.todayDoor(nowMillis)
        return when {
            phase == AdventPhase.TEASER -> AdventBanner(AdventBannerKind.TEASER, 0, L.native.advent.calendarTitle, L.native.advent.bannerTeaser, L.native.advent.bannerTeaserCta)
            day > 0 -> AdventBanner(AdventBannerKind.DOOR, day, L.native.advent.doorOpen(day = LocaleFormat.integer(day.toLong())), L.native.advent.bannerDoor, L.native.advent.bannerDoorCta(day = LocaleFormat.integer(day.toLong())))
            else -> AdventBanner(AdventBannerKind.RESULTS, 0, L.native.advent.calendarTitle, L.native.advent.bannerResults, L.native.advent.bannerResultsCta)
        }
    }

    /** The banner's close button: back the next UTC day. */
    fun closeBanner() {
        env.prefs.putString(BANNER_CLOSED, AdventSeason.utcDay(env.nowMillis()))
        env.emit(AppEvent.AdventBannerChanged)
    }

    /** Today's door was answered on time: no banner for the rest of the UTC day. */
    internal fun answeredToday() {
        env.prefs.putString(ANSWERED_ON, AdventSeason.utcDay(env.nowMillis()))
        env.emit(AppEvent.AdventBannerChanged)
    }

    /** The snow and lights (on unless turned off; Reduce Motion stops them on the platform). */
    var snow: Boolean
        get() = env.prefs.getString(SNOW) != "off"
        set(value) = env.prefs.putString(SNOW, if (value) null else "off")

    // ---- Waves being posted ----

    private fun pendingKey(username: String, day: Int) = "$PENDING_PREFIX${username}_$day"

    internal fun pendingWave(username: String, day: Int): AdventPendingWave? {
        val text = env.prefs.getString(pendingKey(username, day)) ?: return null
        return runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            AdventPendingWave(
                o.getValue("author").jsonPrimitive.content, o.getValue("permlink").jsonPrimitive.content,
                o["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), o.getValue("body").jsonPrimitive.content,
            )
        }.getOrNull()
    }

    internal fun savePendingWave(username: String, day: Int, wave: AdventPendingWave) {
        val o = buildJsonObject {
            put("author", wave.containerAuthor)
            put("permlink", wave.containerPermlink)
            putJsonArray("tags") { wave.tags.forEach { add(JsonPrimitive(it)) } }
            put("body", wave.body)
        }
        env.prefs.putString(pendingKey(username, day), o.toString())
    }

    internal fun clearPendingWave(username: String, day: Int) = env.prefs.putString(pendingKey(username, day), null)

    companion object {
        /** A session this close to its end is signed again before use. */
        const val EXPIRY_MARGIN_MILLIS = 60_000L

        const val BANNER_CLOSED = "advent_banner_closed"
        const val ANSWERED_ON = "advent_answered_on"
        const val SNOW = "advent_snow"
        const val PENDING_PREFIX = "advent_wave_pending_${AdventSeason.SEASON}_"
    }
}
