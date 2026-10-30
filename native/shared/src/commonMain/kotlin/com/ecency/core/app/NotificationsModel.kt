package com.ecency.core.app

import com.ecency.core.api.broadcast.NotificationOps
import com.ecency.core.api.ecency.LeaderboardEntry
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class NotificationsState(
    /** The account every row and the count belong to. UIs draw them only while it is the signed-in account. */
    val account: String? = null,
    val filter: NotificationFilter = NotificationFilter.ACTIVITIES,
    val items: List<NotificationRow> = emptyList(),
    /** The first page is loading with nothing to show (RN's placeholder). */
    val isLoading: Boolean = false,
    /** A reload over rows already shown (pull to refresh, new activity). */
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loaded: Boolean = false,
    /** Set only when there is nothing to show: a failed "load more" keeps the rows. */
    val error: AppError? = null,
    val endReached: Boolean = false,
    val pages: Int = 0,
    val unread: Int = 0,
    /** RN "Show Images" (`hidePostsThumbnails` inverted): off hides the post thumbnails of the rows. */
    val showImages: Boolean = true,
) {
    /** RN "No recent activity". */
    val isEmpty: Boolean get() = loaded && items.isEmpty() && error == null && !isLoading
}

/**
 * RN "Show Images" (`hidePostsThumbnails`), read in one place: notification rows, post, comment and wave bodies,
 * similar entries and the image viewer. Off: an image is a placeholder and its
 * bytes are not requested until it is tapped; the viewer pages through the revealed ones only.
 */
object ContentImages {
    fun shown(prefs: Prefs): Boolean = prefs.getString(NotificationsModel.KEY_SHOW_IMAGES) != "0"

    /** What the image viewer may page through: every image with the setting on, else the ones tapped open. */
    fun viewable(all: List<String>, showImages: Boolean, revealed: (String) -> Boolean): List<String> =
        if (showImages) all else all.filter(revealed)
}

/**
 * The signed-in account's notifications and unread count (RN notificationContainer, useNotificationsQuery,
 * useNotificationReadMutation and the unread count refreshes of applicationContainer). One per app.
 *
 * Nothing is requested while signed out or while the app lock hides content. Every request belongs to the
 * account it was started for: an account change drops the rows, the count and the badge at once and the answer
 * of a request still in flight is ignored.
 */
