package com.ecency.core.render.blocks

import com.ecency.core.render.dom.LenientHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tap targets, embeds and thumbnails from authored attributes; invalid ones degrade to text. */
class BlockSafetyTest {
    // body images load through the image proxy (BlockBuilder.image)
    private fun px(url: String) = SafeTarget.proxify(url, "https://images.ecency.com")!!

    private fun t(text: String) = Inline.Text(text)
    private fun p(vararg inlines: Inline) = Block.Paragraph(inlines.toList())
    private fun a(cls: String, attrs: String, text: String = "x") = BlockBuilder.build("<p><a class=\"$cls\" $attrs>$text</a></p>")

    @Test
    fun accountRules() {
        for (ok in listOf("abc", "spike-user", "abc.d2e", "ecency", "abc.def.ghi", "abcdefghijklmnop")) assertEquals(ok, SafeTarget.account(ok), ok)
        for (bad in listOf(null, "", "ab", "abcdefghijklmnopq", "1abc", "abc-", "ab--c", "Abc", "../../evil", "abc.de", "abc..def", "abc/def", " abc", "a_bc")) {
            assertNull(SafeTarget.account(bad), bad)
        }
    }

    @Test
    fun tagCommunityPermlinkYoutubeRules() {
        assertEquals("hive", SafeTarget.tag("Hive"))
        assertEquals("kr-newbie", SafeTarget.tag("kr-newbie"))
        for (bad in listOf("x/../../y", "", "a b", "a".repeat(41), "tag?x=1", "한국")) assertNull(SafeTarget.tag(bad), bad)
        assertEquals("created", SafeTarget.filter("created"))
        assertNull(SafeTarget.filter("../x"))
        assertEquals("hive-123456", SafeTarget.community("hive-123456"))
        for (bad in listOf("hive-", "hive-12a", "hive-1/../x", "ecency", "HIVE-1")) assertNull(SafeTarget.community(bad), bad)
        assertEquals("my-post-1", SafeTarget.permlink("my-post-1"))
        for (bad in listOf("", "a/b", "A", "a?b", "a".repeat(257), "../x")) assertNull(SafeTarget.permlink(bad), bad)
        assertEquals("dQw4w9WgXcQ", SafeTarget.youtubeId("dQw4w9WgXcQ"))
        for (bad in listOf("zzz&list=evil", "../../x?a=b", "abc", "dQw4w9WgXcQQ", "dQw4w9WgXc!")) assertNull(SafeTarget.youtubeId(bad), bad)
    }

    @Test
    fun externalOnlyHttpOrAppUris() {
        assertEquals(LinkTarget.External("https://example.com/a"), SafeTarget.external(" https://example.com/a "))
        assertEquals(LinkTarget.External("http://example.com"), SafeTarget.external("http://example.com"))
        assertEquals(LinkTarget.AppUri("hive://sign/op/x"), SafeTarget.external("hive://sign/op/x"))
        assertEquals(LinkTarget.AppUri("ecency://transfer?to=x"), SafeTarget.external("ecency://transfer?to=x"))
        for (bad in listOf(
            "web+evil:x", "//evil.com/x", "#top", "/relative", "javascript:alert(1)", "data:text/html,x", "mailto:a@b.c",
            "tel:+123", "file:///etc/passwd", "intent://x", "https://", "hive://", "hive://a b", "https://a\u0000b.com",
        )) assertNull(SafeTarget.external(bad), bad)
    }

