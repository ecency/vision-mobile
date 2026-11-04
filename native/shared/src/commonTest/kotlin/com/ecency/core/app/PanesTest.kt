package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two panes on a wide window (README "iPhone Duo", #3601): on Feed, Chats and Notifications the list stays on the
 * left and what it opens shows on the right, as in Mail. A phone-sized window never gets them.
 */
class PanesTest {
    private val post = AppScreen.Post("alice", "hello")
    private val other = AppScreen.Post("bob", "world")
    private val profile = AppScreen.Profile("carol")
    private val thread = AppScreen.ChatThread("channel-1")
    private val editor = AppScreen.Editor(EditorArgs(EditorKind.NEW_POST))

    private fun screens(items: List<NavItem>) = items.map { it.screen }

    @Test
    fun onlyAWideWindowHasRoomForTwoPanes() {
        // every iPhone and iPhone Duo's displays: the outer one, the inner one held upright, then sideways
        for (width in listOf(375, 402, 440, 466, 669)) assertFalse(NavState.hasRoomForPanes(width.toDouble()), "$width pt")
        assertTrue(NavState.hasRoomForPanes(951.0), "the inner display held sideways")
    }

    @Test
    fun aNarrowWindowPushesAsBefore() {
        val s = NavState().pushIn(post, loggedIn = true, wide = false)
        assertEquals(listOf<AppScreen>(post), s.screens)
        assertTrue(s.pane.isEmpty())
    }

    @Test
    fun aPostOpenedFromTheFeedShowsBesideIt() {
        val s = NavState().pushIn(post, loggedIn = true, wide = true)
        assertTrue(s.atRoot, "the list stays")
        assertEquals(listOf<AppScreen>(post), screens(s.pane))
        // the next post from the list takes its place, as in Mail
        val next = s.pushIn(other, loggedIn = true, wide = true, fromList = true)
        assertEquals(listOf<AppScreen>(other), screens(next.pane))
    }

    @Test
    fun whatAPostOpensStaysInItsPaneAndGoesBackThere() {
        val s = NavState().pushIn(post, loggedIn = true, wide = true).pushIn(profile, loggedIn = true, wide = true)
        assertEquals(listOf(post, profile), screens(s.pane))
        assertEquals(listOf<AppScreen>(post), screens(s.popPaneTo(1).pane))
        // the system back pops the pane, then the list is all that is left
        val back = s.back()!!
        assertEquals(listOf<AppScreen>(post), screens(back.pane))
        assertTrue(back.back()!!.pane.isEmpty())
    }

    @Test
    fun aPostThePaneOpensGoesOnInThePaneInsteadOfReplacingIt() {
        // "more replies", a linked post, a post on a profile in the pane: back returns to the first post
        val s = NavState().pushIn(post, loggedIn = true, wide = true).pushIn(other, loggedIn = true, wide = true)
        assertEquals(listOf(post, other), screens(s.pane))
        // a notification's post, then its author's profile from it
        val n = NavState(tab = AppTab.NOTIFICATION).pushIn(post, loggedIn = true, wide = true).pushIn(profile, loggedIn = true, wide = true)
        assertEquals(listOf(post, profile), screens(n.pane))
        // the same screen again from the pane does nothing
        assertEquals(s.pane, s.pushIn(other, loggedIn = true, wide = true).pane)
        // a list card's author (through the quick profile) goes on in the pane too
        assertEquals(listOf(post, profile), screens(NavState().pushIn(post, loggedIn = true, wide = true).pushIn(profile, loggedIn = true, wide = true).pane))
    }

    @Test
    fun theSideMenusPlacesCoverBothWhateverThePaneHolds() {
        val s = NavState().pushIn(post, loggedIn = true, wide = true).pushIn(profile, loggedIn = true, wide = true, cover = true)
        assertEquals(listOf<AppScreen>(profile), s.screens)
        assertEquals(listOf<AppScreen>(post), screens(s.pane), "the post is there again after it")
        // with nothing open, a profile is no list tap on Feed: it covers both as well
        assertEquals(listOf<AppScreen>(profile), NavState().pushIn(profile, loggedIn = true, wide = true).screens)
    }

    @Test
    fun theEditorAndOtherFullScreensStillCoverEverything() {
        val s = NavState().pushIn(post, loggedIn = true, wide = true).pushIn(editor, loggedIn = true, wide = true)
        assertEquals(listOf<AppScreen>(editor), s.screens)
        assertEquals(listOf<AppScreen>(post), screens(s.pane), "the pane is there again after the editor")
        // with nothing open in the pane the editor covers the list as well
        assertEquals(listOf<AppScreen>(editor), NavState().pushIn(editor, loggedIn = true, wide = true).screens)
    }

    @Test
    fun everyTabOpensWhatItsListOpensBesideIt() {
        val chats = NavState(tab = AppTab.CHATS).pushIn(thread, loggedIn = true, wide = true)
        assertEquals(listOf<AppScreen>(thread), screens(chats.pane))
        val notifications = NavState(tab = AppTab.NOTIFICATION)
        assertEquals(listOf<AppScreen>(post), screens(notifications.pushIn(post, loggedIn = true, wide = true).pane))
        // a follow notification opens the follower's profile
        assertEquals(listOf<AppScreen>(profile), screens(notifications.pushIn(profile, loggedIn = true, wide = true).pane))
        // a wave opens its thread beside the waves; an asset its details beside the wallet
        assertEquals(listOf<AppScreen>(post), screens(NavState(tab = AppTab.WAVES).pushIn(post, loggedIn = true, wide = true).pane))
        val asset = AppScreen.AssetDetails("HIVE")
        val wallet = NavState(tab = AppTab.WALLET).pushIn(asset, loggedIn = true, wide = true)
        assertEquals(listOf<AppScreen>(asset), screens(wallet.pane))
        // sending, swapping, buying points still cover the window
        val send = AppScreen.Transfer("transfer", "HIVE", "hive", "")
        assertEquals(listOf<AppScreen>(send), wallet.pushIn(send, loggedIn = true, wide = true).screens)
    }

    @Test
    fun aScreenThatNeedsAnAccountAsksForTheLoginFirst() {
        val s = NavState(tab = AppTab.CHATS).pushIn(thread, loggedIn = false, wide = true)
        assertEquals(AppScreen.Login, s.top)
        assertTrue(s.pane.isEmpty())
    }

    @Test
    fun foldingMovesThePaneOntoTheStackAndUnfoldingMovesItBack() {
        val wide = NavState().pushIn(post, loggedIn = true, wide = true).pushIn(profile, loggedIn = true, wide = true)
        val folded = wide.fitTo(wide = false)
        assertEquals(listOf(post, profile), folded.screens, "the same place, one screen over the other")
        assertTrue(folded.pane.isEmpty())
        val unfolded = folded.fitTo(wide = true)
        assertEquals(listOf(post, profile), screens(unfolded.pane))
        assertTrue(unfolded.atRoot)
        // a full screen above the pane stays on the stack either way
        val editing = wide.pushIn(editor, loggedIn = true, wide = true)
        assertEquals(listOf(post, profile, editor), editing.fitTo(wide = false).screens)
        assertEquals(editing, editing.fitTo(wide = false).fitTo(wide = true))
    }

    @Test
    fun unfoldingLeavesAStackThatTheListDidNotOpen() {
        // the editor first, or a tab without panes: nothing moves
        val editing = NavState().push(editor).push(post)
        assertEquals(editing, editing.fitTo(wide = true))
        val wallet = NavState(tab = AppTab.WALLET).push(post)
        assertEquals(wallet, wallet.fitTo(wide = true), "a post is not what the wallet opens")
        // a profile is not what the Feed list opens: it stays a pushed screen
        val fromFeed = NavState().push(profile)
        assertEquals(fromFeed, fromFeed.fitTo(wide = true))
    }

    @Test
    fun aTabChangeOrAnAccountChangeClearsThePane() {
        val s = NavState(tab = AppTab.CHATS).pushIn(thread, loggedIn = true, wide = true)
        assertTrue(s.selectTab(AppTab.FEED).pane.isEmpty())
        // a chat belongs to the account that opened it
        assertTrue(s.afterAccountChange().pane.isEmpty())
        assertTrue(s.reset().pane.isEmpty())
    }

    @Test
    fun theSavedNavigationKeepsThePaneAsPushedScreens() {
        val s = NavState().pushIn(post, loggedIn = true, wide = true).pushIn(profile, loggedIn = true, wide = true)
        val restored = NavState.decode(s.encode())
        assertEquals(listOf(post, profile), restored.screens)
        assertTrue(restored.pane.isEmpty())
        assertEquals(listOf(post, profile), screens(restored.fitTo(wide = true).pane))
    }

    @Test
    fun theTopOfThePaneIsWhatTheWindowShowsFirst() {
        assertNull(NavState().paneTop)
        val s = NavState().pushIn(post, loggedIn = true, wide = true)
        assertEquals(post, s.paneTop)
    }

    @Test
    fun theSideMenuSitsBesideTheListUntilAPostOpensThenTheListAndThePost() {
        // nothing open: the side menu and the list
        val start = NavState()
        assertTrue(start.menuBesideList(wide = true))
        // a post: the menu closes, the list and the post
        val reading = start.pushIn(post, loggedIn = true, wide = true)
        assertFalse(reading.menuBesideList(wide = true))
        // the account button or the pane's sidebar button: the menu and the list again, the post kept behind it
        val menu = reading.openDrawer()
        assertTrue(menu.menuBesideList(wide = true))
        assertEquals(listOf<AppScreen>(post), screens(menu.pane))
        // the same post again from the list brings it back; another one replaces it
        assertFalse(menu.pushIn(post, loggedIn = true, wide = true, fromList = true).menuBesideList(wide = true))
        assertEquals(listOf<AppScreen>(other), screens(menu.pushIn(other, loggedIn = true, wide = true, fromList = true).pane))
    }

    @Test
    fun theMenuColumnIsOnlyForAWideWindow() {
        assertFalse(NavState().menuBesideList(wide = false), "a phone keeps the drawer over the tabs")
        assertTrue(NavState(tab = AppTab.WALLET).menuBesideList(wide = true))
        assertTrue(NavState(tab = AppTab.WAVES).menuBesideList(wide = true))
        assertTrue(NavState(tab = AppTab.CHATS).menuBesideList(wide = true))
        assertTrue(NavState(tab = AppTab.NOTIFICATION).menuBesideList(wide = true))
        // a full screen over everything hides it with the rest
        assertFalse(NavState().pushIn(editor, loggedIn = true, wide = true).menuBesideList(wide = true))
    }

    @Test
    fun foldingWithTheMenuColumnShownLeavesNoDrawerOpenOverThePhone() {
        val menu = NavState().pushIn(post, loggedIn = true, wide = true).openDrawer()
        val folded = menu.fitTo(wide = false)
        assertFalse(folded.drawerOpen)
        assertEquals(listOf<AppScreen>(post), folded.screens)
    }
}
