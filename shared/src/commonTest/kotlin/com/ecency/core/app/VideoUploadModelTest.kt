package com.ecency.core.app

import com.ecency.core.api.ecency.ImageUploadTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.speak.BytesSource
import com.ecency.core.api.speak.FakeTusServer
import com.ecency.core.api.speak.TusClient
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.editor.EditorMeta
import com.ecency.core.editor.TextSelection
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 3Speak video upload against an in-memory tus server and a scripted Ecency proxy: no video leaves the process. */
class VideoUploadModelTest {
    private val user = TestApp.USER
    private val other = "spike-other"
    private val file = ByteArray(5000) { (it % 97).toByte() }
    private val embed = "https://play.3speak.tv/embed?v=$user/vid123"

    private inner class World(val scope: TestScope) {
        val server = FakeTusServer()
        val proxy = mutableListOf<Pair<String, JsonObject>>()
        val images = mutableListOf<String>()
        var tokens = 0
        var tokenAnswer: (() -> HttpResult)? = null
        val app = TestApp(scope) { TestApp.chain(it) ?: HttpResult(500, "") }

        init {
            app.env.imageHost = "https://images.example"
            app.env.imageTransport = ImageUploadTransport { _, _, f, _ ->
                images += f.name
                HttpResult(200, """{"url":"https://images.example/thumb-${images.size}.jpg"}""")
            }
            app.env.tus = TusClient(server, chunkSize = 1000)
            app.authBackend.rest = { r -> if ("/api/threespeak/" in r.url) answer(r) else null }
            app.authBackend.privateApi = { HttpResult(200, "{}") }
            listOf(user, other).forEach { name ->
                KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
            }
            KeyVault(app.store).setCurrentUsername(user)
            app.env.session.start()
        }

        private fun answer(r: AuthHttpRequest): HttpResult {
            val path = r.url.substringAfter("/api/threespeak/")
            proxy += path to Json.parseToJsonElement(r.body!!).jsonObject
            return when (path) {
                "upload-token" -> tokenAnswer?.invoke() ?: HttpResult(200, """{"token":"up-${++tokens}","upload_url":"${server.endpoint}","permlink":"vid123","embed_url":"$embed"}""")
                else -> HttpResult(200, "{}")
            }
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        fun editor(text: String = "Before."): EditorModel {
            val m = app.env.editorModel(EditorArgs(EditorKind.NEW_POST))
            m.start()
            idle()
            m.setTitle("Video post")
            m.setBody(text, TextSelection(text.length))
            m.setTagInput("video ")
            idle()
            return m
        }

        /** As the apps do: the upload model is made with the screen, before the editor has loaded its owner. */
        fun early(): Pair<EditorModel, VideoUploadModel> {
            val e = app.env.editorModel(EditorArgs(EditorKind.NEW_POST))
            val m = app.env.videoUploadModel(e)
            e.start()
            idle()
            e.setBody("Before.", TextSelection(7))
            idle()
            return e to m
        }

        fun sheet(editor: EditorModel, frames: Int = 5): VideoUploadModel {
            val m = app.env.videoUploadModel(editor)
            val pick = m.pick(PickedVideo(BytesSource(file, "holiday.mp4"), 20_000))
            assertNotNull(pick)
            m.setThumbs(pick, (1..frames).map { PickedImage(byteArrayOf(it.toByte()), "frame$it.jpg", "image/jpeg", 16, 9) })
            return m
        }
    }

    @Test
    fun theVideoGoesTo3SpeakTheThumbnailFollowsAndTheEmbedIsInserted() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.sheet(editor)
        assertTrue(m.current.canStart)
        assertEquals(2, m.current.effectiveThumb, "the middle frame unless one is chosen")
        m.start()
        w.idle()
        // the token is asked for with the owner's session, through Ecency's proxy
        val (path, body) = w.proxy.first()
        assertEquals("upload-token", path)
        assertEquals(user, body["owner"]!!.jsonPrimitive.content)
        assertEquals("false", body["isShort"]!!.jsonPrimitive.content)
        assertEquals("token-$user", body["code"]!!.jsonPrimitive.content)
        assertContentEquals(file, w.server.stored)
        assertTrue(w.server.requests.all { it.headers["Authorization"] == "Bearer up-1" && it.url.startsWith("https://embed.3speak.tv/") })
        assertTrue(w.server.requests.none { r -> r.headers.keys.any { it.equals("User-Agent", ignoreCase = true) } })
        // the frame is uploaded like any image and named to 3Speak
        assertEquals(listOf("frame3.jpg"), w.images)
        val thumb = w.proxy.single { it.first == "thumbnail" }.second
        assertEquals("vid123", thumb["permlink"]!!.jsonPrimitive.content)
        assertEquals("https://images.example/thumb-1.jpg", thumb["thumbnail_url"]!!.jsonPrimitive.content)
        // RN: the bare address on a line of its own, caret behind it
        assertEquals("Before.\n$embed\n", editor.current.body)
        assertEquals(listOf("https://images.example/thumb-1.jpg"), editor.current.videoThumbUrls)
        assertTrue(m.current.done)
        assertEquals(100.0, m.current.progress)
        assertEquals(0, editor.current.uploading)
        // one video a post, and the 3Speak beneficiary rule sees the embed
        assertTrue(EditorMeta.hasThreeSpeakEmbed(editor.current.body))
        val second = w.app.env.videoUploadModel(editor)
        assertEquals(VideoUploadModel.ONE_PER_POST, second.refusal())
        // the thumbnail is the video's while the video is in the body, and goes with it
        editor.setBody("Before.", TextSelection(0))
        assertTrue(editor.current.videoThumbUrls.isEmpty())
    }

