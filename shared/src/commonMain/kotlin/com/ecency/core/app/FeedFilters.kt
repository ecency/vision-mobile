package com.ecency.core.app

import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.RankedSort
import com.ecency.core.api.model.Post
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.l10n.L

/** The home feed's filters (RN FEED_SCREEN_FILTER_MAP) with their en-US labels. */
enum class FeedFilter(val key: String, private val labelText: () -> String) {
    FRIENDS("friends", { L.waves.following }),
    COMMUNITIES("communities", { L.home.communities }),
    TRENDING("trending", { L.home.trending }),
    HOT("hot", { L.home.hot }),
    CREATED("created", { L.home.new }),
    BLOG("blog", { L.home.myBlog }),
    POSTS("posts", { L.home.myPosts }),
    COMMENTS("comments", { L.home.myComments }),
    ;

    val label: String get() = labelText()

    /** RN pill text. */
    val title: String get() = label.uppercase()

    /** What the tab pages through for [username] (RN useFeedQuery); null when it needs an account and none is given. */
    fun source(username: String?): FeedSource? = when (this) {
        TRENDING -> FeedSource.Ranked(RankedSort.TRENDING)
        HOT -> FeedSource.Ranked(RankedSort.HOT)
        CREATED -> FeedSource.Ranked(RankedSort.CREATED)
        COMMUNITIES -> if (username == null) null else FeedSource.myCommunities()
        FRIENDS -> username?.let { FeedSource.Account(it, AccountPostsSort.FEED) }
        BLOG -> username?.let { FeedSource.Account(it, AccountPostsSort.BLOG) }
        POSTS -> username?.let { FeedSource.Account(it, AccountPostsSort.POSTS) }
        COMMENTS -> username?.let { FeedSource.Account(it, AccountPostsSort.COMMENTS) }
    }

    companion object {
        fun of(key: String): FeedFilter? = entries.firstOrNull { it.key == key }
    }
}

/**
 * RN customTabsReducer `mainTabs` and the customise sheet's rules: signed in, the saved tabs (default
 * Following, Communities, Trending, Hot); signed out, Trending, Hot, New. At least [MIN_TABS], no maximum.
 */
object FeedTabs {
    const val PREFS_KEY = "customTabs.mainTabs"
    const val MIN_TABS = 3
    val TOO_FEW: String get() = L.alert.wrongFilterCount

    val DEFAULT = listOf(FeedFilter.FRIENDS, FeedFilter.COMMUNITIES, FeedFilter.TRENDING, FeedFilter.HOT)
    val GUEST = listOf(FeedFilter.TRENDING, FeedFilter.HOT, FeedFilter.CREATED)

    fun tabs(prefs: Prefs, loggedIn: Boolean): List<FeedFilter> = if (!loggedIn) GUEST else saved(prefs)

    fun saved(prefs: Prefs): List<FeedFilter> {
        val keys = prefs.getString(PREFS_KEY)?.split(',')?.mapNotNull { FeedFilter.of(it.trim()) }?.distinct().orEmpty()
        return if (keys.size >= MIN_TABS) keys else DEFAULT
    }

    /** RN APPLY: null when saved, else the alert text. */
    fun save(prefs: Prefs, tabs: List<FeedFilter>): String? {
        val distinct = tabs.distinct()
        if (distinct.size < MIN_TABS) return TOO_FEW
        prefs.putString(PREFS_KEY, distinct.joinToString(",") { it.key })
        return null
    }

    /**
     * The customise sheet's list: selected filters first in their order, then the rest in map order (RN
     * SelectionList).
     */
    fun sheetOrder(selected: List<FeedFilter>): List<FeedFilter> = selected + FeedFilter.entries.filter { it !in selected }
}

/** RN application.nsfw: '0' always show, '1' always warn (default), '2' always hide. */
enum class NsfwMode(val value: String, private val labelText: () -> String) {
    SHOW("0", { L.settings.nsfw.alwaysShow }),
    WARN("1", { L.settings.nsfw.alwaysWarn }),
    HIDE("2", { L.settings.nsfw.alwaysHide }),
    ;

