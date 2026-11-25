package com.ecency.core.app

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.games.AdventDoor
import com.ecency.core.api.games.AdventDoorKind
import com.ecency.core.api.games.AdventDoorStatus
import com.ecency.core.api.games.AdventEntry
import com.ecency.core.api.games.AdventPayoutStatus
import com.ecency.core.api.games.AdventResult
import com.ecency.core.api.games.AdventSeasonData
import com.ecency.core.api.games.GamesApiException
import com.ecency.core.api.waves.WavePublish
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.cancellation.CancellationException

/** Signing in to the games API (web `use-advent-session` status). */
enum class AdventSignIn { NONE, SIGNING, READY, FAILED }

/** What the door sheet shows. */
enum class AdventSheetMode { LOADING, ANSWER, DONE, RESULT, FAILED }

/** The Wave that shares an answer: being posted, posted and linked, failed (a "Post Wave" button), or not tried. */
enum class AdventWaveState { NONE, POSTING, POSTED, FAILED }

/** One open door (web `door-sheet.tsx`). */
data class AdventSheet(
    val day: Int,
    val mode: AdventSheetMode,
    val door: AdventDoor?,
    val entry: AdventEntry?,
    val result: AdventResult?,
    /** RESULT before the draw ("Winners are drawn at 02:00 UTC."). */
    val notDrawn: Boolean,
    val submitting: Boolean,
    val error: AppError?,
    val wave: AdventWaveState,
    /** The Wave's permlink once posted ("View your Wave"). */
    val wavePermlink: String?,
    /** The door closed before this answer: it counts for the calendar, not for prizes. */
    val late: Boolean,
    /** RESULT: the account answered this door ("See your answer"); else "Answer anyway" while it is open to late answers. */
    val answered: Boolean,
) {
    val canAnswerLate: Boolean get() = mode == AdventSheetMode.RESULT && !answered && door != null && door.status != AdventDoorStatus.LOCKED
}

/** A row of "Your prizes". */
data class AdventPrizeRow(val day: Int, val text: String, val amount: String, val status: String, val paid: Boolean)

/** The grand draw card. */
data class AdventGrandCard(
    val total: String,
    val text: String,
    val progress: String,
    /** 0..1 of the minimum doors answered. */
    val fraction: Double,
    val drawn: Boolean,
    val winners: List<String>,
)

data class AdventState(
    val phase: AdventPhase = AdventPhase.HIDDEN,
    val todayDoor: Int = 0,
    /** The account the calendar is for; null signed out (doors and results only). */
    val account: String? = null,
    val signIn: AdventSignIn = AdventSignIn.NONE,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: AppError? = null,
    val tiles: List<AdventTile> = emptyList(),
    val answered: Int = 0,
    val poolLeft: String? = null,
    val grand: AdventGrandCard? = null,
    val prizes: List<AdventPrizeRow> = emptyList(),
    val pointsSoFar: String? = null,
    val sheet: AdventSheet? = null,
    val snow: Boolean = true,
    /** A door was tapped signed out: the platform opens the login screen once ([AdventModel.consumeLogin]). */
    val askLogin: Boolean = false,
) {
    /** The big line under the kicker: today's door, the countdown's title, the last door or the end. */
    val headline: String
        get() = when {
            phase == AdventPhase.TEASER -> L.native.advent.teaserTitle
            todayDoor > 0 -> L.native.advent.doorOpen(day = LocaleFormat.integer(todayDoor.toLong()))
            phase == AdventPhase.SEASON -> L.native.advent.lastDraw
            else -> L.native.advent.ended
        }

    val kicker: String get() = L.native.advent.kicker(year = AdventSeason.SEASON.toString())
}

/**
 * The Advent Calendar screen (web `app/advent`): the season from the games API, the signed-in account's doors, one
 * door at a time to answer (its Wave posted after), results and the grand draw. Everything shown belongs to the account
 * that loaded it: an account change starts over, and an answer for the previous one is dropped.
 */
