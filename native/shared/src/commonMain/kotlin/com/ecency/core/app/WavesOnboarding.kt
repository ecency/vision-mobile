package com.ecency.core.app

import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** RN en-US `waves_onboarding.*`. */
object WavesOnboardingCopy {
    val TITLE: String get() = L.wavesOnboarding.title
    val SUBTITLE: String get() = L.wavesOnboarding.subtitle
    val DISMISS: String get() = L.wavesOnboarding.dismiss
    val ALL_DONE_TITLE: String get() = L.wavesOnboarding.allDoneTitle
    val ALL_DONE_SUBTITLE: String get() = L.wavesOnboarding.allDoneSubtitle

    fun label(item: WavesOnboardingItemId): String = when (item) {
        WavesOnboardingItemId.WAVE -> L.wavesOnboarding.itemWave
        WavesOnboardingItemId.VOTE -> L.wavesOnboarding.itemVote
        WavesOnboardingItemId.REPLY -> L.wavesOnboarding.itemReply
        WavesOnboardingItemId.CHECKIN -> L.wavesOnboarding.itemCheckin
    }
}

enum class WavesOnboardingItemId(val id: String) {
    WAVE("wave"), VOTE("vote"), REPLY("reply"), CHECKIN("checkin");

    companion object {
        fun of(id: String?): WavesOnboardingItemId? = entries.firstOrNull { it.id == id }
    }
}

data class WavesOnboardingItem(val id: WavesOnboardingItemId, val completed: Boolean) {
    val label: String get() = WavesOnboardingCopy.label(id)

    /** RN: an open "wave" row opens the composer, an open "checkin" row the Perks screen. */
    val actionable: Boolean get() = !completed && (id == WavesOnboardingItemId.WAVE || id == WavesOnboardingItemId.CHECKIN)
}

/** What the quests endpoint says that the checklist reads: today's progress by quest id, and the streak. */
data class QuestProgress(val daily: Map<String, Int>, val streak: Int) {
    companion object {
        /** `/private-api/quests` as text; null when it is not a quests object. */
        fun parse(text: String): QuestProgress? {
            val o = SafeJson.parseOrNull(text).obj() ?: return null
            val daily = o["daily"].arr().orEmpty().mapNotNull { it.obj() }
                .mapNotNull { q -> q.str("id")?.let { it to (q.int("progress") ?: 0) } }
                // RN `find`: the first quest of an id counts
                .asReversed().toMap()
            return QuestProgress(daily, o["streak"].obj()?.int("current") ?: 0)
        }
    }
}

/** RN `utils/wavesOnboarding.ts`, ported and pinned to its own output (`WavesGapsGoldens`). */
@OptIn(ExperimentalTime::class)
object WavesOnboarding {
    const val NEW_ACCOUNT_WINDOW_DAYS = 30
    const val NEW_ACCOUNT_MAX_POSTS = 10
    private const val DAY_MS = 24L * 60 * 60 * 1000

    data class Derived(val eligible: Boolean, val items: List<WavesOnboardingItem>) {
        val completedCount: Int get() = items.count { it.completed }
        val totalCount: Int get() = items.size
        val allComplete: Boolean get() = completedCount == totalCount
    }

    /** RN isNewAccount: created within 30 days (Hive times are UTC without a marker), or fewer than 10 posts. */
    fun isNewAccount(created: String, postCount: Int, nowMillis: Long): Boolean {
        val createdAt = parseDate(if ('Z' !in created) "${created}Z" else created)
        val withinWindow = createdAt != null && nowMillis - createdAt <= NEW_ACCOUNT_WINDOW_DAYS * DAY_MS
        return withinWindow || postCount < NEW_ACCOUNT_MAX_POSTS
    }

    /** `Date.parse` for the instants Hive sends; null for what JS reads as NaN. */
    private fun parseDate(text: String): Long? = runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()

    /** RN deriveWavesOnboardingState; the caller passes what was latched earlier, since daily progress resets at 00:00 UTC. */
    fun derive(quests: QuestProgress, created: String, postCount: Int, persisted: Set<WavesOnboardingItemId>, nowMillis: Long): Derived {
        fun item(id: WavesOnboardingItemId, live: Boolean) = WavesOnboardingItem(id, live || id in persisted)
        fun progress(id: String) = quests.daily[id] ?: 0
        return Derived(
            eligible = isNewAccount(created, postCount, nowMillis),
            items = listOf(
                // a wave is a reply on chain: it has no quest of its own and completes only when one was published here
                item(WavesOnboardingItemId.WAVE, false),
                item(WavesOnboardingItemId.VOTE, progress("vote") > 0),
                item(WavesOnboardingItemId.REPLY, progress("comment") > 0),
                item(WavesOnboardingItemId.CHECKIN, progress("checkin") > 0 || quests.streak >= 1),
            ),
        )
    }
}