    @Test
    fun invalidTargetsDegradeToText() {
        assertEquals(listOf(p(t("x"))), a("markdown-external-link", "data-href=\"web+evil:x\""))
        assertEquals(listOf(p(t("x"))), a("markdown-external-link", "data-href=\"//evil.com/x\""))
        // `#x` is an in-post jump (#2403), never a URL; a fragment with a control character is nothing
        assertEquals(listOf(p(Inline.Link(LinkTarget.Anchor("x"), listOf(t("x"))))), a("markdown-external-link", "data-href=\"#x\""))
        assertEquals(listOf(p(t("x"))), a("markdown-external-link", "data-href=\"#a%0Ab\""))
        assertEquals(listOf(p(t("x"))), a("markdown-external-link", "data-href=\"https://e.com\" data-youtube=\"zzz&amp;list=evil\""))
        assertEquals(listOf(p(t("@../../evil"))), a("markdown-author-link", "data-author=\"../../evil\"", "@../../evil"))
        assertEquals(listOf(p(t("#x/../../y"))), a("markdown-tag-link", "data-tag=\"x/../../y\"", "#x/../../y"))
        assertEquals(listOf(p(t("c"))), a("markdown-community-link", "data-community=\"../x\"", "c"))
        assertEquals(listOf(p(t("x"))), a("markdown-post-link", "data-author=\"../x\" data-permlink=\"p\""))
        assertEquals(listOf(p(t("x"))), a("markdown-witnesses-link", "data-href=\"javascript:alert(1)\""))
        // A valid tag with an unsafe filter keeps the tag and drops the filter.
        assertEquals(listOf(p(Inline.Tag("hive", null))), a("markdown-tag-link", "data-tag=\"hive\" data-filter=\"../x\"", "#hive"))
        // Valid ones still link.
        assertEquals(listOf(p(Inline.Mention("spike-user"))), a("markdown-author-link", "data-author=\"spike-user\"", "@spike-user"))
        assertEquals(listOf(p(Inline.Link(LinkTarget.Youtube("dQw4w9WgXcQ", 5), listOf(t("x"))))), a("markdown-external-link", "data-youtube=\"dQw4w9WgXcQ\" data-start-time=\"5\""))
        assertEquals(listOf(p(Inline.Link(LinkTarget.Youtube("dQw4w9WgXcQ", 0), listOf(t("x"))))), a("markdown-external-link", "data-youtube=\"dQw4w9WgXcQ\" data-start-time=\"-9\""))
    }

    @Test
    fun spoofedPostCardsNeedValidTargets() {
        assertEquals(listOf(p(t("x"))), a("markdown-post-link", "data-is-inline=\"true\" data-author=\"a\" data-permlink=\"b\""))
        assertEquals(listOf(p(t("x"))), a("markdown-post-link", "data-is-inline=\"true\" data-author=\"alice\" data-permlink=\"../b\""))
        assertEquals(
            listOf(Block.PostPreview("alice", "b-1", null)),
            a("markdown-post-link", "data-is-inline=\"true\" data-author=\"alice\" data-permlink=\"b-1\" data-href=\"javascript:x\""),
        )
        // The anchored form supplies the author when data-author is missing.
        assertEquals(listOf(Block.PostPreview("bob", "p", null)), a("markdown-post-link", "data-is-inline=\"true\" data-permlink=\"x#@bob/p\""))
    }

    @Test
    fun videosNeedAllowlistedPlayers() {
        // not a player: a link to the page (the link sheet warns), never a video and never a hole
        val evil = Block.Paragraph(listOf(Inline.Link(LinkTarget.External("https://evil.example/embed"), listOf(Inline.Text("https://evil.example/embed")))))
        assertEquals(listOf(evil), BlockBuilder.build("<iframe src=\"https://evil.example/embed\"></iframe>"))
        assertEquals(emptyList(), BlockBuilder.build("<iframe src=\"javascript:alert(1)\"></iframe>"))
        // <video>: dropped unless the player URL is allowlisted; fallback text shows either way; poster never unproxied.
        assertEquals(listOf(p(t("fallback"))), BlockBuilder.build("<video src=\"https://evil.example/x.mp4\" poster=\"http://track.example/p.png\">fallback</video>"))
        assertEquals(
            listOf(Block.Video("https://player.vimeo.com/video/1", null)),
            BlockBuilder.build("<video src=\"https://player.vimeo.com/video/1\" poster=\"http://track.example/p.png\"></video>"),
        )
        // A video link with an unsafe player and a bad YouTube id keeps its label as text only.
        assertEquals(
            listOf(p(t("label"))),
            BlockBuilder.build("<a class=\"markdown-video-link markdown-video-link-youtube\" data-embed-src=\"https://evil.example/e\" data-youtube=\"../../x?a=b\"><img class=\"video-thumbnail\" src=\"https://images.ecency.com/p/t\" />label</a>"),
        )
        // A valid id alone is enough; an unproxied thumbnail is dropped.
        assertEquals(
            listOf(Block.Video("https://www.youtube.com/embed/dQw4w9WgXcQ", null, "dQw4w9WgXcQ")),
            BlockBuilder.build("<a class=\"markdown-video-link\" data-youtube=\"dQw4w9WgXcQ\"><img class=\"video-thumbnail\" src=\"https://track.example/t.png\" /></a>"),
        )
        // Images: http(s) only.
        assertEquals(emptyList(), BlockBuilder.build("<img src=\"javascript:alert(1)\" /><img src=\"data:image/png;base64,AA\" /><img src=\"//x.example/a.png\" />"))
    }

