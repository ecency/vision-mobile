package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.ecency.ImageUploadTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.speak.BytesSource
import com.ecency.core.api.speak.FakeTusServer
import com.ecency.core.api.speak.TusClient
import com.ecency.core.api.waves.WavePublish
import com.ecency.core.api.waves.WavesGoldens
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A video in the wave composer (RN quick post: SpeakUploaderModal with isShort, the embed after the text, the
 * thumbnail in the metadata, link-hive after publishing) against an in-memory tus server, a scripted Ecency proxy
 * and a fake node: no video, wave or request leaves the process.
 */
class WaveVideoTest {
    private val golden = WavesGoldens.publish.single { it.name == "video_attached" }
    private val alice = golden.author
    private val other = "spike-other"
    private val file = ByteArray(5000) { (it % 97).toByte() }
    private val embed = golden.video!!
    private val thumb = golden.videoThumb!!

    private inner class World(val scope: TestScope) {
        val server = FakeTusServer()
        val proxy = mutableListOf<Pair<String, JsonObject>>()
        var linkAnswers = ArrayDeque<HttpResult>()
        var linkThrows = false
        var rejectBroadcast = false
        var onChainBody: String? = null
        var tokens = 0
        val app = TestApp(scope) { req -> chain(req) }

        init {
            app.env.imageHost = "https://images.ecency.com"
            app.env.imageTransport = ImageUploadTransport { _, _, _, _ -> HttpResult(200, """{"url":"$thumb"}""") }
            app.env.tus = TusClient(server, chunkSize = 1000)
            WaveUploadAttacher.install(app.env) // as the apps do at start: the composer knows uploaded images' ratios
            app.authBackend.rest = { r -> if ("/api/threespeak/" in r.url) answer(r) else if (r.url.endsWith("/private-api/public/bots")) HttpResult(200, "[]") else null }
            app.authBackend.privateApi = { HttpResult(200, "{}") }
            listOf(alice, other).forEach { name ->
                KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
            }
            KeyVault(app.store).setCurrentUsername(alice)
            app.env.session.start()
            idle()
        }

        private fun chain(req: Sent): HttpResult = when (req.method) {
            "bridge.get_account_posts" ->
                if (req.params.jsonObject.getValue("account").jsonPrimitive.content == golden.containerAuthor) {
                    FakeTransport.result(req, """[{"author":"${golden.containerAuthor}","permlink":"${golden.containerPermlink}","json_metadata":{"tags":${Json.encodeToString(golden.containerTags)}}}]""")
                } else {
                    FakeTransport.result(req, "[]")
                }
            "bridge.get_post" -> FakeTransport.result(req, "null")
            "condenser_api.broadcast_transaction" -> if (rejectBroadcast) HttpResult(500, "down") else FakeTransport.result(req, "null")
            "condenser_api.get_content" -> onChainBody?.let { body ->
                val p = req.params.jsonArray
                FakeTransport.result(req, """{"author":${p[0]},"permlink":${p[1]},"body":${Json.encodeToString(body)}}""")
            } ?: FakeTransport.result(req, """{"author":"","permlink":""}""")
            "condenser_api.get_following", "condenser_api.get_accounts" -> FakeTransport.result(req, "[]")
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }

        private fun answer(r: AuthHttpRequest): HttpResult {
            val path = r.url.substringAfter("/api/threespeak/")
            proxy += path to Json.parseToJsonElement(r.body!!).jsonObject
            return when (path) {
                "upload-token" -> HttpResult(200, """{"token":"up-${++tokens}","upload_url":"${server.endpoint}","permlink":"vid123","embed_url":"$embed"}""")
                "link-hive" -> if (linkThrows) throw IllegalStateException("offline") else linkAnswers.removeFirstOrNull() ?: HttpResult(200, "{}")
                else -> HttpResult(200, "{}")
            }
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        fun links() = proxy.filter { it.first == "link-hive" }.map { it.second }

        fun broadcastOps() = app.broadcasts().last().params.jsonArray[0].jsonObject.getValue("operations").jsonArray

        /** The composer with [golden]'s text and an uploaded video. */
        fun composerWithVideo(durationMillis: Long = 20_000): WaveComposeModel {
            val composer = app.env.waves.composer()
            composer.setText(golden.text)
            val video = composer.video()
            val pick = video.pick(PickedVideo(BytesSource(file, "clip.mp4"), durationMillis))
            assertNotNull(pick)
            video.setThumbs(pick, (1..5).map { PickedImage(byteArrayOf(it.toByte()), "frame$it.jpg", "image/jpeg", 16, 9) })
            video.start()
            assertTrue(composer.current.isVideoUploading)
            assertFalse(composer.current.canPublish, "RN: PUBLISH waits for the video")
            idle()
            return composer
        }
    }

    @Test
    fun aVideoGoesUpAsAShortAndThePublishedWaveIsRnsByteForByte() = runTest {
        val w = World(this)
        val composer = w.composerWithVideo()
        // RN SpeakUploaderModal isShort: the token names a short and the owner
        val token = w.proxy.first { it.first == "upload-token" }.second
        assertEquals("true", token["isShort"]!!.jsonPrimitive.content)
        assertEquals(alice, token["owner"]!!.jsonPrimitive.content)
        assertEquals("token-$alice", token["code"]!!.jsonPrimitive.content)
        assertContentEquals(file, w.server.stored)
        assertEquals(embed, composer.current.videoEmbedUrl)
        assertEquals(thumb, composer.current.videoThumbUrl)
        assertFalse(composer.current.isVideoUploading)
        assertFalse(composer.current.canAddVideo, "RN: one video, the button is off")
        assertEquals(VideoUploadModel.ONE_PER_POST, composer.video().refusal())
        assertEquals(golden.text.length, composer.current.length, "the counter is the typed text only, as RN")

        composer.send()
        w.idle()
        assertNotNull(composer.current.sent)
        val ops = w.broadcastOps()
        assertEquals(listOf("comment", "comment_options"), ops.map { it.jsonArray[0].jsonPrimitive.content })
        val comment = ops[0].jsonArray[1].jsonObject
        assertEquals(golden.body, comment.getValue("body").jsonPrimitive.content)
        assertEquals(golden.permlink, comment.getValue("permlink").jsonPrimitive.content)
        // the thumbnail is in `image` with the ratio the upload knew (16:9), as RN's fetchRatios writes it
        assertEquals(
            WavePublish.metadata(golden.body, golden.containerTags!! + "fyp", ratios = mapOf(thumb to 16.0 / 9), videoThumbs = listOf(thumb)),
            comment.getValue("json_metadata").jsonPrimitive.content,
        )
        val beneficiaries = ops[1].jsonArray[1].jsonObject.getValue("extensions").toString()
        assertTrue("threespeakfund" in beneficiaries)
        // RN linkVideoToHive: exactly RN's request, plus the owner's code
        val link = w.links().single()
        assertEquals("token-$alice", link["code"]!!.jsonPrimitive.content)
        assertEquals(golden.link, JsonObject(link.filterKeys { it != "code" }).toString())
        assertEquals("Success!", w.app.env.ui.state.value.toast?.text)
        // the next composer starts without a video
        assertNull(w.app.env.waves.composer().current.videoEmbedUrl)
    }

    @Test
    fun aFailedLinkCallNeverTouchesThePublishedWaveAndIsRepeatedSafely() = runTest {
        val w = World(this)
        val composer = w.composerWithVideo()
        w.linkAnswers = ArrayDeque(listOf(HttpResult(502, ""), HttpResult(429, "")))
        composer.send()
        w.idle()
        // published, one broadcast, no error: the user sees "Success!" whatever the link call does
        assertNotNull(composer.current.sent)
        assertNull(composer.current.error)
        assertEquals(1, w.app.broadcasts().size)
        assertEquals("Success!", w.app.env.ui.state.value.toast?.text)
        // tried again with the very same request until it is answered
        val links = w.links()
        assertEquals(3, links.size)
        assertEquals(1, links.map { it.toString() }.toSet().size)
    }

    @Test
    fun aLinkCallThatNeverAnswersGivesUpAndARefusalIsNotRepeated() = runTest {
        val w = World(this)
        w.linkThrows = true
        val composer = w.composerWithVideo()
        composer.send()
        w.idle()
        assertNotNull(composer.current.sent)
        assertNull(composer.current.error)
        assertEquals(WavesService.LINK_DELAYS.size, w.links().size)
        assertEquals(1, w.app.broadcasts().size)

        val w2 = World(this)
        w2.linkAnswers = ArrayDeque(listOf(HttpResult(400, "")))
        w2.composerWithVideo().send()
        w2.idle()
        assertEquals(1, w2.links().size, "a 4xx is an answer")
    }

    @Test
    fun aRetryThatFindsTheWaveOnChainLinksItOnceAndSendsNothingAgain() = runTest {
        val w = World(this)
        val composer = w.composerWithVideo()
        w.rejectBroadcast = true
        composer.send()
        w.idle()
        assertNotNull(composer.current.error)
        assertEquals(0, w.links().size, "nothing is linked before the wave is known to be published")
        assertEquals(embed, composer.current.videoEmbedUrl, "a failed publish keeps the video")
        val sent = w.app.broadcasts().size
        w.rejectBroadcast = false
        w.onChainBody = golden.body
        composer.clearError()
        composer.send()
        w.idle()
        assertNotNull(composer.current.sent)
        assertEquals(sent, w.app.broadcasts().size)
        assertEquals(1, w.links().size)
        // the same wave is never linked a second time from this run
        w.app.env.waves.linkVideo(alice, golden.permlink, golden.body, golden.containerTags!! + "fyp")
        w.idle()
        assertEquals(1, w.links().size)
    }

    @Test
    fun theVideoBelongsToTheComposersOwner() = runTest {
        val w = World(this)
        val composer = w.app.env.waves.composer()
        composer.setText(golden.text)
        val video = composer.video()
        val pick = video.pick(PickedVideo(BytesSource(file, "clip.mp4"), 20_000))!!
        video.setThumbs(pick, emptyList())
        var switched = false
        w.server.onPatch = { r, _ ->
            if (r.headers["Upload-Offset"] == "2000" && !switched) {
                switched = true
                // from outside the upload: the account change ends the composer and with it this very request
                w.scope.launch { w.app.env.session.switchTo(other) }
            }
            null
        }
        video.start()
        w.idle()
        assertTrue(w.server.stored.size < file.size, "no further piece after the owner left")
        assertNull(composer.current.videoEmbedUrl)
        // the other account gets its own composer and its own, empty upload
        val next = w.app.env.waves.composer()
        assertTrue(next !== composer)
        assertEquals(other, next.owner)
        assertFalse(next.video().current.hasVideo)
        assertNull(next.current.videoEmbedUrl)
        assertTrue(w.proxy.none { it.second["owner"]?.jsonPrimitive?.content == other })
        // and a result that arrives for the old composer is not taken
        composer.setVideoEmbed(embed)
        composer.send()
        w.idle()
        assertEquals(0, w.app.broadcasts().size)
    }

    @Test
    fun aComposerLeftOpenAcrossAnAccountChangeUploadsNothing() = runTest {
        val w = World(this)
        w.app.env.session.switchTo(other)
        w.idle()
        // a sheet that outlived its account (no session event reached it): its upload is still alice's, and refuses
        val stale = WaveComposeModel(w.app.env, alice)
        val video = stale.video()
        val pick = video.pick(PickedVideo(BytesSource(file, "clip.mp4"), 20_000))!!
        video.setThumbs(pick, emptyList())
        video.start()
        w.idle()
        assertEquals(PaidCopy.otherAccount(alice), video.current.error)
        assertTrue(w.proxy.isEmpty(), "no token is asked for, as anyone")
        assertEquals(0, w.server.created)
        assertNull(stale.current.videoEmbedUrl)
        assertFalse(stale.current.isVideoUploading)
    }

    @Test
    fun aShortIsSixtySecondsAtMostAndAFailedUploadContinues() = runTest {
        val w = World(this)
        val composer = w.app.env.waves.composer()
        val video = composer.video()
        assertTrue(video.short)
        assertNull(video.pick(PickedVideo(BytesSource(file, "long.mp4"), 60_001)))
        assertEquals(VideoUploadModel.TOO_LONG_SHORT, video.current.error)
        val pick = video.pick(PickedVideo(BytesSource(file, "ok.mp4"), 60_000))!!
        video.setThumbs(pick, emptyList())
        var patches = 0
        w.server.onPatch = { _, _ -> if (++patches > 2) HttpResult(503, "") else null }
        video.start()
        w.idle()
        assertTrue(video.current.canResume)
        assertFalse(composer.current.isVideoUploading)
        assertTrue(composer.current.canAddVideo)
        // the sheet was closed and opened again: the same model, the same place
        w.server.onPatch = null
        assertTrue(composer.video() === video)
        video.start()
        w.idle()
        assertEquals(1, w.server.created)
        assertEquals(embed, composer.current.videoEmbedUrl)
        assertNull(composer.current.videoThumbUrl, "no frame, no thumbnail")
    }

    @Test
    fun removingTheVideoTakesItsThumbnailAndAllowsAnother() = runTest {
        val w = World(this)
        val composer = w.composerWithVideo()
        composer.removeVideo()
        assertNull(composer.current.videoEmbedUrl)
        assertNull(composer.current.videoThumbUrl)
        assertTrue(composer.current.canAddVideo)
        assertNull(composer.video().refusal())
        assertFalse(composer.video().current.done, "the upload sheet starts over")
        // only 3Speak's own embed addresses are taken
        composer.setVideoEmbed("https://evil.example/embed?v=a/b")
        assertNull(composer.current.videoEmbedUrl)
        composer.setVideoEmbed(embed)
        assertEquals(embed, composer.current.videoEmbedUrl)
        // a video wins over an attached image, as RN's _submitPost
        assertEquals("x\n\n$embed", WavePublish.body("x", "https://images.ecency.com/a.png", embed))
        // a wave without a video links nothing
        composer.removeVideo()
        composer.setText("Just words, long enough to count as a wave")
        composer.send()
        w.idle()
        assertNotNull(composer.current.sent)
        assertEquals(0, w.links().size)
    }
}
