package com.ecency.core.app

import com.ecency.core.api.ActiveVoteDetail
import com.ecency.core.api.EditHistory
import com.ecency.core.api.HistoryDiff
import com.ecency.core.api.HistoryVersion
import com.ecency.core.api.SubscribedCommunity
import com.ecency.core.api.activeVotes
import com.ecency.core.api.broadcast.PostActionOps
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rebloggedBy
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

// ---------------------------------------------------------------- voters

/** One row of the RN voters screen. */
data class VoterRow(
    val voter: String,
    val avatarUrl: String,
    /** RN `calculateVoteReward(...).toFixed(3)`. */
    val reward: String,
    /** RN `percent / 100` as JS prints it ("100", "33.33"). */
    val percent: String,
    val isDownVote: Boolean,
    /** moment fromNow of the vote, or null when the vote time is not after the post (old votes carry 1970). */
    val timeAgo: String?,
    val rshares: Long,
    val percentValue: Int,
    val timeMillis: Long,
) {
    /** [reward] as the row shows it, in the app language when the row was built ([reward] stays POSIX: the sort reads it). */
    val rewardText: String = LocaleFormat.posix(reward, grouping = false)

    /** [percent] with the percent sign, in the app language ("33.33%", "33,33 %"). */
    val percentText: String = percent.toDoubleOrNull()?.let { LocaleFormat.percent(it, 2, 0) } ?: "$percent%"
}

/** RN voters FilterBar: REWARDS, PERCENT, TIME. */
enum class VoterSort(private val labelText: () -> String) { REWARDS({ L.votersDropdown.rewards }), PERCENT({ L.votersDropdown.percent }), TIME({ L.commentFilter.time });

    val label: String get() = labelText()
}

data class VotersState(
    val rows: List<VoterRow> = emptyList(),
    /** All rows before the search filter. */
    val total: Int = 0,
    val sort: VoterSort = VoterSort.REWARDS,
    /** RN: picking the selected option again sorts ascending. */
    val ascending: Boolean = false,
    /** No option picked yet: the RN initial order (rshares descending). */
    val sorted: Boolean = false,
    val query: String = "",
    val isLoading: Boolean = false,
    val error: AppError? = null,
) {
    /** RN header "Voters Info (N)". */
    val title: String get() = L.native.postLists.votersTitle(total = total.toString())
}

