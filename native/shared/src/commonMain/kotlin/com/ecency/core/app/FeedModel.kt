package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedPager
import com.ecency.core.api.repository.FeedSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class FeedState(
    val items: List<PostCard> = emptyList(),
    /** First page loading (no items yet). */
    val isLoading: Boolean = false,
    /** Pull to refresh in progress (items stay visible). */
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** The last load failed: full-screen when [items] is empty, a footer with retry otherwise. */
    val error: AppError? = null,
    /** At least one page arrived since the last refresh. */
    val loaded: Boolean = false,
    /**
     * Pages loaded since the last refresh, counting pages that added nothing new (all duplicates).
     * UIs key their "near the end, load more" check on it, not on [items] size, so such a page does
     * not stall paging.
     */
    val pages: Int = 0,
)

/**
 * One feed screen: ranked (trending/hot/new, optionally a tag or community) or an account's
 * blog/feed. Cards are built on [AppDispatchers.render]; the observer is the signed-in account
 * at the time of [start] / [refresh].
 */
class FeedModel internal constructor(
    private val env: AppEnvironment,
    val source: FeedSource,
    /** RN injects promoted entries on the home feed and the tag screen. */
    private val withPromoted: Boolean = false,
) : StateModel<FeedState>(FeedState(), env.dispatchers) {

    private var pager: FeedPager? = null

    /** The feed's own cards; [FeedState.items] is these with the promoted cards injected. */
    private var base: List<PostCard> = emptyList()
    private var promotedCards: List<PostCard> = emptyList()
    private var promotedPosts: Map<PostKey, Post> = emptyMap()

    /** The post behind a card of this feed (for the options menu). */
    fun post(key: PostKey): Post? = pinnedPost?.takeIf { it.key == key } ?: posts[key] ?: promotedPosts[key]

    /** An account's pinned post ([pin]): first, marked pinned, its copy in the pages dropped. */
    private var pinnedPermlink: String? = null
    private var pinnedPost: Post? = null
    private var pinnedCard: PostCard? = null
    private var pinJob: Job? = null

    private fun display(): List<PostCard> {
        // a pin the viewer muted since it loaded is hidden like the author's other cards
        val pin = pinnedCard?.takeIf { it.author !in env.mutedAuthors }
        val own = if (pin == null) base else listOf(pin) + base.filter { it.key != pin.key }
        return Promoted.inject(own, promotedCards, { it.key }) { it.author in env.mutedAuthors }
    }

    /**
     * RN feedQueries `pinnedPermlink`: the account's pinned post (profile metadata `pinned`) is fetched on its own
     * and leads this account feed, marked pinned (RN `is_pinned_blog`). Null takes it away (an unpin). Only for an
     * account's own feed; a post the feed would hide (muted, nsfw hidden) stays hidden.
     */
    fun pin(permlink: String?) {
        if (source !is FeedSource.Account || permlink == pinnedPermlink) return
        pinnedPermlink = permlink
        // a changed pin: the old one leaves at once (the new one shows when it arrived)
        pinnedPost = null
        pinnedCard = null
        if (current.loaded) redisplay()
        loadPin()
    }

    /** Fetches [pinnedPermlink] again: on [pin], and with every load from the top (votes, stats, a failed try). */
    private fun loadPin() {
        val account = (source as? FeedSource.Account)?.account ?: return
        val permlink = pinnedPermlink
        pinJob?.cancel()
        if (permlink == null) return
        pinJob = scope.launch {
            try {
                val nsfw = NsfwMode.of(env.prefs)
                val found = env.api.posts.getPost(account, permlink, env.session.current.username) ?: return@launch
                val post = found.copy(stats = found.stats.copy(isPinnedBlog = true))
                if (post.author != account || shown(listOf(post), nsfw).isEmpty()) return@launch
                if (permlink != pinnedPermlink) return@launch
                pinnedPost = post
                pinnedCard = cards(listOf(post), nsfw).first()
                if (current.loaded) redisplay()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the feed shows without it, as RN's failed pinned query; the next refresh tries again
            }
        }
    }

    /** The items again after [base] or the promoted cards changed (deleted, muted, votes). */
    private fun redisplay() {
        val next = display()
        setState { if (next == it.items) it else it.copy(items = next) }
    }
    private var job: Job? = null
    private var posts: Map<PostKey, Post> = emptyMap()

    /** When the shown cards began to be read ([Freshness.stamp]); a long trip away since makes them stale. */
    private var readAt = Long.MIN_VALUE

    init {
        // votes made anywhere show on these cards at once; only the cards whose vote changed are rebuilt
        scope.launch {
            var previous = env.localVotes.state.value
            env.localVotes.state.drop(1).collect { local ->
                val changed = Cards.changedKeys(previous, local)
                previous = local
                if (changed.isEmpty()) return@collect
                val user = env.session.current.username
                base = Cards.replaceChanged(base, changed, { it.key }) { c -> posts[c.key]?.let { Cards.withVote(c, it, user, local[c.key]) } }
                pinnedCard = pinnedCard?.let { c -> if (c.key in changed) pinnedPost?.let { Cards.withVote(c, it, user, local[c.key]) } ?: c else c }
                promotedCards = Cards.replaceChanged(promotedCards, changed, { it.key }) { c -> promotedPosts[c.key]?.let { Cards.withVote(c, it, user, local[c.key]) } }
                setState { s ->
                    val items = Cards.replaceChanged(s.items, changed, { it.key }) { c ->
                        (posts[c.key] ?: pinnedPost?.takeIf { it.key == c.key } ?: promotedPosts[c.key])?.let { Cards.withVote(c, it, user, local[c.key]) }
                    }
                    if (items === s.items) s else s.copy(items = items)
                }
            }
        }
        // the observer changed: votes and muted state differ per account, so load again in place
        scope.launch {
            env.events.collect { event ->
                if (event is AppEvent.SessionChanged && (current.loaded || job?.isActive == true)) refresh()
                // deleted from this device: the card goes at once (RN removes it from the list cache)
                if (event is AppEvent.PostDeleted && current.items.any { it.key == event.key }) {
                    if (pinnedPost?.key == event.key) {
                        pinnedPost = null
                        pinnedCard = null
                    }
                    base = base.filter { it.key != event.key }
                    posts = posts - event.key
                    promotedCards = promotedCards.filter { it.key != event.key }
                    redisplay()
                }
                // RN drops a muted author's posts from the list at once
                if (event is AppEvent.MutesLoaded) {
                    base = base.filter { it.author !in env.mutedAuthors }
                    redisplay()
                }
                if (event is AppEvent.AuthorMuted) {
                    base = base.filter { it.author != event.author }
                    redisplay()
                }
            }
        }
    }

    /**
     * The feed is shown (the apps call it on show and on every return to the foreground): loads the first page unless
     * already loaded or loading, and refreshes quietly when the cards predate a long trip away (README "Foreground refresh").
     */
    fun start() {
        if (job?.isActive == true) return
        if (!current.loaded) load(reset = true, refreshing = false)
        else if (env.freshness.isStale(readAt)) refreshQuietly()
    }

    /** Drops everything and loads the first page again; cancels a running load. */
    fun refresh() = load(reset = true, refreshing = current.items.isNotEmpty())

    /**
     * [refresh], then suspends until it finished (or failed): for pull to refresh, which keeps its
     * spinner until then. Cancelled when the model is closed. Call on the main thread.
     */
    @Throws(CancellationException::class)
    suspend fun refreshAndWait() {
        refresh()
        // waits in the model's scope, so closing the model ends the wait instead of leaving it hanging
        scope.async { state.first { !it.isRefreshing && !it.isLoading } }.await()
    }

    /** Next page; no-op while loading, at the end, or after an error (use [retry]). */
    fun loadMore() {
        val s = current
        if (job?.isActive == true || s.endReached || s.error != null || !s.loaded) return
        load(reset = false, refreshing = false)
    }

    /** Repeats whatever failed: the first page when nothing is shown, else the next page. */
    fun retry() {
        if (job?.isActive == true) return
        // a failed first page or refresh drops the pager: start over, keeping visible cards
        val reset = !current.loaded || pager == null
        load(reset = reset, refreshing = reset && current.items.isNotEmpty())
    }

    /** RN usePromotedPostsQuery: fetched with the first page (and on refresh); a failure shows none. */
    private fun loadPromoted(nsfw: NsfwMode) {
        scope.launch {
            val list = try {
                env.rest.promotedEntries()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }.filter { nsfw != NsfwMode.HIDE || !NsfwMode.isNsfw(it) }.distinctBy { it.key }
            val user = env.session.current.username
            val local = env.localVotes.state.value
            val cards = withContext(dispatchers.render) {
                list.map { Cards.of(it, env.renderer, user, local[it.key], env.proxyBase, promoted = true, nsfwWarn = nsfw == NsfwMode.WARN) }
            }
            promotedPosts = list.associateBy { it.key }
            promotedCards = cards
            redisplay()
            env.localVotes.retireConfirmed(list, user)
        }
    }

    private fun load(reset: Boolean, refreshing: Boolean) {
        job?.cancel()
        if (reset) loadPin()
        if (reset) pager = env.api.feeds.pager(source, env.session.current.username)
        val stamp = env.freshness.stamp()
        val activePager = pager ?: env.api.feeds.pager(source, env.session.current.username).also { pager = it }
        setState {
            it.copy(
                isLoading = reset && !refreshing,
                isRefreshing = refreshing,
                isLoadingMore = !reset,
                error = null,
                items = if (reset && !refreshing) emptyList() else it.items,
            )
        }
        job = scope.launch {
            try {
                val nsfw = NsfwMode.of(env.prefs)
                var fresh = shown(activePager.loadMore(), nsfw)
                var read = 1
                // nothing to show yet is not the end: the lists only ask for more from a card on screen. Capped, so a
                // feed filtered almost whole (an nsfw tag under "Always hide") shows its empty state with load more
                while (fresh.isEmpty() && (reset || base.isEmpty()) && !activePager.isEndReached && read <= MAX_AUTO_ADVANCE) {
                    fresh = shown(activePager.loadMore(), nsfw)
                    read++
                }
                val cards = cards(fresh, nsfw)
                posts = (if (reset) emptyMap() else posts) + fresh.associateBy { it.key }
                base = if (reset) cards else base + cards
                if (reset) readAt = stamp
                if (reset && withPromoted) loadPromoted(nsfw)
                setState {
                    it.copy(
                        items = display(),
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        endReached = activePager.isEndReached,
                        loaded = true,
                        // every page read, skipped ones too: the quiet refresh reads this many again
                        pages = (if (reset) 0 else it.pages) + read,
                    )
                }
                env.localVotes.retireConfirmed(fresh, env.session.current.username)
                PerfMarks.markOnce(PerfEvent.FEED_FIRST_PAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reset) pager = null
                setState { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, error = AppError.from(e)) }
            }
        }
    }

    /**
     * README "Foreground refresh": the pages shown are read again from the top in the background (RN refetches every
     * page of the infinite query) and replace the cards at once when all arrived: no spinner, no skeleton, the list
     * stays. A failure keeps the cards and says nothing; the next show tries again.
     */
    private fun refreshQuietly() {
        loadPin()
        val stamp = env.freshness.stamp()
        val wanted = current.pages.coerceAtLeast(1)
        val fresh = env.api.feeds.pager(source, env.session.current.username)
        job = scope.launch {
            try {
                val nsfw = NsfwMode.of(env.prefs)
                val read = mutableListOf<Post>()
                var pages = 0
                // as many pages as were loaded; with nothing to show, on to the first load's cap at most
                while (!fresh.isEndReached && (pages < wanted || (read.isEmpty() && pages <= MAX_AUTO_ADVANCE))) {
                    read += shown(fresh.loadMore(), nsfw)
                    pages++
                }
                val cards = cards(read, nsfw)
                pager = fresh
                posts = read.associateBy { it.key }
                base = cards
                readAt = stamp
                if (withPromoted) loadPromoted(nsfw)
                setState { it.copy(items = display(), endReached = fresh.isEndReached, loaded = true, error = null, pages = pages) }
                env.localVotes.retireConfirmed(read, env.session.current.username)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the cards on screen stay as they are
            }
        }
    }

    /** RN filterNsfwPost ("Always hide") and the account's mutes. */
    private fun shown(posts: List<Post>, nsfw: NsfwMode) =
        posts.filter { (nsfw != NsfwMode.HIDE || !NsfwMode.isNsfw(it.crossPostOriginal ?: it)) && it.author !in env.mutedAuthors }

    private suspend fun cards(posts: List<Post>, nsfw: NsfwMode): List<PostCard> {
        val user = env.session.current.username
        val local = env.localVotes.state.value
        return withContext(dispatchers.render) {
            posts.map { Cards.of(it, env.renderer, user, local[it.key], env.proxyBase, nsfwWarn = nsfw == NsfwMode.WARN) }
        }
    }

    companion object {
        /** Pages read by themselves after one that filtered down to nothing (as [AssetDetailsModel.MAX_AUTO_ADVANCE]). */
        const val MAX_AUTO_ADVANCE = 5
    }
}
