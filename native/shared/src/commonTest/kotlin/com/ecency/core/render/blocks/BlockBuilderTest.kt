package com.ecency.core.render.blocks

import com.ecency.core.render.RenderFixtures
import com.ecency.core.render.blocks.Inline.Mark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockBuilderTest {
    // body images load through the image proxy (BlockBuilder.image)
    private fun px(url: String) = SafeTarget.proxify(url, "https://images.ecency.com")!!

    private fun build(name: String, waves: Boolean = false) = BlockBuilder.build(BlockInputs[name], BuildOptions(waves = waves))
    private fun t(text: String, vararg marks: Mark) = Inline.Text(text, marks.toSet())
    private fun p(vararg inlines: Inline, style: BlockStyle = BlockStyle.Default) = Block.Paragraph(inlines.toList(), style)
    private val center = BlockStyle(align = BlockStyle.Align.Center)

    @Test
    fun everyInputKeepsItsWords() {
        assertEquals(BlockInputs.html.keys, BlockInputs.words.keys)
        val failures = BlockInputs.html.mapNotNull { (name, html) ->
            TextFidelity.wordDiff(BlockInputs.words.getValue(name), BlockBuilder.build(html))?.let { "$name: $it" }
        }
        assertEquals(emptyList(), failures)
        // The Kotlin-side oracle agrees with htmlparser2 on every input.
        val oracle = BlockInputs.html.mapNotNull { (name, html) ->
            if (TextFidelity.htmlWords(html).map { it.lowercase() } == BlockInputs.words.getValue(name).map { it.lowercase() }) null else name
        }
        assertEquals(emptyList(), oracle)
        RenderFixtures.samples.forEach { assertNull(TextFidelity.diff(it.html, BlockBuilder.build(it.html)), it.name) }
        assertNull(TextFidelity.diff(RenderFixtures.POST_HTML, BlockBuilder.build(RenderFixtures.POST_HTML)))
        assertNull(TextFidelity.wordDiff(SampleHtmlWords.POST, BlockBuilder.build(SampleHtml.POST)))
    }

    @Test
    fun fidelityOracleCatchesLostJoinedOrReorderedWords() {
        assertNotNull(TextFidelity.diff("<p>a b</p>", listOf(p(t("a")))))
        assertNotNull(TextFidelity.diff("<p>a</p><p>b</p>", listOf(p(t("b")), p(t("a")))))
        assertNotNull(TextFidelity.diff("<table><tr><td>x</td></tr></table>", emptyList()))
        // Joined words (a lost collapsed space) and split words both fail.
        assertNotNull(TextFidelity.diff("<p>a <b>b</b></p>", listOf(p(t("ab")))))
        assertNotNull(TextFidelity.diff("<p>ab</p>", listOf(p(t("a b")))))
        assertNotNull(TextFidelity.diff("<p>a</p><p>b</p>", listOf(p(t("ab")))))
        assertNull(TextFidelity.diff("<p>a <b>b</b></p>", listOf(p(t("a b")))))
        // Deep markup does not overflow the oracle's stack.
        assertEquals(listOf("x"), TextFidelity.htmlWords("<b>".repeat(20000) + "x"))
    }

    @Test
    fun paragraphsCollapseWhitespaceAndKeepBreaks() {
        assertEquals(
            listOf(
                p(t("First paragraph"), Inline.LineBreak, t("wraps here.")),
                p(t("Second with a hard"), Inline.LineBreak, t("break.")),
            ),
            build("paragraphs"),
        )
    }

    @Test
    fun marks() {
        assertEquals(
            listOf(
                p(
                    t("Some "), t("bold", Mark.Bold), t(", "), t("italic", Mark.Italic), t(", "), t("struck", Mark.Strike), t(", "),
                    t("code", Mark.Code), t(", under, "), t("2", Mark.Sub), t(", "), t("3", Mark.Sup), t(" and "), t("both", Mark.Bold, Mark.Italic), t("."),
                ),
            ),
            build("marks"),
        )
        // <u> is stripped by the sanitizer; hand-written u, ins, mark still map.
        assertEquals(listOf(p(t("ab", Mark.Underline), t("c", Mark.Highlight))), BlockBuilder.build("<p><u>a</u><ins>b</ins><mark>c</mark></p>"))
    }

    @Test
    fun headings() {
        assertEquals((1..6).map { Block.Heading(it, listOf(t("H$it"))) }, build("headings"))
    }

    @Test
    fun mentionAndTagChips() {
        assertEquals(listOf(p(t("Hi "), Inline.Mention("spike-user"), t(", thanks."))), build("mention"))
        assertEquals(listOf(p(t("Posted in "), Inline.Tag("spiketag"), t(" today."))), build("tag"))
        // Chip text differing only in case still becomes a chip (the RN app shows the attribute).
        assertEquals(
            listOf(p(Inline.Tag("hive"))),
            BlockBuilder.build("<p><a class=\"markdown-tag-link\" data-tag=\"hive\">#Hive</a></p>"),
        )
    }

    @Test
    fun namedAuthorAndTagLinksKeepTheirText() {
        assertEquals(
            listOf(p(t("Thanks to "), Inline.Link(LinkTarget.Author("spike-user"), listOf(t("my friend"))), t("."))),
            build("author_link_named"),
        )
        assertEquals(
            listOf(p(t("Join "), Inline.Link(LinkTarget.Tag("hive-123456", "created"), listOf(t("our community"))), t("."))),
            build("community_tag_link"),
        )
    }

    @Test
    fun communityLinks() {
        assertEquals(
            listOf(
                p(
                    t("Join "), Inline.Link(LinkTarget.Community("hive-123456", "created"), listOf(t("our community"))), t(" or "),
                    Inline.Link(LinkTarget.Community("hive-123456", "trending"), listOf(t("trending/hive-123456"))),
                ),
            ),
            build("community_link"),
        )
    }

    @Test
    fun postLinks() {
        assertEquals(
            listOf(
                p(t("See")),
                Block.PostPreview("spike-author", "spike-permlink", "https://ecency.com/hive-123456/@spike-author/spike-permlink"),
                p(t("for more.")),
            ),
            build("post_link_inline"),
        )
        assertEquals(
            listOf(p(t("Read "), Inline.Link(LinkTarget.Post("spike-author", "spike-permlink"), listOf(t("this post"))), t("."))),
            build("post_link_named"),
        )
    }

    @Test
    fun postLinkPermlinkRulesFollowLinkDataParser() {
        assertEquals(LinkTarget.Post("bob", "c-1"), BlockBuilder.postTarget("alice", "x#@bob/c-1"))
        assertEquals(LinkTarget.Post("alice", "p"), BlockBuilder.postTarget("alice", "p?ref=x"))
        assertNull(BlockBuilder.postTarget("alice", null))
        assertNull(BlockBuilder.postTarget(null, "p"))
        // Without a permlink the RN app does nothing; the link falls back to its href.
        assertEquals(
            listOf(p(Inline.Link(LinkTarget.External("https://x.example/y"), listOf(t("x"))))),
            BlockBuilder.build("<p><a class=\"markdown-post-link\" data-href=\"https://x.example/y\" data-author=\"alice\" data-is-inline=\"true\">x</a></p>"),
        )
    }

    @Test
    fun externalLinks() {
        assertEquals(
            listOf(
                p(
                    t("Visit "), Inline.Link(LinkTarget.External("https://example.com/page?x=1&y=2"), listOf(t("example"))), t(" and "),
                    Inline.Link(LinkTarget.External("https://example.org"), listOf(t("https://example.org"))), t("."),
                ),
            ),
            build("external_link"),
        )
        assertEquals(listOf(p(Inline.Link(LinkTarget.AppUri("hive://sign/op/WyJ2b3RlIix7fV0."), listOf(t("sign"))))), build("hive_uri"))
        assertEquals(
            listOf(p(Inline.Link(LinkTarget.External("https://ecency.com/@spike-user/posts"), listOf(t("@spike-user/posts"))))),
            build("profile_link"),
        )
        val witness = build("witnesses").single() as Block.Paragraph
        assertEquals(LinkTarget.External("https://hivesigner.com/sign/account-witness-vote?witness=spike-user&approve=1"), (witness.inlines[1] as Inline.Link).target)
        assertEquals(
            listOf(p(Inline.Link(LinkTarget.External("https://ecency.com/proposals/1"), listOf(t("p"))))),
            BlockBuilder.build("<p><a class=\"markdown-proposal-link\" data-proposal=\"1\" data-href=\"https://ecency.com/proposals/1\">p</a></p>"),
        )
        // Anchors with no target (named anchors) are plain text, marked as a jump target before their block (#2403).
        assertEquals(listOf(Block.Anchor(listOf("top")), p(t("x"))), BlockBuilder.build("<p><a id=\"top\"></a>x</p>"))
    }

    @Test
    fun youtubeLinksAndVideos() {
        val thumb = "https://images.ecency.com/p/S5Eokt4BcQdk7EHeT1aYjzebg2hC7hkthT45eGLbn31bJ5Q9ftb8TvgB67rg3Yh1juwgS7G?format=match&mode=fit"
        assertEquals(listOf(Block.Video("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1", thumb, "dQw4w9WgXcQ", 0)), build("youtube"))
        assertEquals(listOf(Block.Video("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1", thumb, "dQw4w9WgXcQ", 42)), build("youtube_start"))
        assertEquals(listOf(Block.Video("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1", thumb, "dQw4w9WgXcQ", 0)), build("youtube_short"))
        assertEquals(
            listOf(p(t("Watch "), Inline.Link(LinkTarget.Youtube("dQw4w9WgXcQ", 1), listOf(t("the clip"))), t(" now."))),
            build("youtube_named"),
        )
        assertEquals(listOf(Block.Video("https://play.3speak.tv/watch?v=spike-user/abcdefgh&mode=iframe", null)), build("threespeak"))
        assertEquals(listOf(Block.Video("https://player.vimeo.com/video/123456789", null)), build("vimeo"))
        assertEquals(listOf(Block.Video("https://player.vimeo.com/video/123456789", null)), build("iframe"))
        // Not an allowlisted player: dropped, as the RN app does not render <video>.
        assertEquals(emptyList(), build("video_tag"))
        assertEquals(
            listOf(Block.Video("https://www.youtube.com/embed/abcdefghijk", null, "abcdefghijk")),
            BlockBuilder.build("<iframe src=\"https://www.youtube.com/embed/abcdefghijk\"></iframe>"),
        )
        assertEquals(
            listOf(Block.Video("https://player.vimeo.com/video/1", null), p(t("label"))),
            BlockBuilder.build("<a class=\"markdown-video-link\" data-video-href=\"https://player.vimeo.com/video/1\">label</a>"),
        )
    }

    @Test
    fun parseIntMatchesJs() {
        assertEquals(42, BlockBuilder.parseIntJs(" 42s"))
        assertEquals(1, BlockBuilder.parseIntJs("1m30s"))
        assertEquals(0, BlockBuilder.parseIntJs("abc"))
        assertEquals(0, BlockBuilder.parseIntJs(null))
        assertEquals(-3, BlockBuilder.parseIntJs("-3"))
    }

    @Test
    fun styleClasses() {
        // Top-level containers of text are flattened; the text blocks carry the style.
        assertEquals(listOf(p(t("Danger zone"), style = BlockStyle(phishy = true))), build("phishy"))
        assertEquals(listOf(p(t("Justified paragraph text."), style = BlockStyle(justify = true))), build("text_justify"))
        assertEquals(listOf(p(t("Centered by class."), style = center)), build("text_center"))
        val end = BlockStyle(align = BlockStyle.Align.End)
        assertEquals(listOf(p(t("Right by class."), style = end)), build("text_right"))
        assertEquals(listOf(p(t("Aligned centre"), style = center), p(t("Aligned right"), style = end)), build("data_align"))
        // left is absolute too (an Arabic body starts on the right, but text-left stays on the left)
        val left = BlockStyle(align = BlockStyle.Align.Left)
        assertEquals(listOf(p(t("Left"), style = left)), BlockBuilder.build("<div class=\"text-left\">Left</div>", BuildOptions()))
        assertEquals(listOf(p(t("Left"), style = left)), BlockBuilder.build("<p data-align=\"left\">Left</p>", BuildOptions()))
    }

    @Test
    fun centerWrapsImagesAndText() {
        // Flattened for lazy lists: the image keeps a one-block container for its alignment.
        assertEquals(
            listOf(Block.Container(listOf(Block.Image("https://images.ecency.com/p/abc.png", "centered")), center), p(t("Centered caption"), style = center)),
            build("center"),
        )
        // Nested containers stay whole below the top level.
        val q = BlockBuilder.build("<blockquote><center><img src=\"https://x.example/a.png\" /> cap</center></blockquote>").single() as Block.Quote
        assertEquals(listOf(Block.Container(listOf(Block.Image(px("https://x.example/a.png"), null), p(t("cap"), style = center)), center)), q.blocks)
    }

    @Test
    fun pullClassesAreNotHonoured() {
        assertEquals(listOf(Block.Image("https://images.ecency.com/p/left.png", "left"), p(t("Right text"))), build("pull"))
    }

    @Test
    fun alignedParagraphWithImageBecomesContainer() {
        assertEquals(
            listOf(Block.Container(listOf(Block.Image(px("https://x.example/a.png"), null)), center), p(t("cap"), style = center)),
            BlockBuilder.build("<p data-align=\"center\"><img src=\"https://x.example/a.png\" /> cap</p>"),
        )
    }

    @Test
    fun tables() {
        fun cell(h: Boolean, vararg inl: Inline) = Block.Table.Cell(h, listOf(p(*inl)))
        assertEquals(
            listOf(
                Block.Table(
                    listOf(
                        Block.Table.Row(listOf(cell(true, t("Coin")), cell(true, t("Price")))),
                        Block.Table.Row(listOf(cell(false, t("HIVE")), cell(false, t("0.30")))),
                        Block.Table.Row(listOf(cell(false, t("HBD")), cell(false, t("1.00", Mark.Bold)))),
                    ),
                ),
            ),
            build("table"),
        )
        assertEquals(listOf(Block.Table(listOf(Block.Table.Row(listOf(cell(true, t("h")), cell(false, t("d"))))))), build("table_no_head"))
        assertEquals(5, (build("table_wide").single() as Block.Table).maxHeaderColumns)
    }

    @Test
    fun malformedTablesKeepStrayContent() {
        // Content outside rows stays in document order (splitting the table); stray content in a row becomes a cell.
        val blocks = BlockBuilder.build("<table><p>stray</p><tr><td>a</td><div>loose</div></tr><td>orphan</td><tr><td>z</td></tr></table>")
        assertEquals(p(t("stray")), blocks[0])
        val table = blocks[1] as Block.Table
        assertEquals(listOf(Block.Table.Cell(false, listOf(p(t("a")))), Block.Table.Cell(false, listOf(p(t("loose"))))), table.rows.single().cells)
        assertEquals(p(t("orphan")), blocks[2])
        assertEquals(Block.Table(listOf(Block.Table.Row(listOf(Block.Table.Cell(false, listOf(p(t("z")))))))), blocks[3])
        assertEquals(emptyList(), BlockBuilder.build("<table></table>"))
    }

    @Test
    fun details() {
        // render-helper's sanitizer strips details/summary; the result is plain paragraphs.
        assertEquals(listOf(p(t("SpoilerHidden"))), build("details_sanitized"))
        assertEquals(
            listOf(Block.Details(listOf(t("Spoiler "), t("here", Mark.Bold)), listOf(p(t("Hidden "), t("text", Mark.Bold))))),
            BlockBuilder.build("<details><summary>Spoiler <b>here</b></summary>\n<p>Hidden <strong>text</strong></p></details>"),
        )
    }

    @Test
    fun rulesAndCode() {
        assertEquals(listOf(p(t("above")), Block.Rule, p(t("below"))), build("hr"))
        assertEquals(listOf(Block.Code("const x = 1 < 2 && \"a\";\n  indented()")), build("code_block"))
        assertEquals(listOf(Block.Code("four space\ncode")), build("code_indented"))
        assertEquals(listOf(Block.Code("a  b\n  c")), BlockBuilder.build("<pre><b>a  b</b>\n  c\n</pre>"))
    }

    @Test
    fun lists() {
        assertEquals(
            listOf(
                Block.ListBlock(
                    true, 1,
                    listOf(
                        listOf(p(t("First"))),
                        listOf(p(t("Second")), Block.ListBlock(false, 1, listOf(listOf(p(t("nested a"))), listOf(p(t("nested b")))))),
                        listOf(p(t("Third"))),
                    ),
                ),
            ),
            build("lists"),
        )
        assertEquals(listOf(Block.ListBlock(false, 1, listOf(listOf(p(t("one"))), listOf(p(t("two")), p(t("second para")))))), build("loose_list"))
        // The sanitizer drops `start`; hand-written lists keep it.
        assertEquals(1, (build("ordered_start").single() as Block.ListBlock).start)
        assertEquals(5, (BlockBuilder.build("<ol start=\"5\"><li>five</li></ol>").single() as Block.ListBlock).start)
        // A nested list written without its li joins the item above.
        assertEquals(
            listOf(Block.ListBlock(false, 1, listOf(listOf(p(t("a")), Block.ListBlock(false, 1, listOf(listOf(p(t("b"))))))))),
            BlockBuilder.build("<ul><li>a</li><ul><li>b</li></ul></ul>"),
        )
        assertEquals(listOf(Block.ListBlock(false, 1, listOf(listOf(p(t("lone")))))), BlockBuilder.build("<li>lone</li>"))
    }

    @Test
    fun blockquotes() {
        assertEquals(
            listOf(Block.Quote(listOf(p(t("A quote"), Inline.LineBreak, t("over two lines")), Block.Quote(listOf(p(t("nested"))))))),
            build("blockquote"),
        )
    }

    @Test
    fun images() {
        assertEquals(listOf(Block.Image("https://images.ecency.com/p/abc.png", "alt text")), build("image"))
        assertEquals(
            listOf(Block.Image("https://images.ecency.com/p/MvwLKznkcWQJGJABp5CQ2bMLkt9toX2AC22ZwxyDQ?format=match&mode=fit", null)),
            build("image_bare"),
        )
        assertEquals(
            listOf(Block.Image("https://images.ecency.com/p/abc.png", "linked", LinkTarget.External("https://example.com/target"))),
            build("image_in_link"),
        )
        assertEquals(listOf(p(t("before")), Block.Image("https://images.ecency.com/p/mid.png", "mid"), p(t("after"))), build("image_in_text"))
        // An image inside an inline post link is a linked image, not a preview card.
        assertEquals(
            listOf(Block.Image(px("https://x.example/i.png"), null, LinkTarget.Post("alice", "b"))),
            BlockBuilder.build("<p><a class=\"markdown-post-link\" data-is-inline=\"true\" data-author=\"alice\" data-permlink=\"b\"><img src=\"https://x.example/i.png\" /></a></p>"),
        )
        // Text and an image in one link: the text links, the image opens the gallery (RN: only a lone image follows the link).
        val target = LinkTarget.External("https://x.example")
        assertEquals(
            listOf(p(Inline.Link(target, listOf(t("go")))), Block.Image(px("https://x.example/i.png"), null), p(Inline.Link(target, listOf(t("on"))))),
            BlockBuilder.build("<p><a class=\"markdown-external-link\" data-href=\"https://x.example\">go <img src=\"https://x.example/i.png\" /> on</a></p>"),
        )
        assertEquals(emptyList(), BlockBuilder.build("<p><img alt=\"no src\" /></p>"))
    }

    @Test
    fun spansAreFlattened() {
        assertEquals(listOf(p(t("span nested text"))), build("spans"))
    }

    @Test
    fun blockInsideInlineSplitsTheParagraph() {
        assertEquals(listOf(p(t("bold", Mark.Bold)), p(t("block in bold", Mark.Bold)), p(t("tail", Mark.Bold))), build("html_block_in_inline"))
        assertEquals(listOf(Block.Heading(2, listOf(t("Div in heading")), BlockStyle(phishy = true))), build("heading_with_div"))
    }

    @Test
    fun entitiesAndUnicode() {
        assertEquals(listOf(p(t("Fish & chips <tag>  nbsp ©"))), build("entities"))
        assertEquals(listOf(p(t("مرحبا بالعالم")), p(t("שלום עולם"))), build("rtl"))
    }

    @Test
    fun unknownTagsAndEmptyParagraphs() {
        assertEquals(listOf(p(t("Inside abbr custom"))), build("unknown_tags"))
        assertEquals(listOf(p(t("x "), t("y", Mark.Bold))), BlockBuilder.build("<section><custom-el>x <b>y</b></custom-el></section>"))
        assertEquals(listOf(p(t("text"))), build("empty_paragraphs"))
        assertEquals(emptyList(), BlockBuilder.build("<p> \n </p><p><br /></p><div></div><script>alert(1)</script>"))
    }

    @Test
    fun whitespaceAroundInlineBoundaries() {
        // As in a browser, the first space of a collapsed run survives and keeps its element's marks.
        assertEquals(listOf(p(t("a "), t("b ", Mark.Bold), t("c"))), BlockBuilder.build("<p>a <b> b </b> c</p>"))
        assertEquals(listOf(p(t("x"), Inline.LineBreak, t("y"))), BlockBuilder.build("<p>  x  <br />  y  <br /> </p>"))
        val link = LinkTarget.External("https://x.example")
        assertEquals(
            listOf(p(t("see "), Inline.Link(link, listOf(t("the "), t("link", Mark.Bold))), t("."))),
            BlockBuilder.build("<p>see <a class=\"markdown-external-link\" data-href=\"https://x.example\">the <b>link</b></a>.</p>"),
        )
    }

    @Test
    fun waveImageGridOnlyWhenAsked() {
        val img = { n: String -> Block.Image("https://images.ecency.com/p/$n.png", n) }
        assertEquals(listOf(img("a"), img("b"), img("c"), p(t("text")), img("d")), build("wave_images"))
        assertEquals(listOf(Block.ImageGrid(listOf(img("a"), img("b"), img("c"))), p(t("text")), img("d")), build("wave_images", waves = true))
        // Inside <center> too, as the RN app's regex runs over the whole body.
        val two = "<p><img src=\"https://x.example/1.png\" /></p>\n<p><img src=\"https://x.example/2.png\" /></p>"
        val grid = Block.ImageGrid(listOf(Block.Image(px("https://x.example/1.png"), null), Block.Image(px("https://x.example/2.png"), null)))
        assertEquals(listOf(Block.Container(listOf(grid), center)), BlockBuilder.build("<center>$two</center>", BuildOptions(waves = true)))
    }

    @Test
    fun neverThrows() {
        val nasty = listOf(
            "", "   ", "<", "<p>", "</p></div>", "<a", "<p><b>unclosed", "<<<>>>", "&#xFFFFFFFF;", "<table><tr><td>",
            "<ul><li><ol><li>", "<img src=>", "<a class=\"markdown-video-link\"></a>", "<div class=\"phishy\">" + "<div>".repeat(500) + "deep" + "</div>".repeat(501),
            "<b>".repeat(2000) + "deep text",
        )
        for (html in nasty) BlockBuilder.build(html)
        val deep = BlockBuilder.build("<div>".repeat(400) + "deep text" + "</div>".repeat(400))
        assertTrue(BlockText.of(deep).contains("deep text"))
    }

    @Test
    fun fixturePostRendersEveryKind() {
        val blocks = BlockBuilder.build(RenderFixtures.POST_HTML)
        assertEquals(Block.Heading(1, listOf(t("Heading"))), blocks[0])
        assertEquals(Block.Heading(2, listOf(t("Sub heading"))), blocks[1])
        assertEquals(Block.Rule, blocks[3])
        assertIs<Block.Quote>(blocks[4])
        assertTrue(blocks.none { it is Block.Container })
        assertEquals(3, blocks.count { it is Block.Image })
    }

    @Test
    fun sampleHtmlBuildsTheDemoPost() {
        val blocks = BlockBuilder.build(SampleHtml.POST)
        val kinds = mutableSetOf<String>()
        fun walk(b: Block) {
            kinds += b::class.simpleName!!
            when (b) {
                is Block.Container -> b.blocks.forEach(::walk)
                is Block.Quote -> b.blocks.forEach(::walk)
                is Block.ListBlock -> b.items.flatten().forEach(::walk)
                else -> {}
            }
        }
        blocks.forEach(::walk)
        for (k in listOf("Heading", "Paragraph", "Image", "ListBlock", "Quote", "Code", "Rule", "Table", "Video", "PostPreview")) {
            assertTrue(k in kinds, "missing $k in $kinds")
        }
    }

    @Test
    fun bodyImagesAlwaysLoadThroughTheProxy() {
        fun html(body: String) = BlockBuilder.build(body, BuildOptions())
        // through the whole pipeline: an author's own class="no-replace" survives render-helper and skips its proxy
        val rendered = com.ecency.core.render.RenderHelper().renderPostBody("""<img class="no-replace" src="https://tracker.example/pixel.png">""", true)
        assertTrue("tracker.example/pixel.png" in rendered && "no-replace" in rendered, rendered)
        assertTrue(BlockBuilder.build(rendered, BuildOptions()).filterIsInstance<Block.Image>().all { it.src.startsWith("https://images.ecency.com/") }, rendered)
        // render-helper leaves class="no-replace" images unproxied, so an author could make every reader's device
        // fetch from their own server (the reader's IP); the app only loads images through the proxy
        val images = html("""<p><img class="no-replace" src="https://tracker.example/pixel.png" alt="x"></p>""")
            .filterIsInstance<Block.Image>()
        assertEquals(1, images.size)
        assertTrue(images.single().src.startsWith("https://images.ecency.com/"), images.single().src)
        // too long for the proxy: not loaded at all rather than fetched directly
        val long = "https://tracker.example/" + "a".repeat(2100) + ".png"
        assertTrue(html("""<p><img src="$long"></p>""").filterIsInstance<Block.Image>().none { !it.src.startsWith("https://images.ecency.com/") })
        // already proxied images are kept as they are
        assertEquals("https://images.ecency.com/p/abc.png", html("""<p><img src="https://images.ecency.com/p/abc.png"></p>""").filterIsInstance<Block.Image>().single().src)
    }
}