/** RN votersScreen: get_active_votes with each vote's share of the payout, sort and search. */
class VotersModel internal constructor(private val env: AppEnvironment, val key: PostKey) :
    StateModel<VotersState>(VotersState(), env.dispatchers) {
    private var all: List<VoterRow> = emptyList()
    private var job: Job? = null

    fun start() {
        if (all.isNotEmpty() || job?.isActive == true) return
        load()
    }

    fun refresh() = load()

    private fun load() {
        job?.cancel()
        setState { it.copy(isLoading = true, error = null) }
        job = scope.launch {
            try {
                val post = env.api.posts.getPost(key.author, key.permlink, env.session.current.username)
                val votes = env.api.hive.activeVotes(key.author, key.permlink)
                all = rows(votes, post, env.proxyBase, env.nowMillis())
                setState { it.copy(isLoading = false, total = all.size) }
                apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, error = AppError.from(e)) }
            }
        }
    }

    /**
     * RN _handleOnVotersDropdownSelect: another option sorts descending, the same one ascending (RN starts on
     * REWARDS, so tapping REWARDS first sorts ascending, as in RN).
     */
    fun selectSort(sort: VoterSort) {
        setState { it.copy(sort = sort, ascending = it.sort == sort, sorted = true) }
        apply()
    }

    fun search(query: String) {
        setState { it.copy(query = query) }
        apply()
    }

    private fun apply() {
        val s = current
        var list = if (s.query.isEmpty()) all else all.filter { it.voter.uppercase().contains(s.query.uppercase()) }
        if (s.sorted) list = sort(list, s.sort, s.ascending)
        setState { it.copy(rows = list) }
    }

    companion object {
        fun sort(list: List<VoterRow>, sort: VoterSort, ascending: Boolean): List<VoterRow> {
            val selector: (VoterRow) -> Double = when (sort) {
                VoterSort.REWARDS -> { r -> r.reward.toDoubleOrNull() ?: r.rshares.toDouble() }
                VoterSort.PERCENT -> { r -> r.percentValue.toDouble() }
                VoterSort.TIME -> { r -> r.timeMillis.toDouble() }
            }
            return if (ascending) list.sortedBy(selector) else list.sortedByDescending(selector)
        }

        /** RN parseActiveVotes / calculateVoteReward over the votes sorted by rshares, descending. */
        fun rows(votes: List<ActiveVoteDetail>, post: Post?, proxyBase: String, nowMillis: Long): List<VoterRow> {
            val totalRshares = votes.sumOf { it.rshares.toDouble() }
            val payout = post?.totalPayout ?: 0.0
            val ratio = if (totalRshares != 0.0) payout / totalRshares else 0.0
            val created = post?.let { PostCard.epochMillis(it.created) } ?: 0
            return votes.sortedByDescending { it.rshares }.map { v ->
                val time = PostCard.epochMillis(v.time)
                VoterRow(
                    voter = v.voter,
                    avatarUrl = PostCard.avatarUrl(v.voter, proxyBase),
                    reward = Num.fixed(if (v.rshares == 0L) 0.0 else v.rshares * ratio, 3),
                    percent = Num.plain(v.percent / 100.0),
                    isDownVote = v.rshares < 0,
                    timeAgo = if (time > created && time > 0) FromNow.format(time, nowMillis) else null,
                    rshares = v.rshares,
                    percentValue = v.percent,
                    timeMillis = time,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- reblogs

data class ReblogsState(
    val accounts: List<String> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val isLoading: Boolean = false,
    val error: AppError? = null,
    /** The signed-in account is in the list: the button undoes the reblog. */
    val rebloggedByMe: Boolean = false,
) {
    /** RN header `${reblog.title} (${count})`. */
    val title: String get() = L.native.postLists.reblogsTitle(total = total.toString())

    /** RN floating button. */
    val buttonLabel: String get() = if (rebloggedByMe) L.reblog.reblogDelete else L.reblog.reblogPost
}

/** RN reblogScreen: `get_reblogged_by`, search, and the reblog / undo button (the menu's action). */
class ReblogsModel internal constructor(private val env: AppEnvironment, val key: PostKey) :
    StateModel<ReblogsState>(ReblogsState(), env.dispatchers) {
    private var all: List<String> = emptyList()
    private var job: Job? = null

    init {
        scope.launch {
            env.events.collect { if (it is AppEvent.PostChanged && it.key == key || it is AppEvent.SessionChanged) refresh() }
        }
    }

    fun start() {
        if (all.isNotEmpty() || job?.isActive == true) return
        refresh()
    }

    fun refresh() {
        job?.cancel()
        setState { it.copy(isLoading = true, error = null) }
        job = scope.launch {
            try {
                // hived lists the author too; RN shows the list as returned
                all = env.api.hive.rebloggedBy(key.author, key.permlink)
                setState { it.copy(isLoading = false, total = all.size, rebloggedByMe = env.session.current.username in all) }
                search(current.query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, error = AppError.from(e)) }
            }
        }
    }

    fun search(query: String) = setState { s ->
        s.copy(query = query, accounts = if (query.isEmpty()) all else all.filter { it.uppercase().contains(query.uppercase()) })
    }

    /** The button: reblog, or undo when already reblogged; the menu action's toasts. */
    fun toggle(post: Post) {
        val item = if (current.rebloggedByMe) PostMenuItem.UNDO_REBLOG else PostMenuItem.REBLOG
        scope.launch { env.postActions.select(item, post) }
    }
}

// ---------------------------------------------------------------- edit history

data class EditHistoryState(
    val versions: List<HistoryVersion> = emptyList(),
    val selected: Int = 0,
    /** RN header toggle (git-compare icon). */
    val showDiff: Boolean = false,
    val diff: HistoryDiff? = null,
    val isLoading: Boolean = false,
    /** RN alerts: "No History found!" (failure) and "No edit history available for this content." (empty). */
    val message: String? = null,
)

/** RN editHistoryScreen: versions from `comment-history`, plain or diffed against the previous version. */
class EditHistoryModel internal constructor(private val env: AppEnvironment, val key: PostKey) :
    StateModel<EditHistoryState>(EditHistoryState(), env.dispatchers) {
    private var job: Job? = null

    fun start() {
        if (current.versions.isNotEmpty() || job?.isActive == true) return
        setState { it.copy(isLoading = true, message = null) }
        job = scope.launch {
            try {
                val raw = env.rest.commentHistory(key.author, key.permlink)
                // patches are untrusted and applying them is CPU work: never on the main thread, and capped
                val versions = withContext(env.dispatchers.render) { EditHistory.rebuildCapped(raw) }
                if (versions == null) {
                    setState { it.copy(isLoading = false, message = L.native.postLists.noHistoryFound) }
                    return@launch
                }
                setState {
                    it.copy(
                        isLoading = false, versions = versions, selected = 0,
                        message = if (versions.isEmpty()) L.native.postLists.noEditHistoryAvailable else null,
                    )
                }
                if (current.showDiff) computeDiff()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, message = L.native.postLists.noHistoryFound) }
            }
        }
    }

    fun select(index: Int) {
        if (index !in current.versions.indices) return
        setState { it.copy(selected = index, diff = null) }
        if (current.showDiff) computeDiff()
    }

    fun toggleDiff() {
        setState { it.copy(showDiff = !it.showDiff) }
        if (current.showDiff && current.diff == null) computeDiff()
    }

    fun dismissMessage() = setState { it.copy(message = null) }

    private var diffJob: Job? = null

    private fun computeDiff() {
        val versions = current.versions
        val index = current.selected
        if (versions.isEmpty()) return
        diffJob?.cancel()
        diffJob = scope.launch {
            // character diffs of long bodies are CPU work: never on the main thread
            val diff = withContext(env.dispatchers.render) { EditHistory.diff(versions, index) }
            if (current.selected == index && current.versions === versions) setState { it.copy(diff = diff) }
        }
    }

    companion object {
        /** RN version button: "Version N". */
        fun versionLabel(v: HistoryVersion): String = L.native.postLists.versionLabel(version = v.version.toString())
    }
}

// ---------------------------------------------------------------- cross post

data class CrossPostState(
    val communities: List<SubscribedCommunity> = emptyList(),
    val query: String = "",
    val selected: SubscribedCommunity? = null,
    val message: String = "",
    val isLoading: Boolean = false,
    val isPublishing: Boolean = false,
    val error: AppError? = null,
    /** Published: the UI closes and opens [published]'s new posts (RN: community screen, filter created). */
    val published: String? = null,
) {
    val canPublish: Boolean get() = selected != null && !isPublishing

    val filtered: List<SubscribedCommunity>
        get() = if (query.isEmpty()) communities else communities.filter { it.title.lowercase().contains(query.lowercase()) }
}

/** RN crossPostModal: pick a subscribed community, add a message, publish a payout-declined cross post. */
class CrossPostModel internal constructor(private val env: AppEnvironment, val post: Post, private val appVersion: String) :
    StateModel<CrossPostState>(CrossPostState(), env.dispatchers) {

    fun start() {
        val user = env.session.current.username ?: return
        if (current.communities.isNotEmpty() || current.isLoading) return
        setState { it.copy(isLoading = true) }
        scope.launch {
            try {
                // the communities area's read; sorted by title as RN's fetchSubscribedCommunities
                val list = env.communityApi.subscriptions(user).map { SubscribedCommunity(it.community, it.title) }.sortedBy { it.title }
                setState { it.copy(isLoading = false, communities = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, error = AppError.from(e)) }
            }
        }
    }

    fun search(query: String) = setState { it.copy(query = query) }

    fun select(community: SubscribedCommunity) = setState { it.copy(selected = community) }

    fun setMessage(text: String) = setState { it.copy(message = text) }

    fun publish() {
        val user = env.session.current.username ?: return
        val community = current.selected ?: return
        if (current.isPublishing) return
        val original = post.crossPostOriginal ?: post
        setState { it.copy(isPublishing = true, error = null) }
        scope.launch {
            try {
                val signed = env.signing.broadcast(user, PostActionOps.crossPost(user, original, community.name, current.message, appVersion))
                // RN repostQueries: a cross post reports as a reblog (130)
                env.pointActivities.recordBroadcast(user, PointActivity.REBLOG, signed)
                env.ui.toast(PostActionsModel.SUCCESS)
                setState { it.copy(isPublishing = false, published = community.name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AppError.fromBroadcast(e)
                // RN repostQueries onError: out of RC is the app-wide offer, every other failure "Fail!"
                val offered = error.kind != ErrorKind.CANCELLED && env.offerRcIfShort(e)
                if (error.kind != ErrorKind.CANCELLED && !offered) env.ui.toast(PostActionsModel.FAIL)
                setState { it.copy(isPublishing = false, error = error.takeIf { err -> err.kind != ErrorKind.CANCELLED && !offered }) }
            }
        }
    }
}
