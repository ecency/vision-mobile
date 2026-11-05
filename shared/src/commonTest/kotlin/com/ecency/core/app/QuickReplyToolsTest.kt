package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.Sent
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RN quick reply (quickPostModalContent in comment mode): the image button (one image, `text\n\n ![](url)`), the
 * clipboard chip, dictation, AI assist (improve, grammar, summarize) and expand, over [ComposeReplyModel].
 */
class QuickReplyToolsTest {
    private val key = PostKey("alice", "root")
    private val parent = Post("alice", "root")
    private val url = "https://images.ecency.com/DQmReply/photo.jpg"

    private fun handler(): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "null")
            "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root"))
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    private fun TestScope.signedIn(): TestApp {
        val app = TestApp(this, handler = handler())
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun TestApp.uploads(answer: suspend (WaveImageSource) -> String?): MutableList<Pair<String, WaveImageSource>> {
        val asked = mutableListOf<Pair<String, WaveImageSource>>()
        env.waves.attacher = object : WaveImageAttacher {
            override suspend fun attach(username: String, source: WaveImageSource): String? {
                asked += username to source
                return answer(source)
            }
        }
        return asked
    }

    private fun TestApp.sentBody(): String =
        broadcasts().last().params.jsonArray.single().jsonObject.getValue("operations").jsonArray[0].jsonArray[1].jsonObject.getValue("body").jsonPrimitive.content

    @Test
    fun anImageGoesThroughTheAppsUploadAndOutWithTheReplyAsRnWritesIt() = runTest {
        val app = signedIn()
        val reply = app.env.replyModel(key, parent)
        // no uploader wired: a notice, nothing attached
        reply.attach(WaveImageSource.GALLERY)
        assertEquals(WaveComposeModel.NO_UPLOAD, reply.current.error?.message)
        reply.clearError()
        val upload = CompletableDeferred<String?>()
        val asked = app.uploads { upload.await() }
        reply.setText("Nice post")
        reply.attach(WaveImageSource.CAMERA)
        testScheduler.advanceUntilIdle()
        assertTrue(reply.current.isAttaching)
        assertFalse(reply.current.canSend, "Reply waits for the upload")
        upload.complete(url)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(TestApp.USER to WaveImageSource.CAMERA), asked, "uploaded for the account that opened the composer")
        assertEquals(url, reply.current.imageUrl)
        assertFalse(reply.current.isAttaching)
        reply.send()
        testScheduler.advanceUntilIdle()
        assertEquals("Nice post\n\n ![]($url)", app.sentBody(), "RN _submitPost: the text, a blank line, then the image")
        assertNull(reply.current.imageUrl, "a sent reply starts empty")
    }

    @Test
    fun anImageFromTheUploadsGalleryBecomesTheRepliesOneImageAsRnsUploadsGalleryModal() = runTest {
        val app = signedIn()
        val reply = app.env.replyModel(key, parent)
        val gallery = "https://images.ecency.com/DQmGallery/funny.gif"
        // RN UploadsGalleryModal allowMultiple={false}: a tapped upload is the reply's image, nothing is uploaded
        reply.pickUploaded(gallery)
        assertEquals(gallery, reply.current.imageUrl)
        assertFalse(reply.current.isAttaching)
        testScheduler.advanceUntilIdle()
        assertEquals(gallery, ComposeReplyModel(app.env, key, parent).also { testScheduler.advanceUntilIdle() }.current.imageUrl, "saved with the draft like an uploaded one")
        reply.setText("Nice post")
        // a second pick replaces it, as RN's one image
        reply.pickUploaded(url)
        assertEquals(url, reply.current.imageUrl)
        // only a clean address on the image host goes into a body
        reply.pickUploaded("https://evil.example/x.jpg")
        reply.pickUploaded("https://images.ecency.com/a b.jpg")
        assertEquals(url, reply.current.imageUrl)
        // not while a picked image is still uploading: the upload's answer would replace it
        val upload = CompletableDeferred<String?>()
        app.uploads { upload.await() }
        reply.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        reply.pickUploaded(gallery)
        assertEquals(url, reply.current.imageUrl)
        upload.complete(null)
        testScheduler.advanceUntilIdle()
        reply.pickUploaded(gallery)
        reply.send()
        testScheduler.advanceUntilIdle()
        assertEquals("Nice post\n\n ![]($gallery)", app.sentBody(), "sent as RN writes a reply image")
    }

    @Test
    fun anUploadsGalleryPickIsTheComposersOwnersOnly() = runTest {
        val app = signedIn()
        val reply = ComposeReplyModel(app.env, key, parent)
        testScheduler.advanceUntilIdle()
        app.signIn("spike-bob")
        app.env.session.switchTo("spike-bob")
        testScheduler.advanceUntilIdle()
        // a sheet left open across a switch: another account's gallery image never lands in this account's reply
        reply.pickUploaded(url)
        assertNull(reply.current.imageUrl)
        assertEquals(ComposeReplyModel.ACCOUNT_CHANGED, reply.current.error?.message)
    }

    @Test
    fun anImageAloneIsAReplyAndRemovingItLeavesNothingToSend() = runTest {
        val app = signedIn()
        app.uploads { url }
        val reply = app.env.replyModel(key, parent)
        assertFalse(reply.current.canSend)
        reply.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        assertTrue(reply.current.canSend, "RN: the body is the image line, which is not empty")
        reply.removeImage()
        assertNull(reply.current.imageUrl)
        assertFalse(reply.current.canSend)
        // a cancelled picker attaches nothing
        app.uploads { null }
        reply.attach(WaveImageSource.FILES)
        testScheduler.advanceUntilIdle()
        assertNull(reply.current.imageUrl)
        assertFalse(reply.current.isAttaching)
    }

    @Test
    fun theClipboardChipPastesOnceAndStaysClosed() = runTest {
        val app = signedIn()
        val asked = app.uploads { url }
        val reply = app.env.replyModel(key, parent)
        reply.setClipboardHasImage(true)
        assertTrue(reply.current.clipboardChip)
        reply.pasteClipboardImage()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(TestApp.USER to WaveImageSource.CLIPBOARD), asked)
        assertEquals(url, reply.current.imageUrl)
        assertFalse(reply.current.clipboardChip)
        reply.setClipboardHasImage(true)
        assertFalse(reply.current.clipboardChip, "RN dismissedClipboardRef: once used it does not come back")
    }

    @Test
    fun dictationAndAiAssistWriteIntoTheReplyAsInRn() = runTest {
        val app = signedIn()
        val reply = app.env.replyModel(key, parent)
        assertEquals(listOf("improve", "check_grammar", "summarize"), reply.aiActions, "RN quick reply supportedActions")
        assertEquals(TestApp.USER, reply.aiOwner)
        assertEquals(LocalDrafts.reply("alice", "root"), reply.paidScope, "the editor's reply mode pays in the same scope")
        reply.setText("hello")
        assertEquals("hello", reply.aiText)
        val seq = reply.current.prefillSeq
        assertTrue(reply.insertDictation("world"))
        assertEquals("hello world", reply.current.text, "the transcript after one space")
        assertTrue(reply.current.prefillSeq > seq, "the text field takes the new text")
        assertTrue(reply.applyAiResult("improve", "Hello, world."))
        assertEquals("Hello, world.", reply.current.text)
        assertFalse(reply.applyAiResult(AiAssistModel.RECAP_ACTION, "recap"), "a recap is copy only")
        assertFalse(reply.applyAiResult("title", "A title"), "not one of the reply's actions")
        assertEquals("Hello, world.", reply.current.text)
    }

    @Test
    fun expandCarriesTheImageIntoTheFullEditorAsRnDoes() = runTest {
        val app = signedIn()
        app.uploads { url }
        val reply = app.env.replyModel(key, parent)
        reply.setText("half a thought")
        reply.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        var args: EditorArgs? = null
        reply.expand { args = it }
        testScheduler.advanceUntilIdle()
        assertEquals(EditorKind.REPLY, args?.kind)
        // RN editorContainer: replyMediaUrls go into the reply's body as ` ![](url)` after a blank line
        assertEquals("half a thought\n\n ![]($url)", reply.current.text)
        assertNull(reply.current.imageUrl)
        val stored = app.env.localDrafts.load(TestApp.USER, LocalDrafts.reply("alice", "root"))
        assertEquals("half a thought\n\n ![]($url)", stored?.body)
    }

    @Test
    fun anAttachedImageSurvivesARestartWithTheDraftUntilItIsRemovedOrSent() = runTest {
        val app = signedIn()
        app.uploads { url }
        val slot = LocalDrafts.reply("alice", "root")
        val reply = app.env.replyModel(key, parent)
        reply.setText("Nice post")
        reply.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        // the app is closed and opened again: a new composer over the same device storage
        val reopened = ComposeReplyModel(app.env, key, parent)
        testScheduler.advanceUntilIdle()
        assertEquals("Nice post", reopened.current.text)
        assertEquals(url, reopened.current.imageUrl, "the uploaded image is part of the saved reply draft")
        reopened.removeImage()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.localDrafts.load(TestApp.USER, slot)?.replyImage, "removing the image updates the saved draft")
        assertNull(ComposeReplyModel(app.env, key, parent).also { testScheduler.advanceUntilIdle() }.current.imageUrl)
        // an image alone is a draft too
        reopened.setText("")
        reopened.attach(WaveImageSource.CAMERA)
        testScheduler.advanceUntilIdle()
        val imageOnly = ComposeReplyModel(app.env, key, parent)
        testScheduler.advanceUntilIdle()
        assertEquals(url, imageOnly.current.imageUrl)
        assertTrue(imageOnly.current.canSend)
        imageOnly.send()
        testScheduler.advanceUntilIdle()
        assertEquals(com.ecency.core.api.waves.WavePublish.body("", url), app.sentBody())
        assertNull(app.env.localDrafts.load(TestApp.USER, slot), "a sent reply leaves no draft, image included")
    }

    @Test
    fun aTextOnlyReplyDraftFromAnEarlierBuildStillOpens() = runTest {
        val app = signedIn()
        val slot = LocalDrafts.reply("alice", "root")
        app.env.localDrafts.saveRaw(TestApp.USER, slot, """{"title":"","body":"old words","tags":[],"updated":1,"description":"","thumb":"","reblog":false}""")
        val reply = ComposeReplyModel(app.env, key, parent)
        testScheduler.advanceUntilIdle()
        assertEquals("old words", reply.current.text)
        assertNull(reply.current.imageUrl)
        assertFalse("replyImage" in LocalDraft(body = "x").encode(), "a draft without an image is written as before")
    }

    @Test
    fun expandWaitsForARunningUploadSoTheImageIsNotLost() = runTest {
        val app = signedIn()
        val upload = CompletableDeferred<String?>()
        app.uploads { upload.await() }
        val reply = app.env.replyModel(key, parent)
        reply.setText("half a thought")
        reply.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        var args: EditorArgs? = null
        reply.expand { args = it }
        testScheduler.advanceUntilIdle()
        assertNull(args, "the editor does not open while the image is uploading")
        assertEquals("half a thought", reply.current.text)
        upload.complete(url)
        testScheduler.advanceUntilIdle()
        assertEquals(url, reply.current.imageUrl, "the upload still lands in the reply")
        reply.expand { args = it }
        testScheduler.advanceUntilIdle()
        assertEquals(EditorKind.REPLY, args?.kind)
        assertEquals("half a thought\n\n ![]($url)", reply.current.text)
    }
}
