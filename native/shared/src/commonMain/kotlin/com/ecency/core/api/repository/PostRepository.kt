package com.ecency.core.api.repository

import com.ecency.core.api.EcencyException
import com.ecency.core.api.HiveApi
import com.ecency.core.api.guarded
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Comment orders offered by the app's comment filter ("top", "reputation", "votes", "time"). */
enum class CommentSort { TRENDING, REPUTATION, VOTES, AGE }

/** One comment in a flattened reply thread. [level] 1 is a direct reply to the post. */
data class CommentNode(
    val comment: Post,
    val level: Int,
    /** Replies exist below this node but were cut at [Discussion.MAX_THREAD_LEVEL]: show "more replies". */
    val hasHiddenReplies: Boolean,
)

/** A top-level comment and its replies, flattened depth-first (levels 2..MAX_THREAD_LEVEL). */
data class CommentThread(val comment: Post, val replies: List<CommentNode>)

/**
 * A post's comment section, shaped like the app's useDiscussionQuery + sortComments:
 * top-level comments sorted by [sort] (pinned reply first), each with its replies in the
 * bridge's order down to [MAX_THREAD_LEVEL]. Comments by [botAuthors] go to [botThreads].
 */
data class Discussion(
    val root: Post?,
    val threads: List<CommentThread>,
    val botThreads: List<CommentThread>,
    /** Every entry the bridge returned (root included), by key. */
    val entries: Map<PostKey, Post>,
    val sort: CommentSort,
    /** Local comments not yet returned by the bridge (shown on top). */
    val pending: Set<PostKey> = emptySet(),
) {
    /** The thread under any comment, for "more replies" navigation (that comment becomes level 1). */
    fun threadFrom(key: PostKey): CommentThread? {
        val comment = entries[key] ?: return null
        return CommentThread(comment, flatten(entries, comment.replies, 2))
    }

    companion object {
        /** The app's MAX_THREAD_LEVEL. */
        const val MAX_THREAD_LEVEL = 3

        fun build(
            rootKey: PostKey,
            entries: Collection<Post>,
            sort: CommentSort = CommentSort.TRENDING,
            botAuthors: Set<String> = emptySet(),
            /** Just-posted local comments: shown first, like the app's renderOnTop. */
            onTop: Set<PostKey> = emptySet(),
        ): Discussion {
            val byKey = LinkedHashMap<PostKey, Post>()
            entries.forEach { byKey[it.key] = it }
            val root = byKey[rootKey]
            val topLevel = byKey.values.filter {
                it.parentAuthor == rootKey.author && it.parentPermlink == rootKey.permlink
            }
            val ordered = sortComments(topLevel, sort, root?.metadata?.pinnedReply, onTop)
            val threads = ordered.map { CommentThread(it, flatten(byKey, it.replies, 2)) }
            val (bots, users) = threads.partition { it.comment.author in botAuthors }
            return Discussion(root, users, bots, byKey, sort, onTop.filterTo(HashSet()) { it in byKey })
        }

        private fun flatten(byKey: Map<PostKey, Post>, keys: List<PostKey>, level: Int): List<CommentNode> {
            if (level > MAX_THREAD_LEVEL) return emptyList()
            val out = mutableListOf<CommentNode>()
            for (key in keys) {
                val comment = byKey[key] ?: continue
                val cut = level == MAX_THREAD_LEVEL && (comment.replies.isNotEmpty() || comment.children > 0)
                out += CommentNode(comment, level, hasHiddenReplies = cut)
                out += flatten(byKey, comment.replies, level + 1)
            }
            return out
        }

        /**
         * The app's comparators, made consistent: the JS versions answer "a after b" for two
         * downvoted comments both ways, which JS tolerates but a JVM sort may reject. Here
         * downvoted comments go last and keep their relative order. The base order before the
         * chosen sort is the SDK's "created" order, as the app requests it.
         */
        fun sortComments(
            comments: List<Post>,
            sort: CommentSort,
            pinnedReply: PostKey?,
            onTop: Set<PostKey> = emptySet(),
        ): List<Post> {
            val negativeLast = compareBy<Post> { it.isDownvoted }
            val base = comments.sortedWith(negativeLast.thenByDescending { it.created })
            val sorted = when (sort) {
                CommentSort.TRENDING -> base.sortedWith(negativeLast.thenByDescending { it.totalPayout })
                CommentSort.REPUTATION -> base.sortedByDescending { it.authorReputation }
                CommentSort.VOTES -> base.sortedByDescending { it.activeVotes.size }
                CommentSort.AGE -> base
            }
            // renderOnTop beats the sort; the pinned reply is then moved in front of everything.
            val (top, rest) = sorted.partition { it.key in onTop }
            val withTop = top + rest
            val pinnedIndex = withTop.indexOfFirst { it.key == pinnedReply }
            if (pinnedIndex <= 0) return withTop
            return listOf(withTop[pinnedIndex]) + withTop.filterIndexed { i, _ -> i != pinnedIndex }
        }
    }
}

