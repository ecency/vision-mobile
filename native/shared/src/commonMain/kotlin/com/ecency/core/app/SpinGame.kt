package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.ecency.GameStatus
import com.ecency.core.api.ecency.PerksHttpException
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** RN en-US `free_estm.*` and the spin game's alerts. */
object SpinCopy {
    val TITLE: String get() = L.freeEstm.title
    val SPIN_LEFT: String get() = L.freeEstm.spinRight
    val BUTTON: String get() = L.freeEstm.button
    val GET_SPIN: String get() = L.freeEstm.getSpin
    val TIMER_TEXT: String get() = L.freeEstm.timerText
    val KEY_MISSING: String get() = L.native.spin.keyMissing
    val UNKNOWN_ERROR: String get() = L.alert.unknowError

    fun score(score: Int) = L.native.spin.score(score = score)

    /** RN SpinGameView's animation: the rings turn for 8 s after a press, whatever the answer. */
    const val SPIN_MILLIS = 8_000L

    /** RN refreshes the countdown every 3 s. */
    const val TIMER_TICK_MILLIS = 3_000L

    /**
     * RN `moment.utc(moment(nextDate).diff(new Date())).format('H:m:ss')`: the time to [nextDate] as a time of day
     * (a day or more wraps, a date in the past reads as the time before midnight), hours and minutes unpadded.
     * Empty when there is no readable date (RN shows "Invalid date").
     */
    @OptIn(ExperimentalTime::class)
    fun timeLeft(nextDate: String?, nowMillis: Long): String {
        val target = nextDate?.let(::parseDate) ?: return ""
        val day = 24L * 60 * 60 * 1000
        val inDay = ((target - nowMillis) % day + day) % day
        val seconds = inDay / 1000
        return "${seconds / 3600}:${seconds / 60 % 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** ISO instants; one without a zone is read as UTC (moment would read it in the device's zone). */
    @OptIn(ExperimentalTime::class)
    internal fun parseDate(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val zoned = t.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(t.substringAfter('T', ""))
        return runCatching { Instant.parse(if (zoned) t else "${t}Z").toEpochMilliseconds() }.getOrNull()
    }
}

data class SpinState(
    /** The account the game is for; drawn only while it is the signed-in one. */
    val account: String? = null,
    /** RN `isLoading`: true until the first status answered. */
    val loading: Boolean = true,
    /** RN `gameRight`: spins left. */
    val gameRight: Int = 1,
    val nextDate: String? = null,
    /** The last claim's points (the bubble). */
    val score: Int = 0,
    /** The rings turn (8 s from the press). */
    val spinning: Boolean = false,
    /** A claim (with its status check) is on its way. */
    val claiming: Boolean = false,
    /** RN Alert.alert. */
    val alert: String? = null,
) {
    val showCounters: Boolean get() = !spinning && !loading
    val showButton: Boolean get() = showCounters && gameRight > 0
    val showProducts: Boolean get() = showCounters && gameRight <= 0
    val showTags: Boolean get() = showButton
    val showScore: Boolean get() = !spinning && score > 0

    /** Under the countdown (shown with no spin left): the free spin resets at 00:00 UTC. */
    val resetNote: String? get() = if (showProducts) DailyReset.RESETS_DAILY else null
}

/**
 * Status requests of the spin game, numbered app-wide (RN's module counter `statusRequestSeq`): a reopened screen
 * never shares a number with a request the previous screen left in flight, and only the newest request may set
 * the counters or hand its key to a claim.
 */
class SpinStatusRequests internal constructor() {
    private var seq = 0L

    internal fun next(): Long = ++seq

    internal fun isNewest(n: Long): Boolean = n == seq
}

/**
 * RN SpinGameContainer with ab9a588a82, 184d50d2c8 and 764e5a24d9:
 * - the status (`get-game`) always comes from the server, never from a cache, and every request is numbered; only
 *   the newest answer sets the counters, and a superseded failure says nothing;
 * - a claim is only sent by a press, one at a time: a second press while one runs sends nothing;
 * - a press asks for a fresh status first; when a newer status request started meanwhile its key is dropped and
 *   nothing is claimed; status 18 means no spin is left; a status without a key is "Game key missing";
 * - the claim (`post-game`) runs in the app's scope: it outlives the screen, but a claim whose screen closed starts
 *   no status check (it would supersede the request of a reopened screen).
 */
class SpinGameModel internal constructor(private val env: AppEnvironment) : StateModel<SpinState>(SpinState(), env.dispatchers) {
    private var started = false
    private var closed = false
    private var spinTimer: Job? = null

