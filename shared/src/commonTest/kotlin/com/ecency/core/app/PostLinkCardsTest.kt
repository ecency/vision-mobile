package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostMetadata
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.LinkCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #3705: a post link card shows the linked post's title, summary and thumbnail. */
class PostLinkCardsTest {
    private val link = "https://ecency.com/hive-123456/@spike-author/spike-permlink"

    private fun previews(blocks: List<Block>): List<Block.PostPreview> = blocks.filterIsInstance<Block.PostPreview>()

    private fun linking(meta: String): Post = Post(
        author = "spike-reader",
        permlink = "an-index",
        body = "See\n\n$link\n\nfor more.",
        metadata = PostMetadata.parse(Json.parseToJsonElement(meta)),
    )

    // ---- links_meta: text until the read, never an image ----

    @Test
    fun linksMetaGivesTheCardsTextButNeverAnImage() {
        val renderer = RenderHelperPostBodyRenderer()
        val post = linking("""{"links_meta":{"$link":{"title":"A linked post","summary":"What it says first.","image":"https://example.com/a.jpg"}}}""")
        // any author writes anything there: the image could be an NSFW post's cover, or one the linked post does not have
        assertEquals(LinkCard("A linked post", "What it says first.", null), previews(renderer.blocks(post)).single().card)
    }

    @Test
    fun linksMetaIsReadCarefully() {
        val renderer = RenderHelperPostBodyRenderer()
        assertNull(previews(renderer.blocks(linking("""{"links_meta":{"$link":{"title":"  ","summary":"x"}}}"""))).single().card)
        assertNull(previews(renderer.blocks(linking("""{"links_meta":{"$link":null}}"""))).single().card)
        assertNull(previews(renderer.blocks(linking("""{"links_meta":"broken"}"""))).single().card)
        assertEquals(LinkCard("T", "", null), previews(renderer.blocks(linking("""{"links_meta":{"$link":{"title":"T","summary":7}}}"""))).single().card)
        // long text is cut: a title to 256, a summary to the feed card's 150
        val long = linking("""{"links_meta":{"$link":{"title":"${"t".repeat(5_000)}","summary":"${"s".repeat(5_000)}"}}}""")
        val cut = assertNotNull(previews(renderer.blocks(long)).single().card)
        assertEquals(256, cut.title.length)
        assertEquals(150, cut.summary.length)
        // another post's entry is not this card's; the same post on another frontend or with a query is
        assertNull(previews(renderer.blocks(linking("""{"links_meta":{"https://ecency.com/@spike-other/elsewhere":{"title":"Other"}}}"""))).single().card)
        assertEquals("Peak", previews(renderer.blocks(linking("""{"links_meta":{"https://peakd.com/@spike-author/spike-permlink?ref=x":{"title":"Peak"}}}"""))).single().card?.title)
    }

    // ---- read when shown ----

    private class Chain {
        var gets = 0
        val getsOf = mutableMapOf<String, Int>()
        var running = 0
        var most = 0
        var gate: CompletableDeferred<Unit>? = null
        var answer: (String) -> String? = { permlink ->
            """{"author":"spike-author","permlink":"$permlink","title":"Title of $permlink","body":"The linked post's own text, long enough to read.","json_metadata":{"image":["https://example.com/$permlink.jpg"]},"author_reputation":60,"stats":{"gray":false}}"""
        }
    }

    private fun app(scope: TestScope, chain: Chain) = TestApp(scope) { req ->
        if (req.method == "bridge.get_post") {
            chain.gets++
            chain.running++
            chain.most = maxOf(chain.most, chain.running)
            try {
                chain.gate?.await()
            } finally {
                chain.running--
            }
            val permlink = req.params.jsonObject["permlink"]!!.jsonPrimitive.content
            chain.getsOf[permlink] = (chain.getsOf[permlink] ?: 0) + 1
            val a = chain.answer(permlink)
            if (a == "fail") com.ecency.core.api.rpc.HttpResult(500, "down") else FakeTransport.result(req, a ?: "null")
        } else {
            TestApp.chain(req) ?: com.ecency.core.api.rpc.HttpResult(500, "")
        }
    }

    @Test
    fun aCardWithoutLinksMetaReadsTheLinkedPostOnceWhenShown() = runTest {
        val chain = Chain()
        val app = app(this, chain)
        val cards = app.env.postLinkCards
        assertNull(cards.card("spike-author", "first"))
        assertEquals(0, chain.gets, "nothing is read before the card shows")
        cards.shown("spike-author", "first")
        cards.shown("spike-author", "first")
        testScheduler.advanceUntilIdle()
        cards.shown("spike-author", "first")
        testScheduler.advanceUntilIdle()
        assertEquals(1, chain.gets, "shown three times, read once")
        val card = assertNotNull(cards.card("spike-author", "first"))
        assertEquals("Title of first", card.title)
        assertTrue(card.summary.startsWith("The linked post's own text"), card.summary)
        val image = assertNotNull(card.image)
        assertTrue("width=${PostLinkCards.THUMB_PX}&height=${PostLinkCards.THUMB_PX}" in image, "a thumbnail's size, not a feed card's: $image")
        assertFalse(cards.isPlain("spike-author", "first"))
        // an author the reader mutes: the plain card at once (the state changes, so cards on screen redraw)
        val before = cards.current.mutes
        app.env.setAuthorMuted("spike-author", true)
        testScheduler.advanceUntilIdle()
        assertTrue(cards.current.mutes > before)
        assertNull(cards.card("spike-author", "first"))
        assertTrue(cards.isPlain("spike-author", "first"))
        assertEquals(card, cards.current.cards["spike-author/first"])
    }