    @Test
    fun metadataImageIsTheThumbnailFallback() {
        val opts = BuildOptions(metadataImage = "https://example.com/cover.jpg")
        val proxied = SafeTarget.proxify("https://example.com/cover.jpg", opts.proxyBase)!!
        assertTrue(proxied.startsWith("https://images.ecency.com/p/"))
        assertEquals(listOf(Block.Video("https://play.3speak.tv/watch?v=a/b", proxied)), BlockBuilder.build("<a class=\"markdown-video-link\" data-embed-src=\"https://play.3speak.tv/watch?v=a/b\"></a>", opts))
        assertEquals(listOf(Block.Video("https://player.vimeo.com/video/1", proxied)), BlockBuilder.build("<iframe src=\"https://player.vimeo.com/video/1\"></iframe>", opts))
        // An own proxied thumbnail wins; an unusable metadata image gives none.
        val own = "https://images.ecency.com/p/own?format=match&mode=fit"
        assertEquals(own, (BlockBuilder.build("<a class=\"markdown-video-link\" data-youtube=\"dQw4w9WgXcQ\"><img class=\"video-thumbnail\" src=\"$own\" /></a>", opts).single() as Block.Video).thumbnail)
        assertNull((BlockBuilder.build("<iframe src=\"https://player.vimeo.com/video/1\"></iframe>", BuildOptions(metadataImage = "javascript:x")).single() as Block.Video).thumbnail)
    }

    @Test
    fun pastMaxDepthLinksImagesAndBlocksSurvive() {
        val depth = BlockBuilder.MAX_DEPTH + 30
        val html = "<div>".repeat(depth) +
            "<b>bold</b> <a class=\"markdown-external-link\" data-href=\"https://x.example\">link</a>" +
            "<p>para</p><a class=\"markdown-external-link\" data-href=\"https://y.example\"><img src=\"https://x.example/i.png\" /></a>" +
            "<table><tr><td>cell</td></tr></table><ul><li>item</li></ul>" +
            "<a class=\"markdown-post-link\" data-is-inline=\"true\" data-author=\"alice\" data-permlink=\"p\">@alice/p</a>" +
            "</div>".repeat(depth)
        val blocks = BlockBuilder.build(html)
        assertNull(TextFidelity.diff(html, blocks))
        val first = blocks[0] as Block.Paragraph
        assertEquals(Inline.Link(LinkTarget.External("https://x.example"), listOf(t("link"))), first.inlines.last())
        assertTrue(Block.Image(px("https://x.example/i.png"), null, LinkTarget.External("https://y.example")) in blocks)
        assertTrue(Block.PostPreview("alice", "p", null) in blocks)
        assertEquals(listOf("bold link", "para", "cell", "item"), blocks.filterIsInstance<Block.Paragraph>().map { BlockText.ofInlines(it.inlines) })
    }

    @Test
    fun lenientFallbackKeepsLinksAndImages() {
        val doc = LenientHtml.parse("<p>a <a class=\"markdown-external-link\" data-href=\"https://x.example?a=1&amp;b=2\">l<b>i</b></a> &amp; <img src=\"https://x.example/i.png\"> <div>unclosed <span>x</p> tail")
        val blocks = BlockBuilder.fromDocument(doc)
        assertEquals(
            p(t("a "), Inline.Link(LinkTarget.External("https://x.example?a=1&b=2"), listOf(t("l"), Inline.Text("i", setOf(Inline.Mark.Bold)))), t(" &")),
            blocks[0],
        )
        assertEquals(Block.Image(px("https://x.example/i.png"), null), blocks[1])
        // The end tag of the outer p closes the open div and span too.
        assertEquals(listOf("unclosed x", "tail"), blocks.drop(2).map { BlockText.of(listOf(it)).trim() })
        assertNotNull(BlockBuilder.build("<<<a href=x><img src=y"))
    }
}