    /** The account the game follows (set at once, so a second session emission for it asks nothing again). */
    private var followed: String? = null

    /** RN `isClaimingRef`. */
    private var claiming = false

    fun start() {
        if (started) return
        started = true
        // the session's own state, not only its events: an account that lands while the screen opens is never missed
        scope.launch {
            env.session.awaitLoaded()
            env.session.state.collect { s ->
                if (s.username != followed) {
                    followed = s.username
                    setState { SpinState(account = s.username) }
                    if (s.username != null) statusCheck()
                }
            }
        }
        // RN `fetchData={statusCheck}`: a delivered 5 spins pack shows its spins
        scope.launch {
            env.purchases.outcomes.collect { if (it is PurchaseOutcome.Delivered && it.productId == PurchaseSkus.SPINS && current.account != null) statusCheck() }
        }
    }

    override fun close() {
        closed = true
        super.close()
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    /** RN `_statusCheck`. */
    private fun statusCheck() {
        scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            if (current.account != user) setState { SpinState(account = user) }
            val n = env.spinStatusRequests.next()
            try {
                val status = env.perksApi.gameStatus(user)
                if (env.spinStatusRequests.isNewest(n) && owns(user)) {
                    setState { it.copy(gameRight = status.remaining, nextDate = status.nextDate) }
                }
                if (owns(user)) setState { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!owns(user)) return@launch
                if (env.spinStatusRequests.isNewest(n)) setState { it.copy(alert = message(e)) }
                setState { it.copy(loading = false) }
            }
        }
    }

    /** RN `_handleOnSpinPress` + `_startGame`: the rings turn for 8 s; one claim at a time. */
    fun spin() {
        val user = current.account ?: return
        if (env.session.current.username != user || !current.showButton) return
        setState { it.copy(spinning = true) }
        spinTimer?.cancel()
        spinTimer = scope.launch {
            delay(SpinCopy.SPIN_MILLIS)
            setState { it.copy(spinning = false) }
        }
        // one claim at a time: a second press while one is in flight sends nothing
        if (claiming) return
        claiming = true
        setState { it.copy(claiming = true) }
        // the status and the claim run in the app's scope, so a closed screen does not cut a claim off
        env.background.launch {
            try {
                startGame(user)
            } finally {
                claiming = false
                if (!closed) setState { it.copy(claiming = false) }
            }
        }
    }

    private suspend fun startGame(user: String) {
        val n = env.spinStatusRequests.next()
        val status: GameStatus = try {
            env.perksApi.gameStatus(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (env.spinStatusRequests.isNewest(n) && live(user)) setState { it.copy(alert = message(e)) }
            return
        }
        // a newer status request started while this one was out, and it may have seen this spin used
        if (!env.spinStatusRequests.isNewest(n)) return
        if (status.noSpinLeft) {
            if (live(user)) setState { it.copy(nextDate = status.nextDate, gameRight = 0) }
            return
        }
        val key = status.key
        if (key == null) {
            if (live(user)) setState { it.copy(alert = SpinCopy.KEY_MISSING) }
            return
        }
        // the account must still be the one that pressed: its code goes with the claim
        if (env.session.current.username != user) return
        val score = try {
            env.perksApi.claimSpin(user, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (live(user)) setState { it.copy(alert = message(e)) }
            return
        }
        // RN asks nothing else after a claim (the quests card hides the spin quest): no quest refresh here
        // a claim can outlive its screen: a status check from a closed screen would supersede a reopened one's
        if (!live(user)) return
        setState { it.copy(gameRight = if (status.status != GameStatus.PACK) 0 else 5, score = score) }
        statusCheck()
    }

    private fun live(user: String) = !closed && owns(user)

    private fun owns(user: String) = current.account == user && env.session.current.username == user

    /** RN `err.message || 'An error occurred'`: the server's own message when it sent one, else the generic text. */
    private fun message(e: Exception): String = when (e) {
        is PerksHttpException -> e.serverMessage ?: SpinCopy.UNKNOWN_ERROR
        is EcencyHttpException -> if (e.status == 0) PerksCopy.TIMED_OUT else SpinCopy.UNKNOWN_ERROR
        else -> SpinCopy.UNKNOWN_ERROR
    }
}