data class WavesOnboardingState(
    /** The account the card is for; the UI draws it only while this is the signed-in account. */
    val account: String? = null,
    /** RN renders nothing unless all of: eligible, not dismissed, and not finished (except the one celebration). */
    val visible: Boolean = false,
    val items: List<WavesOnboardingItem> = emptyList(),
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    /** Everything is done and this is the one time the card says so. */
    val celebrating: Boolean = false,
) {
    /** RN `waves_onboarding.progress`. */
    val progressText: String get() = L.wavesOnboarding.progress(completed = completedCount.toString(), total = totalCount.toString())

    /** RN `Math.round(completed / total * 100)`. */
    val percent: Int get() = if (totalCount == 0) 0 else kotlin.math.floor(completedCount * 100.0 / totalCount + 0.5).toInt()
}

/**
 * RN WavesOnboardingChecklist: the dismissible "Getting started" card for new accounts on the Waves tab.
 * Completion comes from the quests endpoint (the editor area's client, [QuestChip.loadRaw]) and the account's
 * age and post count; what was completed is latched per account in the device preferences, because daily quest
 * progress resets at 00:00 UTC and an item must never un-check itself. "Post your first Wave" latches when a
 * wave is published from this app ([AppEvent.WavePosted]). Once everything is done the card celebrates once
 * and then stays away; Dismiss hides it for good.
 *
 * The card belongs to one account: an account change drops it before anything of the next account is read, a
 * late answer for the previous account is ignored, and a latch is only ever written for the account it was
 * earned by.
 */
