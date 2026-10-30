package com.ecency.core.app

import com.ecency.core.api.ecency.Referral
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** RN en-US `refer.*`. */
object ReferCopy {
    val TITLE: String get() = L.refer.referEarn
    val EARNED: String get() = L.refer.earned
    val PENDING: String get() = L.refer.pending
    val REFER: String get() = L.refer.refer
    val DELEGATE_HP: String get() = L.refer.delegateHp
    val REWARDED: String get() = L.refer.rewarded
    val NOT_REWARDED: String get() = L.refer.notRewarded
    val EMPTY: String get() = L.refer.emptyText

    /** RN `_handleRefer`: what the share sheet gets. */
    fun shareUrl(username: String): String = "https://ecency.com/signup?referral=$username"
}

/** One row of the list (RN UserListItem with the `$$` mark and "Delegate HP"). */
data class ReferralRow(
    val id: Long,
    val username: String,
    val isRewarded: Boolean,
    /** When the account was referred, epoch milliseconds, for the platform's locale date; null when unreadable. */
    val createdMillis: Long?,
) {
    /** RN's popover on the `$$` mark. */
    val rewardHint: String get() = if (isRewarded) ReferCopy.REWARDED else ReferCopy.NOT_REWARDED
}

data class ReferState(
    /** The account whose referrals these are; rows are only drawn while it is the signed-in one. */
    val account: String? = null,
    /** RN: rewarded * 100. */
    val earnedPoints: Int = 0,
    /** RN: (total - rewarded) * 100. */
    val pendingPoints: Int = 0,
    val rows: List<ReferralRow> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val error: AppError? = null,
    val pages: Int = 0,
) {
    /** RN `ListEmptyComponent`: the hint once loaded with nothing. */
    val isEmpty: Boolean get() = loaded && rows.isEmpty() && error == null

    val shareUrl: String? get() = account?.let(ReferCopy::shareUrl)
}

/**
 * RN referScreen: the signed-in account's referred accounts (`/private-api/referrals/<user>`, paged by
 * `max_id`), the points earned and pending from `/stats`, and the signup link to share. The list belongs to
 * the account signed in when it loaded: an account change empties it and loads the new account's, and an
 * answer still on its way for the previous account is dropped.
 */
@OptIn(ExperimentalTime::class)
class ReferModel internal constructor(private val env: AppEnvironment) : StateModel<ReferState>(ReferState(), env.dispatchers) {
    private var job: Job? = null
    private var nextMaxId: Long? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.events.collect { event ->
                if (event is AppEvent.SessionChanged && event.username != current.account) {
                    job?.cancel()
                    nextMaxId = null
                    setState { ReferState() }
                    if (event.username != null) load(refresh = false)
                }
            }
        }
        load(refresh = false)
    }

    /** Pull to refresh (RN refetches the list; the stats come with it here). */
    fun refresh() {
        if (current.isLoading) return
        load(refresh = true)
    }

    fun retry() = load(refresh = false)

    fun loadMore() {
        val s = current
        val user = s.account ?: return
        val maxId = nextMaxId ?: return
        if (job?.isActive == true || !s.loaded || !s.hasMore) return
        setState { it.copy(isLoadingMore = true, error = null) }
        job = scope.launch {
            try {
                val page = env.onboardingApi.referrals(user, maxId)
                if (!owns(user)) return@launch
                nextMaxId = page.nextMaxId
                setState { st ->
                    val known = st.rows.map { it.id }.toSet()
                    val fresh = page.items.map(::row).filter { it.id !in known }
                    // a page that brings nothing new ends the list: a server that repeats itself cannot page forever
                    st.copy(rows = st.rows + fresh, isLoadingMore = false, hasMore = page.nextMaxId != null && fresh.isNotEmpty(), pages = st.pages + 1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (owns(user)) setState { it.copy(isLoadingMore = false, error = AppError.from(e)) }
            }
        }
    }

    private fun load(refresh: Boolean) {
        job?.cancel()
        setState { it.copy(isLoading = !refresh, isRefreshing = refresh, error = null) }
        job = scope.launch {
            var user: String? = null
            try {
                env.session.awaitLoaded()
                user = env.session.current.username
                if (user == null) {
                    setState { ReferState() }
                    return@launch
                }
                val name: String = user
                setState { if (it.account == name) it else ReferState(account = name, isLoading = true) }
                val stats = async { runCatching { env.onboardingApi.referralStats(name) } }
                val page = env.onboardingApi.referrals(name, null)
                // RN: the stats are their own query; without them the points read 0 and the list still shows
                val counts = stats.await().onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (!owns(name)) return@launch
                nextMaxId = page.nextMaxId
                setState {
                    it.copy(
                        earnedPoints = (counts?.rewarded ?: 0) * 100,
                        pendingPoints = ((counts?.total ?: 0) - (counts?.rewarded ?: 0)) * 100,
                        rows = page.items.map(::row).distinctBy { r -> r.id },
                        isLoading = false, isRefreshing = false, loaded = true, hasMore = page.nextMaxId != null && page.items.isNotEmpty(), pages = 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val name = user
                if (name == null || owns(name)) setState { it.copy(isLoading = false, isRefreshing = false, error = AppError.from(e)) }
            }
        }
    }

    /** The answer is still for the account on screen. */
    private fun owns(user: String): Boolean = env.session.current.username == user && current.account == user

    private fun row(r: Referral) = ReferralRow(r.id, r.referredUsername, r.isRewarded, parseMillis(r.created))

    companion object {
        /** JS `new Date(created)` for the ISO shapes the backend sends; anything else has no date. */
        fun parseMillis(created: String): Long? {
            val text = created.trim()
            if (text.isEmpty() || text.length > 40) return null
            val iso = if (text.length == 19 && text[10] == ' ') text.replace(' ', 'T') else text
            return runCatching { Instant.parse(iso).toEpochMilliseconds() }
                .recoverCatching { Instant.parse(iso + "Z").toEpochMilliseconds() }.getOrNull()
        }
    }
}
