package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import com.ecency.core.l10n.TimeUnit
import com.ecency.core.render.ImageProxy
import com.ecency.core.render.blocks.isHiveAccountName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.round

/** Everything a feed card shows, precomputed off the main thread. */
data class PostCard(
    val key: PostKey,
    val author: String,
    /** Proxy avatar URL ("small"), empty for an invalid account name. */
    val avatarUrl: String,
    val reputation: Int,
    val title: String,
    val summary: String,
    /** Proxied card image, or null (the UI shows no image). */
    val image: String?,
    /** Chain "YYYY-MM-DDTHH:MM:SS" (UTC). */
    val created: String,
    val createdEpochMillis: Long,
    val category: String,
    val community: String?,
    val communityTitle: String?,
    /** Shown payout in HBD, capped at max_accepted_payout as the RN app does. */
    val payout: Double,
    /** "$1.234" */
    val payoutText: String,
    /** Payout declined: the UI strikes [payoutText] through. */
    val isPayoutDeclined: Boolean,
    val votes: Int,
    val comments: Int,
    val votedByMe: Boolean,
    val downvotedByMe: Boolean,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val rebloggedBy: List<String>,
    /** Cross-post: the original's author, else null. */
    val crossPostAuthor: String?,
    /** RN "PROMOTED" (promoted-entries injected into the feed). */
    val isPromoted: Boolean = false,
    /** RN "POLL" label and chart icon. */
    val hasPoll: Boolean = false,
    /** NSFW under the "Always warn" setting: the card shows RN's NSFW placeholder image. */
    val isNsfw: Boolean = false,
    /** RN mute hint text when [isMuted]. */
    val mutedText: String? = null,
    val reblogs: Int = 0,
    val isPinnedBlog: Boolean = false,
    /** Cross-post wrapper: RN "cross post by {username} in {community}". */
    val crossPostLabel: String? = null,
    /** RN EcencySourceBadge on cards and headers: json_metadata.app's first segment starts with "ecency". */
    val isFromEcency: Boolean = false,
    /** RN "Author disclosed AI usage" (json_metadata.ai_tools). */
    val aiDisclosed: Boolean = false,
) {
    /** Without the reading fields (Swift sees no default arguments; keeps older callers and tests compiling). */
    constructor(
        key: PostKey, author: String, avatarUrl: String, reputation: Int, title: String, summary: String, image: String?,
        created: String, createdEpochMillis: Long, category: String, community: String?, communityTitle: String?,
        payout: Double, payoutText: String, isPayoutDeclined: Boolean, votes: Int, comments: Int, votedByMe: Boolean,
        downvotedByMe: Boolean, isPinned: Boolean, isMuted: Boolean, rebloggedBy: List<String>, crossPostAuthor: String?,
    ) : this(
        key, author, avatarUrl, reputation, title, summary, image, created, createdEpochMillis, category, community, communityTitle,
        payout, payoutText, isPayoutDeclined, votes, comments, votedByMe, downvotedByMe, isPinned, isMuted, rebloggedBy, crossPostAuthor,
        false, false, false, null, 0, false, null,
    )

    /** RN `_featuredText`: "PROMOTED", "POLL" or both joined with " | ". */
    val featuredText: String?
        get() = listOfNotNull(L.post.promoted.takeIf { isPromoted }, L.post.poll.takeIf { hasPoll }).joinToString(" | ").ifEmpty { null }

    companion object {
        /**
         * The proxy avatar of [account]. After a profile edit on this device the URL carries RN's cache stamp
         * (`?stamp=<time>`, [AvatarCacheStamps]), so every avatar of that account loads the new picture.
         */
        fun avatarUrl(account: String, proxyBase: String = ImageProxy.DEFAULT_BASE, size: String = "small"): String {
            if (!isHiveAccountName(account)) return ""
            val base = "$proxyBase/u/$account/avatar/${avatarSize(size)}"
            return AvatarCacheStamps.of(account)?.let { "$base?stamp=$it" } ?: base
        }

        /**
         * The image service's avatar sizes are "small", "medium" and "large", the names the website requests too,
         * so the app and the site share one CDN cache entry per account and size. Anything else (a number, a
         * path) becomes "small".
         */
        fun avatarSize(size: String): String = if (size in AVATAR_SIZES) size else "small"

        private val AVATAR_SIZES = setOf("small", "medium", "large")

        fun formatPayout(value: Double): String {
            val thousandths = round(value * 1000).toLong().coerceAtLeast(0)
            return "$" + (thousandths / 1000) + "." + (thousandths % 1000).toString().padStart(3, '0')
        }

        internal fun epochMillis(chainTime: String): Long = try {
            ChainTime.toEpochSeconds(chainTime) * 1000
        } catch (e: Exception) {
            0
        }
    }
}

