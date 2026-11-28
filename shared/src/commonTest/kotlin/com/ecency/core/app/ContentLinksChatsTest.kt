package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** #3753: ecency.com/chats links (pasted into Discord and elsewhere) open the conversation in the app. */
class ContentLinksChatsTest {
    private val id = "sk6xregduj81pj5hx67a3r8ath"

    @Test
    fun aChatLinkOpensTheChatsTabWithThatConversation() {
        val entry = NavEntry(AppTab.CHATS, listOf(AppScreen.ChatThread(id)))
        assertEquals(entry, ContentLinks.entry("https://ecency.com/chats/$id"))
        assertEquals(entry, ContentLinks.entry("https://www.ecency.com/chats/$id/"))
        assertEquals(entry, ContentLinks.entry("https://ecency.com/chats/$id?utm_source=discord"))
    }

    @Test
    fun bareChatsOpensTheChatsTab() {
        assertEquals(NavEntry.tab(AppTab.CHATS), ContentLinks.entry("https://ecency.com/chats"))
        assertEquals(NavEntry.tab(AppTab.CHATS), ContentLinks.entry("https://ecency.com/chats/"))
    }

    @Test
    fun anythingButAMattermostChannelIdStaysOnTheWebsite() {
        for (bad in listOf(
            "x", "..", "%2e%2e", "SK6XREGDUJ81PJ5HX67A3R8ATH", "sk6xregduj81pj5hx67a3r8at", "sk6xregduj81pj5hx67a3r8athx",
            "sk6xregduj81pj5hx67a3r8at-", "sk6xregduj81pj5hx67a3r8at_",
        )) {
            assertNull(ContentLinks.entry("https://ecency.com/chats/$bad"), bad)
            assertEquals("https://ecency.com/chats/$bad", ContentLinks.browserFallback("https://ecency.com/chats/$bad"), bad)
        }
        assertNull(ContentLinks.entry("https://ecency.com/chats/../wallet"))
        assertNull(ContentLinks.entry("https://ecency.com/chats/$id/extra"))
        assertNull(ContentLinks.entry("https://ecency.com/chatsx"))
        assertNull(ContentLinks.browserFallback("https://ecency.com/chats/$id"))
    }

    @Test
    fun onlyEcencysOwnChats() {
        // Chats are Ecency's; another Hive frontend's /chats is not this app's conversation
        assertNull(ContentLinks.entry("https://peakd.com/chats/$id"))
        assertNull(ContentLinks.entry("https://evil.example/chats/$id"))
        assertNull(ContentLinks.entry("http://ecency.com/chats/$id"))
    }

    @Test
    fun signedOutTheLoginGateComesFirst() {
        val entry = assertNotNull(ContentLinks.entry("https://ecency.com/chats/$id"), "the link opens in the app")
        val out = NavState().open(entry, loggedIn = false)
        assertEquals(AppTab.CHATS, out.tab)
        assertEquals(listOf<AppScreen>(AppScreen.Login), out.screens)
        val signedIn = NavState().open(entry, loggedIn = true)
        assertEquals(AppTab.CHATS, signedIn.tab)
        assertEquals(listOf<AppScreen>(AppScreen.ChatThread(id)), signedIn.screens)
    }

    @Test
    fun signedOutTheConversationOpensAfterSignIn() {
        val entry = assertNotNull(ContentLinks.entry("https://ecency.com/chats/$id"))
        val out = NavState().open(entry, loggedIn = false)
        // Android: the login screen is on the stack and the sign-in replaces it
        val android = out.afterAccountChange(signedIn = true)
        assertEquals(AppTab.CHATS, android.tab)
        assertEquals(listOf<AppScreen>(AppScreen.ChatThread(id)), android.screens, "the conversation, not the Chats list")
        assertNull(android.afterLogin, "opened once")
        // iOS: the login screen is a sheet, popped off the stack before it shows
        val ios = out.popTo(0).afterAccountChange(signedIn = true)
        assertEquals(AppTab.CHATS, ios.tab)
        assertEquals(listOf<AppScreen>(AppScreen.ChatThread(id)), ios.screens)
        // the reader left the thread; a later sign-in (another account) opens nothing again
        assertEquals(emptyList(), ios.popTo(0).afterAccountChange(signedIn = true).screens)
    }

    @Test
    fun aCancelledLoginLeavesNoConversationForLater() {
        val entry = assertNotNull(ContentLinks.entry("https://ecency.com/chats/$id"))
        val cancelled = NavState().open(entry, loggedIn = false).popTo(0).dropAfterLogin()
        assertNull(cancelled.afterLogin)
        val later = cancelled.afterAccountChange(signedIn = true)
        assertEquals(AppTab.CHATS, later.tab)
        assertEquals(emptyList(), later.screens, "a sign-in later (accounts sheet, another link) does not open the old thread")
    }

    @Test
    fun aChatLinkInContentOpensTheConversationOnTop() {
        // tapped in a chat message or a post: the conversation goes on top, the open tab stays
        assertEquals(NavEntry.screen(AppScreen.ChatThread(id)), ContentLinks.contentEntry("https://ecency.com/chats/$id"))
        assertNull(ContentLinks.contentEntry("https://ecency.com/chats"))
    }
}