class NotificationsModel internal constructor(private val env: AppEnvironment) :
    StateModel<NotificationsState>(NotificationsState(), env.dispatchers) {

    private var listJob: Job? = null
    private var unreadJob: Job? = null

    /** Bumped on every account change; results carrying an older value are dropped. */
    private var generation = 0
    private var started = false

    /** Signed in and not hidden by the lock: requests may run. */
    private var active = false

    /** The notifications tab was shown: the list loads as soon as it may. */
    private var wanted = false
    private var hivePerMVests = 0.0

    /** Row counts of the pages in memory, oldest-loaded first (the window RN's `maxPages` keeps). */
    private val pageSizes = ArrayDeque<Int>()

    /** Every id loaded since the last reload, rows that left the window included: a server that starts over cannot page forever. */
    private val seenIds = mutableSetOf<String>()

    /** Call once at app start (with [SessionModel.start] and [AppLockModel.start]); safe to call again. */
    fun start() {
        if (started) return
        started = true
        setState { it.copy(showImages = ContentImages.shown(env.prefs)) }
        scope.launch {
            combine(env.session.state, env.appLock.state) { s, l -> Triple(s.isLoading, s.username, l.hidesContent) }
                .distinctUntilChanged()
                .collect { sync() }
        }
    }

    /**
     * Brings the model in line with the session and the lock as they are right now: another account drops
     * everything, and becoming active (signed in, not hidden) reads the count and loads a list that was asked for.
     * Every entry point runs it first, so none depends on the order in which collectors were told about a change.
     */
    private fun sync() {
        if (!started) return
        val session = env.session.current
        if (session.isLoading) return
        val user = session.username
        if (user != current.account) reset(user)
        val nowActive = user != null && !env.appLock.current.hidesContent
        val becameActive = nowActive && !active
        active = nowActive
        if (becameActive) {
            refreshUnread()
            if (wanted && !current.loaded) load(reset = true)
        }
    }

    /**
     * The account is about to change ([AppEnvironment.forgetSessionState], before the session shows the new one):
     * rows, count and badge go now, so no frame ever shows them next to another account.
     */
    internal fun forgetAccount() = reset(current.account)

    private fun reset(user: String?) {
        generation++
        active = false
        listJob?.cancel()
        unreadJob?.cancel()
        listJob = null
        unreadJob = null
        pageSizes.clear()
        seenIds.clear()
        setState { NotificationsState(account = user, filter = it.filter, showImages = it.showImages) }
        env.badges.setNotifications(0)
    }

    // ---- settings ----

    /** The preference changed outside [setShowImages] (the RN settings import). Main thread. */
    internal fun reloadShowImages() = setState { it.copy(showImages = ContentImages.shown(env.prefs)) }

    /** RN settings "Show Images": kept on the device; off hides the rows' post thumbnails. */
    fun setShowImages(show: Boolean) {
        env.prefs.putString(KEY_SHOW_IMAGES, if (show) null else "0")
        setState { it.copy(showImages = show) }
    }

    // ---- list ----

    /** The notifications tab is on screen: loads the first page unless it is there already. */
    fun show() {
        wanted = true
        sync()
        if (active && !current.loaded && listJob?.isActive != true) load(reset = true)
    }

    fun selectFilter(filter: NotificationFilter) {
        sync()
        if (filter == current.filter) return
        listJob?.cancel()
        pageSizes.clear()
        seenIds.clear()
        setState { NotificationsState(account = it.account, filter = filter, unread = it.unread, showImages = it.showImages) }
        if (active) load(reset = true)
    }

    /**
     * A quick chip next to the filter menu (Replies, Mentions): the same as choosing that filter in the menu, and
     * a tap on the chip already selected goes back to every activity.
     */
    fun toggleQuickFilter(filter: NotificationFilter) =
        selectFilter(if (filter == current.filter) NotificationFilter.ACTIVITIES else filter)

    /** Pull to refresh: the first page again, and the badge with it (RN `_refreshUnreadCount`). */
    fun refresh() {
        sync()
        if (!active) return
        load(reset = true)
        refreshUnread()
    }

    /** [refresh], then suspends until the list settled (the pull-to-refresh indicator). */
    @Throws(CancellationException::class)
    suspend fun refreshAndWait() {
        refresh()
        state.first { !it.isLoading && !it.isRefreshing }
    }

    fun retry() = refresh()

    fun loadMore() {
        sync()
        val s = current
        if (!active || listJob?.isActive == true || !s.loaded || s.endReached || s.items.isEmpty()) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        val user = current.account ?: return
        val gen = generation
        val filter = current.filter
        val since = if (reset) "" else current.items.lastOrNull()?.id ?: return
        listJob?.cancel()
        setState { it.copy(isLoading = reset && it.items.isEmpty(), isRefreshing = reset && it.items.isNotEmpty(), isLoadingMore = !reset, error = null) }
        listJob = scope.launch {
            try {
                val page = env.notificationsApi.notifications(user, filter.apiValue, since)
                val rate = if (page.any { it.type == "delegations" && "VESTS" in it.amount }) hivePerMVests() else 0.0
                if (gen != generation || filter != current.filter) return@launch
                val rows = page.map { NotificationFormat.row(it, rate, env.proxyBase) }
                // computed once, outside the state update (it changes the window's bookkeeping)
                if (reset) {
                    pageSizes.clear()
                    seenIds.clear()
                }
                val fresh = rows.distinctBy { it.id }.filter { seenIds.add(it.id) }
                if (fresh.isNotEmpty()) pageSizes.addLast(fresh.size)
                var dropCount = 0
                // RN `maxPages`: older pages keep coming, the page loaded first leaves memory
                while (pageSizes.size > WINDOW_PAGES) dropCount += pageSizes.removeFirst()
                setState { s ->
                    s.copy(
                        items = if (reset) fresh else (s.items + fresh).drop(dropCount),
                        isLoading = false, isRefreshing = false, isLoadingMore = false, loaded = true, error = null,
                        // only an empty page, or one of rows already seen, is the end (RN asks for the first page again there)
                        endReached = fresh.isEmpty(),
                        pages = if (reset) 1 else s.pages + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation || filter != current.filter) return@launch
                setState { s ->
                    s.copy(
                        isLoading = false, isRefreshing = false, isLoadingMore = false, loaded = true,
                        error = if (s.items.isEmpty()) AppError.from(e) else null,
                    )
                }
            }
        }
    }

    /** RN `globalProps.hivePerMVests`, for delegation amounts in HP. Read once; 0 while it could not be read. */
    private suspend fun hivePerMVests(): Double {
        if (hivePerMVests > 0) return hivePerMVests
        return try {
            val props = env.api.client.call("condenser_api.get_dynamic_global_properties").obj()
            val fund = NotificationFormat.leadingNumber(props?.str("total_vesting_fund_hive").orEmpty())
            val shares = NotificationFormat.leadingNumber(props?.str("total_vesting_shares").orEmpty())
            (if (shares > 0) fund / shares * 1e6 else 0.0).also { hivePerMVests = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0.0
        }
    }

    // ---- unread count ----

    /**
     * Asks the server for the unread count (RN `fetchUnreadActivityCount` with `force`): after the account was
     * read, on foreground, after a push or websocket event, on pull to refresh. Overlapping calls share one
     * request; a failure keeps the last known count. When the count grew, the list reloads.
     */
    fun refreshUnread() {
        sync()
        if (!active || unreadJob?.isActive == true) return
        val user = current.account ?: return
        val gen = generation
        unreadJob = scope.launch {
            val count = try {
                env.notificationsApi.unreadCount(user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (gen != generation) return@launch
            val before = current.unread
            setUnread(count)
            val s = current
            if (count > before && s.loaded && !s.isLoading && !s.isRefreshing && !s.isLoadingMore) load(reset = true)
        }
    }

    /** Back in front (RN `_handleAppStateChange`). */
    fun onForeground() = refreshUnread()

    private fun setUnread(count: Int) {
        setState { it.copy(unread = count.coerceAtLeast(0)) }
        env.badges.setNotifications(count)
    }

    // ---- mark as read ----

    /**
     * The swipe on a row (and its accessibility action): marks one unread row read without opening it (SDK
     * useMarkNotificationsRead with an id). False when the row is not there or already read.
     */
    fun markRead(id: String): Boolean {
        sync()
        val row = current.items.firstOrNull { it.id == id } ?: return false
        return !row.read && mark(row.id)
    }

    /** A row was tapped: marks it read when it was not, and returns where the tap goes (null: nowhere). */
    fun open(id: String): NavEntry? {
        sync()
        val row = current.items.firstOrNull { it.id == id } ?: return null
        if (!row.read) mark(row.id)
        return row.target
    }

    /** RN `_readAllNotification`: nothing while offline. Also writes `setLastRead` on chain, as RN does. */
    fun markAllRead() {
        if (!env.connectivity.current.online) return
        if (!mark(null)) return
        val user = current.account ?: return
        scope.launch {
            try {
                env.signing.broadcast(user, NotificationOps.setLastRead(user, env.nowMillis()))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the Ecency side is marked; the chain marker is retried by the next "mark all"
            }
        }
    }

    /**
     * A tapped push carried [id] (id-shaped, and addressed to the signed-in account: [PushModel] checked both).
     * The server only marks the caller's own unread rows, so the id goes straight to `notifications/mark`,
     * however far back the notification is; a row already known to be read is skipped.
     */
    internal fun markFromPush(id: String) {
        sync()
        if (!active || id.isEmpty()) return
        if (current.items.any { it.id == id && it.read }) return
        mark(id)
    }

    /**
     * SDK useMarkNotificationsRead: rows and the count change at once, the server's count replaces the guess, a
     * failure puts both back and shows RN's "Fail!". False when nobody is signed in.
     */
    private fun mark(id: String?): Boolean {
        sync()
        val user = current.account
        if (user == null || !active) {
            env.ui.toast(L.alert.fail)
            return false
        }
        val gen = generation
        val before = current
        val wasUnread = before.items.filter { !it.read && (id == null || it.id == id) }.map { it.id }.toSet()
        val guess = when {
            id == null -> 0
            wasUnread.isNotEmpty() && before.unread > 0 -> before.unread - 1
            else -> before.unread
        }
        setState { s -> s.copy(items = s.items.map { if (it.id in wasUnread) it.copy(read = true) else it }) }
        setUnread(guess)
        scope.launch {
            try {
                val result = env.notificationsApi.markRead(user, id)
                if (gen != generation) return@launch
                result.unread?.let { setUnread(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                setState { s -> s.copy(items = s.items.map { if (it.id in wasUnread) it.copy(read = false) else it }) }
                setUnread(before.unread)
                env.ui.toast(L.alert.fail)
                refreshUnread()
            }
        }
        return true
    }

    companion object {
        /** RN `maxPages`: the last 10 pages (of the API's fixed page size) stay in memory. */
        const val WINDOW_PAGES = 10

        internal const val KEY_SHOW_IMAGES = "content.showImages"
    }
}

/** RN leaderboard durations (constants/options/leaderboard.ts). */
enum class LeaderboardDuration(val apiValue: String, private val labelText: () -> String) {
    DAY("day", { L.leaderboard.daily }),
    WEEK("week", { L.leaderboard.weekly }),
    MONTH("month", { L.leaderboard.monthly }),
    ;

    val label: String get() = labelText()
}

data class LeaderboardState(
    val duration: LeaderboardDuration = LeaderboardDuration.DAY,
    val users: List<LeaderboardEntry> = emptyList(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
) {
    /** The daily board shows a check for users who finished their quests instead of the rank. */
    val showsQuestCheck: Boolean get() = duration == LeaderboardDuration.DAY

    /** The daily board's reset line (00:00 UTC); null on the weekly and monthly boards. */
    fun resetLine(nowMillis: Long): String? = if (duration == LeaderboardDuration.DAY) DailyReset.todayLine(nowMillis) else null
}

/** RN leaderboardContainer: the top users by points for a day, week or month. Public data, no account needed. */
class LeaderboardModel internal constructor(private val env: AppEnvironment) : StateModel<LeaderboardState>(LeaderboardState(), env.dispatchers) {
    private var job: Job? = null

    fun start() {
        if (!current.loaded && job?.isActive != true) load()
    }

    fun select(duration: LeaderboardDuration) {
        if (duration == current.duration) return
        setState { LeaderboardState(duration = duration) }
        load()
    }

    fun refresh() = load()

    /** RN's tooltip on a row's reward (`leaderboard.tooltip_earn`), shown through the toast channel. */
    fun showEarnHint() {
        env.ui.toast(L.leaderboard.tooltipEarn)
    }

    @Throws(CancellationException::class)
    suspend fun refreshAndWait() {
        load()
        state.first { !it.isLoading }
    }

    private fun load() {
        val duration = current.duration
        job?.cancel()
        setState { it.copy(isLoading = true) }
        job = scope.launch {
            try {
                val users = env.notificationsApi.leaderboard(duration.apiValue)
                // daily: users who finished their quests first, each group in its own order (a stable partition)
                val ordered = if (duration == LeaderboardDuration.DAY) users.filter { it.questsDone } + users.filter { !it.questsDone } else users
                setState { it.copy(users = ordered, isLoading = false, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, loaded = true) }
                env.ui.toast(L.alert.somethingWrongMsg(message = AppError.from(e).message))
            }
        }
    }
}
