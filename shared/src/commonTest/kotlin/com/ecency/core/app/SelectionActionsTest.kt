package com.ecency.core.app

import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.model.Post
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.Inline
import com.ecency.core.render.blocks.LinkTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #3699: a reader selects part of a post or comment body and copies, quotes, translates or shares it. */
class SelectionActionsTest {
    @Test
    fun aQuoteMarksEveryLineAndEndsWithABlankLine() {
        assertEquals("> one line\n\n", BodySelection.quote("one line"))
        assertEquals("> first\n> second\n\n", BodySelection.quote("first\nsecond"))
        // a blank line stays inside the quote, so the paragraphs after it are quoted too
        assertEquals("> first\n>\n> second\n\n", BodySelection.quote("first\n\nsecond"))
        assertEquals("> trimmed\n\n", BodySelection.quote("  \n trimmed  \n\n"))
        assertEquals("> a\n> b\n\n", BodySelection.quote("a\r\nb"))
        assertNull(BodySelection.quote(""))
        assertNull(BodySelection.quote(" \n\t "))
    }

    @Test
    fun aQuoteGoesAfterTheDraftWithABlankLineBetween() {
        assertEquals("> quoted\n\n", BodySelection.appendQuote("", "quoted"))
        assertEquals("> quoted\n\n", BodySelection.appendQuote("  \n", "quoted"))
        assertEquals("My take\n\n> quoted\n\n", BodySelection.appendQuote("My take\n", "quoted"))
        assertNull(BodySelection.appendQuote("My take", "  "))
    }

    @Test
    fun theShareTextIsTheSelectionInQuotesAndThePostLink() {
        val post = Post("spike-bob", "a-post", category = "hive-1", url = "/hive-1/@spike-bob/a-post")
        assertEquals("\"Well said\"\n\nhttps://ecency.com/@spike-bob/a-post", BodySelection.shareText(" Well said\n", post))
    }

    @Test
    fun theSelectTextViewReadsTheBodyAsPlainParagraphs() {
        val blocks = listOf(
            Block.Heading(2, listOf(Inline.Text("Title"))),
            Block.Paragraph(listOf(Inline.Text("Hello "), Inline.Mention("spike-bob"), Inline.Text(", see "), Inline.Link(LinkTarget.External("https://example.com"), listOf(Inline.Text("this"))))),
            Block.Image("https://images.example/a.png", "an image"),
            Block.ListBlock(ordered = true, start = 3, items = listOf(listOf(Block.Paragraph(listOf(Inline.Text("three")))), listOf(Block.Paragraph(listOf(Inline.Text("four")))))),
            Block.ListBlock(ordered = false, start = 1, items = listOf(listOf(Block.Paragraph(listOf(Inline.Text("dot")))))),
            Block.Quote(listOf(Block.Paragraph(listOf(Inline.Text("quoted words"))))),
            Block.Code("val x = 1\nval y = 2"),
            Block.Table(listOf(Block.Table.Row(listOf(Block.Table.Cell(true, listOf(Block.Paragraph(listOf(Inline.Text("a"))))), Block.Table.Cell(false, listOf(Block.Paragraph(listOf(Inline.Text("b"))))))))),
            Block.Paragraph(listOf(Inline.Text("   "))),
            Block.Details(listOf(Inline.Text("More")), listOf(Block.Paragraph(listOf(Inline.Text("hidden"))))),
        )
        assertEquals(
            "Title\n\nHello @spike-bob, see this\n\n3. three\n4. four\n\n• dot\n\nquoted words\n\nval x = 1\nval y = 2\n\na | b\n\nMore\n\nhidden",
            BodySelection.text(blocks),
        )
    }

    @Test
    fun quotingWaitsForTheSavedDraftAndAddsToIt() = runTest {
        val app = TestApp(this) { req -> TestApp.chain(req) ?: HttpResult(500, "") }
        app.signIn()
        val parent = Post("spike-bob", "a-post", body = "Body")
        app.env.localDrafts.save(TestApp.USER, LocalDrafts.reply("spike-bob", "a-post"), LocalDraft(body = "My take", updated = 1))
        val reply = app.env.replyModel(PostKey("spike-bob", "a-post"), parent)
        // quoted while the accounts are still being read, so before the saved draft is: the draft is kept and the
        // quote goes after it
        assertTrue(reply.quote("well said"))
        testScheduler.advanceUntilIdle()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertEquals("My take\n\n> well said\n\n", reply.current.text)
        assertTrue(reply.current.prefillSeq > 0, "the text field takes the new text")
        assertFalse(reply.quote("   "), "nothing to quote")
        testScheduler.advanceUntilIdle()
        assertEquals("My take\n\n> well said\n\n", reply.current.text)
        // the same composer again (one per parent): the next quote follows
        assertTrue(app.env.replyModel(PostKey("spike-bob", "a-post"), parent).quote("and this"))
        testScheduler.advanceUntilIdle()
        assertEquals("My take\n\n> well said\n\n> and this\n\n", reply.current.text)
    }

    @Test
    fun theTranslationOfASelectionSendsTheWholeSelection() = runTest {
        val sent = mutableListOf<String>()
        val app = TestApp(this) { req -> TestApp.chain(req) ?: HttpResult(500, "") }
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/translate")) {
                sent += Json.parseToJsonElement(r.body!!).jsonObject["q"]!!.jsonPrimitive.content
                HttpResult(200, """{"translatedText":"Hola"}""")
            } else null
        }
        val long = "word ".repeat(120).trim()
        val sheet = app.env.selectionTranslationModel(" $long \n", "es")
        sheet.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(long), sent, "the whole selection, not a 200 character summary")
        assertEquals(long, sheet.current.original)
        assertEquals("Hola", sheet.current.translated)
        sheet.close()
    }

    @Test
    fun theSelectTextModelRendersThePostBodyAsPlainText() = runTest {
        val app = TestApp(this) { req -> TestApp.chain(req) ?: HttpResult(500, "") }
        val model = app.env.selectTextModel(Post("spike-bob", "a-post", body = "Hello **world**, @spike-alice\n\n- one\n- two\n\n![](https://images.example/a.png)"))
        assertNull(model.current.text)
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals("Hello world, @spike-alice\n\n\u2022 one\n\u2022 two", model.current.text)
        model.close()
    }

    @Test
    fun selectTextIsOfferedForAPostOrACommentWithABodyWhereTheScreenAsks() = runTest {
        val app = TestApp(this) { req -> TestApp.chain(req) ?: HttpResult(500, "") }
        val post = Post("spike-bob", "a-post", body = "Body")
        val comment = Post("spike-bob", "re-a", body = "A comment", depth = 1, parentAuthor = "spike-alice", parentPermlink = "a-post")
        val select = PostMenuContext(selectText = true)
        assertTrue(PostMenuItem.SELECT_TEXT in app.env.postActions.menu(post, select))
        assertTrue(PostMenuItem.SELECT_TEXT in app.env.postActions.menu(comment, select))
        assertFalse(PostMenuItem.SELECT_TEXT in app.env.postActions.menu(post.copy(body = " "), select), "nothing to select")
        assertFalse(PostMenuItem.SELECT_TEXT in app.env.postActions.menu(comment, PostMenuContext()), "not where the screen does not ask (waves)")
        assertEquals("Select text", PostMenuItem.SELECT_TEXT.label)
        assertEquals(PostMenuEffect.SelectText(post), app.env.postActions.select(PostMenuItem.SELECT_TEXT, post))
    }
}
