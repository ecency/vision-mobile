package com.ecency.core.app

import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.render.blocks.LinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NavigationTest {
    private val post = AppScreen.Post("alice", "hello")

    @Test
    fun routeTableMirrorsEveryRnRouteName() {
        // src/constants/routeNames.ts: SCREENS (40) and MODALS (5), each by its RN string
        val rn = listOf(
            "BookmarksScreen", "BoostScreen", "DraftsScreen", "EditorScreen", "FollowsScreen", "SpinGameScreen",
            "PerksScreen", "FeedScreen", "LoginScreen", "PinCodeScreen", "PostScreen", "ProfileEditScreen",
            "ProfileScreen", "ReblogsScreen", "RedeemScreen", "RegisterScreen", "SearchResultScreen",
            "TagResultScreen", "SettingsScreen", "TransferScreen", "VotersScreen", "AccountBoostScreen",
            "CommunityScreen", "CommunitiesScreen", "CommunityMembersScreen", "CommunitySettingsScreen",
            "CommunityActivitiesScreen", "WebBrowserScreen", "ReferScreen", "AssetDetailsScreen",
            "EditHistoryScreen", "WelcomeScreen", "ChatThreadScreen", "AccountListScreen", "BackupKeysScreen",
            "TradeScreen", "AiImageGeneratorScreen", "DappBrowserScreen", "EmailDigestsScreen",
            "AssetsSelectModal", "AccountListModal", "PollWizardModal", "BotCommentsModal", "HiveSignerModal",
        )
        for (name in rn) assertTrue(RouteName.ofRnName(name) != null, "missing $name")
        assertEquals(RouteName.entries.size, RouteName.entries.map { it.rnName }.toSet().size, "RN names are unique")
        assertNull(RouteName.ofRnName("NopeScreen"))
    }

    @Test
    fun unbuiltRoutesGoToThePlaceholderThatNamesTheFeature() {
        for (route in RouteName.entries.filter { !it.built }) {
            val screen = AppScreen.of(route)
            assertTrue(screen is AppScreen.Pending, "$route")
            assertEquals(route.title, screen.title)
            assertEquals(route, screen.route)
        }
        assertSame(AppScreen.Login, AppScreen.of(RouteName.LOGIN))
        assertSame(AppScreen.Settings, AppScreen.of(RouteName.SETTINGS))
        assertEquals(AppScreen.Profile("bob"), AppScreen.of(RouteName.PROFILE, "bob"))
        assertSame(AppScreen.Explore, AppScreen.of(RouteName.DAPP_BROWSER))
        assertEquals(AppScreen.DappBrowser("https://peakd.com"), AppScreen.of(RouteName.DAPP_BROWSER, "https://peakd.com"))
        assertSame(AppScreen.QrScan, AppScreen.of(RouteName.QR_SCAN))
        // a browser page is saved as the browser's home (the URL is history, maybe with a page's token); the scanner is not restored
        assertEquals("explore", NavState.encodeScreen(AppScreen.DappBrowser("https://dapp.example/?token=secret")))
        assertEquals(AppScreen.Explore, NavState.decodeScreen(NavState.encodeScreen(AppScreen.DappBrowser("https://dapp.example/"))))
        assertNull(NavState.decodeScreen(NavState.encodeScreen(AppScreen.QrScan)))
    }

    @Test
    fun tabsAreRnsInOrderWithTheirIconsGatesAndBadges() {
        assertEquals(
            listOf("FeedTabbar", "WavesTabbar", "ChatsTabbar", "WalletTabbar", "NotificationTabbar"),
            AppTab.entries.map { it.rnName },
        )
        assertEquals(listOf(AppIcon.HOME, AppIcon.WAVES, AppIcon.CHAT, AppIcon.WALLET, AppIcon.NOTIFICATIONS), AppTab.entries.map { it.icon })
        assertEquals(listOf(AppTab.CHATS, AppTab.WALLET, AppTab.NOTIFICATION), AppTab.entries.filter { it.loginText != null })
        assertEquals("Login to see", AppTab.WALLET.loginText)
        assertEquals(BadgeKind.NOTIFICATION, AppTab.NOTIFICATION.badge)
        assertEquals(BadgeKind.CHAT, AppTab.CHATS.badge)
        assertNull(AppTab.FEED.badge)
        assertTrue(AppTab.FEED.showsBoost(loggedIn = true))
        assertFalse(AppTab.FEED.showsBoost(loggedIn = false))
        assertFalse(AppTab.CHATS.showSearch)
        assertEquals(listOf(AppTab.FEED, AppTab.WALLET), AppTab.entries.filter { it.showQr })
    }

    @Test
    fun pushAndBackStack() {
        var s = NavState().push(post).push(AppScreen.Profile("alice"))
        assertEquals(listOf(post, AppScreen.Profile("alice")), s.screens)
        assertEquals(s, s.push(AppScreen.Profile("alice")), "the screen on top is not pushed twice")
        val ids = s.stack.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        s = s.push(post) // the same post again, deeper: a new entry
        assertEquals(3, s.stack.size)
        assertTrue(s.stack[0].id != s.stack[2].id)
        s = s.back()!!.back()!!
        assertEquals(listOf(post), s.screens)
        assertEquals(post, s.top)
        s = s.back()!!
        assertTrue(s.atRoot)
        assertNull(s.back(), "at the Feed root back leaves the app")
    }

    @Test
    fun backClosesTheDrawerFirstThenReturnsToTheFeedTab() {
        var s = NavState().selectTab(AppTab.WALLET).openDrawer()
        assertTrue(s.drawerOpen)
        s = s.back()!!
        assertFalse(s.drawerOpen)
        assertEquals(AppTab.WALLET, s.tab)
        s = s.back()!!
        assertEquals(AppTab.FEED, s.tab, "RN backBehavior initialRoute")
        assertNull(s.back())
    }

    @Test
    fun drawerOnlyOpensAtTheRootAndPushingClosesIt() {
        val pushed = NavState().push(post)
        assertFalse(pushed.openDrawer().drawerOpen)
        val open = NavState().openDrawer()
        assertFalse(open.push(AppScreen.Settings).drawerOpen)
        assertFalse(open.selectTab(AppTab.CHATS).drawerOpen)
        assertFalse(open.closeDrawer().drawerOpen)
    }

    @Test
    fun tabSwitchingKeepsTabAndClearsTheStack() {
        var s = NavState().selectTab(AppTab.NOTIFICATION)
        assertEquals(AppTab.NOTIFICATION, s.tab)
        s = s.push(post).selectTab(AppTab.WAVES)
        assertTrue(s.atRoot)
        assertEquals(AppTab.WAVES, s.tab)
    }

    @Test
    fun gatedScreensAskForLoginWhenSignedOut() {
        val editor = AppScreen.of(RouteName.EDITOR)
        assertEquals(AppScreen.Login, NavState().pushGated(editor, loggedIn = false).top)
        assertEquals(editor, NavState().pushGated(editor, loggedIn = true).top)
        assertEquals(post, NavState().pushGated(post, loggedIn = false).top, "posts are public")
    }

    @Test
    fun popToRemovesSeveral() {
        val s = NavState().push(post).push(AppScreen.Settings).push(AppScreen.Login)
        assertEquals(listOf(post), s.popTo(1).screens)
        assertTrue(s.popTo(-3).atRoot)
        assertSame(s, s.popTo(5))
    }

    @Test
    fun deepLinkToATabAndAStack() {
        val start = NavState().push(post).openDrawer()
        val entry = NavEntry(AppTab.NOTIFICATION, listOf(AppScreen.Profile("bob"), post))
        val s = start.open(entry, loggedIn = true)
        assertEquals(AppTab.NOTIFICATION, s.tab)
        assertEquals(listOf(AppScreen.Profile("bob"), post), s.screens)
        assertFalse(s.drawerOpen)
        assertEquals(AppTab.FEED, s.back()!!.back()!!.back()!!.tab)
    }

    @Test
    fun deepLinkThroughTheLinkRouter() {
        val route = LinkRouter.route(LinkTarget.Post("alice", "hello"))
        val s = NavState().selectTab(AppTab.WALLET).open(NavEntry.from(route)!!, loggedIn = false)
        assertEquals(AppTab.WALLET, s.tab, "a screen link goes on top of the open tab")
        assertEquals(listOf(post), s.screens)
        val tag = NavEntry.from(LinkRouter.route(LinkTarget.Tag("photography", "hot")))!!
        assertEquals(AppScreen.TagResult("photography", "hot"), tag.screens.single())
        assertEquals(RouteName.TAG_RESULT, tag.screens.single().route)
        assertEquals(RouteName.COMMUNITY, AppScreen.Feed(FeedSource.Ranked(RankedSort.HOT, "hive-1"), "C").route)
        assertNull(NavEntry.from(LinkRouter.route(LinkTarget.External("https://example.com"))))
        assertNull(NavEntry.from(Route.Unsupported("x")))
    }

    @Test
    fun restoreRoundTripsTabAndStack() {
        val s = NavState().selectTab(AppTab.CHATS)
            .push(post)
            .push(AppScreen.Feed(FeedSource.Ranked(RankedSort.HOT, "hive-100001"), "Test\u001FCommunity"))
            .push(AppScreen.Feed(FeedSource.Account("alice", AccountPostsSort.BLOG), "Blog"))
            .push(AppScreen.Profile("alice"))
            .push(AppScreen.of(RouteName.DRAFTS))
            .push(AppScreen.Settings)
            .push(AppScreen.Login)
        val restored = NavState.decode(s.encode())
        assertEquals(AppTab.CHATS, restored.tab)
        assertEquals(s.screens.size, restored.screens.size)
        assertEquals(AppScreen.Feed(FeedSource.Ranked(RankedSort.HOT, "hive-100001"), "Test Community"), restored.screens[1])
        assertEquals(s.screens.filterIndexed { i, _ -> i != 1 }, restored.screens.filterIndexed { i, _ -> i != 1 })
        assertFalse(restored.drawerOpen, "the side menu is not restored open")
        assertEquals(restored, NavState.decodeJoined(NavState.encodeJoined(restored)))
        assertEquals(NavState(), NavState.decodeJoined(""))
    }

    @Test
    fun restoreSkipsWhatThisBuildDoesNotKnow() {
        val s = NavState.decode(listOf("tab\u001FSPACESHIP", "post\u001Fa\u001Fb", "unknown\u001Fx", "pending\u001FNOPE\u001F", "post"))
        assertEquals(AppTab.FEED, s.tab)
        assertEquals(listOf(AppScreen.Post("a", "b")), s.screens)
    }

    @Test
    fun resetGoesBackToTheFeedRootWithFreshIds() {
        val s = NavState().selectTab(AppTab.WALLET).push(post).openDrawer()
        val r = s.reset()
        assertEquals(AppTab.FEED, r.tab)
        assertTrue(r.atRoot)
        assertTrue(r.push(post).stack.single().id >= s.nextId, "ids are never reused (they key models)")
    }

    @Test
    fun deepLinksToGatedScreensAskForLoginFirst() {
        val transfer = AppScreen.of(RouteName.TRANSFER)
        val out = NavState().open(NavEntry(AppTab.WALLET, listOf(transfer, post)), loggedIn = false)
        assertEquals(AppTab.WALLET, out.tab)
        assertEquals(listOf<AppScreen>(AppScreen.Login), out.screens, "nothing that needs the account is queued behind login")
        val inn = NavState().open(NavEntry(AppTab.WALLET, listOf(transfer, post)), loggedIn = true)
        assertEquals(listOf(transfer, post), inn.screens)
    }

    @Test
    fun signOutKeepsTheTabAndPublicScreensAndDropsGatedOnes() {
        val s = NavState().selectTab(AppTab.WALLET).push(post).push(AppScreen.of(RouteName.TRANSFER)).push(AppScreen.Profile("bob"))
            .push(AppScreen.of(RouteName.DRAFTS))
        val out = s.copy(drawerOpen = true).afterSignOut()
        assertEquals(AppTab.WALLET, out.tab)
        assertEquals(listOf(post, AppScreen.Profile("bob")), out.screens)
        assertFalse(out.drawerOpen)
    }

    @Test
    fun backupKeysIsGatedAndNeverRestored() {
        assertSame(AppScreen.BackupKeys, AppScreen.of(RouteName.BACKUP_KEYS))
        assertEquals(AppScreen.Login, NavState().pushGated(AppScreen.BackupKeys, loggedIn = false).top)
        val s = NavState().push(AppScreen.Settings).push(AppScreen.BackupKeys)
        assertEquals(listOf<AppScreen>(AppScreen.Settings), NavState.decode(s.encode()).screens)
        assertNull(NavState.decodeScreen("pending\u001FBACKUP_KEYS\u001F"), "nor as a pending line")
    }

    @Test
    fun contentLinksOpenTheirScreens() {
        assertEquals(listOf<AppScreen>(post), ContentLinks.entry("https://ecency.com/@alice/hello")?.screens)
        assertEquals(listOf<AppScreen>(post), ContentLinks.entry("https://ecency.com/hive-1/@alice/hello?x=1")?.screens)
        assertEquals(listOf<AppScreen>(AppScreen.Profile("alice", "posts")), ContentLinks.entry("https://www.ecency.com/@alice/posts")?.screens)
        assertEquals(RouteName.TAG_RESULT, ContentLinks.entry("https://ecency.com/hot/photography")?.screens?.single()?.route)
        assertEquals(RouteName.COMMUNITY, ContentLinks.entry("https://ecency.com/created/hive-125125")?.screens?.single()?.route)
        assertNull(ContentLinks.entry("https://evil.example/@alice/hello"), "other hosts")
        assertNull(ContentLinks.entry("http://ecency.com/@alice/hello"), "https only")
        assertNull(ContentLinks.entry("https://ecency.com/auth?code=x"), "auth links are not content")
        assertNull(ContentLinks.entry("https://ecency.com/@Alice!/x"), "validated like body links")
        assertNull(ContentLinks.entry("javascript:alert(1)"))
    }

    @Test
    fun theBareFeedAndWavesAppLinksOpenTheirScreens() {
        // RN AndroidManifest: /trending, /hot, /waves and /waves/* (TAG_RESULT without a tag, the Waves tab, a wave)
        assertEquals(
            listOf<AppScreen>(AppScreen.Feed(com.ecency.core.api.repository.FeedSource.Ranked(com.ecency.core.api.RankedSort.TRENDING), "Trending")),
            ContentLinks.entry("https://ecency.com/trending")?.screens,
        )
        assertEquals(
            listOf<AppScreen>(AppScreen.Feed(com.ecency.core.api.repository.FeedSource.Ranked(com.ecency.core.api.RankedSort.HOT), "Hot")),
            ContentLinks.entry("https://ecency.com/hot/")?.screens,
        )
        assertEquals(
            listOf<AppScreen>(AppScreen.Feed(com.ecency.core.api.repository.FeedSource.Ranked(com.ecency.core.api.RankedSort.CREATED), "New")),
            ContentLinks.entry("https://ecency.com/created")?.screens,
        )
        assertEquals(NavEntry.tab(AppTab.WAVES), ContentLinks.entry("https://ecency.com/waves"))
        assertEquals(NavEntry.tab(AppTab.WAVES), ContentLinks.entry("https://ecency.com/waves?utm=x"))
        assertEquals(listOf<AppScreen>(AppScreen.Post("alice", "wave-1")), ContentLinks.entry("https://ecency.com/waves/alice/wave-1")?.screens)
        assertNull(ContentLinks.entry("https://ecency.com/waves/alice"), "no wave named")
        assertNull(ContentLinks.entry("https://ecency.com/payout"), "not one of RN's bare feeds")
    }

    @Test
    fun anAccountChangeDropsGatedScreens() {
        val s = NavState().push(post).push(AppScreen.of(RouteName.TRANSFER)).push(AppScreen.Settings)
        assertEquals(listOf(post, AppScreen.Settings), s.afterAccountChange().screens)
        assertEquals(s.afterAccountChange(), s.afterSignOut())
    }

    /** Every typed screen the profile, communities and feed waves added, one of each. */
    private val waveScreens: List<AppScreen> = listOf(
        AppScreen.Profile("alice", "posts"), AppScreen.Follows("alice", following = true, count = 3), AppScreen.ProfileEdit,
        AppScreen.Bookmarks(1), AppScreen.AccountList("Voters", listOf(AccountListEntry("alice", 5))),
        AppScreen.Communities, AppScreen.Community("hive-125125", "hot"), AppScreen.CommunityMembers("hive-125125", "Test"),
        AppScreen.CommunitySettings("hive-125125"), AppScreen.CommunityActivities("hive-125125", "Test"), AppScreen.Search,
        AppScreen.TagResult("photography", "created"), AppScreen.Explore,
        AppScreen.Voters("alice", "one"), AppScreen.Reblogs("alice", "one"), AppScreen.EditHistory("alice", "one"), AppScreen.BotComments("alice", "one"),
    )

    @Test
    fun anAccountChangeDropsEveryGatedScreenOfTheWaves() {
        val gated = setOf<AppScreen>(AppScreen.ProfileEdit, AppScreen.Bookmarks(1), AppScreen.CommunitySettings("hive-125125"))
        val state = waveScreens.fold(NavState()) { s, screen -> s.push(screen) }
        assertEquals(waveScreens, state.screens)
        // the previous account's bookmarks, profile form and community settings go; public screens stay in place
        assertEquals(waveScreens - gated, state.afterAccountChange().screens)
        assertEquals(gated, waveScreens.filter { it.route.requiresLogin }.toSet())
        // a signed-out reader is sent to the login screen for each of them, and gets the public ones
        for (screen in waveScreens) {
            val pushed = NavState().pushGated(screen, loggedIn = false).screens.single()
            assertEquals(if (screen in gated) AppScreen.Login else screen, pushed, "$screen")
        }
        // every route flagged as built and gated has a typed screen behind it (nothing gated is a placeholder)
        for (route in RouteName.entries.filter { it.built && it.requiresLogin }) {
            assertEquals(route, AppScreen.of(route, "hive-125125").route)
            assertTrue(AppScreen.of(route, "hive-125125") !is AppScreen.Pending, route.name)
        }
    }

    @Test
    fun everyWaveScreenSurvivesSaveAndRestoreExceptWhatMustNot() {
        val state = waveScreens.fold(NavState()) { s, screen -> s.push(screen) }
        assertEquals(waveScreens, NavState.decode(state.encode()).screens)
    }
}