    @Test
    fun aMissingMutedOrFailedPostKeepsThePlainCard() = runTest {
        val chain = Chain()
        chain.answer = { permlink ->
            when (permlink) {
                "gone" -> null
                "muted" -> """{"author":"spike-author","permlink":"muted","title":"Hidden","body":"x","stats":{"gray":true}}"""
                "down" -> "fail"
                else -> """{"author":"spike-author","permlink":"$permlink","title":"T","body":"b"}"""
            }
        }
        val app = app(this, chain)
        val cards = app.env.postLinkCards
        listOf("gone", "muted", "down").forEach { cards.shown("spike-author", it) }
        testScheduler.advanceUntilIdle()
        listOf("gone", "muted", "down").forEach { assertNull(cards.card("spike-author", it), it) }
        // the linking post's links_meta text never stands in for a post the read rejected; a failed read is not settled
        assertTrue(cards.isPlain("spike-author", "gone") && cards.isPlain("spike-author", "muted"))
        assertFalse(cards.isPlain("spike-author", "down"))
        val before = chain.getsOf.toMap()
        // a missing or muted post is not read again; a failed read is, the next time the card shows
        listOf("gone", "muted", "down").forEach { cards.shown("spike-author", it) }
        testScheduler.advanceUntilIdle()
        assertEquals(before["gone"], chain.getsOf["gone"])
        assertEquals(before["muted"], chain.getsOf["muted"])
        assertTrue(chain.getsOf.getValue("down") > before.getValue("down"), "the failed one is read again")
        chain.answer = { """{"author":"spike-author","permlink":"$it","title":"Back","body":"b"}""" }
        cards.shown("spike-author", "down")
        testScheduler.advanceUntilIdle()
        assertEquals("Back", cards.card("spike-author", "down")?.title)
    }

    @Test
    fun aPostWithoutATitleOrAnInvalidLinkGivesNoCard() = runTest {
        val chain = Chain()
        chain.answer = { """{"author":"spike-author","permlink":"$it","title":"  ","body":"a comment, not a post"}""" }
        val app = app(this, chain)
        val cards = app.env.postLinkCards
        cards.shown("spike-author", "untitled")
        cards.shown("Not An Account!", "p")
        cards.shown("spike-author", "")
        testScheduler.advanceUntilIdle()
        assertNull(cards.card("spike-author", "untitled"))
        assertEquals(listOf("untitled"), chain.getsOf.keys.toList(), "an invalid link is never read")
        assertTrue(cards.current.cards.isEmpty())
    }

    @Test
    fun aFewReadsRunAtOnce() = runTest {
        val chain = Chain()
        chain.gate = CompletableDeferred()
        val app = app(this, chain)
        val cards = app.env.postLinkCards
        (1..8).forEach { cards.shown("spike-author", "p$it") }
        // not past any timeout: the reads are held, not timed out
        repeat(20) { testScheduler.runCurrent() }
        assertEquals(PostLinkCards.MAX_READS, chain.running, "the rest wait")
        chain.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(PostLinkCards.MAX_READS, chain.most)
        assertEquals(8, chain.gets)
        (1..8).forEach { assertNotNull(cards.card("spike-author", "p$it")) }
    }

    @Test
    fun anNsfwPostShowsNoThumbnail() = runTest {
        val chain = Chain()
        chain.answer = { """{"author":"spike-author","permlink":"$it","title":"T","body":"b","json_metadata":{"tags":["nsfw"],"image":["https://example.com/x.jpg"]}}""" }
        val app = app(this, chain)
        app.env.postLinkCards.shown("spike-author", "n")
        testScheduler.advanceUntilIdle()
        val card = assertNotNull(app.env.postLinkCards.card("spike-author", "n"))
        assertNull(card.image)
        // "Always show", as feed cards, for the card already read too; back to "Always warn" hides it again
        app.env.prefs.putString(NsfwMode.PREFS_KEY, NsfwMode.SHOW.value)
        assertNotNull(app.env.postLinkCards.card("spike-author", "n")?.image)
        app.env.prefs.putString(NsfwMode.PREFS_KEY, NsfwMode.WARN.value)
        assertNull(app.env.postLinkCards.card("spike-author", "n")?.image)
    }
}
