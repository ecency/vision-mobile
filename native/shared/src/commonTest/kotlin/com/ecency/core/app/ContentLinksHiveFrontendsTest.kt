package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Links to other Hive frontends (peakd, hive.blog, inleo...) open in the app as ecency.com links do (RN
 * deepLinkParser via postUrlParser): a chat message, a QR code or a pasted link to a post on any of them is the
 * same post.
 */
class ContentLinksHiveFrontendsTest {
    private fun screens(url: String) = ContentLinks.entry(url)?.screens

    @Test
    fun aPostOnAnotherHiveFrontendOpensInTheApp() {
        for (url in listOf(
            "https://peakd.com/@alice/my-post", "https://www.peakd.com/@alice/my-post", "https://hive.blog/@alice/my-post",
            "https://hive.blog/hive-123456/@alice/my-post", "https://peakd.com/photography/@alice/my-post?ref=x",
            "https://inleo.io/@alice/my-post", "https://leofinance.io/@alice/my-post#comments", "https://Snapie.io/@alice/my-post",
        )) {
            assertEquals(listOf<AppScreen>(AppScreen.Post("alice", "my-post")), screens(url), url)
        }
    }

    @Test
    fun profilesAndTagsOnAnotherHiveFrontendOpenInTheApp() {
        assertEquals(listOf<AppScreen>(AppScreen.Profile("alice")), screens("https://peakd.com/@alice"))
        assertEquals(listOf<AppScreen>(AppScreen.Profile("alice", "wallet")), screens("https://hive.blog/@alice/wallet"))
        assertEquals(listOf<AppScreen>(AppScreen.TagResult("photography", "trending")), screens("https://peakd.com/trending/photography"))
        assertEquals(listOf<AppScreen>(AppScreen.Community("hive-123456", "created")), screens("https://hive.blog/created/hive-123456"))
    }

    @Test
    fun otherSitesAndLookalikesStayInTheBrowser() {
        for (url in listOf(
            "https://medium.com/@alice/my-post", "https://peakd.com.evil.example/@alice/my-post", "https://evilpeakd.com/@alice/my-post",
            "http://peakd.com/@alice/my-post", "https://peakd.com/faq", "https://peakd.com/waves/alice/wave-1",
            "https://peakd.com/@alice/Some.Thing",
        )) {
            assertNull(ContentLinks.entry(url), url)
        }
        // only first-party pages fall back to the in-app browser as a handed-over link
        assertNull(ContentLinks.browserFallback("https://peakd.com/@alice/my-post"))
    }
}

class ChatLinkCardOpenTest {
    @Test
    fun aPostCardOpensThePostInTheAppWhateverFrontendTheLinkWasOn() {
        val card = ChatLinkCard("https://peakd.com/hive-1/@alice/my-post", "T", "", null, com.ecency.core.api.model.PostKey("alice", "my-post"))
        assertEquals(listOf<AppScreen>(AppScreen.Post("alice", "my-post")), ContentLinks.entry(card.openUrl)?.screens)
        // a plain page card opens its link
        assertEquals("https://example.com/a", ChatLinkCard("https://example.com/a", "T", "", null).openUrl)
    }
}

/** A link tapped in a chat message or a link card ([ContentLinks.contentEntry]). */
class ContentLinksInContentTest {
    @Test
    fun aReplyNamedInTheFragmentOpensTheReply() {
        assertEquals(listOf<AppScreen>(AppScreen.Post("bob", "re-my-post")),
            ContentLinks.contentEntry("https://peakd.com/photography/@alice/my-post#@bob/re-my-post")?.screens)
        assertEquals(listOf<AppScreen>(AppScreen.Post("alice", "my-post")), ContentLinks.contentEntry("https://ecency.com/@alice/my-post#comments")?.screens)
        // the fragment of a site the app does not open is not read
        assertNull(ContentLinks.contentEntry("https://example.com/@alice/my-post#@bob/re-my-post"))
    }

    @Test
    fun aLinkThatWouldChangeTabsLeavesTheConversationOpen() {
        // the Waves composer link switches tabs and clears the stack: the chat would close, its draft lost
        assertNotNull(ContentLinks.entry("https://ecency.com/waves?text=hello")?.tab)
        assertNull(ContentLinks.contentEntry("https://ecency.com/waves?text=hello"))
        // a wave's thread is a screen over the chat
        assertEquals(1, ContentLinks.contentEntry("https://ecency.com/waves/alice/wave-1")?.screens?.size)
    }
}
