package com.ecency.core.app

import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.RankedSort
import com.ecency.core.api.model.Post
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.render.blocks.LinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkRouterTest {
    @Test
    fun validTargets() {
        assertEquals(Route.Post("alice", "hello-world"), LinkRouter.route(LinkTarget.Post("alice", "hello-world")))
        assertEquals(Route.Profile("alice.bob"), LinkRouter.route(LinkTarget.Author("alice.bob")))
        assertEquals(Route.Feed(FeedSource.Ranked(RankedSort.CREATED, "photo"), "#photo"), LinkRouter.route(LinkTarget.Tag("photo", "created")))
        assertEquals(Route.Feed(FeedSource.Ranked(RankedSort.TRENDING, "photo"), "#photo"), LinkRouter.route(LinkTarget.Tag("photo", null)))
        assertEquals(
            Route.Feed(FeedSource.Ranked(RankedSort.HOT, "hive-125125"), "hive-125125"),
            LinkRouter.route(LinkTarget.Community("hive-125125", "hot")),
        )
        assertEquals(Route.ExternalBrowser("https://example.com/a?b=1"), LinkRouter.route(LinkTarget.External("https://example.com/a?b=1")))
        assertEquals(Route.ExternalBrowser("http://example.com"), LinkRouter.route(LinkTarget.External("http://example.com")))
        assertEquals(
            Route.VideoPlayer("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1&start=42"),
            LinkRouter.route(LinkTarget.Youtube("dQw4w9WgXcQ", 42)),
        )
        assertEquals(
            Route.VideoPlayer("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1"),
            LinkRouter.route(LinkTarget.Youtube("dQw4w9WgXcQ", -5)),
        )
        assertEquals(FeedSource.Account("alice", AccountPostsSort.BLOG), LinkRouter.profileFeed("alice"))
    }

    @Test
    fun maliciousTargetsAreUnsupported() {
        val bad = listOf(
            LinkTarget.External("javascript:alert(1)"),
            LinkTarget.External("JaVaScRiPt:alert(1)"),
            LinkTarget.External("data:text/html,<script>alert(1)</script>"),
            LinkTarget.External("file:///etc/passwd"),
            LinkTarget.External("intent://scan#Intent;scheme=zxing;end"),
            LinkTarget.External("//evil.example"),
            LinkTarget.External("https://evil.example\nSet-Cookie: x"),
            LinkTarget.External("https://"),
            LinkTarget.External("https://evil.example:80abc/"),
            LinkTarget.External("hive://sign/op/transfer"),
            LinkTarget.AppUri("javascript://alert(1)"),
            LinkTarget.AppUri("intent://scan"),
            LinkTarget.AppUri("hive://sign op"),
            LinkTarget.Video("https://evil.example/player"),
            LinkTarget.Video("javascript:alert(1)"),
            LinkTarget.Youtube("dQw4w9WgXcQ\"><x", 0),
            LinkTarget.Youtube("short", 0),
            LinkTarget.Post("alice", "../../etc"),
            LinkTarget.Post("Alice", "ok"),
            LinkTarget.Author("a"),
            LinkTarget.Author("alice/../x"),
            LinkTarget.Tag("<script>", null),
            LinkTarget.Community("hive-abc", null),
            LinkTarget.Community("alice", null),
        )
        for (target in bad) assertIs<Route.Unsupported>(LinkRouter.route(target), "$target")
    }

    @Test
    fun hiveAndEcencyLinksGoToTheLinkModel() {
        // RN postInteractionHandler isHiveUri: linkProcessor.handleLink (the model asks before it signs)
        assertEquals(Route.AppLink("hive://sign/op/x"), LinkRouter.route(LinkTarget.AppUri("hive://sign/op/x")))
        assertEquals(Route.AppLink("ecency://transfer?to=x"), LinkRouter.route(LinkTarget.AppUri("ecency://transfer?to=x")))
        assertEquals(null, NavEntry.from(Route.AppLink("hive://sign/op/x")), "never a screen by itself")
    }

    @Test
    fun hostileFilterFallsBackToTrending() {
        val route = LinkRouter.route(LinkTarget.Tag("photo", "javascript:x")) as Route.Feed
        assertEquals(RankedSort.TRENDING, (route.source as FeedSource.Ranked).sort)
    }

    @Test
    fun errorMappingCoversEveryKind() {
        assertEquals(ErrorKind.NETWORK, AppError.from(com.ecency.core.api.rpc.AllNodesFailedException("m", emptyList())).kind)
        assertEquals(ErrorKind.STORAGE, AppError.from(com.ecency.core.secure.SecureStoreException.KeyInvalidated()).kind)
        assertEquals(ErrorKind.INVALID_INPUT, AppError.from(com.ecency.core.api.InvalidInputException("x")).kind)
        assertEquals(ErrorKind.UNKNOWN, AppError.from(IllegalStateException("boom")).kind)
        val wrapped = AppError(ErrorKind.PAID_OUT, "paid", false)
        assertEquals(wrapped, AppError.from(AppException(wrapped)))
    }

    @Test
    fun interimRendererSummaryImageAndFallbacks() {
        val renderer = InterimPostBodyRenderer("https://i.ecency.com")
        val long = Post("alice", "p", body = "word ".repeat(100))
        val summary = renderer.summary(long)
        assertTrue(summary.length <= PostBodyRenderer.SUMMARY_LENGTH, summary)
        assertTrue(summary.endsWith("word"), summary)
        assertEquals("Hello world", renderer.summary(Post("alice", "p", body = "# Hello\n\n**world**")))
        assertNull(renderer.cardImage(Post("alice", "p")))
        // an unterminated tag soup never throws
        renderer.blocks(Post("alice", "p", body = "<div><table><tr><td><img src=x onerror=alert(1)>"))
        assertEquals(PostCard.formatPayout(0.0), "$0.000")
        assertEquals(PostCard.formatPayout(12.3456), "$12.346")
        assertEquals("", PostCard.avatarUrl("Not Valid"))
    }

    @Test
    fun perfMarksOnce() {
        val seen = mutableListOf<String>()
        PerfMarks.listener = { e, _ -> seen += e }
        try {
            PerfMarks.resetOnce()
            PerfMarks.mark(PerfEvent.POST_OPEN_TAP)
            PerfMarks.markOnce(PerfEvent.APP_START)
            assertNull(PerfMarks.markOnce(PerfEvent.APP_START))
            assertEquals(listOf("post_open_tap", "app_start"), seen)
        } finally {
            PerfMarks.listener = null
        }
    }
}
