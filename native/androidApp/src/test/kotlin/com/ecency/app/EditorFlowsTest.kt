package com.ecency.app

import androidx.compose.ui.test.performTextClearance
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.Navigator
import com.ecency.core.api.EcencyApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcTransport
import com.ecency.core.app.AppDispatchers
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AuthServices
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.app.RouteName
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** Communities, search and explore against a scripted node (nothing on the network, nothing broadcast anywhere). */
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.ecency.app.ui.StackEntry
import com.ecency.core.api.ecency.ImageUploadTransport
import com.ecency.core.app.EditorModel
import com.ecency.core.app.InMemoryDraftStorage
import com.ecency.core.app.LocalDrafts
import com.ecency.core.app.PickedImage
import com.ecency.core.app.UploadsModel
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertNull

/** The editor and the drafts screen against a scripted node, Ecency API and image host (nothing on the network). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val api: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    @Volatile private var apiDown = false
    private val storage = InMemoryDraftStorage()
    private val uploadGate = CompletableDeferred<Unit>()

    private val drafts = """[{"_id":"d1","title":"Saved draft","body":"draft body","tags":"ecency test","modified":"2026-10-05T10:00:00.000Z","created":"2026-10-05T10:00:00.000Z","meta":{"rewardType":"sp"}}]"""

    private val http = AuthHttp { request ->
        if (request.url.contains("/private-api/")) {
            val path = request.url.substringAfter("/private-api/")
            api += path to request.body.orEmpty()
            if (apiDown) return@AuthHttp HttpResult(503, "{}")
            fun wrapped(data: String) = HttpResult(200, """{"data":$data,"pagination":{"total":1,"limit":20,"offset":0,"has_next":false}}""")
            when {
                path.startsWith("drafts?") -> wrapped(drafts)
                path == "drafts" -> HttpResult(200, drafts)
                path == "drafts-add" -> HttpResult(200, """{"drafts":[{"_id":"d9","title":"t","body":"b","tags":"","modified":"2026-10-06T10:00:00.000Z","meta":{}}]}""")
                path.startsWith("schedules?") -> wrapped("""[{"_id":"s1","title":"Later post","body":"b","permlink":"later","tags_arr":["a"],"schedule":"2026-11-01T10:00:00+00:00","status":1}]""")
                path.startsWith("images?") -> wrapped("""[{"_id":"i1","url":"https://images.example/one.jpg","created":"2026-10-01"}]""")
                path.startsWith("fragments?") -> wrapped("""[{"id":"f1","title":"Footer","body":"Thanks for reading!"}]""")
                path == "support-settings" -> HttpResult(200, """{"beneficiary_percent":0,"curation_percent":0}""")
                path == "points" -> HttpResult(200, """{"points":"1,000.000","unclaimed_points":"0"}""")
                path == "ai-assist-price" -> HttpResult(200, """[{"action":"improve","cost":5,"free_remaining":0},{"action":"suggest_tags","cost":2,"free_remaining":1}]""")
                path == "ai-generate-price" -> HttpResult(200, """{"prices":[{"aspect_ratio":"4:3","cost":150}],"power":[{"power":1,"multiplier":1},{"power":2,"multiplier":2}]}""")
                path == "ai-transcribe-price" -> HttpResult(200, """{"unit_seconds":30,"unit_cost":15,"free_remaining":0,"max_seconds":300}""")
                path == "ai-transcribe" -> {
                    transcribed += request.bytes!!.decodeToString()
                    HttpResult(200, """{"text":"Spoken words.","units":1,"cost":15}""")
                }
                path == "ai-assist" || path == "ai-generate-image" -> {
                    paid += path to request.body.orEmpty()
                    synchronized(paidAnswers) { paidAnswers.removeFirstOrNull() }?.invoke()
                        ?: if (path == "ai-assist") HttpResult(200, """{"action":"improve","output":"Text improved by the scripted assistant.","cost":5}""")
                        else HttpResult(200, """{"url":"https://images.example/ai/generated.png","cost":150}""")
                }
                else -> HttpResult(200, "{}")
            }
        } else if ("/api/threespeak/" in request.url) {
            speakCalls += request.url.substringAfter("/api/threespeak/") to request.body.orEmpty()
            if (request.url.endsWith("/upload-token")) HttpResult(200, """{"token":"up-1","upload_url":"https://embed.3speak.tv/uploads","permlink":"vid123","embed_url":"https://play.3speak.tv/embed?v=tester/vid123"}""")
            else HttpResult(200, "{}")
        } else if (request.url.startsWith("https://embed.3speak.tv/")) {
            // a tus server in a few lines: creation, offset, pieces in order
            tusRequests += request
            when (request.method) {
                "POST" -> HttpResult(201, "", mapOf("location" to "/uploads/abc"))
                "HEAD" -> HttpResult(200, "", mapOf("upload-offset" to tusStored.get().toString()))
                "PATCH" -> if (request.headers["Upload-Offset"] != tusStored.get().toString()) HttpResult(409, "") else {
                    HttpResult(204, "", mapOf("upload-offset" to tusStored.addAndGet(request.bytes!!.size.toLong()).toString()))
                }
                else -> HttpResult(405, "")
            }
        } else if ("/api/newsletter/" in request.url) {
            digestCalls += request
            when (request.method) {
                "GET" -> HttpResult(200, """{"subscriptions":[]}""")
                "POST" -> HttpResult(200, """{"status":"pending_confirmation"}""")
                else -> HttpResult(200, "{}")
            }
        } else if (request.url.endsWith("/translate")) {
            translateAsked += request.body.orEmpty()
            val q = Json.parseToJsonElement(request.body!!).jsonObject["q"]!!.jsonPrimitive.content
            if (translateDown) HttpResult(500, "{}") else HttpResult(200, """{"translatedText":${kotlinx.serialization.json.JsonPrimitive("ES: $q")}}""")
        } else {
            node.auth.services.http.send(request)
        }
    }
    /** Requests that reached the scripted newsletter relay. */
    private val digestCalls: MutableList<com.ecency.core.auth.AuthHttpRequest> = Collections.synchronizedList(mutableListOf())

    /** Paid AI requests that reached the scripted API, and answers to give before the default one. */
    private val paid: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val paidAnswers = ArrayDeque<() -> HttpResult>()

    private fun paidKeys() = paid.map { Json.parseToJsonElement(it.second).jsonObject["idempotency_key"]!!.jsonPrimitive.content }

    /** Multipart bodies that reached the scripted transcription endpoint. */
    private val transcribed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** A microphone that records nothing: no audio exists in these tests. */
    private val recorder = object : com.ecency.core.app.DictationRecorder {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var clip: ByteArray? = null
        override fun hasPermission() = true
        override fun start(ownerTag: String): Boolean { events += "start"; return true }
        override fun stop(): String? { events += "stop"; clip = "fake-audio".toByteArray(); return "clip.m4a" }
        override fun cancel() { events += "cancel" }
        override fun read(path: String): ByteArray? = clip
        override fun delete(path: String) { clip = null }
        override fun clipNames(): List<String> = if (clip == null) emptyList() else listOf("clip.m4a")
    }

    private val speakCalls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val tusRequests: MutableList<AuthHttpRequest> = Collections.synchronizedList(mutableListOf())
    private val tusStored = java.util.concurrent.atomic.AtomicLong(0)

    private val translateAsked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var translateDown = false

    private val imageHost = ImageUploadTransport { _, _, file, onProgress ->
        onProgress(1, 2)
        uploadGate.await()
        HttpResult(200, """{"url":"https://images.example/${file.name}"}""")
    }

    private val env by lazy {
        AppEnvironment(
            api = EcencyApi(listOf("https://node-a.example"), node.transport),
            vault = KeyVaultAsync(KeyVault(node.store), Dispatchers.IO),
            renderer = InterimPostBodyRenderer("https://i.ecency.com"),
            dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
            proxyBase = "https://i.ecency.com",
            debugKeyImport = true,
            nowMillis = { 1_791_288_000_000L },
            auth = AuthServices(http, node.auth.services.sockets),
        ).also {
            it.prefs.putString(com.ecency.core.app.FeedTabs.PREFS_KEY, "trending,hot,created,friends,communities")
            it.draftStorage = storage
            it.imageTransport = imageHost
            it.imageHost = "https://images.example"
            it.dictationRecorder = recorder
        }
    }
    private val navigator = Navigator()
    // the looping preview, recorded instead of decoded: what plays, whether it plays, and that it is released
    private val previews: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val previewPlayer = com.ecency.app.ui.VideoPreviewPlayer { ref, playing, modifier ->
        androidx.compose.runtime.DisposableEffect(ref, playing) {
            previews += (if (playing) "play " else "pause ") + ref.substringAfterLast('/')
            onDispose {}
        }
        androidx.compose.runtime.DisposableEffect(ref) { onDispose { previews += "release" } }
        androidx.compose.foundation.layout.Box(modifier)
    }

    // "Record Video": a camera app that writes a small file into the uri it is given (or nothing, when it cancels)
    private val appContext get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
    @Volatile private var cameraWrites: ByteArray? = ByteArray(4000) { (it % 13).toByte() }
    private val recorded: MutableList<android.net.Uri> = Collections.synchronizedList(mutableListOf())
    private val captures by lazy {
        com.ecency.app.VideoCaptures(appContext) { uri ->
            recorded += uri
            cameraWrites?.let { com.ecency.app.EditorImages.captureFile(appContext, uri)!!.apply { parentFile?.mkdirs() }.writeBytes(it) }
            true
        }
    }
    private fun captureFiles() = java.io.File(appContext.cacheDir, "camera").listFiles()?.map { it.name }.orEmpty()

    private val host = object : AppHost {
        override val videoCaptures: com.ecency.app.VideoCaptures get() = captures
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }


    /**
     * FileProvider remembers its folders per authority for the life of the JVM, while Robolectric gives every
     * test a new data folder: without this the second test's capture uri would point into the first test's.
     */
    @Before
    fun forgetFileProviderRoots() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        synchronized(cache as Any) { (cache as MutableMap<String, Any>).clear() }
    }

    @Before
    fun setUp() {
        acceptTerms()
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun exists(tag: String, text: String) =
        compose.onAllNodes(hasTestTag(tag) and hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun waitFor(tag: String) {
        compose.waitUntil(FAILURE_BOUND_MILLIS) { idleMain(); exists(tag) }
        compose.waitForIdle()
    }

    private fun waitUntil(what: () -> Boolean) = compose.waitUntil(FAILURE_BOUND_MILLIS) { idleMain(); what() }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        idleMain()
        compose.waitForIdle()
    }

    /** A tap inside a dialog or sheet: its window takes no injected touches under Robolectric. */
    private fun tap(tag: String) {
        settle()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle()
    }

    private fun launchSignedIn() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-tester"))
        vault.save(VaultAccount("second", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-second"))
        vault.setCurrentUsername("tester")
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.ecency.app.ui.LocalVideoPreview provides previewPlayer) { EcencyApp(env, host, navigator) }
        }
        waitUntil { env.session.current.username == "tester" }
        waitFor("card:alice/trending-one")
    }

    private fun push(screen: AppScreen) = compose.runOnUiThread { navigator.push(screen) }

    private fun editorEntry(): StackEntry = navigator.top

    private fun model(): EditorModel = editorEntry().peek<EditorModel>("editor")!!

    private fun openEditor(screen: AppScreen = AppScreen.Editor()) {
        push(screen)
        waitFor("editorBody")
    }

    /** The image generator and dictation live in the AI Assist sheet (the editor's AI hub), not on the toolbar. */
    private fun openFromAiAssist(tag: String) {
        compose.onNodeWithTag("toolAiAssist").performScrollTo().performClick()
        waitFor(tag)
        tap(tag)
        assertFalse("the hub gave way to the tool", exists("aiAssistSheet"))
    }

    /** The direction Compose resolved for the first paragraph of the text field [tag]. */
    private fun fieldDirection(tag: String): androidx.compose.ui.text.style.ResolvedTextDirection {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first().getParagraphDirection(0)
    }

    @Test
    fun inAnRtlUiTheEditorFieldsFollowWhatIsTyped() {
        com.ecency.core.l10n.L10n.setLanguage("ar-SA")
        try {
            launchSignedIn()
            openEditor()
            write(title = "Hello world", body = "Hello world. @alice")
            val ltr = androidx.compose.ui.text.style.ResolvedTextDirection.Ltr
            // a style without a direction would lay these out right to left (".Hello world", "alice@")
            assertEquals(ltr, fieldDirection("editorTitle"))
            compose.onNodeWithTag("tagInput").performTextInput("travel")
            assertEquals(ltr, fieldDirection("tagInput"))
            assertEquals(ltr, fieldDirection("editorBody"))
            compose.onNodeWithTag("editorBody").performTextClearance()
            compose.onNodeWithTag("editorBody").performTextInput("مرحبا بالعالم")
            assertEquals(androidx.compose.ui.text.style.ResolvedTextDirection.Rtl, fieldDirection("editorBody"))
        } finally {
            com.ecency.core.l10n.L10n.setLanguage(com.ecency.core.l10n.L10n.SOURCE)
        }
    }

    private fun write(title: String = "Native post", body: String = "Hello from the native editor") {
        compose.onNodeWithTag("editorTitle").performTextInput(title)
        compose.onNodeWithTag("tagInput").performTextInput("ecency test ")
        compose.onNodeWithTag("editorBody").performTextInput(body)
        compose.waitForIdle()
    }

    private fun operations(): List<String> {
        val tx = Json.parseToJsonElement(node.broadcasts().last()).jsonObject["params"]!!.jsonArray[0].jsonObject
        return tx["operations"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content }
    }

    @Test
    fun typedTagsLeaveTheFieldAndNeverRaiseADuplicate() {
        launchSignedIn()
        openEditor()
        // one key at a time, as typed: each word becomes a chip and leaves the field
        for (key in "a b c ") compose.onNodeWithTag("tagInput").performTextInput(key.toString())
        compose.waitForIdle()
        for (tag in listOf("a", "b", "c")) assertTrue(tag, exists("tag:$tag"))
        assertEquals("", model().current.tagInput)
        compose.onNodeWithTag("tagInput").assert(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(""),
            ),
        )
        assertNull("no duplicate toast for new tags", env.ui.state.value.toast)
        // a real duplicate: one toast, no second chip, the field cleared
        for (key in "a ") compose.onNodeWithTag("tagInput").performTextInput(key.toString())
        compose.waitForIdle()
        assertEquals("Tag exists already", env.ui.state.value.toast?.text)
        assertEquals(listOf("a", "b", "c"), model().current.tags)
        assertEquals("", model().current.tagInput)
        // a duplicate pasted in one go into the cleared field: one toast too, never dropped quietly
        compose.runOnUiThread { env.ui.dismiss(env.ui.state.value.toast!!.id) }
        compose.onNodeWithTag("tagInput").performTextInput("b ")
        compose.waitForIdle()
        assertEquals("Tag exists already", env.ui.state.value.toast?.text)
        assertEquals(listOf("a", "b", "c"), model().current.tags)
    }

    @Test
    fun aNewPostIsWrittenConfirmedAndPublished() {
        launchSignedIn()
        compose.onNodeWithTag("createFab").performClick()
        waitFor("editorBody")
        compose.onNodeWithTag("editorSubmit").assertIsNotEnabled()
        write()
        compose.onNodeWithTag("tag:ecency").assertExists()
        compose.onNodeWithTag("wordsCount").assertTextContains("5 words")
        compose.onNodeWithTag("editorSubmit").assertIsEnabled().performClick()
        compose.onNodeWithText("Publishing new post").assertExists()
        assertTrue("nothing is sent before the confirm", node.broadcasts().isEmpty())
        tap("confirmYes")
        waitUntil { node.broadcasts().isNotEmpty() }
        assertEquals(listOf("comment", "comment_options"), operations())
        // RN: the profile replaces the editor
        waitUntil { navigator.top.screen == AppScreen.Profile("tester") }
        assertTrue(storage.values.isEmpty())
    }

    private val longBody = "This is the body of the post and it is long enough for the language to be guessed with the words that are in it."

    @Test
    fun translateAddsTheTranslationToTheEndOfThePost() {
        launchSignedIn()
        openEditor()
        write(body = longBody)
        compose.onNodeWithTag("toolTranslate").performScrollTo().performClick()
        waitFor("translateRun")
        compose.onNodeWithTag("translateSource", useUnmergedTree = true).assertTextContains("English", substring = true)
        compose.onNodeWithTag("translateTarget", useUnmergedTree = true).assertTextContains("Spanish", substring = true)
        assertTrue("nothing is translated before the button", translateAsked.isEmpty())
        compose.onNodeWithTag("translateApply", useUnmergedTree = true).assertIsNotEnabled()
        tap("translateRun")
        waitFor("translatePreview")
        assertEquals(1, translateAsked.size)
        tap("translateApply")
        waitUntil { model().current.body.endsWith("ES: $longBody") }
        assertEquals("$longBody\n\n---\n\n## Spanish\n\nES: $longBody", model().current.body)
        assertEquals("Native post [EN | ES]", model().current.title)
        assertFalse(exists("translateSheet"))
    }

    @Test
    fun aFailedTranslationChangesNothing() {
        launchSignedIn()
        openEditor()
        write(body = longBody)
        translateDown = true
        compose.onNodeWithTag("toolTranslate").performScrollTo().performClick()
        waitFor("translateRun")
        tap("translateRun")
        waitFor("translateError")
        compose.onNodeWithTag("translateApply", useUnmergedTree = true).assertIsNotEnabled()
        assertEquals(longBody, model().current.body)
        assertEquals("Native post", model().current.title)
    }

    @Test
    fun aShortDraftIsNotTranslated() {
        launchSignedIn()
        openEditor()
        write(body = "Too short")
        compose.onNodeWithTag("toolTranslate").performScrollTo().performClick()
        waitFor("translateTooShort")
        assertFalse(exists("translateRun"))
        assertTrue(translateAsked.isEmpty())
    }

    @Test
    fun aiAssistShowsPriceAndBalanceChargesOnceAndReplacesTheBody() {
        launchSignedIn()
        openEditor()
        write(body = longBody)
        compose.onNodeWithTag("toolAiAssist").performScrollTo().performClick()
        waitFor("aiAction:improve")
        compose.onNodeWithTag("aiBalance", useUnmergedTree = true).assertTextContains("Balance: 1,000.000 POINTS")
        compose.onNodeWithTag("aiPrice:suggest_tags", useUnmergedTree = true).assertTextContains("1 free left", substring = true)
        compose.onNodeWithTag("aiSubmit", useUnmergedTree = true).assertIsNotEnabled()
        tap("aiAction:improve")
        compose.onNodeWithText("Run AI Assist (5 POINTS)", useUnmergedTree = true).assertExists()
        assertTrue("nothing is charged before the button", paid.isEmpty())
        tap("aiSubmit")
        waitFor("aiResult")
        assertEquals(1, paid.size)
        tap("aiApply")
        waitUntil { model().current.body == "Text improved by the scripted assistant." }
        assertEquals(setOf("writing_edit"), model().disclosedAiTools)
        assertFalse(exists("aiAssistSheet"))
    }

    @Test
    fun aLostAiAnswerOffersCheckAgainWithTheSameKey() {
        launchSignedIn()
        openEditor()
        write(body = longBody)
        paidAnswers += { throw IllegalStateException("connection lost") }
        compose.onNodeWithTag("toolAiAssist").performScrollTo().performClick()
        waitFor("aiAction:improve")
        compose.onNodeWithTag("aiToolImage", useUnmergedTree = true).assertIsEnabled()
        tap("aiAction:improve")
        tap("aiSubmit")
        waitFor("aiNotice")
        compose.onNodeWithText("Check again", useUnmergedTree = true).assertExists()
        assertTrue(exists("aiGiveUp"))
        // leaving for another AI tool would close this run before its answer is known
        compose.onNodeWithTag("aiToolImage", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithTag("aiToolDictate", useUnmergedTree = true).assertIsNotEnabled()
        settle()
        assertEquals("no retry by itself", 1, paid.size)
        assertEquals(longBody, model().current.body)
        tap("aiSubmit")
        waitFor("aiResult")
        assertEquals(2, paid.size)
        assertEquals(1, paidKeys().toSet().size)
        // the sheet closed and opened again starts a new run with a new key only after this one was delivered
        tap("aiTryAgain")
        tap("aiSubmit")
        waitUntil { paid.size == 3 }
        assertEquals(2, paidKeys().toSet().size)
    }

    @Test
    fun anAiImageIsPaidOnceFetchedWhenPendingAndInserted() {
        launchSignedIn()
        openEditor()
        write(body = "Start")
        paidAnswers += { HttpResult(202, """{"error":"delivery_pending","retry_after":2}""") }
        openFromAiAssist("aiToolImage")
        waitFor("aiImageGenerate")
        // the price arrives from the points API after the screen shows ("Cost: ..." until then): wait for it
        waitUntil { exists("aiImageCost", "Cost: 150 POINTS") }
        compose.onNodeWithTag("aiImageGenerate", useUnmergedTree = true).assertIsNotEnabled()
        tap("aiImageSuggestion")
        tap("aiPower:2")
        compose.onNodeWithText("Generate Image (300 POINTS)", useUnmergedTree = true).assertExists()
        tap("aiImageGenerate")
        // the pending answer is asked for again after the server's retry_after: the looper's clock has to move
        waitUntil { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)); paid.size == 2 }
        waitFor("aiImageResult")
        assertEquals(1, paidKeys().toSet().size)
        tap("aiImageInsert")
        waitUntil { "![](https://images.example/ai/generated.png)" in model().current.body }
        assertEquals(setOf("media_generation"), model().disclosedAiTools)
    }

    @Test
    fun dictationChargesOnStopSaysSoAndInsertsTheTranscript() {
        launchSignedIn()
        openEditor()
        write(body = "Typed.")
        openFromAiAssist("aiToolDictate")
        waitFor("dictationMain")
        waitUntil { compose.onAllNodes(hasText("15 Points per 30 seconds"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dictationBalance", useUnmergedTree = true).assertTextContains("Balance: 1,000.000 POINTS")
        // the lines that arrive after the sheet opened have their room from the first frame: the sheet does not grow
        val lines = compose.onNodeWithTag("dictationLines", useUnmergedTree = true).fetchSemanticsNode()
        val balance = compose.onNodeWithTag("dictationBalance", useUnmergedTree = true).fetchSemanticsNode().size.height
        val hint = compose.onNodeWithTag("dictationHint", useUnmergedTree = true).fetchSemanticsNode().size.height
        assertFalse("no free units in this run: two of the three lines show", exists("dictationFree"))
        assertTrue("room for all three is held (${lines.size.height} for $balance + $hint)", lines.size.height >= balance + hint + balance)
        tap("dictationMain")
        waitFor("dictationRecording")
        // the tap that spends points says how many
        compose.onNodeWithText("Stop (15 Points)", useUnmergedTree = true).assertExists()
        assertTrue("nothing is uploaded while recording", transcribed.isEmpty())
        tap("dictationMain")
        waitUntil { model().current.body == "Typed. Spoken words." }
        assertEquals(1, transcribed.size)
        assertTrue("name=\"audio\"; filename=\"dictation.m4a\"" in transcribed.single())
        assertTrue("fake-audio" in transcribed.single())
        assertNull("the clip is deleted after its upload was answered", recorder.clip)
        compose.onNodeWithText("Record more", useUnmergedTree = true).assertExists()
        assertTrue(exists("dictationSheet"))
    }

    @Test
    fun aRecordingGoesOnInTheBackgroundAndEndsWithTheSheetForFree() {
        launchSignedIn()
        openEditor()
        write(body = "Typed.")
        openFromAiAssist("aiToolDictate")
        waitFor("dictationMain")
        waitUntil { compose.onAllNodes(hasText("15 Points per 30 seconds"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tap("dictationMain")
        waitFor("dictationRecording")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        idleMain()
        // README "Background media": leaving the foreground does not stop it (the microphone service holds it)
        assertEquals(listOf("start"), recorder.events.toList())
        assertFalse(env.isForeground)
        assertTrue(env.dictationSessions.isRecording)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        settle()
        waitFor("dictationRecording")
        // the sheet going away ends it, thrown away and never charged
        compose.runOnIdle { env.dictationSessions.stopAll() }
        idleMain()
        assertEquals(listOf("start", "cancel"), recorder.events.toList())
        assertFalse(env.dictationSessions.isRecording)
        settle()
        assertTrue(transcribed.isEmpty())
        assertEquals("Typed.", model().current.body)
    }

    @Test
    fun theLockEngagingWhileRecordingHoldsTheClipForTheNextSheet() {
        com.ecency.core.secure.PinLock(KeyVault(node.store), com.ecency.core.secure.PinLock.DEFAULT_ITERATIONS).setPin("1234")
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(com.ecency.app.ui.LocalVideoPreview provides previewPlayer) { EcencyApp(env, host, navigator) }
        }
        fun unlock() {
            waitUntil { env.appLock.current.isLocked }
            compose.runOnIdle { "1234".forEach { env.appLock.enterDigit(it.digitToInt()) } }
            waitUntil { !env.appLock.current.hidesContent }
        }
        unlock()
        waitFor("card:alice/trending-one")
        openEditor()
        write(body = "Typed.")
        openFromAiAssist("aiToolDictate")
        waitFor("dictationMain")
        waitUntil { compose.onAllNodes(hasText("15 Points per 30 seconds"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tap("dictationMain")
        waitFor("dictationRecording")
        // the return after the lock timeout: the lock cover replaces the composer (and closes the sheet)
        compose.runOnIdle { env.appLock.lockIfEnabled() }
        settle()
        assertEquals("stopped, not thrown away", listOf("start", "stop"), recorder.events.toList())
        unlock()
        openFromAiAssist("aiToolDictate")
        waitUntil { compose.onAllNodes(hasText("Transcribe", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("nothing is sent behind the lock", transcribed.isEmpty())
    }

    @Test
    fun aCallTakingTheMicrophoneHoldsTheClipUnsent() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        env.dictationSessions.host = AndroidDictationHost(context, env.dictationSessions)
        launchSignedIn()
        openEditor()
        write(body = "Typed.")
        openFromAiAssist("aiToolDictate")
        waitFor("dictationMain")
        waitUntil { compose.onAllNodes(hasText("15 Points per 30 seconds"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tap("dictationMain")
        waitFor("dictationRecording")
        // the system gives the microphone's audio focus to a call
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val focus = org.robolectric.Shadows.shadowOf(audio).lastAudioFocusRequest
        org.junit.Assert.assertNotNull("recording asks for audio focus", focus)
        compose.runOnIdle { focus.listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) }
        settle()
        assertEquals(listOf("start", "stop"), recorder.events.toList())
        waitUntil { compose.onAllNodes(hasText("Transcribe", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(env.dictationSessions.isRecording)
        assertTrue("nothing is sent before the user confirms", transcribed.isEmpty())
    }

    @Test
    fun aVideoIsUploadedWithItsThumbnailAndOnlyOneFitsAPost() {
        launchSignedIn()
        openEditor()
        write(body = "Start")
        compose.onNodeWithTag("toolVideo").performScrollTo().performClick()
        waitFor("videoChoose")
        assertFalse(exists("videoStart"))
        // the system picker cannot be driven here: the picked video and its frames arrive as the screen would report them
        val video = editorEntry().peek<com.ecency.core.app.VideoUploadModel>("video")!!
        val bytes = ByteArray(3_000_000) { (it % 31).toByte() }
        compose.runOnUiThread {
            val pick = video.pick(com.ecency.core.app.PickedVideo(object : com.ecency.core.api.speak.TusSource {
                override val name = "holiday.mp4"
                override val size = bytes.size.toLong()
                override fun read(offset: Long, length: Int) = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            }, 12_000))!!
            video.setThumbs(pick, (1..3).map { PickedImage(byteArrayOf(it.toByte()), "frame$it.jpg", "image/jpeg", 16, 9) })
        }
        waitFor("videoStart")
        compose.onNodeWithTag("videoName", useUnmergedTree = true).assertTextContains("holiday.mp4", substring = true)
        assertTrue(exists("videoThumb1"))
        uploadGate.complete(Unit)
        tap("videoStart")
        waitUntil { "https://play.3speak.tv/embed?v=tester/vid123" in model().current.body }
        assertEquals("Start\nhttps://play.3speak.tv/embed?v=tester/vid123\n", model().current.body)
        assertEquals(3_000_000L, tusStored.get())
        assertTrue("a piece is at most 2 MB", tusRequests.filter { it.method == "PATCH" }.all { it.bytes!!.size <= 2 * 1024 * 1024 })
        assertTrue("no user agent of the app goes to 3Speak", tusRequests.none { r -> r.headers.keys.any { it.equals("User-Agent", true) } })
        assertEquals("upload-token", speakCalls.first().first)
        assertEquals(listOf("https://images.example/frame2.jpg"), model().current.videoThumbUrls)
        waitUntil { !exists("videoSheet") }
        // RN: a second video is refused with a notice
        compose.onNodeWithTag("toolVideo").performScrollTo().performClick()
        waitFor("videoNotice")
        assertFalse(exists("videoSheet"))
    }

    @Test
    fun aRecordedVideoIsPreviewedUploadedAndItsTemporaryFileIsDeleted() {
        launchSignedIn()
        openEditor()
        write(body = "Start")
        compose.onNodeWithTag("toolVideo").performScrollTo().performClick()
        waitFor("videoRecord")
        assertTrue(exists("videoChoose"))
        tap("videoRecord")
        // the camera app closes with its recording
        compose.runOnUiThread { captures.onResult(true) }
        waitFor("videoStart")
        val name = recorded.single().lastPathSegment!!.substringAfterLast('/')
        assertEquals(listOf(name), captureFiles())
        // RN: the looping, muted preview plays until the upload starts
        waitFor("videoPreview")
        waitUntil { previews.lastOrNull() == "play $name" }
        // the app leaves the foreground: the preview pauses, and plays again in front
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        waitUntil { previews.lastOrNull() == "pause $name" }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitUntil { previews.lastOrNull() == "play $name" }
        // RN's pencil: another image as the thumbnail (the picker cannot be driven; the prepared image arrives as it would)
        assertTrue(exists("videoThumbEdit"))
        val video = editorEntry().peek<com.ecency.core.app.VideoUploadModel>("video")!!
        compose.runOnUiThread { video.setCustomThumb(PickedImage(byteArrayOf(7), "cover.jpg", "image/jpeg", 4, 3)) }
        uploadGate.complete(Unit)
        tap("videoStart")
        waitUntil { "https://play.3speak.tv/embed?v=tester/vid123" in model().current.body }
        assertEquals(4000L, tusStored.get())
        assertEquals(listOf("https://images.example/cover.jpg"), model().current.videoThumbUrls)
        waitUntil { !exists("videoSheet") }
        // uploaded: the recording is gone from the cache, and the player was released with the sheet
        waitUntil { captureFiles().isEmpty() }
        assertEquals("release", previews.last())
        assertTrue("paused while uploading", "pause $name" in previews.dropLast(1).takeLast(2))
    }

    @Test
    fun aCancelledRecordingOrAClosedSheetLeavesNoFileAndNoCameraIsSaid() {
        launchSignedIn()
        openEditor()
        write(body = "Start")
        compose.onNodeWithTag("toolVideo").performScrollTo().performClick()
        waitFor("videoRecord")
        // the camera app is closed without a recording
        cameraWrites = null
        tap("videoRecord")
        compose.runOnUiThread { captures.onResult(false) }
        idleMain()
        assertTrue(captureFiles().isEmpty())
        assertFalse(exists("videoStart"))
        // a recording is made, then the sheet is closed without uploading it
        cameraWrites = ByteArray(1000)
        tap("videoRecord")
        compose.runOnUiThread { captures.onResult(true) }
        waitFor("videoStart")
        assertEquals(1, captureFiles().size)
        tap("videoClose")
        waitUntil { !exists("videoSheet") }
        waitUntil { captureFiles().isEmpty() }
        assertEquals("Start", model().current.body)
    }

    @Test
    fun theDigestOfferAfterAFirstPostAsksForAnAddressAndClosesWithItsAccount() {
        launchSignedIn()
        // RN maybeOfferFirstPublishDigest shows this sheet (when it does is in the shared tests)
        compose.runOnUiThread { env.digest.show("own", "tester", firstPublish = true) }
        waitFor("digestSubmit")
        compose.onNodeWithTag("digestTitle", useUnmergedTree = true).assertTextContains("Congrats on your first post!")
        compose.onNodeWithTag("digestSubmit").assertIsNotEnabled()
        compose.onNodeWithTag("digestEmail").performTextInput("tester@example.com")
        tap("digestCadence:monthly")
        compose.onNodeWithTag("digestSubmit").assertIsEnabled()
        tap("digestSubmit")
        waitFor("digestCheckInbox")
        val sent = digestCalls.last { it.method == "POST" }
        assertTrue(sent.url.endsWith("/api/newsletter/subscribe"))
        assertEquals("""{"email":"tester@example.com","type":"own","target":"tester","cadence":"monthly","source":"mobile-app","code":"access-tester"}""", sent.body)
        tap("digestOk")
        waitUntil { !exists("digestSheet") }
        // open again, then the account changes: the sheet goes with its account
        compose.runOnUiThread { env.digest.show("own", "tester") }
        waitFor("digestSubmit")
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) { env.session.switchTo("second") }
        waitUntil { !exists("digestSheet") && env.session.current.username == "second" }
        assertFalse(env.digest.current.open)
    }

    @Test
    fun theGeneratorRouteIsTheGeneratorWithoutAnEditorToInsertInto() {
        launchSignedIn()
        push(AppScreen.of(RouteName.AI_IMAGE_GENERATOR, ""))
        waitFor("aiImageGenerate")
        // the price arrives from the points API after the screen shows ("Cost: ..." until then): wait for it
        waitUntil { exists("aiImageCost", "Cost: 150 POINTS") }
        compose.onNodeWithTag("aiImagePrompt").performTextInput("A lighthouse at dawn")
        compose.onNodeWithText("Generate Image (150 POINTS)", useUnmergedTree = true).assertExists()
        tap("aiImageGenerate")
        waitUntil { paid.size == 1 }
        waitFor("aiImageResult")
        assertTrue(exists("aiImageShare"))
        assertFalse("nothing to insert into", exists("aiImageInsert"))
    }

    /**
     * Owner order (10-28): Aa, Image, Video, AI Assist, Snippets, Link, Poll, Translate, Clear. On a 320dp phone at
     * twice the text size the first four are on screen without scrolling, and the AI tools have no buttons of their
     * own: AI Assist lists them.
     */
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE) // real font metrics
    fun theToolbarLeadsWithTheWritingToolsAndAiAssistIsOnANarrowScreenAtLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        launchSignedIn()
        openEditor()
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val order = listOf("toolFormat", "toolImage", "toolVideo", "toolAiAssist", "toolSnippets", "toolLink", "toolPoll", "toolTranslate", "toolClear")
        // unclipped: the bounds of a button past the screen edge are clipped to it
        fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val lefts = order.map { node(it).positionInRoot.x }
        assertEquals("toolbar order ${order.zip(lefts)}", lefts.sorted(), lefts)
        val screen = bounds("editorToolbar").right
        val aiRight = node("toolAiAssist").let { it.positionInRoot.x + it.size.width }
        assertTrue("AI Assist on screen without scrolling (ends at $aiRight of $screen)", aiRight <= screen)
        assertFalse(exists("toolAiImage"))
        assertFalse(exists("toolDictate"))
        // the labels stop at 1.2 times; a long press shows the name at the full text size (iOS: large content viewer)
        fun fontScaleOf(matcher: androidx.compose.ui.test.SemanticsMatcher): Float {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNode(matcher, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.first().layoutInput.density.fontScale
        }
        assertEquals(1.2f, fontScaleOf(hasText("AI Assist") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("toolAiAssist"))), 0.001f)
        compose.onNodeWithTag("toolAiAssist").performTouchInput { longClick() }
        waitFor("toolAiAssist:tooltip")
        assertEquals(2f, fontScaleOf(hasText("AI Writing Assistant") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("toolAiAssist:tooltip"))), 0.001f)
        assertFalse("a long press opens nothing", exists("aiAssistSheet"))
        settle()
        compose.onNodeWithTag("toolAiAssist").performClick()
        waitFor("aiToolImage")
        assertTrue(exists("aiToolDictate"))
        // the tools are a group above the actions, never between a chosen action and its text
        waitFor("aiAction:improve")
        fun top(tag: String) = node(tag).positionInRoot.y
        assertTrue("tools above the actions", top("aiToolDictate") < top("aiAction:improve"))
        assertTrue("nothing is charged by opening the hub", paid.isEmpty())
    }

    /** A reply (and an edit) has no poll; the rest keeps the owner's order. */
    @Test
    fun aReplysToolbarHasNoPollAndKeepsTheOrder() {
        launchSignedIn()
        openEditor(AppScreen.Editor.of("reply:alice/trending-one"))
        assertTrue(model().current.isReply)
        assertFalse(exists("toolPoll"))
        val order = listOf("toolFormat", "toolImage", "toolVideo", "toolAiAssist", "toolSnippets", "toolLink", "toolTranslate", "toolClear")
        val lefts = order.map { compose.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x }
        assertEquals("toolbar order ${order.zip(lefts)}", lefts.sorted(), lefts)
    }

    @Test
    fun toolbarFormatsTheSelectionAndPreviewRendersTheBody() {
        launchSignedIn()
        openEditor()
        compose.onNodeWithTag("editorBody").performTextInput("hello world")
        compose.onNodeWithTag("editorBody").performTextInputSelection(TextRange(6, 11))
        compose.onNodeWithTag("toolFormat").performClick()
        compose.onNodeWithTag("formatBold").performClick()
        compose.onNodeWithTag("editorBody").assertTextContains("hello **world**")
        assertEquals(15, model().current.selection.start)
        compose.onNodeWithTag("formatItalic").performClick()
        compose.onNodeWithTag("editorBody").assertTextContains("hello **world****")
        // the link sheet refuses a non-link and inserts a real one at the caret
        compose.onNodeWithTag("toolLink").performClick()
        settle()
        compose.onNodeWithTag("linkUrl").performTextInput("not-a-link")
        tap("linkInsert")
        compose.onNodeWithTag("linkInvalid").assertExists()
        compose.onNodeWithTag("linkUrl").performTextReplacement("https://ecency.com")
        compose.onNodeWithTag("linkLabel").performTextInput("Ecency")
        tap("linkInsert")
        assertTrue(model().current.body.contains("[Ecency](https://ecency.com)"))
        compose.onNodeWithTag("previewToggle").performClick()
        waitFor("editorPreview")
        waitUntil { model().current.preview != null }
        compose.onNodeWithText("world", substring = true).assertExists()
        assertFalse(exists("editorToolbar"))
    }

    @Test
    fun snippetsAndGalleryImagesGoInAtTheCaret() {
        launchSignedIn()
        openEditor()
        compose.onNodeWithTag("editorBody").performTextInput("start")
        compose.onNodeWithTag("toolSnippets").performClick()
        waitFor("snippet:Footer")
        tap("snippet:Footer")
        assertEquals("start\nThanks for reading!\n", model().current.body)
        compose.onNodeWithTag("toolImage").performClick()
        waitFor("galleryImage:https://images.example/one.jpg")
        compose.onNodeWithTag("openGallery", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("openFiles", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("openCamera", useUnmergedTree = true).assertExists()
        tap("galleryImage:https://images.example/one.jpg")
        assertTrue(model().current.body.contains("![](https://images.example/one.jpg)"))
    }

    @Test
    fun anUploadShowsProgressBlocksPublishingAndCanBeCancelled() {
        launchSignedIn()
        openEditor()
        write()
        val uploads = editorEntry().peek<UploadsModel>("uploads")!!
        compose.runOnUiThread { uploads.upload(listOf(PickedImage(ByteArray(8), "photo.jpg", "image/jpeg", 800, 600)), insert = true) }
        waitFor("uploadProgress")
        assertTrue(model().current.body.contains("![](Uploading... photo.jpg)"))
        assertFalse("publishing waits for the upload", exists("editorSubmit"))
        compose.onNodeWithTag("cancelUpload").performClick()
        waitUntil { model().current.uploading == 0 }
        assertFalse(model().current.body.contains("Uploading..."))
        compose.onNodeWithTag("editorSubmit").assertIsEnabled()
        // and one that finishes becomes the image
        compose.runOnUiThread { uploads.upload(listOf(PickedImage(ByteArray(8), "done.jpg", "image/jpeg", 800, 600)), insert = true) }
        waitFor("uploadProgress")
        uploadGate.complete(Unit)
        waitUntil { model().current.body.contains("![](https://images.example/done.jpg)") }
    }

    @Test
    fun leavingSavesTheDraftAndAFailedSaveKeepsItOnTheDevice() {
        launchSignedIn()
        openEditor()
        write(title = "Kept", body = "do not lose me")
        compose.onNodeWithTag("editorBack").performClick()
        waitUntil { api.any { it.first == "drafts-add" } }
        assertTrue(api.last { it.first == "drafts-add" }.second.contains("do not lose me"))
        waitUntil { storage.values.isEmpty() }

        apiDown = true
        openEditor()
        write(title = "Offline", body = "still here")
        compose.onNodeWithTag("editorBack").performClick()
        waitUntil { storage.values.containsKey(LocalDrafts.key("tester", LocalDrafts.NEW_POST)) }
        // the editor opens with it again, as after a crash or process death (the model is new)
        openEditor()
        waitUntil { model().current.title == "Offline" }
        compose.onNodeWithTag("editorBody").assertTextContains("still here")
        compose.onNodeWithTag("tag:test").assertExists()
    }

    @Test
    fun anAccountSwitchClosesTheEditorWithoutPublishingAndKeepsTheDraftForItsOwner() {
        launchSignedIn()
        openEditor()
        write(title = "Mine", body = "written as tester")
        val mine = model()
        // switchTo runs on the main dispatcher, which is this thread: start it, then let the looper run
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) { env.session.switchTo("second") }
        waitUntil { env.session.current.username == "second" && navigator.top.screen == null }
        assertTrue("the editor left with its account", navigator.state.screens.none { it is AppScreen.Editor })
        compose.runOnUiThread { mine.submit() }
        settle()
        assertTrue(node.broadcasts().isEmpty())
        waitUntil { storage.values.containsKey(LocalDrafts.key("tester", LocalDrafts.NEW_POST)) }
        // the other account's editor starts empty
        openEditor()
        waitUntil { !model().current.loading }
        assertEquals("second", model().current.owner)
        assertEquals("", model().current.title)
        assertEquals("", model().current.body)
        assertNull(storage.values[LocalDrafts.key("second", LocalDrafts.NEW_POST)])
    }

    @Test
    fun draftsScreenOpensDeletesAndListsSchedules() {
        launchSignedIn()
        push(AppScreen.Drafts())
        waitFor("draftRow:Saved draft")
        compose.onNodeWithTag("delete:Saved draft", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Are you sure you want to remove?").assertExists()
        tap("confirmAction")
        waitUntil { api.any { it.first == "drafts-delete" && it.second.contains("\"d1\"") } }
        compose.onNodeWithTag("draftsTab:Schedules").performClick()
        waitFor("draftRow:Later post")
        compose.onNodeWithTag("scheduleStatus:Later post", useUnmergedTree = true).assertTextContains("Pending")
        compose.onNodeWithTag("draftRow:Later post").performClick()
        compose.onNodeWithText("Are you sure to move to drafts?").assertExists()
        tap("confirmAction")
        waitUntil { api.any { it.first == "schedules-move" } }
        // a draft opens in the editor with its saved options
        push(AppScreen.Editor.of("draft:d1"))
        waitFor("editorBody")
        waitUntil { model().current.title == "Saved draft" }
        compose.onNodeWithTag("editorBody").assertTextContains("draft body")
        assertEquals(com.ecency.core.editor.RewardType.POWER_UP, model().current.reward)
        compose.onNodeWithTag("postOptions").performClick()
        waitFor("optionsSheet")
        compose.onNodeWithTag("thumbHint", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("benef:tester", useUnmergedTree = true).assertExists()
    }
}