class AdventModel internal constructor(private val env: AppEnvironment) : StateModel<AdventState>(AdventState(), env.dispatchers) {
    private var started = false
    private var loadJob: Job? = null
    private var sheetJob: Job? = null
    private var season: AdventSeasonData? = null

    /** 401s answered by a new session since the last success (web `lost`: one automatic sign-in, then ask). */
    private var refusals = 0

    fun start() {
        if (started) return
        started = true
        setState { it.copy(snow = env.advent.snow) }
        scope.launch {
            env.events.collect { event ->
                if (event is AppEvent.SessionChanged && event.username != current.account) {
                    loadJob?.cancel()
                    sheetJob?.cancel()
                    season = null
                    refusals = 0
                    setState { AdventState(snow = it.snow) }
                    load()
                }
            }
        }
        load()
    }

    /** Pull to refresh, the screen coming back, the season changing phase. */
    fun refresh() = load()

    fun retrySignIn() {
        refusals = 0
        load()
    }

    fun toggleSnow() {
        val on = !current.snow
        env.advent.snow = on
        setState { it.copy(snow = on) }
    }

    fun consumeLogin() = setState { it.copy(askLogin = false) }

    fun closeSheet() {
        sheetJob?.cancel()
        setState { it.copy(sheet = null) }
    }

    private fun load() {
        loadJob?.cancel()
        val now = env.nowMillis()
        setState { it.copy(phase = AdventSeason.phase(now), todayDoor = AdventSeason.todayDoor(now), loading = true, error = null) }
        loadJob = scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username
            if (user != current.account) setState { AdventState(snow = it.snow, phase = it.phase, todayDoor = it.todayDoor, loading = true, account = user) }
            try {
                val data = withToken(user) { token -> env.advent.api.season(AdventSeason.SEASON, token) }
                val grand = runCatching { env.advent.api.grand(AdventSeason.SEASON) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (!owns(user)) return@launch
                season = data
                setState { s -> show(s, data, grand, user).copy(loading = false, loaded = true, signIn = if (user == null) AdventSignIn.NONE else AdventSignIn.READY) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!owns(user)) return@launch
                val failedSignIn = e is SignInRefused
                setState { it.copy(loading = false, error = if (failedSignIn) null else AppError.from(e, "advent.load"), signIn = if (failedSignIn) AdventSignIn.FAILED else it.signIn) }
            }
        }
    }

    private fun show(s: AdventState, data: AdventSeasonData, grand: com.ecency.core.api.games.AdventGrand?, user: String?): AdventState {
        val teaser = s.phase == AdventPhase.TEASER
        val me = data.me?.takeIf { it.account == user }
        val tiles = data.doors.map { AdventDoors.tile(it, me?.doors?.get(it.day), user != null, teaser) }
        val prizes = buildList<AdventPrizeRow> {
            me?.doors?.entries?.sortedBy { it.key }?.forEach { (day, d) ->
                d.prize?.takeIf { it.status != AdventPayoutStatus.CANCELLED }?.let { p ->
                    add(AdventPrizeRow(day, L.native.advent.prizeRow(day = LocaleFormat.integer(day.toLong()), place = LocaleFormat.integer(p.place.toLong())), AdventCopy.prize(p.kind, p.amount), AdventCopy.status(p.status), p.status == AdventPayoutStatus.PAID))
                }
                d.participation?.let { p ->
                    add(AdventPrizeRow(day, L.native.advent.participationRow(day = LocaleFormat.integer(day.toLong())), AdventCopy.prize(com.ecency.core.api.games.AdventRewardKind.POINTS, p.amount), AdventCopy.status(if (p.paid) AdventPayoutStatus.PAID else AdventPayoutStatus.PENDING), p.paid))
                }
            }
            me?.grand?.takeIf { it.status != AdventPayoutStatus.CANCELLED }?.let { g ->
                add(AdventPrizeRow(0, L.native.advent.grandRow(place = LocaleFormat.integer(g.place.toLong())), AdventCopy.prize(com.ecency.core.api.games.AdventRewardKind.POINTS, g.amount), AdventCopy.status(g.status), g.status == AdventPayoutStatus.PAID))
            }
        }
        val points = me?.doors?.values?.sumOf { d ->
            (d.participation?.amount ?: 0.0) + (d.prize?.takeIf { it.kind == com.ecency.core.api.games.AdventRewardKind.POINTS && it.status != AdventPayoutStatus.CANCELLED }?.amount ?: 0.0)
        }?.plus(me.grand?.takeIf { it.status != AdventPayoutStatus.CANCELLED }?.amount ?: 0.0)
        val answered = me?.answered ?: 0
        val card = grand?.takeIf { s.phase == AdventPhase.SEASON }?.let { g ->
            val min = g.minDoors.coerceAtLeast(1)
            AdventGrandCard(
                total = L.native.advent.grandTotal(n = AdventCopy.amount(g.amounts.sum())),
                text = L.native.advent.grandText(n = LocaleFormat.integer(g.minDoors.toLong()), time = LocaleFormat.date(g.drawAtMillis) + ", " + LocaleFormat.time(g.drawAtMillis)),
                progress = L.native.advent.grandProgress(n = LocaleFormat.integer(minOf(answered, g.minDoors).toLong()), of = LocaleFormat.integer(g.minDoors.toLong())),
                fraction = (minOf(answered, min).toDouble() / min),
                drawn = g.drawn,
                winners = g.winners.filterNot { it.cancelled }.map { "${LocaleFormat.integer(it.place.toLong())}. @${it.account}" },
            )
        }
        return s.copy(
            account = user, tiles = tiles, answered = answered,
            poolLeft = data.poolLeft?.let { AdventCopy.amount(it) },
            grand = card, prizes = prizes,
            pointsSoFar = points?.takeIf { it > 0 }?.let { L.native.advent.pointsSoFar(n = AdventCopy.amount(it)) },
        )
    }