    @Test
    fun aModelMadeBeforeTheEditorLoadedUploadsForItsOwner() = runTest {
        val w = World(this)
        val (editor, m) = w.early()
        val pick = m.pick(PickedVideo(BytesSource(file, "early.mp4")))!!
        m.setThumbs(pick, emptyList())
        m.start()
        w.idle()
        assertTrue(m.current.done)
        assertEquals(user, w.proxy.first().second["owner"]!!.jsonPrimitive.content)
        assertEquals("Before.\n$embed\n", editor.current.body)
    }

    @Test
    fun aFailedUploadContinuesWhereItStoppedWithoutStartingOver() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.sheet(editor)
        var patches = 0
        w.server.onPatch = { _, _ -> if (++patches > 2) HttpResult(413, "") else null }
        m.start()
        w.idle()
        assertEquals("Video file is too large. Maximum size is 1GB.", m.current.error)
        assertTrue(m.current.canResume)
        assertEquals("CONTINUE UPLOAD", m.current.startLabel)
        assertEquals(2000, w.server.stored.size)
        assertEquals("Before.", editor.current.body)
        w.server.onPatch = null
        m.start()
        w.idle()
        assertEquals(1, w.server.created, "the upload is not created a second time")
        assertEquals(1, w.tokens, "nor is a second token asked for")
        assertContentEquals(file, w.server.stored)
        val head = w.server.requests.indexOfLast { it.method == "HEAD" }
        assertEquals("2000", w.server.requests[head + 1].headers["Upload-Offset"], "from what 3Speak has")
        assertTrue(m.current.done)
        assertEquals("Before.\n$embed\n", editor.current.body)
    }

    @Test
    fun anUploadThatIsGoneStartsOverWithANewToken() = runTest {
        val w = World(this)
        val m = w.sheet(w.editor())
        w.server.onPatch = { _, _ -> throw IllegalStateException("offline") }
        w.server.onHead = { throw IllegalStateException("offline") }
        m.start()
        w.idle()
        assertTrue(m.current.canResume)
        w.server.onPatch = null
        w.server.onHead = null
        w.server.gone = true
        m.start()
        w.idle()
        assertEquals(2, w.server.created)
        assertEquals(2, w.tokens)
        assertContentEquals(file, w.server.stored)
        assertTrue(m.current.done)
    }

    @Test
    fun theUploadStopsWithTheAccountAndAnotherAccountNeverContinuesIt() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.sheet(editor)
        var switched = false
        w.server.onPatch = { r, _ ->
            if (r.headers["Upload-Offset"] == "2000" && !switched) {
                switched = true
                // the account changes while the third piece is on its way
                w.app.env.session.switchTo(other)
            }
            null
        }
        m.start()
        w.idle()
        assertTrue(w.server.stored.size < file.size, "no further piece after the owner left")
        assertFalse(m.current.done)
        assertEquals(PaidCopy.otherAccount(user), m.current.error)
        assertEquals("Before.", editor.current.body)
        // the other account's own editor starts from nothing: its token, its upload
        val theirs = w.editor("Theirs.")
        val n = w.sheet(theirs)
        w.server.onPatch = null
        n.start()
        w.idle()
        assertEquals(other, w.proxy.last { it.first == "upload-token" }.second["owner"]!!.jsonPrimitive.content)
        assertEquals("token-$other", w.proxy.last { it.first == "upload-token" }.second["code"]!!.jsonPrimitive.content)
        assertEquals(2, w.server.created)
        // and the first owner's sheet refuses to start while they are signed out
        m.clearError()
        m.start()
        w.idle()
        assertEquals(PaidCopy.otherAccount(user), m.current.error)
        assertEquals("Before.", editor.current.body)
    }

    @Test
    fun answersOfTheProxyAreNotTrusted() = runTest {
        for (answer in listOf(
            """{"token":"up","upload_url":"https://evil.example/uploads","permlink":"p","embed_url":"$embed"}""",
            """{"token":"","permlink":"p","embed_url":"$embed"}""",
            """{"token":"up","permlink":"p","embed_url":"https://evil.example/embed?v=a/b"}""",
            """not json""",
        )) {
            val w = World(this)
            val editor = w.editor()
            val m = w.sheet(editor)
            w.tokenAnswer = { HttpResult(200, answer) }
            m.start()
            w.idle()
            assertFalse(m.current.done, answer)
            assertEquals(VideoUploadModel.GENERIC, m.current.error, answer)
            assertEquals("Before.", editor.current.body, answer)
            assertTrue(w.server.requests.none { it.url.contains("evil") }, "the file goes to 3Speak or nowhere")
        }
        val w = World(this)
        w.tokenAnswer = { HttpResult(429, "") }
        val m = w.sheet(w.editor())
        m.start()
        w.idle()
        assertEquals("Too many upload requests. Please try again later.", m.current.error)
        assertFalse(m.current.canResume)
    }

    @Test
    fun thumbnailsAreOptionalAndFramesFollowRnsTimes() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.sheet(editor, frames = 0)
        assertNull(m.current.effectiveThumb)
        m.start()
        w.idle()
        assertTrue(m.current.done)
        assertTrue(w.images.isEmpty())
        assertTrue(w.proxy.none { it.first == "thumbnail" })
        assertTrue(editor.current.videoThumbUrls.isEmpty())
        assertEquals(listOf(2000L, 6000L, 10000L, 14000L, 18000L), VideoUploadModel.thumbTimesMillis(20_000))
        assertEquals(listOf(1000L, 3000L, 5000L, 7000L, 9000L), VideoUploadModel.thumbTimesMillis(null))
        // Math.round((i + 0.5) * 1001 / 5)
        assertEquals(listOf(100L, 300L, 501L, 701L, 901L), VideoUploadModel.thumbTimesMillis(1001))
        // frames for a video that was replaced meanwhile are dropped
        val again = w.app.env.videoUploadModel(w.editor())
        val first = again.pick(PickedVideo(BytesSource(file, "a.mp4")))!!
        val second = again.pick(PickedVideo(BytesSource(file, "b.mp4")))!!
        again.setThumbs(first, listOf(PickedImage(byteArrayOf(1), "old.jpg", "image/jpeg")))
        assertTrue(again.current.generatingThumbs)
        again.setThumbs(second, emptyList())
        assertFalse(again.current.generatingThumbs)
        assertEquals("b.mp4", again.current.fileName)
    }

    @Test
    fun draftsCarryAndRestoreTheVideoThumbnail() {
        val body = "Text\n$embed\n"
        assertEquals(listOf("https://images.example/t.jpg"), EditorMeta.collectVideoThumbUrls(EditorMeta.restoreVideoThumbs(body, listOf("https://images.example/t.jpg")), body))
        assertTrue(EditorMeta.restoreVideoThumbs("no video", listOf("https://images.example/t.jpg")).isEmpty())
        assertTrue(EditorMeta.restoreVideoThumbs("$body\nhttps://play.3speak.tv/embed?v=a/second\n", listOf("https://images.example/t.jpg")).isEmpty(), "two videos: the cover belongs to neither for sure")
        val draft = LocalDraft(body = body, videoThumbs = listOf(com.ecency.core.editor.VideoThumb(embed, "https://images.example/t.jpg")), aiTools = listOf("writing_edit", "bogus"))
        val back = LocalDraft.decode(draft.encode())!!
        assertEquals(draft.videoThumbs, back.videoThumbs)
        assertEquals(listOf("writing_edit"), back.aiTools)
    }

    // ---- a recording's temporary file, another image as the thumbnail, the preview ----

    private fun picked(released: MutableList<String>, name: String, millis: Long? = 20_000, bytes: ByteArray = file) =
        PickedVideo(BytesSource(bytes, name), millis, "ref:$name") { released += name }

    @Test
    fun aRecordingsFileIsLetGoOnceItIsUploadedAndNotBefore() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.app.env.videoUploadModel(editor)
        val released = mutableListOf<String>()
        val pick = m.pick(picked(released, "capture.mp4"))!!
        m.setThumbs(pick, emptyList())
        assertEquals("ref:capture.mp4", m.current.previewRef)
        w.app.env.appLock.start()
        w.idle()
        assertTrue(m.current.previewPlays)
        m.start()
        assertFalse(m.current.previewPlays, "RN paused={isUploading}")
        assertTrue(released.isEmpty(), "the file is being read")
        w.idle()
        assertTrue(m.current.done)
        assertEquals(listOf("capture.mp4"), released)
        m.close()
        assertEquals(listOf("capture.mp4"), released, "once only")
    }

    @Test
    fun aSignedOutOrWipedAccountsVideoIsLetGoAtOnceAlsoWithItsEditorStillOpen() = runTest {
        val w = World(this)
        val m = w.app.env.videoUploadModel(w.editor())
        val released = mutableListOf<String>()
        val pick = m.pick(picked(released, "capture.mp4"))!!
        m.setThumbs(pick, emptyList())
        w.server.onPatch = { _, _ -> HttpResult(503, "") }
        m.start()
        w.idle()
        assertTrue(m.current.canResume)
        // switching accounts keeps the place (the owner may come back) ...
        w.app.env.session.switchTo(other)
        w.idle()
        assertTrue(released.isEmpty())
        w.app.env.session.switchTo(user)
        w.idle()
        // ... signing out does not: the temporary file goes with the account, and nothing can be continued
        w.app.env.session.logout(user)
        w.idle()
        assertEquals(listOf("capture.mp4"), released)
        assertFalse(m.current.hasVideo)
        assertFalse(m.current.canResume)
        m.close()
        assertEquals(listOf("capture.mp4"), released, "once only")

        // a wipe: the same, for a video that was only picked
        val w2 = World(this)
        val m2 = w2.app.env.videoUploadModel(w2.editor())
        val released2 = mutableListOf<String>()
        m2.pick(picked(released2, "picked.mp4"))!!
        w2.app.env.resetAfterWipe()
        w2.idle()
        assertEquals(listOf("picked.mp4"), released2)
        assertFalse(m2.current.hasVideo)
    }

    @Test
    fun aRecordingThatIsGoneEndsTheUploadWithAClearStateNotAnOfferToContinue() = runTest {
        val w = World(this)
        val m = w.app.env.videoUploadModel(w.editor())
        val released = mutableListOf<String>()
        val source = BytesSource(file, "capture.mp4")
        val pick = m.pick(PickedVideo(source, 20_000, "ref:capture.mp4") { released += "capture.mp4" })!!
        m.setThumbs(pick, emptyList())
        w.server.onPatch = { _, _ -> HttpResult(503, "") }
        m.start()
        w.idle()
        assertTrue(m.current.canResume, "a failed upload can be continued while its file is there")
        // the file disappears (deleted by something else) before "Continue upload"
        source.unreadable = true
        w.server.onPatch = null
        m.clearError()
        m.start()
        w.idle()
        assertFalse(m.current.canResume, "nothing to continue with")
        assertFalse(m.current.hasVideo)
        assertFalse(m.current.uploading)
        assertEquals(VideoUploadModel.SOURCE_GONE, m.current.error)
        assertEquals("START UPLOAD", m.current.startLabel)
        assertEquals(listOf("capture.mp4"), released)
        // another video can be picked and goes up from the start
        val again = m.pick(picked(mutableListOf(), "second.mp4"))!!
        m.setThumbs(again, emptyList())
        m.clearError()
        m.start()
        w.idle()
        assertTrue(m.current.done)
    }

    @Test
    fun aFailedUploadKeepsItsFileUntilItIsContinuedOrTheModelGoes() = runTest {
        val w = World(this)
        val m = w.app.env.videoUploadModel(w.editor())
        val released = mutableListOf<String>()
        val pick = m.pick(picked(released, "capture.mp4"))!!
        m.setThumbs(pick, emptyList())
        w.server.onPatch = { _, _ -> HttpResult(503, "") }
        m.start()
        w.idle()
        assertTrue(m.current.canResume)
        // closing the sheet does not throw a resumable upload's file away
        m.dismissed()
        assertTrue(released.isEmpty())
        assertTrue(m.current.hasVideo)
        // leaving the editor does
        m.close()
        assertEquals(listOf("capture.mp4"), released)
    }

    @Test
    fun aVideoThatIsNotTakenReplacedOrLeftInAClosedSheetIsLetGo() = runTest {
        val w = World(this)
        val released = mutableListOf<String>()
        // a short over 60 seconds and an empty file are refused, and let go at once
        val short = VideoUploadModel(w.app.env, object : VideoUploadTarget {
            override val owner: String? = user
            override fun refusal(): String? = null
            override fun uploadsChanged(delta: Int) {}
            override fun insertVideo(embedUrl: String, thumbUrl: String?) = true
        }, isShort = true)
        assertNull(short.pick(picked(released, "long.mp4", millis = 61_000)))
        assertNull(short.pick(picked(released, "empty.mp4", bytes = ByteArray(0))))
        assertEquals(listOf("long.mp4", "empty.mp4"), released)
        released.clear()
        // another pick replaces the first
        val m = w.app.env.videoUploadModel(w.editor())
        m.pick(picked(released, "first.mp4"))
        val second = m.pick(picked(released, "second.mp4"))!!
        m.setThumbs(second, emptyList())
        assertEquals(listOf("first.mp4"), released)
        // the sheet is closed without an upload: the picked video is forgotten
        m.dismissed()
        assertEquals(listOf("first.mp4", "second.mp4"), released)
        assertFalse(m.current.hasVideo)
        assertNull(m.current.previewRef)
        // a pick that arrives while an upload runs is not taken either
        val pick = m.pick(picked(released, "third.mp4"))!!
        m.setThumbs(pick, emptyList())
        m.start()
        assertNull(m.pick(picked(released, "late.mp4")))
        assertEquals("late.mp4", released.last())
        w.idle()
        assertTrue(m.current.done)
    }

    @Test
    fun anotherImageCanBeTheThumbnailAndGoesUpLikeAnyImage() = runTest {
        val w = World(this)
        val editor = w.editor()
        val m = w.sheet(editor)
        val own = PickedImage(byteArrayOf(9, 9), "cover.jpg", "image/jpeg", 4, 3)
        m.setCustomThumb(own)
        assertTrue(m.current.customThumb)
        assertNull(m.current.effectiveThumb, "no frame is marked while another image is the thumbnail")
        assertTrue(m.thumbImage() === own)
        // choosing a frame again drops it
        m.selectThumb(0)
        assertFalse(m.current.customThumb)
        assertEquals("frame1.jpg", m.thumbImage()?.name)
        m.setCustomThumb(own)
        m.start()
        // not while the upload runs
        m.setCustomThumb(PickedImage(byteArrayOf(1), "late.jpg", "image/jpeg", 1, 1))
        w.idle()
        assertEquals(listOf("cover.jpg"), w.images, "the picked image, through the signed image upload")
        assertEquals("https://images.example/thumb-1.jpg", w.proxy.single { it.first == "thumbnail" }.second["thumbnail_url"]!!.jsonPrimitive.content)
        assertEquals(listOf("https://images.example/thumb-1.jpg"), editor.current.videoThumbUrls)
        // a new pick starts from the frames again
        val again = w.app.env.videoUploadModel(w.editor())
        again.setCustomThumb(own)
        assertFalse(again.current.customThumb, "there is no video yet")
    }

    @Test
    fun thePreviewNeverPlaysBehindTheAppLock() = runTest {
        val w = World(this)
        com.ecency.core.secure.PinLock(KeyVault(w.app.store), 16).setPin("1234")
        val m = w.app.env.videoUploadModel(w.editor())
        val pick = m.pick(PickedVideo(BytesSource(file, "clip.mp4"), 20_000, "ref:clip.mp4"))!!
        m.setThumbs(pick, emptyList())
        w.idle()
        // the lock state is not known yet: content is hidden, nothing plays
        assertTrue(m.current.locked)
        assertFalse(m.current.previewPlays)
        w.app.env.appLock.start()
        w.idle()
        assertTrue(w.app.env.appLock.current.hidesContent)
        assertFalse(m.current.previewPlays)
        "1234".forEach { w.app.env.appLock.enterDigit(it.digitToInt()) }
        w.idle()
        assertFalse(m.current.locked)
        assertTrue(m.current.previewPlays)
        // and a reset (the sheet closed) keeps what the model knows about the lock
        m.dismissed()
        assertFalse(m.current.locked)
    }
}
