package com.ecency.core.app

import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.LinkTarget
import com.ecency.core.render.blocks.SafeTarget

object LinkCopy {
    /** A body link the app cannot open (Android's toast). */
    val UNSUPPORTED: String get() = L.native.link.unsupported

    /** iOS alert title for a link that cannot be opened. */
    val CANNOT_OPEN: String get() = L.native.link.cannotOpen

    /** iOS alert body for a link the app does not support. */
    val UNSUPPORTED_VERSION: String get() = L.native.link.unsupportedVersion
}

/** Where a tap goes. */
sealed interface Route {
    data class Post(val author: String, val permlink: String) : Route

    /** An account's blog. */
    data class Profile(val author: String) : Route

    /** A tag or community feed ([title] is "#tag" or the community name). */
    data class Feed(val source: FeedSource, val title: String) : Route

    /** http(s) only; the UI opens it in Custom Tabs / SFSafariViewController. */
    data class ExternalBrowser(val url: String) : Route

    /** An allowlisted player URL for the video sheet. */
    data class VideoPlayer(val embedUrl: String) : Route

    /**
     * A `hive://` or `ecency://` link in a body (RN isHiveUri: linkProcessor.handleLink): the UI hands it to the
     * auth link model ([AuthLinkModel.handle]), which asks before anything is signed or shared.
     */
    data class AppLink(val uri: String) : Route

    /**
     * An `#id` jump within the page being read (#2403): the post screen scrolls ([com.ecency.core.render.blocks.BlockAnchors])
     * before it routes anything; anywhere else nothing happens. Never a new screen.
     */
    data class InPage(val id: String) : Route

    /** Not handled by the spike, or rejected as unsafe; the UI does nothing (or says so). */
    data class Unsupported(val reason: String) : Route
}

/**
 * Body taps to app routes. Every value is validated again with [SafeTarget] even though the
 * block builder already did (defence in depth: targets also come from other sources later).
 */
object LinkRouter {
    fun route(target: LinkTarget): Route = when (target) {
        is LinkTarget.Post -> SafeTarget.post(target.author, target.permlink)?.let { Route.Post(it.author, it.permlink) }
            ?: unsafe("post")
        is LinkTarget.Author -> SafeTarget.account(target.author)?.let { Route.Profile(it) } ?: unsafe("account")
        is LinkTarget.Tag -> SafeTarget.tag(target.tag)?.let { Route.Feed(FeedSource.Ranked(sort(target.filter), it), "#$it") }
            ?: unsafe("tag")
        is LinkTarget.Community -> SafeTarget.community(target.community)
            ?.let { Route.Feed(FeedSource.Ranked(sort(target.filter), it), it) } ?: unsafe("community")
        is LinkTarget.Youtube -> SafeTarget.youtubeId(target.videoId)?.let { id ->
            val start = target.startSeconds.coerceAtLeast(0)
            val url = "https://www.youtube.com/embed/$id?autoplay=1" + (if (start > 0) "&start=$start" else "")
            SafeTarget.embed(url)?.let { Route.VideoPlayer(it) }
        } ?: unsafe("video")
        is LinkTarget.Video -> SafeTarget.embed(target.embedUrl)?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?.let { Route.VideoPlayer(it) } ?: unsafe("video")
        is LinkTarget.External -> external(target.url)
        is LinkTarget.AppUri -> appLink(target.uri)
        // a link to another post with a fragment opens that post (at its top)
        is LinkTarget.Anchor -> target.post?.let { route(it) } ?: Route.InPage(target.id)
    }

    /** An account's blog feed for [Route.Profile]. */
    fun profileFeed(author: String): FeedSource = FeedSource.Account(author, AccountPostsSort.BLOG)

    private fun external(url: String): Route {
        val checked = SafeTarget.external(url) as? LinkTarget.External ?: return unsafe("link")
        val u = SafeTarget.httpUrl(checked.url) ?: return unsafe("link")
        val scheme = u.substringBefore("://").lowercase()
        return if (scheme == "http" || scheme == "https") Route.ExternalBrowser(u) else unsafe("link")
    }

    /** Only the two schemes the link model knows, checked again (the target may come from elsewhere later). */
    private fun appLink(uri: String): Route {
        val checked = SafeTarget.external(uri) as? LinkTarget.AppUri ?: return unsafe("link")
        val scheme = checked.uri.substringBefore("://").lowercase()
        return if (scheme == "hive" || scheme == "ecency") Route.AppLink(checked.uri) else unsafe("link")
    }

    private fun sort(filter: String?): RankedSort = when (SafeTarget.filter(filter)) {
        "hot" -> RankedSort.HOT
        "created" -> RankedSort.CREATED
        "payout" -> RankedSort.PAYOUT
        else -> RankedSort.TRENDING
    }

    private fun unsafe(what: String) = Route.Unsupported("invalid $what")
}
