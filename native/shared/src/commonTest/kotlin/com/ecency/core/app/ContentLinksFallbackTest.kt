package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pages the app is handed by App Links and universal links (README "Login and accounts"). */
class ContentLinksFallbackTest {
    @Test
    fun profilePagesAreNeverPostLookups() {
        for (page in listOf("feed", "rss", "rss.xml", "insights", "payout", "activities", "engine", "spk", "Feed")) {
            assertEquals(listOf<AppScreen>(AppScreen.Profile("alice", "")), ContentLinks.entry("https://ecency.com/@alice/$page")?.screens, page)
        }
        // a real permlink still opens the post
        assertEquals(1, ContentLinks.entry("https://ecency.com/@alice/my-post-2024")?.screens?.size)
        // anything that is no permlink is not a post
        assertNull(ContentLinks.entry("https://ecency.com/@alice/Some.Thing"))
    }

    @Test
    fun unclaimedFirstPartyPagesOpenInTheBrowser() {
        for (url in listOf(
            "https://ecency.com/faq", "https://ecency.com/@alice/Some.Thing", "https://www.ecency.com/proposals",
            "https://alpha.ecency.com/@alice", "https://ecency.com/witnesses",
        )) {
            assertEquals(url, ContentLinks.browserFallback(url), url)
        }
    }

    @Test
    fun claimedForeignOrInsecureLinksHaveNoFallback() {
        for (url in listOf(
            "https://ecency.com/@alice", "https://ecency.com/@alice/my-post", "https://ecency.com/trending/hive",
            "https://ecency.com/@alice/feed", "https://ecency.com/waves/alice/wave-1",
            "http://ecency.com/faq", "https://ecency.com.evil.example/faq", "https://peakd.com/@alice", "ecency://faq",
        )) {
            assertNull(ContentLinks.browserFallback(url), url)
        }
    }
}
