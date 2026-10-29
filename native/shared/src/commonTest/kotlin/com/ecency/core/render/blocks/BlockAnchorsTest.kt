package com.ecency.core.render.blocks

import com.ecency.core.app.LinkRouter
import com.ecency.core.app.Route
import com.ecency.core.render.RenderHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #2403: in-post anchors through the whole pipeline (render-helper port, then blocks). Synthetic bodies only. */
class BlockAnchorsTest {
    private val body = """
        <a id="top"></a>
        <center><sup><a href="#english" target="_self">English</a> ° <a href="#deutsch" target="_self">Deutsch</a></sup></center>

        <a data-id="english"></a>
        ## First section
        Some text with [a jump](#deutsch) and [a slug jump](#third-part-2).

        <div class="text-justify">
        Intro of the second part.
        <a id="deutsch"></a>

        ## Zweiter Teil
        Mehr Text <span id="inline_spot">hier</span>.
        </div>

        > quoted <a id="in-quote"></a> text

        ## Third part 2
        [this post](https://ecency.com/hive-100000/@spike-alice/anchor-post#deutsch)
        [another post](https://ecency.com/@spike-bob/other-post#sec)
        [a comment](https://ecency.com/hive-100000/@spike-alice/anchor-post#@spike-bob/re-anchor-post)
        <a name="old-style">named</a> <a id="9bad"></a> [top](#) [encoded](#Zweiter%20Teil)
    """.trimIndent()

    private val blocks = BlockBuilder.build(RenderHelper().renderPostBody(body, forApp = true))
    private val here = LinkTarget.Post("spike-alice", "anchor-post")

    private fun links(): List<LinkTarget> {
        val out = ArrayList<LinkTarget>()
        fun inl(list: List<Inline>) {
            for (i in list) if (i is Inline.Link) out += i.target
        }
        fun walk(b: Block) {
            when (b) {
                is Block.Paragraph -> inl(b.inlines)
                is Block.Heading -> inl(b.inlines)
                is Block.Container -> b.blocks.forEach(::walk)
                is Block.Quote -> b.blocks.forEach(::walk)
                else -> {}
            }
        }
        blocks.forEach(::walk)
        return out
    }

    private fun headingAfter(index: Int): String? =
        blocks.drop(index).firstOrNull { it is Block.Heading }?.let { BlockText.ofInlines((it as Block.Heading).inlines) }

    @Test
    fun idsAndDataIdsMarkTheBlockTheyStart() {
        val index = BlockAnchors.index(blocks)
        assertEquals(setOf("top", "english", "deutsch", "inline_spot", "in-quote"), index.keys)
        assertEquals(0, index["top"])
        // an anchor sits right before the block that holds it, and draws nothing
        assertIs<Block.Anchor>(blocks[index.getValue("english")])
        assertEquals("First section", headingAfter(index.getValue("english")))
        assertEquals("Zweiter Teil", headingAfter(index.getValue("deutsch")))
        // inside a quote: the quote is the target
        assertIs<Block.Quote>(blocks[index.getValue("in-quote")])
        // render-helper's sanitizer drops `name` and ids that are not names, so neither is a target
        assertNull(index["old-style"])
        assertNull(index["9bad"])
        // nothing of an anchor is text
        assertTrue("old-style" !in BlockText.of(blocks))
    }

    @Test
    fun fragmentLinksAreJumpsAndOtherPostsStayPosts() {
        val targets = links()
        assertTrue(LinkTarget.Anchor("english") in targets)
        assertTrue(LinkTarget.Anchor("deutsch") in targets)
        assertTrue(LinkTarget.Anchor("") in targets, "# alone")
        assertTrue(LinkTarget.Anchor("Zweiter Teil") in targets, "percent-decoded")
        assertTrue(LinkTarget.Anchor("deutsch", here) in targets)
        assertTrue(LinkTarget.Anchor("sec", LinkTarget.Post("spike-bob", "other-post")) in targets)
        // `#@author/permlink` is the comment form, never an anchor: the link stays a post link
        assertTrue(here in targets)
    }

    @Test
    fun jumpsResolveIdsSlugsAndTheTopOnThisPostOnly() {
        val deutsch = BlockAnchors.index(blocks).getValue("deutsch")
        assertEquals(deutsch, BlockAnchors.jump(blocks, LinkTarget.Anchor("deutsch"), here))
        assertEquals(deutsch, BlockAnchors.jump(blocks, LinkTarget.Anchor("deutsch", here), here))
        assertEquals(deutsch, BlockAnchors.jump(blocks, LinkTarget.Anchor("deutsch", LinkTarget.Post("Spike-Alice", "anchor-post")), here))
        assertNull(BlockAnchors.jump(blocks, LinkTarget.Anchor("sec", LinkTarget.Post("spike-bob", "other-post")), here), "another post opens")
        // a heading without an id by its slug, as links written for GitHub style sites expect
        val slug = BlockAnchors.resolve(blocks, "third-part-2")!!
        assertEquals("Third part 2", BlockText.ofInlines((blocks[slug] as Block.Heading).inlines))
        assertEquals(BlockAnchors.resolve(blocks, "zweiter-teil"), BlockAnchors.resolve(blocks, "Zweiter Teil"))
        // `#` and `#top` are the top; an unknown id is nowhere
        assertEquals(0, BlockAnchors.resolve(blocks, ""))
        assertEquals(0, BlockAnchors.resolve(listOf(Block.Rule, Block.Rule), "TOP"))
        assertNull(BlockAnchors.resolve(blocks, "nowhere"))
        assertEquals("hello-world", BlockAnchors.slug(" Hello, World! "))
    }

    @Test
    fun anAnchorRoutesToNoScreenUnlessItNamesAnotherPost() {
        assertEquals(Route.InPage("deutsch"), LinkRouter.route(LinkTarget.Anchor("deutsch")))
        assertEquals(Route.Post("spike-bob", "other-post"), LinkRouter.route(LinkTarget.Anchor("sec", LinkTarget.Post("spike-bob", "other-post"))))
        assertNull(com.ecency.core.app.NavEntry.from(Route.InPage("x")))
    }

    @Test
    fun anchorsSurviveContainersAndTheEndOfTheBody() {
        val built = BlockBuilder.build("<div class=\"text-center\"><p>a</p><img src=\"https://images.ecency.com/p/x.png\"><a id=\"end\"></a></div>")
        val index = BlockAnchors.index(built)
        assertTrue("end" in index)
        assertTrue(index.getValue("end") in built.indices)
        // a heading with an id of its own
        val heading = BlockBuilder.build("<p>before</p><h2 id=\"sec-1\">Section</h2>")
        assertIs<Block.Anchor>(heading[BlockAnchors.index(heading).getValue("sec-1")])
        assertIs<Block.Heading>(heading[BlockAnchors.index(heading).getValue("sec-1") + 1])
    }
}