/**
 * A vote this device broadcast (or is broadcasting); overrides the server's view until it catches up.
 * [estimate] moves the shown payout (RN voteCacheUtils); null when the vote sheet's estimate was not read.
 */
data class LocalVote(val weight: Int, val pending: Boolean, val estimate: VoteEstimate? = null) {
    /** Without an estimate (Swift sees no default arguments). */
    constructor(weight: Int, pending: Boolean) : this(weight, pending, null)
}

/** What a vote is worth: [value] in HBD and [rshares], both negative for a downvote and zero for an unvote. */
data class VoteEstimate(val value: Double, val rshares: Long) {
    companion object {
        val NONE = VoteEstimate(0.0, 0)
    }
}

/** How a vote changes what a card or post shows. [payout] is the post's total payout with the vote applied. */
data class VoteView(val votedByMe: Boolean, val downvotedByMe: Boolean, val votes: Int, val payout: Double)

/**
 * Votes made on this device, keyed by post. Feeds and posts combine them with what the API
 * returned, so an optimistic vote shows everywhere at once and a rollback undoes it everywhere.
 */
class LocalVotes {
    private val votes = MutableStateFlow<Map<PostKey, LocalVote>>(emptyMap())
    val state: StateFlow<Map<PostKey, LocalVote>> = votes.asStateFlow()

    fun set(key: PostKey, vote: LocalVote) = votes.update { it + (key to vote) }

    fun remove(key: PostKey) = votes.update { it - key }

    fun get(key: PostKey): LocalVote? = votes.value[key]

    fun clear() {
        votes.value = emptyMap()
    }

    /**
     * Retires the estimate of each landed vote that [posts] (fresh server data, already stored by the caller) hold on
     * chain: the server's payout counts the vote from then on, so a later change of it from another client shows as
     * the server has it instead of the spent estimate being added again.
     */
    fun retireConfirmed(posts: Collection<Post>, username: String?) {
        if (username == null) return
        votes.update { map ->
            var out = map
            for (post in posts) {
                val local = map[post.key] ?: continue
                val estimate = local.estimate ?: continue
                if (local.pending || !sameVote(post.voteOf(username)?.rshares ?: 0, estimate.rshares)) continue
                out = out + (post.key to local.copy(estimate = null))
            }
            out
        }
    }

