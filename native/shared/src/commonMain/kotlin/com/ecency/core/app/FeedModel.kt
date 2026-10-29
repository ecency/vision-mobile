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
    fun post(key: PostKey): Post? = posts[key] ?: promotedPosts[key]

    private fun display(): List<PostCard> =
        Promoted.inject(base, promotedCards, { it.key }) { it.author in env.mutedAuthors }

    /** The items again after [base] or the promoted cards changed (deleted, muted, votes). */
    private fun redisplay() {
        val next = display()
        setState { if (next == it.items) it else it.copy(items = next) }
    }
    private var job: Job? = null
    private var posts: Map<PostKey, Post> = emptyMap()

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
                promotedCards = Cards.replaceChanged(promotedCards, changed, { it.key }) { c -> promotedPosts[c.key]?.let { Cards.withVote(c, it, user, local[c.key]) } }
                setState { s ->
                    val items = Cards.replaceChanged(s.items, changed, { it.key }) { c ->
                        (posts[c.key] ?: promotedPosts[c.key])?.let { Cards.withVote(c, it, user, local[c.key]) }
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
                    base = base.filter { it.key != event.key }
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

    /** Loads the first page unless already loaded or loading. */
    fun start() {
        if (current.loaded || job?.isActive == true) return
        load(reset = true, refreshing = false)
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
        }
    }

    private fun load(reset: Boolean, refreshing: Boolean) {
        job?.cancel()
        if (reset) pager = env.api.feeds.pager(source, env.session.current.username)
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
                val loaded = activePager.loadMore()
                val nsfw = NsfwMode.of(env.prefs)
                // RN filterNsfwPost ("Always hide") and the account's mutes
                val fresh = loaded.filter { (nsfw != NsfwMode.HIDE || !NsfwMode.isNsfw(it.crossPostOriginal ?: it)) && it.author !in env.mutedAuthors }
                val user = env.session.current.username
                val local = env.localVotes.state.value
                val cards = withContext(dispatchers.render) {
                    fresh.map { Cards.of(it, env.renderer, user, local[it.key], env.proxyBase, nsfwWarn = nsfw == NsfwMode.WARN) }
                }
                posts = (if (reset) emptyMap() else posts) + fresh.associateBy { it.key }
                base = if (reset) cards else base + cards
                if (reset && withPromoted) loadPromoted(nsfw)
                setState {
                    it.copy(
                        items = display(),
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        endReached = activePager.isEndReached,
                        loaded = true,
                        pages = if (reset) 1 else it.pages + 1,
                    )
                }
                PerfMarks.markOnce(PerfEvent.FEED_FIRST_PAGE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reset) pager = null
                setState { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, error = AppError.from(e)) }
            }
        }
    }
}
