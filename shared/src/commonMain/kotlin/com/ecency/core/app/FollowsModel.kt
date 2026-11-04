package com.ecency.core.app

import com.ecency.core.api.model.FriendRow
import com.ecency.core.l10n.L
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class FollowsState(
    val username: String,
    /** True: whom [username] follows; false: who follows [username]. */
    val following: Boolean,
    /** The count the profile showed, for the title; null when opened from a link. */
    val count: Int?,
    val rows: List<FriendRow> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val loaded: Boolean = false,
    val error: AppError? = null,
    /** Pages loaded; UIs key "near the end" on it so a page that added nothing still lets the next one start. */
    val pages: Int = 0,
    val query: String = "",
) {
    /** RN "Follower (12)" / "Following". */
    val title: String
        get() = count?.let { n -> if (following) L.native.follows.followingCount(count = n.toString()) else L.native.follows.followerCount(count = n.toString()) }
            ?: if (following) L.waves.following else L.profile.follower

    /** RN filters the loaded rows by name (case-insensitive substring). */
    val visible: List<FriendRow>
        get() = if (query.isEmpty()) rows else query.uppercase().let { q -> rows.filter { it.name.uppercase().contains(q) } }

    /** RN list empty states: "User is not found." or "Fail!: <message>". */
    val emptyText: String?
        get() = when {
            visible.isNotEmpty() || isLoading -> null
            error != null && rows.isEmpty() -> L.native.follows.fail(message = error.message)
            loaded || query.isNotEmpty() -> L.voters.noUser
            else -> null
        }
}

/**
 * Followers or following of an account (RN followsContainer over the SDK's getFriendsInfiniteQueryOptions):
 * pages of 100 names from `condenser_api.get_followers` / `get_following`, then their profiles from
 * `bridge.get_profiles` for reputation. Differences from RN: the next page starts from the last NAME of the
 * follow list (the SDK used the last profile, so a missing profile ended or shifted paging), and the node's
 * inclusive start row is not shown twice.
 */
class FollowsModel internal constructor(
    private val env: AppEnvironment,
    username: String,
    following: Boolean,
    count: Int?,
) : StateModel<FollowsState>(FollowsState(username, following, count), env.dispatchers) {
    private var job: Job? = null
    private var cursor: String? = null

    fun start() {
        if (current.loaded || job?.isActive == true) return
        load(reset = true)
    }

    fun refresh() = load(reset = true)

    fun loadMore() {
        val s = current
        if (job?.isActive == true || !s.loaded || s.endReached || s.error != null) return
        load(reset = false)
    }

    fun retry() {
        if (job?.isActive == true) return
        load(reset = !current.loaded || current.rows.isEmpty())
    }

    fun search(query: String) = setState { it.copy(query = query.trim()) }

    private fun load(reset: Boolean) {
        job?.cancel()
        if (reset) cursor = null
        val start = cursor
        setState { it.copy(isLoading = reset, isLoadingMore = !reset, error = null, rows = if (reset) emptyList() else it.rows) }
        job = scope.launch {
            try {
                val s = current
                val names = env.api.hive.follows(s.username, s.following, start, PAGE_SIZE)
                val fresh = if (start != null && names.firstOrNull() == start) names.drop(1) else names
                val profiles = env.api.hive.profiles(fresh).associateBy { it.name }
                // the node's order; accounts without a profile (deleted or not yet indexed) are left out, as the SDK
                val rows = fresh.mapNotNull { n -> profiles[n]?.let { FriendRow(it.name, it.reputation, it.active) } }
                cursor = names.lastOrNull() ?: start
                setState {
                    val known = if (reset) emptySet() else it.rows.map { r -> r.name }.toSet()
                    it.copy(
                        rows = (if (reset) emptyList() else it.rows) + rows.filter { r -> r.name !in known },
                        isLoading = false,
                        isLoadingMore = false,
                        loaded = true,
                        endReached = names.size < PAGE_SIZE,
                        pages = if (reset) 1 else it.pages + 1,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, isLoadingMore = false, loaded = true, error = AppError.from(e)) }
            }
        }
    }

    companion object {
        /** The SDK's friends page size. */
        const val PAGE_SIZE = 100
    }
}
