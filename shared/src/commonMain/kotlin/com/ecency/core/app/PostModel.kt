package com.ecency.core.app

import com.ecency.core.api.activeVotes
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.CommentSort
import com.ecency.core.api.repository.Discussion
import com.ecency.core.render.blocks.Block
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One row of the comment list: a comment at [level] 1..3 with its rendered body. */
data class CommentItem(
    val post: Post,
    val level: Int,
    /** Replies exist below level 3: show "more replies" (opens [Route.Post] for this comment). */
    val hasHiddenReplies: Boolean,
    /** Just posted from this device, not yet indexed by the API. */
    val isLocal: Boolean,
    /** Header data (author, avatar, reputation, payout, votes); summary and image are empty. */
    val card: PostCard,
    val blocks: List<Block>,
    /** [isLocal] and not seen on chain yet: "sending to the chain". Once the chain holds it, it shows as posted. */
    val isSending: Boolean = false,
) {
    val key: PostKey get() = post.key
}

data class PostState(
    val key: PostKey,
    val post: Post? = null,
    /** Header data for the post itself (title, author, payout, votes...). */
    val card: PostCard? = null,
    /** Null until rendered (off the main thread); then the body blocks, possibly empty. */
    val body: List<Block>? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val notFound: Boolean = false,
    /** Loading the post failed. */
    val error: AppError? = null,
    val comments: List<CommentItem> = emptyList(),
    val commentsLoading: Boolean = false,
    /** Loading the discussion failed (the post itself may be fine). */
    val commentsError: AppError? = null,
    val commentSort: CommentSort = CommentSort.TRENDING,
    /** RN parentPost: the parent of a comment opened on its own (not for waves). */
    val parent: Post? = null,
    /** The parent's plain summary (RN parseSummary). */
    val parentSummary: String = "",
    /** Top-level comments by known bots (RN BotCommentsPreview / botComments screen), rendered like [comments]. */
    val botComments: List<CommentItem> = emptyList(),
    /** RN postReadingMetadata (words, minutes, speech text); null until computed. */
    val reading: ReadingInfo? = null,
    /** RN postDisplayView footer `<Tags>`: the category and tags, community titles filled in as they are looked up. */
    val tags: List<PostTag> = emptyList(),
)

/**
 * A post and its discussion. The post usually comes from the feed's cache at once; its body is
 * rendered on [AppDispatchers.render] and cached per post and last update, so reopening it is
 * instant. Comments are sorted by [CommentSort] with the pinned reply first and replies posted
 * from this device on top, nested to level 3.
 */