    companion object {
        /** The server's view of [post] for [username], with [local] applied on top. */
        fun view(post: Post, username: String?, local: LocalVote?): VoteView {
            val server = username?.let { post.voteOf(it) }
            val serverUp = server != null && server.rshares > 0
            val serverDown = server != null && server.rshares < 0
            val count = post.stats.totalVotes.coerceAtLeast(post.activeVotes.size)
            if (local == null) return VoteView(serverUp, serverDown, count, post.totalPayout)
            val hadVote = server != null
            val hasVote = local.weight != 0
            val adjusted = count + when {
                hasVote && !hadVote -> 1
                !hasVote && hadVote -> -1
                else -> 0
            }
            val payout = (post.totalPayout + payoutDelta(post, server?.rshares ?: 0, local.estimate)).coerceAtLeast(0.0)
            return VoteView(local.weight > 0, local.weight < 0, adjusted.coerceAtLeast(0), payout)
        }

        /**
         * RN applyVoteToPost: what the local vote adds to the server's payout, the estimate less the share of the
         * reader's vote the server already counts ([serverRshares], RN calculateVoteReward). Nothing once the
         * server holds the vote itself (its rshares are the estimate's, or none for an unvote: RN skips a voter
         * record without its optimistic marker), so fresh chain data is never counted twice ([retireConfirmed] then
         * drops the estimate for good); nothing for a post already paid out or one that declined rewards (a vote
         * adds nothing it pays).
         */
        internal fun payoutDelta(post: Post, serverRshares: Long, estimate: VoteEstimate?): Double {
            if (estimate == null || post.isPaidout || post.isDeclinedPayout) return 0.0
            if (sameVote(serverRshares, estimate.rshares)) return 0.0
            val total = post.activeVotes.sumOf { it.rshares.toDouble() }
            val share = if (serverRshares == 0L || total == 0.0) 0.0 else serverRshares * post.totalPayout / total
            return estimate.value - share
        }

        /** The chain's rshares for the vote match the estimate (within 1 %: vests can move between read and vote). */
        internal fun sameVote(server: Long, estimate: Long): Boolean {
            if (estimate == 0L) return server == 0L
            if ((server > 0) != (estimate > 0) || server == 0L) return false
            return abs(server - estimate) <= abs(estimate) / 100
        }
    }
}

internal object Cards {
    fun of(
        post: Post,
        renderer: PostBodyRenderer,
        username: String?,
        local: LocalVote?,
        proxyBase: String,
        promoted: Boolean = false,
        nsfwWarn: Boolean = false,
    ): PostCard {
        val vote = LocalVotes.view(post, username, local)
        val shown = shownPayout(post, vote.payout)
        val original = post.crossPostOriginal
        val display = original ?: post
        return PostCard(
            key = post.key,
            author = post.author,
            avatarUrl = PostCard.avatarUrl(post.author, proxyBase),
            reputation = post.authorReputation,
            title = display.title,
            summary = renderer.summary(display),
            image = renderer.cardImage(display),
            created = post.created,
            createdEpochMillis = PostCard.epochMillis(post.created),
            category = post.category,
            community = post.community,
            communityTitle = post.communityTitle,
            payout = shown,
            payoutText = PostCard.formatPayout(shown),
            isPayoutDeclined = post.isDeclinedPayout,
            votes = vote.votes,
            comments = post.children,
            votedByMe = vote.votedByMe,
            downvotedByMe = vote.downvotedByMe,
            isPinned = post.stats.isPinned,
            isMuted = post.isMuted,
            rebloggedBy = post.rebloggedBy,
            crossPostAuthor = original?.author,
            isPromoted = promoted,
            hasPoll = com.ecency.core.api.PostPoll.fromMetadata(display.metadata.rawJson) != null,
            isNsfw = nsfwWarn && NsfwMode.isNsfw(display),
            mutedText = if (post.isMuted) MutedReveals.text(post.moderationReason, post.community) else null,
            reblogs = post.reblogs,
            isPinnedBlog = post.stats.isPinnedBlog == true,
            crossPostLabel = if (original != null) L.post.crossPost(username = post.author, community = post.communityTitle ?: post.community ?: post.category) else null,
            isFromEcency = PostBadges.headerFromEcency(post.metadata.app),
            aiDisclosed = post.metadata.aiDisclosed,
        )
    }

    /** Posts whose local vote differs between [before] and [after]. */
    fun changedKeys(before: Map<PostKey, LocalVote>, after: Map<PostKey, LocalVote>): Set<PostKey> {
        if (before === after) return emptySet()
        val changed = HashSet<PostKey>()
        for ((k, v) in after) if (before[k] != v) changed += k
        for (k in before.keys) if (k !in after) changed += k
        return changed
    }

    /**
     * [items] with only the entries whose key is in [changed] replaced by [update] (null keeps the
     * entry). Returns [items] itself when nothing changed, so unchanged lists keep their identity and
     * every other element keeps its instance.
     */
    fun <T> replaceChanged(items: List<T>, changed: Set<PostKey>, key: (T) -> PostKey, update: (T) -> T?): List<T> {
        if (changed.isEmpty()) return items
        var out: MutableList<T>? = null
        items.forEachIndexed { i, item ->
            if (key(item) !in changed) return@forEachIndexed
            val next = update(item) ?: return@forEachIndexed
            if (next == item) return@forEachIndexed
            if (out == null) out = items.toMutableList()
            out!![i] = next
        }
        return out ?: items
    }