/**
 * Single posts and discussions with a small in-memory cache (entries expire after [ttlMillis]).
 *
 * Comments the user just broadcast can be added with [addLocalComment]: they are shown at the
 * top of the discussion (the app's renderOnTop) until the bridge returns them (hivemind indexes
 * a few seconds behind the chain), and are dropped after [localCommentTtlMillis] if it never
 * does (the broadcast most likely did not land).
 */
@OptIn(ExperimentalTime::class)
class PostRepository(
    private val api: HiveApi,
    ttlMillis: Long = 60_000,
    maxPosts: Int = 500,
    maxDiscussions: Int = 20,
    /** The SDK's CONFIG.defaultObserver, used for discussions when nobody is logged in. */
    private val defaultObserver: String = "ecency",
    private val localCommentTtlMillis: Long = 5 * 60_000,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class CacheKey(val key: PostKey, val observer: String)

    private class LocalComment(val post: Post, val addedAt: Long)

    private val posts = MemoryCache<CacheKey, Post>(maxPosts, ttlMillis, nowMillis)
    private val discussions = MemoryCache<CacheKey, List<Post>>(maxDiscussions, ttlMillis, nowMillis)
    private val localMutex = Mutex()
    private val local = mutableMapOf<PostKey, List<LocalComment>>()

    /** `bridge.get_post`, served from cache unless [refresh]. Null when the post does not exist. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun getPost(author: String, permlink: String, observer: String? = null, refresh: Boolean = false): Post? = guarded {
        val cacheKey = CacheKey(PostKey(author, permlink), observer.orEmpty())
        if (!refresh) posts.get(cacheKey)?.let { return@guarded it }
        val post = api.post(author, permlink, observer) ?: return@guarded null
        posts.put(cacheKey, post)
        post
    }

    /** `bridge.get_discussion`, shaped for display. An unknown post gives an empty discussion. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun getDiscussion(
        author: String,
        permlink: String,
        observer: String? = null,
        sort: CommentSort = CommentSort.TRENDING,
        botAuthors: Set<String> = emptySet(),
        refresh: Boolean = false,
    ): Discussion = guarded {
        val rootKey = PostKey(author, permlink)
        val resolvedObserver = observer?.takeIf { it.isNotBlank() } ?: defaultObserver
        val cacheKey = CacheKey(rootKey, resolvedObserver)
        val fetched = (if (refresh) null else discussions.get(cacheKey))
            ?: (api.discussion(author, permlink, resolvedObserver) ?: emptyList()).also { discussions.put(cacheKey, it) }
        val (entries, pendingKeys) = mergeLocal(rootKey, fetched)
        Discussion.build(rootKey, entries, sort, botAuthors, onTop = pendingKeys)
    }

    /** Shows a just-broadcast comment under [root] (at the top) until the bridge returns it. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun addLocalComment(root: PostKey, comment: Post): Unit = guarded {
        localMutex.withLock {
            local[root] = local[root].orEmpty().filter { it.post.key != comment.key } + LocalComment(comment, nowMillis())
        }
    }

    /**
     * Forgets cached copies of [key] (e.g. after voting on it): the post itself and every cached
     * discussion that contains it, so a voted comment refreshes in its thread too.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun invalidate(key: PostKey): Unit = guarded {
        posts.removeIf { k, _ -> k.key == key }
        discussions.removeIf { k, entries -> k.key == key || entries.any { it.key == key } }
    }

    /** Drops every cached post, discussion and local comment (they carry per-account vote state). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun clearUserData(): Unit = guarded {
        posts.clear()
        discussions.clear()
        localMutex.withLock { local.clear() }
    }

    internal suspend fun remember(fetched: List<Post>, observer: String?) {
        fetched.forEach { posts.put(CacheKey(it.key, observer.orEmpty()), it) }
    }

    /** Fetched entries plus still-pending local comments, and the keys of those pending ones. */
    private suspend fun mergeLocal(root: PostKey, fetched: List<Post>): Pair<List<Post>, Set<PostKey>> {
        val fetchedKeys = fetched.mapTo(HashSet()) { it.key }
        val stillPending = localMutex.withLock {
            val now = nowMillis()
            val kept = local[root].orEmpty().filter { it.post.key !in fetchedKeys && now - it.addedAt <= localCommentTtlMillis }
            if (kept.isEmpty()) local.remove(root) else local[root] = kept
            kept.map { it.post }
        }
        if (stillPending.isEmpty()) return fetched to emptySet()
        // Attach each pending comment to its parent's reply list so it shows in the right thread.
        val byKey = LinkedHashMap<PostKey, Post>()
        fetched.forEach { byKey[it.key] = it }
        stillPending.forEach { comment ->
            byKey[comment.key] = comment
            val parentKey = PostKey(comment.parentAuthor ?: return@forEach, comment.parentPermlink ?: return@forEach)
            val parent = byKey[parentKey] ?: return@forEach
            if (comment.key !in parent.replies) {
                byKey[parentKey] = parent.copy(replies = parent.replies + comment.key, children = parent.children + 1)
            }
        }
        return byKey.values.toList() to stillPending.mapTo(HashSet()) { it.key }
    }
}