class PostModel internal constructor(
    private val env: AppEnvironment,
    val key: PostKey,
) : StateModel<PostState>(PostState(key), env.dispatchers) {

    private var postJob: Job? = null
    private var commentsJob: Job? = null
    private var discussion: Discussion? = null
    private var localKeys: Set<PostKey> = emptySet()

    init {
        scope.launch {
            var previous = env.localVotes.state.value
            env.localVotes.state.drop(1).collect { local ->
                val changed = Cards.changedKeys(previous, local)
                previous = local
                if (changed.isNotEmpty()) applyVotes(changed)
            }
        }
        scope.launch {
            env.events.collect { event ->
                when {
                    event is AppEvent.CommentPosted && event.root == key -> loadComments(refresh = false)
                    // the menu changed this post (pin, mute, reblog) or one of its comments
                    event is AppEvent.PostChanged && event.key == key && current.post != null -> refresh()
                    (event is AppEvent.PostChanged && current.comments.any { it.key == event.key }) ||
                        (event is AppEvent.PostDeleted && current.comments.any { it.key == event.key }) -> loadComments(refresh = true)
                    // another observer: votes on the post and its comments differ, so fetch both again in place
                    event is AppEvent.SessionChanged && (current.post != null || postJob?.isActive == true) -> refresh()
                }
            }
        }
    }

    /** Loads the post and comments unless already loaded or loading. */
    fun start() {
        if (current.post != null || postJob?.isActive == true) return
        loadPost(refresh = false)
        loadComments(refresh = false)
    }

    /** Fetches both again, bypassing caches. */
    fun refresh() {
        loadPost(refresh = true)
        loadComments(refresh = true)
    }

    /**
     * [refresh], then suspends until the post and its comments finished loading (or failed): for pull
     * to refresh. Cancelled when the model is closed. Call on the main thread.
     */
    @Throws(CancellationException::class)
    suspend fun refreshAndWait() {
        refresh()
        // waits in the model's scope, so closing the model ends the wait instead of leaving it hanging
        scope.async { state.first { !it.isLoading && !it.isRefreshing && !it.commentsLoading } }.await()
    }

    fun retry() {
        if (current.post == null || current.error != null) loadPost(refresh = true)
        if (current.commentsError != null || discussion == null) loadComments(refresh = true)
    }

    fun setCommentSort(sort: CommentSort) {
        if (sort == current.commentSort) return
        setState { it.copy(commentSort = sort) }
        loadComments(refresh = false)
    }

    private fun loadPost(refresh: Boolean) {
        postLoads++
        postJob?.cancel()
        setState { it.copy(isLoading = it.post == null, isRefreshing = refresh && it.post != null, error = null) }
        postJob = scope.launch {
            try {
                val post = env.api.posts.getPost(key.author, key.permlink, env.session.current.username, refresh)
                if (post == null) {
                    setState { it.copy(isLoading = false, isRefreshing = false, notFound = true) }
                    return@launch
                }
                showPost(post)
                setState { it.copy(isLoading = false, isRefreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, isRefreshing = false, error = AppError.from(e, "post.loadPost")) }
            }
        }
    }

    /** The weightless vote this screen shows, per account and post: the percent the chain had ([resolveWeightlessVote]). */
    private val weightlessShown = mutableMapOf<Pair<String, PostKey>, Int>()

    /** The account, post and load last looked up: one lookup per load of the post, a refresh asks again. */
    private var weightlessAsked: Triple<String, PostKey, Int>? = null
    private var postLoads = 0

    /**
     * A vote with no payout weight (an account with little HP) is listed by the bridge with rshares 0, as a removed
     * vote is, so it would read as no vote. In the background, once per load of the post, `get_active_votes` tells the
     * two apart by the vote's percent; a vote is then shown as this device's own votes are ([LocalVotes]), and taken
     * back when a later load finds it removed or weighted. A vote made on this device always wins. Not RN.
     */
    private fun resolveWeightlessVote(post: Post) {
        val me = env.session.current.username ?: return
        val tag = me to post.key
        if (!isOwnWeightless(tag, env.localVotes.get(post.key))) return
        if (post.voteOf(me)?.rshares != 0L) {
            // no vote listed, or one with weight: the bridge's answer stands
            if (weightlessShown.remove(tag) != null) env.localVotes.remove(post.key)
            return
        }
        val ask = Triple(me, post.key, postLoads)
        if (weightlessAsked == ask) return
        weightlessAsked = ask
        scope.launch {
            try {
                val percent = env.api.hive.activeVotes(post.author, post.permlink).firstOrNull { it.voter == me }?.percent ?: 0
                if (env.session.current.username != me || !isOwnWeightless(tag, env.localVotes.get(post.key))) return@launch
                if (percent == 0) {
                    if (weightlessShown.remove(tag) != null) env.localVotes.remove(post.key)
                } else {
                    weightlessShown[tag] = percent
                    env.localVotes.set(post.key, LocalVote(percent, pending = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the post shows as the bridge has it; the next load asks again
                if (weightlessAsked == ask) weightlessAsked = null
                ErrorReports.quiet(e, "post.weightlessVote")
            }
        }
    }

    /** No vote of this device for the post, or the one [resolveWeightlessVote] showed for [tag]. */
    private fun isOwnWeightless(tag: Pair<String, PostKey>, local: LocalVote?): Boolean =
        local == null || (!local.pending && local.estimate == null && weightlessShown[tag] == local.weight)

    /** Shows [post]; renders its body only when it is new or was updated. */
    private suspend fun showPost(post: Post) {
        val previous = current.post
        val card = card(post, withSummary = false)
        setState { it.copy(post = post, card = card, notFound = false, tags = PostTags.of(post, tagTitles)) }
        lookUpTagTitles(post)
        env.localVotes.retireConfirmed(listOf(post), env.session.current.username)
        resolveWeightlessVote(post)
        // RN usePlausibleTracker: one pageview per post screen, when its post first loads
        if (!pageviewRecorded) {
            pageviewRecorded = true
            env.analytics.pageview(post.url)
        }
        // a wave opened as its own thread votes with the wave weight memory too (RN isWavePost)
        if (post.parentAuthor in WAVES_HOSTS) env.waves.markWaves(listOf(post.key))
        if (previous != null && previous.updated == post.updated && current.body != null) return
        // one render per version, even when the post and the discussion arrive together
        if (renderingVersion == post.updated && bodyJob?.isActive == true) return
        bodyJob?.cancel()
        renderingVersion = post.updated
        if (post.depth > 0 && !post.parentAuthor.isNullOrEmpty() && current.parent == null) loadParent(post)
        scope.launch {
            val reading = kotlinx.coroutines.withContext(env.dispatchers.render) { ReadingInfo.of(post) }
            if (current.post?.updated == post.updated) setState { it.copy(reading = reading) }
        }
        bodyJob = scope.launch {
            val blocks = env.renderBody(post)
            if (current.post?.updated == post.updated) {
                setState { it.copy(body = blocks) }
                PerfMarks.mark(PerfEvent.POST_BODY_READY)
            }
        }
    }

    private var bodyJob: Job? = null

    /** Community titles of the post's tags (RN tagContainer getCommunity): a title, or null for no such community. */
    private val tagTitles = mutableMapOf<String, String?>()
    private val tagLookups = mutableSetOf<String>()

    private fun lookUpTagTitles(post: Post) {
        for (name in PostTags.lookups(post)) {
            if (!tagLookups.add(name)) continue // once per screen, even when the post comes again
            scope.launch {
                val title = try {
                    env.communityApi.community(name)?.title.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // not reported: optional community title, RN falls back to the name
                    return@launch // RN's fallback: the name, still a community
                }
                tagTitles[name] = title.ifBlank { null }
                current.post?.let { p -> setState { it.copy(tags = PostTags.of(p, tagTitles)) } }
            }
        }
    }

    private var pageviewRecorded = false

    private fun loadParent(post: Post) {
        // RN skips waves (their parent is a container post of the host account)
        if (post.parentAuthor in WAVES_HOSTS) return
        scope.launch {
            val parent = try {
                env.api.posts.getPost(post.parentAuthor!!, post.parentPermlink.orEmpty(), env.session.current.username)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorReports.quiet(e, "post.parent")
                null
            } ?: return@launch
            val summary = kotlinx.coroutines.withContext(env.dispatchers.render) { env.renderer.summary(parent) }
            setState { it.copy(parent = parent, parentSummary = summary) }
        }
    }
    private var renderingVersion: String? = null

    private fun loadComments(refresh: Boolean) {
        commentsJob?.cancel()
        setState { it.copy(commentsLoading = true, commentsError = null) }
        commentsJob = scope.launch {
            try {
                val d = env.api.posts.getDiscussion(
                    key.author, key.permlink, env.session.current.username, current.commentSort, env.botAuthors(), refresh = refresh,
                )
                discussion = d
                // the discussion carries a fresh copy of the post (votes, payout)
                d.root?.let { root -> if (current.post == null || root.updated >= current.post!!.updated) showPost(root) }
                localKeys = d.pending
                // the body comes first: comment bodies render only once it is ready (they are below it)
                bodyJob?.join()
                // votes made while the items render are applied again before the final list replaces the state
                val votesAtBuild = env.localVotes.state.value
                val nodes = flatten(d)
                suspend fun item(node: Triple<Post, Int, Boolean>): CommentItem {
                    val (post, level, hidden) = node
                    return CommentItem(post, level, hidden, post.key in localKeys, card(post, withSummary = false), env.renderBody(post), isSending = post.key in d.sending)
                }
                val bots = d.botThreads.map { item(Triple(it.comment, 1, false)) }
                setState { it.copy(botComments = bots) }
                // A long discussion shows its first screens at once and the rest when rendered. Only on a
                // first load: a reload keeps the list the reader may be scrolled into until the new one is complete.
                if (current.comments.isEmpty() && nodes.size > FIRST_COMMENTS) {
                    val first = nodes.subList(0, FIRST_COMMENTS).map { item(it) }
                    setState { it.copy(comments = first) }
                    val rest = nodes.subList(FIRST_COMMENTS, nodes.size).map { item(it) }
                    setState { it.copy(comments = withVotesSince(first + rest, votesAtBuild), commentsLoading = false) }
                } else {
                    val items = nodes.map { item(it) }
                    setState { it.copy(comments = withVotesSince(items, votesAtBuild), commentsLoading = false) }
                }
                env.localVotes.retireConfirmed(nodes.map { it.first }, env.session.current.username)
                if (d.sending.isNotEmpty()) watchSending()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(commentsLoading = false, commentsError = AppError.from(e, "post.comments")) }
            }
        }
    }

    private var sendingJob: Job? = null

    /**
     * Live test: a reply said "sending to the chain" for over 75 s after it landed, until hivemind returned it. The
     * chain itself (`database_api.find_comments`, [com.ecency.core.api.HiveApi.content]) is asked every
     * [SENDING_CHECK_MILLIS] for the replies still sending; one it holds shows as posted at once, and its local copy
     * stays on top until the discussion has it. Asked [SENDING_CHECKS] times at most (a transaction expires after 60 s).
     */
    private fun watchSending() {
        if (sendingJob?.isActive == true) return
        sendingJob = scope.launch {
            repeat(SENDING_CHECKS) {
                kotlinx.coroutines.delay(SENDING_CHECK_MILLIS)
                val sending = current.comments.filter { it.isSending }.map { it.key }
                if (sending.isEmpty()) return@launch
                val landed = sending.filter { k ->
                    try {
                        env.api.hive.content(k.author, k.permlink) != null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ErrorReports.quiet(e, "post.replyOnChain")
                        false
                    }
                }
                if (landed.isNotEmpty()) {
                    landed.forEach { env.api.posts.confirmLocalComment(it) }
                    // the discussion from the cache with the local copies again, now posted; a read already running
                    // (a pull to refresh) is never cancelled for it: it shows the confirmed copies itself, and a copy
                    // it missed is found again on the next check
                    if (commentsJob?.isActive != true) loadComments(refresh = false)
                }
            }
        }
    }

    private fun flatten(d: Discussion): List<Triple<Post, Int, Boolean>> = d.threads.flatMap { thread ->
        listOf(Triple(thread.comment, 1, false)) + thread.replies.map { Triple(it.comment, it.level, it.hasHiddenReplies) }
    }

    private fun card(post: Post, withSummary: Boolean): PostCard {
        val user = env.session.current.username
        val local = env.localVotes.get(post.key)
        return Cards.of(post, if (withSummary) env.renderer else NoSummary, user, local, env.proxyBase)
    }

    /** [items] with the local votes that changed after [since] applied; the other rows keep their instances. */
    private fun withVotesSince(items: List<CommentItem>, since: Map<PostKey, LocalVote>): List<CommentItem> {
        val local = env.localVotes.state.value
        val changed = Cards.changedKeys(since, local)
        if (changed.isEmpty()) return items
        val user = env.session.current.username
        return Cards.replaceChanged(items, changed, { it.key }) { it.copy(card = Cards.withVote(it.card, it.post, user, local[it.key])) }
    }

    /** Re-applies votes to the post and the comments in [changed] only; other rows keep their instances. */
    private fun applyVotes(changed: Set<PostKey>) {
        val user = env.session.current.username
        val local = env.localVotes.state.value
        setState { s ->
            val card = s.card?.let { c -> s.post?.takeIf { it.key in changed }?.let { Cards.withVote(c, it, user, local[it.key]) } ?: c }
            val comments = Cards.replaceChanged(s.comments, changed, { it.key }) {
                it.copy(card = Cards.withVote(it.card, it.post, user, local[it.key]))
            }
            if (card == s.card && comments === s.comments) s else s.copy(card = card, comments = comments)
        }
    }

    private companion object {
        /** RN src/constants/waves.ts hosts: their entries are waves, shown without a parent box. */
        val WAVES_HOSTS: Set<String> = com.ecency.core.api.waves.Waves.CONTAINER_HOSTS.toSet()

        /** Comments rendered and shown before the rest of a long discussion (about two screens). */
        const val FIRST_COMMENTS = 12

        /** Between chain checks of a reply still sending (a block is 3 s). */
        const val SENDING_CHECK_MILLIS = 3_000L

        /** Chain checks of a reply still sending: 75 s, past the 60 s a transaction can still land in. */
        const val SENDING_CHECKS = 25
    }

    /** Post headers need no summary or card image; skip that work. */
    private object NoSummary : PostBodyRenderer {
        override fun blocks(post: Post): List<Block> = emptyList()
        override fun summary(post: Post): String = ""
        override fun cardImage(post: Post): String? = null
    }
}