class WavesOnboardingModel internal constructor(private val env: AppEnvironment) :
    StateModel<WavesOnboardingState>(WavesOnboardingState(), env.dispatchers) {

    private class Persisted(val dismissed: Boolean = false, val celebrated: Boolean = false, val done: Set<WavesOnboardingItemId> = emptySet())

    private var job: Job? = null
    private var started = false
    private var quests: QuestProgress? = null
    private var created: String? = null
    private var postCount = 0
    private var celebrating = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.events.collect { event ->
                when (event) {
                    is AppEvent.SessionChanged -> if (event.username != current.account) load()
                    AppEvent.DataWiped -> load()
                    // RN WAVES_ONBOARDING_LATCH_EVENT: the submit path is the only "posted a wave" signal
                    is AppEvent.WavePosted -> if (event.key.author == current.account) latch(WavesOnboardingItemId.WAVE)
                    else -> {}
                }
            }
        }
        // a points activity of the account was credited meanwhile (RN scheduleQuestsRefresh)
        scope.launch { env.questRefreshes.due.collect { if (it == current.account) refresh() } }
        load()
    }

    /** RN refetches the quests when the app comes back and a little after a check-in was recorded. */
    fun refresh() {
        if (job?.isActive == true) return
        val user = current.account ?: return load()
        job = scope.launch { fetch(user, quiet = true) }
    }


    /** RN `_onDismiss`: gone for good for this account. */
    fun dismiss() {
        val user = current.account ?: return
        if (env.session.current.username != user) return
        write(user) { Persisted(dismissed = true, celebrated = it.celebrated, done = it.done) }
        publish(user)
    }

    private fun load() {
        job?.cancel()
        quests = null
        created = null
        celebrating = false
        setState { WavesOnboardingState() }
        job = scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            setState { WavesOnboardingState(account = user) }
            fetch(user, quiet = false)
        }
    }

    private suspend fun fetch(user: String, quiet: Boolean) {
        try {
            val raw = QuestChip.loadRaw(env, user)
            val account = if (created == null || !quiet) env.api.accounts.getAccount(user) else null
            if (!owns(user)) return
            raw?.let(QuestProgress::parse)?.let { quests = it }
            account?.let {
                created = it.created
                postCount = it.postCount
            }
            publish(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // RN renders nothing until both inputs arrived
        }
    }

    private fun latch(item: WavesOnboardingItemId) {
        val user = current.account ?: return
        if (env.session.current.username != user) return
        write(user) { Persisted(it.dismissed, it.celebrated, it.done + item) }
        publish(user)
    }

    /** RN's merged effect: latch what is newly complete, celebrate once, then derive what shows. */
    private fun publish(user: String) {
        if (!owns(user)) return
        val q = quests
        val c = created
        if (q == null || c == null) {
            setState { WavesOnboardingState(account = user) }
            return
        }
        var persisted = read(user)
        val derived = WavesOnboarding.derive(q, c, postCount, persisted.done, env.nowMillis())
        val done = derived.items.filter { it.completed }.map { it.id }.toSet()
        val celebrationNeeded = derived.allComplete && !persisted.celebrated
        if (celebrationNeeded) celebrating = true
        if (!persisted.done.containsAll(done) || celebrationNeeded) {
            write(user) { Persisted(it.dismissed, it.celebrated || celebrationNeeded, it.done + done) }
            persisted = read(user)
        }
        val visible = derived.eligible && !persisted.dismissed && !(derived.allComplete && !celebrating)
        setState {
            WavesOnboardingState(
                account = user, visible = visible, items = derived.items, completedCount = derived.completedCount,
                totalCount = derived.totalCount, celebrating = derived.allComplete && celebrating,
            )
        }
    }

    private fun owns(user: String): Boolean = env.session.current.username == user && current.account == user

    private fun read(user: String): Persisted {
        val o = env.prefs.getString(key(user))?.let { SafeJson.parseOrNull(it, 4) } as? JsonObject ?: return Persisted()
        return Persisted(
            dismissed = (o["dismissed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            celebrated = (o["celebrated"] as? JsonPrimitive)?.booleanOrNull ?: false,
            done = (o["done"] as? JsonArray).orEmpty().mapNotNull { WavesOnboardingItemId.of((it as? JsonPrimitive)?.content) }.toSet(),
        )
    }

    /** Read, change, write in one step on the main thread: a write never starts from an older copy. */
    private fun write(user: String, change: (Persisted) -> Persisted) {
        val next = change(read(user))
        env.prefs.putString(
            key(user),
            buildJsonObject {
                put("dismissed", next.dismissed)
                put("celebrated", next.celebrated)
                put("done", JsonArray(WavesOnboardingItemId.entries.filter { it in next.done }.map { JsonPrimitive(it.id) }))
            }.toString(),
        )
    }

    companion object {
        /** RN QUESTS_REFRESH_DELAY: an activity is credited a little over a minute after it happened. */
        const val QUESTS_REFRESH_DELAY_MILLIS = QuestRefreshes.DELAY_MILLIS

        /** RN storage key `waves_onboarding_<username>`. */
        fun key(username: String): String = "$KEY_PREFIX$username"

        const val KEY_PREFIX = "waves.onboarding."
    }
}

/**
 * RN useCheckIn: the daily check-in (point activity 10) recorded when the reader browses waves, at most once per
 * 15 minutes and account (the backend drops anything closer). `POST /private-api/usr-activity {ty: 10, code}`
 * with the account's code. The window is claimed before the request so two calls never both send, and given
 * back when the request fails so the next visit tries again. Nothing is sent while signed out or locked.
 */
class CheckInRecorder internal constructor(private val env: AppEnvironment) {
    private val lastAt = mutableMapOf<String, Long>()

    /** Main thread. Returns whether a request was started. */
    fun record(): Boolean {
        val user = env.session.current.username ?: return false
        if (env.appLock.current.hidesContent) return false
        val now = env.nowMillis()
        val last = lastAt[user]
        if (last != null && now - last in 0 until THROTTLE_MILLIS) return false
        lastAt[user] = now
        // tried three times and parked when it still fails (RN useUserActivityMutation); the quest numbers follow
        // about a minute after it went through, for the account that checked in ([PointActivities])
        env.pointActivities.record(user, CHECKIN_ACTIVITY) { ok ->
            if (!ok && lastAt[user] == now) lastAt.remove(user)
        }
        return true
    }

    companion object {
        /** RN PointActivityIds.CHECKIN. */
        const val CHECKIN_ACTIVITY = PointActivity.CHECKIN

        /** RN CHECKIN_THROTTLE_MS. */
        const val THROTTLE_MILLIS = 15L * 60 * 1000
    }
}