    // ---- a door ----

    /** A tap on door [day] (web `onDoor`). */
    fun openDoor(day: Int) {
        val door = season?.doors?.firstOrNull { it.day == day } ?: return
        if (door.status == AdventDoorStatus.LOCKED || current.phase != AdventPhase.SEASON) return
        val user = current.account
        val me = season?.me?.doors?.get(day)
        if (door.status == AdventDoorStatus.DRAWN || user == null) {
            // signed out, an open door asks for the login; a drawn one shows its result
            if (user == null && door.status != AdventDoorStatus.DRAWN) {
                setState { it.copy(askLogin = true) }
                return
            }
            showResult(door, answered = me?.answered == true)
            return
        }
        loadDoor(user, door)
    }

    /** From a result: "Answer anyway" or "See your answer". */
    fun answerFromResult() {
        val sheet = current.sheet ?: return
        val door = sheet.door ?: return
        val user = current.account ?: run { setState { it.copy(askLogin = true) }; return }
        loadDoor(user, door)
    }

    fun retrySheet() {
        val sheet = current.sheet ?: return
        val door = sheet.door ?: season?.doors?.firstOrNull { it.day == sheet.day } ?: return
        if (sheet.mode == AdventSheetMode.RESULT || door.status == AdventDoorStatus.DRAWN && current.account == null) showResult(door, sheet.answered)
        else current.account?.let { loadDoor(it, door) }
    }

    private fun loadDoor(user: String, door: AdventDoor) {
        sheetJob?.cancel()
        setState { it.copy(sheet = blank(door.day, AdventSheetMode.LOADING, door)) }
        sheetJob = scope.launch {
            try {
                val opened = withToken(user) { token -> env.advent.api.open(AdventSeason.SEASON, door.day, token!!) }
                if (!owns(user)) return@launch
                val pending = env.advent.pendingWave(user, door.day)
                val wave = when {
                    opened.entry.wave != null -> AdventWaveState.POSTED
                    opened.entry.answered && pending != null -> AdventWaveState.FAILED
                    else -> AdventWaveState.NONE
                }
                setState {
                    it.copy(sheet = blank(door.day, if (opened.entry.answered) AdventSheetMode.DONE else AdventSheetMode.ANSWER, opened.door).copy(
                        entry = opened.entry, wave = wave, wavePermlink = opened.entry.wave,
                        late = !opened.entry.answered && env.nowMillis() > opened.door.closeAtMillis && opened.door.closeAtMillis > 0,
                        answered = opened.entry.answered,
                    ))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!owns(user)) return@launch
                setState { s -> s.copy(sheet = s.sheet?.copy(mode = AdventSheetMode.FAILED, error = AppError.from(e, "advent.open")), signIn = if (e is SignInRefused) AdventSignIn.FAILED else s.signIn) }
            }
        }
    }

