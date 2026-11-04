package com.ecency.core.app

import com.ecency.core.l10n.L

/**
 * RN postInteractionHandler's link sheet for external links in post bodies (title "Link"): Copy, Open in Explore (with
 * its warning first), Open in System browser, Cancel. "Open in Explore" is offered only once the dApp browser can open
 * a URL ([exploreScreen] is a real screen, not the "coming soon" one).
 */
object LinkSheet {
    val TITLE: String get() = L.post.link // post.link
    val COPY: String get() = L.post.linkCopy // post.link_copy
    val OPEN_EXPLORE: String get() = L.post.linkOpenExplore // post.link_open_explore
    val OPEN_SYSTEM: String get() = L.post.linkOpenSystem // post.link_open_system
    val CANCEL: String get() = L.alert.cancel // alert.cancel
    val CONTINUE: String get() = L.alert.continue_ // alert.continue
    val COPIED: String get() = L.alert.copied // alert.copied
    val EXPLORE_WARNING_TITLE: String get() = L.post.linkExploreWarningTitle // post.link_explore_warning_title

    /** post.link_explore_warning_desc, then a blank line and the raw URL (RN's dash written as a full stop). */
    fun exploreWarning(url: String) =
        L.native.linkSheet.theExploreBrowserCan(url = url)

    /** The dApp browser for [url] when it is built and the link is a web page; null hides "Open in Explore". */
    fun exploreScreen(url: String): AppScreen? = exploreScreen(url) { AppScreen.of(RouteName.DAPP_BROWSER, it) }

    /** [exploreScreen] over a [resolve] of the dApp browser route (tests stand in for the built browser). */
    internal fun exploreScreen(url: String, resolve: (String) -> AppScreen): AppScreen? {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return null
        return resolve(url).takeIf { it !is AppScreen.Pending && it != AppScreen.Explore }
    }
}
