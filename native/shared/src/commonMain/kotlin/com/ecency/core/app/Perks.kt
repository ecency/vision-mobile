package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.bool
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.l10n.L
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** RN en-US `perks.*` (the empty text without RN's dash). */
object PerksCopy {
    val HEADER: String get() = L.perks.header
    val QUESTS_TITLE: String get() = L.perks.questsTitle
    val QUESTS_SUBTITLE: String get() = L.perks.questsSubtitle
    val QUESTS_EMPTY: String get() = L.native.perks.questsEmpty
    val SPEND_TITLE: String get() = L.perks.spendTitle
    val FREEZE_BUYING: String get() = L.perks.streakFreezeBuying
    val FREEZE_ERROR: String get() = L.perks.streakFreezeError

    /** RN QueryErrorRetry (`alert.load_failed_retry`, `alert.request_timed_out`, `alert.something_wrong_reload`, `alert.retrying`). */
    val LOAD_FAILED: String get() = L.alert.loadFailedRetry
    val TIMED_OUT: String get() = L.alert.requestTimedOut
    val TRY_AGAIN: String get() = L.alert.somethingWrongReload
    val RETRYING: String get() = L.alert.retrying

    /** The confirmation this app adds before the Points are spent (RN buys on the tap). */
    val FREEZE_CONFIRM_TITLE: String get() = L.native.perks.freezeConfirmTitle
    val CANCEL: String get() = L.alert.cancel
    val CONFIRM: String get() = L.alert.confirm

    fun streak(n: Int) = L.native.plurals.perks.streak(n = n)
    fun freezeOwned(n: Int) = L.perks.streakFreezeOwned(n = n.toString())
    fun freezeBuy(price: Int = QuestCatalog.STREAK_FREEZE_PRICE) = L.perks.streakFreezeBuy(price = price.toString())
    fun freezeConfirmBody(price: Int = QuestCatalog.STREAK_FREEZE_PRICE) =
        L.native.perks.spendPointsOnA(price = price.toString())

    fun tier(tier: QuestTier) = when (tier) {
        QuestTier.DAILY -> L.perks.daily
        QuestTier.WEEKLY -> L.perks.weekly
        QuestTier.MONTHLY -> L.perks.monthly
    }

    /** RN `perks.<i18nKey>`. */
    fun questTitle(i18nKey: String) = when (i18nKey) {
        "checkin" -> L.perks.checkin
        "post" -> L.perks.post
        "comment" -> L.perks.comment
        "vote" -> L.perks.vote
        "reblog" -> L.perks.reblog
        "spin" -> L.perks.spin
        else -> i18nKey
    }
}

enum class QuestTier(val id: String) { DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly") }

/** SDK `QuestCatalogEntry`. */
data class QuestCatalogEntry(val id: String, val tier: QuestTier, val goal: Int, val i18nKey: String, val icon: String)

/** SDK `modules/quests/catalog.ts` (2.4.26): which quests exist, their goals, copy keys and icon hints. */
object QuestCatalog {
    val ENTRIES = listOf(
        QuestCatalogEntry("checkin", QuestTier.DAILY, 1, "checkin", "check-circle"),
        QuestCatalogEntry("post", QuestTier.DAILY, 1, "post", "pencil"),
        QuestCatalogEntry("comment", QuestTier.DAILY, 3, "comment", "comment"),
        QuestCatalogEntry("vote", QuestTier.DAILY, 10, "vote", "chevron-up-circle"),
        QuestCatalogEntry("reblog", QuestTier.DAILY, 1, "reblog", "repeat"),
        QuestCatalogEntry("spin", QuestTier.DAILY, 1, "spin", "gift"),
        QuestCatalogEntry("post", QuestTier.WEEKLY, 5, "post", "pencil"),
        QuestCatalogEntry("comment", QuestTier.WEEKLY, 15, "comment", "comment"),
        QuestCatalogEntry("vote", QuestTier.WEEKLY, 50, "vote", "chevron-up-circle"),
        QuestCatalogEntry("reblog", QuestTier.WEEKLY, 5, "reblog", "repeat"),
        QuestCatalogEntry("post", QuestTier.MONTHLY, 20, "post", "pencil"),
    )

    /** SDK STREAK_FREEZE_PRICE / STREAK_FREEZE_MAX_OWNED: labels and button state only, the server prices the buy. */
    const val STREAK_FREEZE_PRICE = 300
    const val STREAK_FREEZE_MAX_OWNED = 2

    /** RN questsCard: the tier's entries without the spin (it has its own screen). */
    fun shown(tier: QuestTier): List<QuestCatalogEntry> = ENTRIES.filter { it.tier == tier && it.id != "spin" }
}

/** One quest row: the catalog entry with this account's progress. */
data class QuestRow(val id: String, val tier: QuestTier, val title: String, val progress: Int, val goal: Int, private val hint: String) {
    val completed: Boolean get() = progress >= goal

    /** RN `goal > 0 ? Math.min(100, Math.round(progress / goal * 100)) : 0` (never below 0 here). */
    val percent: Int get() = if (goal > 0) minOf(100, kotlin.math.floor(progress * 100.0 / goal + 0.5).toInt()).coerceAtLeast(0) else 0

    /** Drawn filled once [completed]. */
    val icon: AppIcon get() = if (completed) AppIcon.CHECK_CIRCLE else AppIcon.quest(hint)

    /** RN `${progress}/${goal}`. */
    val count: String get() = "$progress/$goal"
}

/** SDK `QuestsResponse` as this screen reads it: progress by quest id per tier, and the streak. Untrusted. */
data class QuestsData(
    val progress: Map<QuestTier, Map<String, Int>>,
    val streak: Int,
    val atRisk: Boolean,
    val freezesOwned: Int,
) {
    fun rows(tier: QuestTier): List<QuestRow> = QuestCatalog.shown(tier).map { e ->
        QuestRow(e.id, tier, PerksCopy.questTitle(e.i18nKey), progress[tier]?.get(e.id) ?: 0, e.goal, e.icon)
    }

    companion object {
        /** `/private-api/quests` as text; null when it is not a quests object. */
        fun parse(text: String): QuestsData? {
            val o = SafeJson.parseOrNull(text, 6).obj() ?: return null
            // RN byId: Object.fromEntries, so the last row of an id wins
            fun tier(key: String): Map<String, Int> = o[key].arr().orEmpty().mapNotNull { it.obj() }
                .mapNotNull { q -> q.str("id")?.takeIf { it.length <= 32 }?.let { it to (q.int("progress") ?: 0).coerceAtLeast(0) } }
                .toMap()
            val streak = o["streak"].obj()
            val current = (streak?.int("current") ?: 0).coerceAtLeast(0)
            return QuestsData(
                mapOf(QuestTier.DAILY to tier("daily"), QuestTier.WEEKLY to tier("weekly"), QuestTier.MONTHLY to tier("monthly")),
                current, current > 0 && streak?.bool("at_risk") == true, (streak?.int("freezes_owned") ?: 0).coerceAtLeast(0),
            )
        }
    }
}

/** A way to spend points (RN spendOptions). */
data class SpendOption(val id: String, val title: String, val description: String, val icon: AppIcon, val screen: AppScreen)

/** RN spendOptions `OPTIONS`, in RN's order. */
object SpendOptions {
    val ALL: List<SpendOption> get() = listOf(
        SpendOption("spin", L.perks.spin, L.perks.spinDesc, AppIcon.GIFT, AppScreen.SpinGame),
        SpendOption("boost_plus", L.perks.boostPlus, L.perks.boostPlusDesc, AppIcon.BOOST, AppScreen.Redeem("boost_plus")),
        SpendOption("rc_topup", L.perks.rcTopup, L.perks.rcTopupDesc, AppIcon.BOLT, AppScreen.Redeem("rc_topup")),
        SpendOption("promote", L.perks.promote, L.perks.promoteDesc, AppIcon.MEGAPHONE, AppScreen.Redeem("promote")),
        SpendOption("account_boost", L.perks.accountBoost, L.perks.accountBoostDesc, AppIcon.ROCKET, AppScreen.of(RouteName.ACCOUNT_BOOST)),
    )
}

data class PerksState(
    /** The account the card is for; drawn only while it is the signed-in one. */
    val account: String? = null,
    val quests: QuestsData? = null,
    val loading: Boolean = false,
    /** RN `isError && !data`: the card says so instead of showing every quest at 0. */
    val failed: Boolean = false,
    val timedOut: Boolean = false,
    val retrying: Boolean = false,
    val tier: QuestTier = QuestTier.DAILY,
    /** The confirmation of a streak freeze buy is open. */
    val confirmingFreeze: Boolean = false,
    val buyingFreeze: Boolean = false,
    /** RN Alert.alert. */
    val alert: String? = null,
    /** A one-shot request to open Get Points for this account (RN: 402 on the freeze buy). */
    val getPointsFor: String? = null,
) {
    val rows: List<QuestRow> get() = quests?.rows(tier).orEmpty()
    val streak: Int get() = quests?.streak ?: 0
    val showStreak: Boolean get() = streak > 0
    val freezesOwned: Int get() = quests?.freezesOwned ?: 0
    val showFreezeOwned: Boolean get() = showStreak && freezesOwned > 0
    val showFreezeBuy: Boolean get() = showStreak && freezesOwned < QuestCatalog.STREAK_FREEZE_MAX_OWNED
    val freezeLabel: String get() = if (buyingFreeze) PerksCopy.FREEZE_BUYING else PerksCopy.freezeBuy()
    val errorText: String get() = if (timedOut) PerksCopy.TIMED_OUT else PerksCopy.LOAD_FAILED

    /** The daily tier's reset line (00:00 UTC); null on the weekly and monthly tiers. */
    fun resetLine(nowMillis: Long): String? = if (tier == QuestTier.DAILY) DailyReset.todayLine(nowMillis) else null
}

/** How a streak freeze buy ended. */
sealed interface FreezeOutcome {
    data class Delivered(val owned: Int?) : FreezeOutcome

    /** The server answered with a refusal: nothing was charged. */
    data class Refused(val status: Int) : FreezeOutcome

    /** No usable answer: the Points may have been spent. The key is kept, so the next try is a replay. */
    data object Lost : FreezeOutcome
}

/**
 * Streak freeze buys, app-wide per account (like [ListenUnlocks]): one idempotency key per confirmed buy, made at the
 * confirmation and kept while the outcome is unknown, so the next confirmation after a lost answer replays the same
 * key (the server charges a key once) instead of buying twice. A buy runs in the app's scope: closing the screen
 * does not cut it off, and a screen that opens meanwhile joins it. A key goes only with a definite outcome: not with
 * a logout (the account may come back and buy again; the kept key makes that a replay), only with the process.
 */
class StreakFreezeBuys internal constructor(private val env: AppEnvironment) {
    private val keys = mutableMapOf<String, String>()
    private val running = mutableMapOf<String, Deferred<FreezeOutcome>>()

    fun key(account: String): String? = keys[account]

    fun running(account: String): Deferred<FreezeOutcome>? = running[account]

    /** Main thread. Joins the buy already running for [account], else starts one with the kept or a new key. */
    internal fun start(account: String): Deferred<FreezeOutcome> {
        running[account]?.let { return it }
        val key = keys.getOrPut(account) { env.idempotencyKey() }
        val job = env.background.async(start = CoroutineStart.LAZY) {
            val outcome = try {
                FreezeOutcome.Delivered(env.perksApi.buyStreakFreeze(account, key).owned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EcencyHttpException) {
                // 0 is a timeout, a 5xx or 502 says nothing about the charge
                if (e.status in 400..499) FreezeOutcome.Refused(e.status) else FreezeOutcome.Lost
            } catch (e: Exception) {
                FreezeOutcome.Lost
            }
            when (outcome) {
                is FreezeOutcome.Delivered, is FreezeOutcome.Refused -> keys.remove(account)
                FreezeOutcome.Lost -> Unit
            }
            running.remove(account)
            outcome
        }
        running[account] = job
        job.start()
        return job
    }

}

/**
 * RN scheduleQuestsRefresh: a points activity is credited a little over a minute after it happened, so the quest
 * numbers (Perks card, Waves checklist) are asked again [DELAY_MILLIS] after the last activity of an account. One
 * pending refresh per account: a second account never cancels the first one's.
 */
class QuestRefreshes internal constructor(private val env: AppEnvironment) {
    private val timers = mutableMapOf<String, Job>()
    private val mutable = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The accounts whose quest numbers are due again. */
    val due: SharedFlow<String> = mutable

    /** Main thread. */
    fun schedule(username: String?) {
        val name = username?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return
        timers.remove(name)?.cancel()
        timers[name] = env.background.launch {
            delay(DELAY_MILLIS)
            timers.remove(name)
            mutable.emit(name)
        }
    }

    companion object {
        /** RN QUESTS_REFRESH_DELAY. */
        const val DELAY_MILLIS = 70_000L
    }
}

/**
 * RN PerksScreen: the quests card (daily, weekly and monthly progress, the streak and its freezes) and the ways to
 * spend points. The quests are asked when the screen opens, again when the app comes back to the foreground (RN
 * opts this query into refetch on focus) and a little after an activity of the account was recorded. The card
 * belongs to the signed-in account: an account change empties it and loads the new account's.
 */
class PerksModel internal constructor(private val env: AppEnvironment) : StateModel<PerksState>(PerksState(), env.dispatchers) {
    private var job: Job? = null
    private var started = false

    /** The account the card follows (set at once, so a second session emission for it loads nothing again). */
    private var followed: String? = null

    /** A refresh asked for while one was on its way (after a freeze buy): it runs once that one is over. */
    private var refreshAgain = false

    fun start() {
        if (started) return
        started = true
        // the session's own state, not only its events: an account that lands while the screen opens is never missed
        scope.launch {
            env.session.awaitLoaded()
            env.session.state.collect { s ->
                if (s.username != followed) {
                    followed = s.username
                    load()
                }
            }
        }
        scope.launch { env.questRefreshes.due.collect { if (it == current.account) refresh() } }
    }

    /** The app came back to the foreground. */
    fun onForeground() = refresh()

    fun retry() {
        if (current.account == null) return load()
        setState { it.copy(retrying = true) }
        fetch(current.account ?: return)
    }

    fun selectTier(tier: QuestTier) = setState { it.copy(tier = tier) }

    fun dismissAlert() = setState { it.copy(alert = null) }

    fun getPointsOpened() = setState { it.copy(getPointsFor = null) }

    /** "Protect streak": asks first. Nothing is sent from here. */
    fun askFreeze() {
        val s = current
        if (s.account == null || s.buyingFreeze || !s.showFreezeBuy || env.session.current.username != s.account) return
        setState { it.copy(confirmingFreeze = true) }
    }

    fun cancelFreeze() = setState { it.copy(confirmingFreeze = false) }

    /** The confirmation's "Confirm": one buy (or the replay of the last unanswered one) for the signed-in account. */
    fun confirmFreeze() {
        val user = current.account
        if (!current.confirmingFreeze) return
        setState { it.copy(confirmingFreeze = false) }
        if (user == null || current.buyingFreeze || env.session.current.username != user) return
        follow(user, env.streakFreezeBuys.start(user))
    }

    private fun follow(user: String, buy: Deferred<FreezeOutcome>) {
        setState { it.copy(buyingFreeze = true) }
        scope.launch {
            val outcome = buy.await()
            if (current.account != user) return@launch
            setState { it.copy(buyingFreeze = false) }
            when (outcome) {
                is FreezeOutcome.Delivered -> Unit
                // not enough Points: the Points top-up, for this account (RN passes the username)
                is FreezeOutcome.Refused -> when (outcome.status) {
                    402 -> setState { it.copy(getPointsFor = user) }
                    409 -> Unit // already at the maximum: the count is asked again below and the button goes
                    else -> setState { it.copy(alert = PerksCopy.FREEZE_ERROR) }
                }
                FreezeOutcome.Lost -> setState { it.copy(alert = PerksCopy.FREEZE_ERROR) }
            }
            // SDK onSettled: the owned count again, after a success and after an error alike, also when a refresh
            // started before the buy's outcome is still on its way (its answer may predate the buy)
            if (job?.isActive == true) refreshAgain = true else refresh()
        }
    }

    private fun refresh() {
        val user = current.account ?: return
        if (job?.isActive == true) return
        fetch(user)
    }

    private fun load() {
        job?.cancel()
        refreshAgain = false
        setState { PerksState(tier = it.tier) }
        job = scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            setState { it.copy(account = user, loading = true) }
            // a buy of this account still running (another screen started it) shows as busy, and its end refreshes
            env.streakFreezeBuys.running(user)?.let { follow(user, it) }
            fetchNow(user)
        }
    }

    private fun fetch(user: String) {
        job?.cancel()
        job = scope.launch { fetchNow(user) }
    }

    private suspend fun fetchNow(user: String) {
        try {
            val data = QuestsData.parse(env.perksApi.quests(user)) ?: throw EcencyHttpException(200, "malformed quests")
            if (!owns(user)) return
            setState { it.copy(quests = data, loading = false, retrying = false, failed = false, timedOut = false) }
            // the streak widget (README "Widgets")
            env.widgets.streakRead(user, data.streak)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!owns(user)) return
            // RN shows the error only while there is no data; earlier numbers stay on a failed refetch
            setState { it.copy(loading = false, retrying = false, failed = it.quests == null, timedOut = (e as? EcencyHttpException)?.status == 0) }
        }
        if (refreshAgain && owns(user)) {
            refreshAgain = false
            job = scope.launch { fetchNow(user) }
        }
    }

    private fun owns(user: String) = current.account == user && env.session.current.username == user
}