    private fun showResult(door: AdventDoor, answered: Boolean) {
        sheetJob?.cancel()
        setState { it.copy(sheet = blank(door.day, AdventSheetMode.LOADING, door).copy(answered = answered)) }
        val user = current.account
        sheetJob = scope.launch {
            try {
                val result = env.advent.api.result(AdventSeason.SEASON, door.day)
                if (!owns(user)) return@launch
                setState { it.copy(sheet = it.sheet?.copy(mode = AdventSheetMode.RESULT, result = result)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!owns(user)) return@launch
                val notDrawn = e is GamesApiException && e.code == "not_drawn"
                setState { it.copy(sheet = it.sheet?.copy(mode = if (notDrawn) AdventSheetMode.RESULT else AdventSheetMode.FAILED, notDrawn = notDrawn, error = if (notDrawn) null else AppError.from(e, "advent.result"))) }
            }
        }
    }

    /**
     * Sends the answer: a written [answer] (trimmed, at most [MAX_ANSWER] characters) or a [choice]. Then the Wave
     * (web: in the same tap); the answer counts even when the Wave fails.
     */
    fun submit(answer: String?, choice: Int) {
        val sheet = current.sheet ?: return
        val door = sheet.door ?: return
        val user = current.account ?: return
        if (sheet.mode != AdventSheetMode.ANSWER || sheet.submitting) return
        val text = answer?.trim()?.take(MAX_ANSWER)
        if (door.kind == AdventDoorKind.OPEN && text.isNullOrEmpty()) return
        if (door.kind != AdventDoorKind.OPEN && choice !in door.choices.indices) return
        setState { it.copy(sheet = it.sheet?.copy(submitting = true, error = null)) }
        sheetJob?.cancel()
        sheetJob = scope.launch {
            val late = try {
                withToken(user) { token ->
                    env.advent.api.answer(AdventSeason.SEASON, door.day, token!!, if (door.kind == AdventDoorKind.OPEN) text else null, if (door.kind == AdventDoorKind.OPEN) null else choice)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!owns(user)) return@launch
                if (e is GamesApiException && e.code == "already_answered") {
                    loadDoor(user, door)
                    return@launch
                }
                setState { it.copy(sheet = it.sheet?.copy(submitting = false, error = AppError(ErrorKind.NETWORK, L.native.advent.answerFailed, true))) }
                return@launch
            }
            if (!owns(user)) return@launch
            if (!late && door.day == AdventSeason.todayDoor(env.nowMillis())) env.advent.answeredToday()
            val entry = AdventEntry(true, text, if (door.kind == AdventDoorKind.OPEN) null else choice, late, null)
            setState { it.copy(sheet = it.sheet?.copy(mode = AdventSheetMode.DONE, submitting = false, entry = entry, answered = true, wave = AdventWaveState.POSTING)) }
            // the calendar shows the answered door at once; the season is read again after
            refresh()
            postWave(user, door, text)
        }
    }

    /** "Post Wave" after a Wave that failed, or "Add your Wave to the door" for one posted and not linked. */
    fun retryWave() {
        val sheet = current.sheet ?: return
        val door = sheet.door ?: return
        val user = current.account ?: return
        if (sheet.wave == AdventWaveState.POSTING || sheet.wave == AdventWaveState.POSTED) return
        setState { it.copy(sheet = it.sheet?.copy(wave = AdventWaveState.POSTING)) }
        sheetJob?.cancel()
        sheetJob = scope.launch { postWave(user, door, sheet.entry?.answer) }
    }

    /**
     * Posts the answer's Wave once: the container and body are kept until the Wave is linked to the door, so a retry
     * (after a lost answer, a crash, a new container post) signs the same reply, and one already on chain is only linked.
     */
    private suspend fun postWave(user: String, door: AdventDoor, answer: String?) {
        try {
            val pending = env.advent.pendingWave(user, door.day) ?: run {
                val container = env.waves.latestContainer()
                AdventPendingWave(container.author, container.permlink, WavePublish.containerTags(container.metadata.rawJson).orEmpty(), AdventWave.text(door.day, door.kind, answer))
                    .also { env.advent.savePendingWave(user, door.day, it) }
            }
            val draft = WavePublish.draft(user, pending.containerAuthor, pending.containerPermlink, pending.tags, pending.body)
            if (env.api.hive.content(user, draft.permlink) == null) {
                val signed = env.signing.broadcast(user, JsonArray(draft.operations.map(OperationJson::encode)))
                env.pointActivities.recordBroadcast(user, PointActivity.content(pending.containerAuthor, isUpdate = false), signed)
            }
            if (!owns(user)) return
            setState { it.copy(sheet = it.sheet?.takeIf { s -> s.day == door.day }?.copy(wavePermlink = draft.permlink) ?: it.sheet) }
            link(user, door.day, draft.permlink)
            env.advent.clearPendingWave(user, door.day)
            if (owns(user)) setState { it.copy(sheet = it.sheet?.takeIf { s -> s.day == door.day }?.copy(wave = AdventWaveState.POSTED) ?: it.sheet) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorReports.quiet(e, "advent.wave")
            if (owns(user)) setState { it.copy(sheet = it.sheet?.takeIf { s -> s.day == door.day }?.copy(wave = AdventWaveState.FAILED) ?: it.sheet) }
        }
    }

    /** Web `linkWave`: up to 3 tries, 800 then 1600 ms apart, each with the account's own session. */
    private suspend fun link(user: String, day: Int, permlink: String) {
        var last: Exception? = null
        for ((i, wait) in LINK_DELAYS.withIndex()) {
            if (i > 0) delay(wait)
            try {
                withToken(user) { token -> env.advent.api.wave(AdventSeason.SEASON, day, token!!, permlink) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("not linked")
    }

    /**
     * Runs [block] with [user]'s game session (null signed out: no token). A 401 signs in again once; a second 401
     * since the last success stops with [SignInRefused] ("Could not sign in. Try again").
     */
    private suspend fun <T> withToken(user: String?, block: suspend (String?) -> T): T {
        if (user == null) return block(null)
        if (refusals >= MAX_REFUSALS) throw SignInRefused()
        if (current.signIn != AdventSignIn.READY && owns(user)) setState { it.copy(signIn = AdventSignIn.SIGNING) }
        val token = try {
            env.advent.token(user, fresh = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GamesApiException) {
            if (e.status == 401 || e.status == 403) {
                refusals = MAX_REFUSALS
                throw SignInRefused()
            }
            throw e
        }
        return try {
            block(token).also { refusals = 0 }
        } catch (e: GamesApiException) {
            if (e.status != 401) throw e
            env.advent.dropped(user, token)
            refusals++
            if (refusals >= MAX_REFUSALS) throw SignInRefused()
            withToken(user, block)
        }
    }

    private fun owns(user: String?): Boolean = env.session.current.username == user && current.account == user

    private fun blank(day: Int, mode: AdventSheetMode, door: AdventDoor?) = AdventSheet(day, mode, door, null, null, false, false, null, AdventWaveState.NONE, null, false, false)

    /** The games API refused the account's sign-in twice: the screen asks ("Could not sign in. Try again"). */
    class SignInRefused : Exception("sign-in refused")

    companion object {
        const val MAX_ANSWER = 2000
        private const val MAX_REFUSALS = 2
        private val LINK_DELAYS = listOf(0L, 800L, 1600L)

        /** Web `ADVENT_URL`: the calendar's page, for a link and a share. */
        const val URL = AdventWave.URL

        /** A Wave's page on ecency.com. */
        fun waveUrl(author: String, permlink: String): String = "https://ecency.com/@$author/$permlink"
    }
}