    /** Re-applies votes to an existing card without re-rendering its summary. */
    fun withVote(card: PostCard, post: Post, username: String?, local: LocalVote?): PostCard {
        val vote = LocalVotes.view(post, username, local)
        val shown = shownPayout(post, vote.payout)
        return card.copy(
            votes = vote.votes, votedByMe = vote.votedByMe, downvotedByMe = vote.downvotedByMe,
            payout = shown, payoutText = PostCard.formatPayout(shown),
        )
    }

    /** [payout] capped at max_accepted_payout, as the RN app shows it. */
    private fun shownPayout(post: Post, payout: Double): Double {
        val maxPayout = Post.assetAmount(post.maxAcceptedPayout)
        return if (maxPayout > 0 && payout >= maxPayout) maxPayout else payout
    }
}

/**
 * Ages for cards and comments in the app language: CLDR's narrow durations and "now" ("now", "5m", "3h", "2d",
 * "4w", "1y" in en-US; "5 Min.", "3 Std." in de-DE; "3 ч" in ru-RU).
 */
object TimeAgo {
    fun short(epochMillis: Long, nowMillis: Long): String {
        val seconds = ((nowMillis - epochMillis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> LocaleFormat.now()
            seconds < 3600 -> LocaleFormat.age(seconds / 60, TimeUnit.MINUTE)
            seconds < 86_400 -> LocaleFormat.age(seconds / 3600, TimeUnit.HOUR)
            seconds < 7 * 86_400 -> LocaleFormat.age(seconds / 86_400, TimeUnit.DAY)
            seconds < 365 * 86_400 -> LocaleFormat.age(seconds / (7 * 86_400), TimeUnit.WEEK)
            else -> LocaleFormat.age(seconds / (365 * 86_400), TimeUnit.YEAR)
        }
    }
}

/** RN avatarCacheStamp per account, process-wide: set when a profile edit went through here. Thread-safe. */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
object AvatarCacheStamps {
    private val stamps = kotlin.concurrent.atomics.AtomicReference<Map<String, Long>>(emptyMap())

    fun of(account: String): Long? = stamps.load()[account]

    internal fun set(account: String, at: Long) {
        while (true) {
            val old = stamps.load()
            if (stamps.compareAndSet(old, old + (account to at))) return
        }
    }

    /** Forgets every stamp (tests). */
    internal fun clear() = stamps.store(emptyMap())
}

/** RN EcencySourceBadge and the AI disclosure: when they show and their copy. */
object PostBadges {
    val SOURCE_ECENCY: String get() = L.post.sourceEcency // post.source_ecency
    val AI_DISCLOSED: String get() = L.aiUsage.disclosed // ai_usage.disclosed

    /** Cards, headers and comments (RN postCardHeader): the first segment of `app` starts with "ecency"; none without an app. */
    fun headerFromEcency(app: String?): Boolean = app.orEmpty().substringBefore('/').lowercase().startsWith("ecency")

    /** The post screen's "posted via" line (RN postDisplayView): no app counts as Ecency, and "contains" is enough. */
    fun footerFromEcency(app: String?): Boolean = (app?.substringBefore('/')?.takeIf { it.isNotEmpty() } ?: "ecency").lowercase().contains("ecency")

    /** RN post.posted_by "Posted by {username} via {appname} " (the app's first segment capitalized, else Ecency), then the age. */
    fun postedBy(post: Post, nowMillis: Long): String {
        val app = post.metadata.app?.substringBefore('/')?.takeIf { it.isNotEmpty() }?.let { it.replaceFirstChar { c -> c.uppercaseChar() } } ?: "Ecency"
        return L.post.postedBy(username = post.author, appname = app) + (PayoutDetails.fromNow(post.created, nowMillis) ?: "")
    }
}