    val label: String get() = labelText()

    companion object {
        const val PREFS_KEY = "nsfw"

        fun of(prefs: Prefs): NsfwMode = entries.firstOrNull { it.value == prefs.getString(PREFS_KEY) } ?: WARN

        /** RN filterNsfwPost's test. */
        fun isNsfw(post: Post): Boolean = post.parentPermlink == "nsfw" || "nsfw" in post.metadata.tags

        /** RN NSFW_IMAGE, shown in place of the card image when warning. */
        const val NSFW_IMAGE = "https://i.ecency.com/DQmZ1jW4p7o5GyoqWyCib1fSLE2ftbewsMCt2GvbmT9kmoY/nsfw_3x.png"
    }
}

/** RN postsListContainer promoted injection: the i-th kept promoted post at index i * 4 + 3, while the list is long enough. */
object Promoted {
    fun <T> inject(feed: List<T>, promoted: List<T>, key: (T) -> Any, muted: (T) -> Boolean = { false }): List<T> {
        if (promoted.isEmpty()) return feed
        val keys = feed.map(key).toHashSet()
        // not muted, not in the feed, and each promoted entry once
        val kept = promoted.filter { !muted(it) && keys.add(key(it)) }
        val result = feed.toMutableList()
        kept.forEachIndexed { i, p ->
            val index = i * 4 + 3
            if (result.size > index) result.add(index, p)
        }
        return result
    }
}

/** RN useMutedReveal: muted cards revealed this session (in memory, as RN). */
class MutedReveals {
    private val revealed = mutableSetOf<com.ecency.core.api.model.PostKey>()
    private val flaggedWaves = mutableSetOf<com.ecency.core.api.model.PostKey>()

    /**
     * A wave the waves API flagged explicit, seen in a list this session (review L8): its thread screen covers its
     * media too until it is revealed. A wave opened by a link was never listed, so it is not known here.
     */
    fun markFlaggedWave(key: com.ecency.core.api.model.PostKey) {
        flaggedWaves += key
    }

    /** The post screen of [key] covers its body's media: a flagged wave under "Always warn", not revealed yet. */
    fun coversWave(key: com.ecency.core.api.model.PostKey, mode: NsfwMode): Boolean =
        mode == NsfwMode.WARN && key in flaggedWaves && key !in revealed

    fun isRevealed(key: com.ecency.core.api.model.PostKey): Boolean = key in revealed

    fun reveal(key: com.ecency.core.api.model.PostKey) {
        revealed += key
    }

    companion object {
        /** RN postCardContent _mutedText for a [com.ecency.core.api.model.ModerationReason]. */
        fun text(reason: com.ecency.core.api.model.ModerationReason?, community: String?): String = when (reason) {
            com.ecency.core.api.model.ModerationReason.DOWNVOTED -> L.post.mutedDownvoted
            com.ecency.core.api.model.ModerationReason.LOW_TRUST -> L.post.mutedLowTrust
            else -> if (community?.startsWith("hive-") == true) L.post.communityMuted else L.post.muted
        }

        val REVEAL: String get() = L.post.mutedReveal
    }
}

/**
 * RN useImageReveal and utils/revealedImages: with "Show Images" off a card's content image is not loaded at all;
 * "Tap to load image" reveals that one image for the rest of the session (in memory, as RN). The NSFW placeholder
 * and cards without an image are never gated.
 */
class ImageReveals {
    private val revealed = mutableSetOf<String>()

    fun isRevealed(url: String): Boolean = url in revealed

    fun reveal(url: String) {
        revealed += url
    }

    /** Whether [card]'s image is held back for a reader with [showImages]. */
    fun hides(card: PostCard, showImages: Boolean): Boolean {
        val image = card.image ?: return false
        return !showImages && !card.isNsfw && image !in revealed
    }
}
