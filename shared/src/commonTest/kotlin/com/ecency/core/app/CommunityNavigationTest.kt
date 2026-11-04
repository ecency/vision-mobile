package com.ecency.core.app

import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.render.blocks.LinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommunityNavigationTest {
    private val screens = listOf(
        AppScreen.Communities,
        AppScreen.Community("hive-100001", "hot"),
        AppScreen.CommunityMembers("hive-100001", "Test Square"),
        AppScreen.CommunitySettings("hive-100001"),
        AppScreen.CommunityActivities("hive-100001", ""),
        AppScreen.Search,
        AppScreen.TagResult("photography", "created"),
        AppScreen.Explore,
    )

    @Test
    fun builtRoutesAndTheirScreens() {
        listOf(
            RouteName.COMMUNITIES, RouteName.COMMUNITY, RouteName.COMMUNITY_MEMBERS, RouteName.COMMUNITY_SETTINGS,
            RouteName.COMMUNITY_ACTIVITIES, RouteName.SEARCH_RESULT, RouteName.TAG_RESULT, RouteName.DAPP_BROWSER,
        ).forEach { assertTrue(it.built, "$it") }
        assertTrue(RouteName.COMMUNITY_SETTINGS.requiresLogin)
        assertFalse(RouteName.COMMUNITIES.requiresLogin)
        assertFalse(RouteName.COMMUNITY_MEMBERS.requiresLogin)
        assertSame(AppScreen.Communities, AppScreen.of(RouteName.COMMUNITIES))
        assertEquals(AppScreen.Community("hive-1"), AppScreen.of(RouteName.COMMUNITY, "hive-1"))
        assertSame(AppScreen.Search, AppScreen.of(RouteName.SEARCH_RESULT))
        assertEquals(AppScreen.TagResult("travel"), AppScreen.of(RouteName.TAG_RESULT, "travel"))
        assertEquals(AppScreen.CommunitySettings("hive-1"), AppScreen.of(RouteName.COMMUNITY_SETTINGS, "hive-1"))
    }

    @Test
    fun screensSurviveProcessDeath() {
        var s = NavState()
        screens.forEach { s = s.push(it) }
        assertEquals(screens, NavState.decode(s.encode()).screens)
    }

    @Test
    fun settingsNeedAnAccount() {
        assertEquals(AppScreen.Login, NavState().pushGated(AppScreen.CommunitySettings("hive-1"), loggedIn = false).top)
        val signedIn = NavState().push(AppScreen.Community("hive-1")).push(AppScreen.CommunitySettings("hive-1"))
        assertEquals(listOf<AppScreen>(AppScreen.Community("hive-1")), signedIn.afterAccountChange().screens)
    }

    @Test
    fun communityAndTagLinksOpenTheirScreens() {
        assertEquals(
            listOf<AppScreen>(AppScreen.Community("hive-125125", "hot")),
            NavEntry.from(LinkRouter.route(LinkTarget.Community("hive-125125", "hot")))!!.screens,
        )
        assertEquals(listOf<AppScreen>(AppScreen.Community("hive-125125", "created")), ContentLinks.entry("https://ecency.com/created/hive-125125")?.screens)
        assertEquals(listOf<AppScreen>(AppScreen.TagResult("photography", "hot")), ContentLinks.entry("https://ecency.com/hot/photography")?.screens)
        // an account feed stays a feed
        val blog = FeedSource.Account("alice", com.ecency.core.api.AccountPostsSort.BLOG)
        assertEquals(AppScreen.Feed(blog, "Blog"), NavEntry.feedScreen(blog, "Blog"))
        assertEquals(AppScreen.Feed(FeedSource.Ranked(RankedSort.TRENDING), "All"), NavEntry.feedScreen(FeedSource.Ranked(RankedSort.TRENDING), "All"))
    }

    @Test
    fun sideMenuOpensCommunitiesAndExplore() {
        assertEquals(MenuAction.Open(AppScreen.Communities), SideMenu.action(SideMenuItem.COMMUNITIES, "alice", false))
        assertEquals(MenuAction.Open(AppScreen.Explore), SideMenu.action(SideMenuItem.EXPLORE, "alice", false))
    }
}
