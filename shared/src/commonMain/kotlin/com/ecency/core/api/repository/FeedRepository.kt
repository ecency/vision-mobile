package com.ecency.core.api.repository

import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.EcencyException
import com.ecency.core.api.HiveApi
import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.guarded
import com.ecency.core.api.PageCursor
import com.ecency.core.api.RankedSort
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Which feed to page through. */
sealed interface FeedSource {
    /** Global, tag or community feed: `bridge.get_ranked_posts`. A community is a tag like "hive-125125". */
    data class Ranked(val sort: RankedSort, val tag: String = "") : FeedSource

    /** An account's blog, posts, comments, replies, or its "friends" feed (FEED). */
    data class Account(val account: String, val sort: AccountPostsSort = AccountPostsSort.BLOG) : FeedSource

    companion object {
        /** The app's "communities" tab: newest posts from the observer's subscriptions. */
        fun myCommunities() = Ranked(RankedSort.CREATED, tag = "my")
    }
}

/** One page, in the bridge's order. [nextCursor] is null at the end of the feed. */
data class FeedPage(val posts: List<Post>, val nextCursor: PageCursor?) {
    val hasMore: Boolean get() = nextCursor != null
}

/**
 * Paged feeds, as the app's useFeedQuery: the same bridge calls, the same cursor rule (the last
 * entry of the page, in the bridge's ranking), the same end-of-feed rule (ranked: an empty page;
 * account: a short page), and cross-posts resolved to their original like the SDK's resolvePost.
 * Every post fetched is also put into [PostRepository]'s cache when one is given.
 */
class FeedRepository(
    private val api: HiveApi,
    private val postCache: PostRepository? = null,
    /** The app's POSTS_FETCH_COUNT. */
    val pageSize: Int = 10,
    private val resolveCrossPosts: Boolean = true,
) {
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun page(source: FeedSource, cursor: PageCursor? = null, observer: String? = null, limit: Int = pageSize): FeedPage = guarded {
        if (limit !in 1..100) throw InvalidInputException("limit must be 1..100, got $limit")
        val raw = when (source) {
            is FeedSource.Ranked -> api.rankedPosts(source.sort, source.tag, cursor, limit, observer)
            is FeedSource.Account -> api.accountPosts(source.account, source.sort, cursor, limit, observer)
        }
        val last = raw.lastOrNull()
        val next = when {
            last == null -> null
            source is FeedSource.Account && raw.size < limit -> null
            else -> PageCursor(last.author, last.permlink)
        }
        val posts = if (resolveCrossPosts) resolve(raw, observer) else raw
        postCache?.remember(posts, observer)
        FeedPage(posts, next)
    }

    /** A stateful pager for one list screen. */
    fun pager(source: FeedSource, observer: String? = null): FeedPager = FeedPager(this, source, observer)

    private suspend fun resolve(posts: List<Post>, observer: String?): List<Post> = coroutineScope {
        posts.map { post ->
            async {
                if (!post.isCrossPost) return@async post
                try {
                    val original = api.post(post.metadata.originalAuthor!!, post.metadata.originalPermlink!!, observer)
                    if (original != null) post.copy(crossPostOriginal = original) else post
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    post // as the SDK: a failed original fetch keeps the wrapper
                }
            }
        }.awaitAll()
    }
}

/**
 * Accumulates pages of one feed. Posts already shown are not repeated when a later page
 * overlaps (a ranking can shift between requests). Calls are serialised: a second [loadMore]
 * while one runs waits for it.
 */
class FeedPager internal constructor(
    private val repository: FeedRepository,
    val source: FeedSource,
    private val observer: String?,
) {
    private val mutex = Mutex()
    private var cursor: PageCursor? = null
    private var started = false
    private val seen = mutableSetOf<PostKey>()
    private val items = mutableListOf<Post>()

    /** Everything loaded so far. */
    val posts: List<Post> get() = items.toList()

    var isEndReached: Boolean = false
        private set

    /** Pages in a row that added nothing new. */
    private var emptyPages = 0

    /** Loads the next page and returns the new posts (empty at the end). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun loadMore(): List<Post> = mutex.withLock {
        if (isEndReached) return@withLock emptyList()
        val requested = if (started) cursor else null
        val page = repository.page(source, requested, observer)
        started = true
        cursor = page.nextCursor
        val fresh = page.posts.filter { seen.add(it.key) }
        emptyPages = if (fresh.isEmpty()) emptyPages + 1 else 0
        // a node that repeats the cursor, or keeps answering with posts already shown, would page forever
        isEndReached = page.nextCursor == null || page.nextCursor == requested || emptyPages >= MAX_EMPTY_PAGES
        items += fresh
        fresh
    }

    /** Drops everything and loads the first page again. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun refresh(): List<Post> {
        mutex.withLock {
            cursor = null
            started = false
            isEndReached = false
            emptyPages = 0
            seen.clear()
            items.clear()
        }
        return loadMore()
    }

    private companion object {
        /** Consecutive pages of nothing new after which the feed counts as ended. */
        const val MAX_EMPTY_PAGES = 3
    }
}
