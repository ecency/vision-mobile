package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.LocalReelPlayer
import com.ecency.app.ui.ReelPlayer
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.waves.WavesApi
import com.ecency.core.auth.AuthHttp
import kotlinx.coroutines.launch
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

/** The Waves tab on Android over a scripted waves backend: lists, tabs, the composer, the Shorts reels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WavesFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode().apply {
        accountPosts = """[{"author":"ecency.waves","permlink":"waves-20261006","json_metadata":{"tags":["waves-20261006"]}}]"""
    }
    private val wavesRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun row(author: String, permlink: String, body: String = "Wave $permlink", video: Boolean = false) =
        """{"id":1,"author":"$author","permlink":"$permlink","title":"","body":"$body","json_metadata":{"app":"ecency/4.0.0-mobile","tags":["waves"]},
           "timestamp":"2026-10-06T11:00:00+00:00","pending_payout_value":"0.100 HBD","net_votes":2,"children":1,"host":"ecency.waves","_cursor":"c-$permlink"
           ${if (video) ""","video":{"platform":"3speak","author":"$author","permlink":"v-$permlink","embed_url":"https://play.3speak.tv/embed?v=$author/v-$permlink","thumbnail_url":null}""" else ""}}"""

    /** Replaces the For you page when set. */
    @Volatile private var feedOverride: String? = null

    private val waves = AuthHttp { request ->
        wavesRequests += request.url
        val url = request.url
        val override = feedOverride
        when {
            override != null && "/waves/feed" in url && "following=" !in url && "tag=" !in url -> HttpResult(200, override)
            "/waves/feed" in url && "following=" in url -> HttpResult(200, "[${row("friend", "f1")}]")
            "/waves/feed" in url && "tag=hive" in url -> HttpResult(200, "[${row("tagged", "t1")}]")
            "/waves/feed" in url -> HttpResult(200, "[${row("alice", "w1", body = "Hello #hive world")},${row("bob", "w2")}]")
            "/waves/shorts" in url -> HttpResult(200, "[${row("carol", "s1", video = true)},${row("dave", "s2", video = true)}]")
            "api/embed" in url -> HttpResult(200, """{"videoUrl":"https://play.3speak.tv/hls?u=${url.substringAfterLast('/')}"}""")
            "trending/tags" in url -> HttpResult(200, """[{"tag":"art","posts":3}]""")
            else -> HttpResult(200, "[]")
        }
    }
    private val env by lazy { node.env().also { it.waves.api = WavesApi(waves) } }

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun share(text: String) {}
    }

    /** Stands in for Media3: records which streams have a player right now. */
    private val playing: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val fakePlayer = ReelPlayer { url, _, _, _ ->
        androidx.compose.runtime.DisposableEffect(url) {
            playing += url
            onDispose { playing -= url }
        }
        androidx.compose.foundation.layout.Box(Modifier)
    }

    @Before
    fun setUp() {
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

    private fun launch(fontScale: Float = 1f) = compose.setContent {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) { EcencyTheme(dark = false) { CompositionLocalProvider(LocalReelPlayer provides fakePlayer) { EcencyApp(env, host) } } }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun theWavesReadForTalkBackAndFitAtFontScale2() {
        launch(fontScale = 2f)
        openWaves()
        compose.mainClock.advanceTimeBy(1_000)
        compose.assertAccessible("waves")
        compose.assertNothingClipped("waves")
        val header = compose.onAllNodes(hasTestTag("wave:header")).fetchSemanticsNodes().first()
        assertEquals("one description, the badges do not add their own", 1, header.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].size)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("exists(tag)") { exists(tag) }
    private fun waitGone(tag: String) = compose.await("!exists(tag)") { !exists(tag) }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    private fun pump(condition: () -> Boolean) {
        compose.mainClock.autoAdvance = false
        try {
            compose.await("the condition (main clock moved by hand)") {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                compose.mainClock.advanceTimeByFrame()
                condition()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun openWaves() {
        waitFor("bottomTab:WAVES")
        compose.onNodeWithTag("bottomTab:WAVES").performClick()
        waitFor("wave:alice/w1")
    }

    @Test
    fun theTabListsWavesAndAWaveOpensItsThread() {
        launch()
        openWaves()
        assertTrue(exists("wave:bob/w2") && exists("waves:tab:for-you") && exists("waves:tab:following") && exists("createFab"))
        assertFalse(exists("comingSoon"))
        assertTrue(wavesRequests.first { "/waves/feed" in it }.endsWith("/private-api/waves/feed?limit=20"))
        // RN #1019: an avatar that is a button on its own has a screen reader label
        compose.onAllNodes(hasTestTag("wave:avatar") and hasAnyAncestor(hasTestTag("wave:alice/w1")))[0]
            .assert(androidx.compose.ui.test.hasContentDescription("Profile of @alice"))
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        compose.onAllNodes(hasTestTag("wave:body") and hasAnyAncestor(hasTestTag("wave:alice/w1")))[0].performClick()
        waitFor("postScreen")
    }

    @Test
    fun aWaveFlaggedExplicitIsCoveredUnderWarnWithReadableTextAndAPerWaveReveal() {
        // RN #3586: "always warn" (the default) keeps the flagged wave, text readable, media covered
        val image = "![pic](https://images.ecency.com/DQmFlagged/pic.png)"
        feedOverride = "[" + row("alice", "w1", body = "Readable words $image").dropLast(1) + ""","nsfw":true}""" + "," + row("bob", "w2") + "]"
        launch()
        openWaves()
        waitFor("wave:nsfw")
        assertTrue("the text renders", exists("wave:body"))
        assertTrue("the image is a placeholder", compose.onAllNodes(hasTestTag("image-hidden") and hasAnyAncestor(hasTestTag("wave:alice/w1"))).fetchSemanticsNodes().isNotEmpty())
        // review L8: its thread keeps the media covered, with its own reveal
        // the body's own click (a tap at its centre would land on the image placeholder, which reveals that image)
        compose.onAllNodes(hasTestTag("wave:body") and hasAnyAncestor(hasTestTag("wave:alice/w1")))[0].performSemanticsAction(SemanticsActions.OnClick)
        waitFor("postNsfwReveal")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("wave:nsfw")
        // a reveal in the thread uncovers the row too (delta review 1)
        compose.onAllNodes(hasTestTag("wave:body") and hasAnyAncestor(hasTestTag("wave:alice/w1")))[0].performSemanticsAction(SemanticsActions.OnClick)
        waitFor("postNsfwReveal")
        click("postNsfwReveal")
        waitGone("postNsfwReveal")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("wave:alice/w1")
        assertFalse("revealed in the thread: the row is uncovered", exists("wave:nsfw"))
        assertTrue(
            "revealed: the placeholder is gone",
            compose.onAllNodes(hasTestTag("image-hidden") and hasAnyAncestor(hasTestTag("wave:alice/w1"))).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun aWaveFlaggedExplicitIsLeftOutUnderHide() {
        feedOverride = "[" + row("alice", "w1").dropLast(1) + ""","nsfw":true}""" + "," + row("bob", "w2") + "]"
        env.prefs.putString(com.ecency.core.app.NsfwMode.PREFS_KEY, com.ecency.core.app.NsfwMode.HIDE.value)
        launch()
        waitFor("bottomTab:WAVES")
        compose.onNodeWithTag("bottomTab:WAVES").performClick()
        waitFor("wave:bob/w2")
        assertFalse(exists("wave:alice/w1"))
    }

    @Test
    fun signedOutFollowingAndComposeAskToLogIn() {
        launch()
        openWaves()
        click("waves:tab:following")
        waitFor("login")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("wave:alice/w1")
        click("createFab")
        waitFor("login")
        assertFalse(exists("waveComposer"))
    }

    @Test
    fun aSignedInReaderSeesTheirFollowingTab() {
        node.signIn()
        launch()
        openWaves()
        click("waves:tab:following")
        waitFor("wave:friend/f1")
        assertTrue(wavesRequests.any { it.endsWith("following=tester&observer=tester") })
    }

    @Test
    fun theComposerOffersDictationAiAssistAndTheClipboardChip() {
        node.signIn()
        // an image in the clipboard (only its description is read for the chip)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData("img", arrayOf("image/png"), android.content.ClipData.Item(android.net.Uri.parse("content://fake/clip.png"))))
        launch()
        openWaves()
        click("createFab")
        waitFor("waveComposer")
        waitFor("waveClipboardChip")
        click("waveClipboardClose")
        pump { !exists("waveClipboardChip") }
        assertTrue(exists("waveDictate"))
        // RN supportedActions for a wave: improve, grammar and summary only
        click("waveAiAssist")
        pump { exists("aiAction:improve") }
        assertTrue(exists("aiAction:summarize") && exists("aiAction:check_grammar"))
        assertFalse(exists("aiAction:generate_title"))
        assertFalse(exists("aiAction:suggest_tags"))
    }

    @Test
    fun publishingAWaveBroadcastsUnderTheContainerAndShowsItOnTop() {
        node.signIn()
        launch()
        openWaves()
        click("createFab")
        waitFor("waveComposer")
        // an empty composer: PUBLISH is off
        compose.onNodeWithTag("wavePublish").assertIsNotEnabled()
        compose.onNodeWithTag("waveText").performTextInput("Hi")
        waitFor("waveShortHint")
        compose.onNodeWithTag("wavePublish").assertIsEnabled()
        // the text fills the sheet down to the hint and the toolbar: no empty stripe above the toolbar
        val dp = compose.density.density
        val textBox = compose.onNodeWithTag("waveText").fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithTag("waveShortHint").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("waveAttach").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("waveComposer").fetchSemanticsNode().boundsInRoot
        assertTrue("the text grows to fill the sheet", textBox.height > 200 * dp)
        assertTrue("the hint follows the text", hint.top - textBox.bottom < 16 * dp)
        assertTrue("the toolbar follows the hint", toolbar.top - hint.bottom < 24 * dp)
        assertTrue("the toolbar closes the sheet", sheet.bottom - toolbar.bottom < 24 * dp)
        compose.onNodeWithTag("waveText").performTextReplacement("x".repeat(251))
        compose.waitForIdle()
        compose.onNodeWithTag("wavePublish").assertIsNotEnabled()
        compose.onNodeWithTag("waveText").performTextReplacement("A wave from the native app, long enough to count")
        compose.waitForIdle()
        assertFalse(exists("waveShortHint"))
        compose.onNodeWithTag("wavePublish").assertIsEnabled()
        click("wavePublish")
        val composer = env.waves.composer()
        try {
            pump { node.broadcasts().isNotEmpty() || composer.current.error != null }
        } catch (e: AssertionError) {
            throw AssertionError("no broadcast: ${composer.current} sent=${node.sent.takeLast(4).map { it.take(120) }}")
        }
        assertEquals(null, composer.current.error)
        val tx = node.broadcasts().single()
        assertTrue(tx, "\"parent_author\":\"ecency.waves\"" in tx && "\"parent_permlink\":\"waves-20261006\"" in tx && "\"permlink\":\"wave-" in tx)
        assertTrue(tx, "\\\"type\\\":\\\"wave\\\"" in tx)
        pump { !exists("waveComposer") }
        pump { exists("wave:local") }
    }

    @Test
    fun aVideoInTheComposerShowsAsAttachedGoesIntoTheWaveAndComesOffOnATap() {
        node.signIn()
        launch()
        openWaves()
        click("createFab")
        waitFor("waveComposer")
        // RN: the video button opens the uploader (the editor's sheet over the composer's own upload model)
        click("waveVideoButton")
        waitFor("videoSheet")
        assertTrue(exists("videoChoose"))
        // RN "Record Video"; this host has no camera launcher, which is said
        click("videoRecord")
        waitFor("videoSheetNotice")
        click("videoClose")
        pump { !exists("videoSheet") }
        val composer = env.waves.composer()
        val embed = "https://play.3speak.tv/embed?v=tester/vid123"
        compose.runOnUiThread { composer.setVideoEmbed(embed) }
        waitFor("waveVideo")
        compose.onNodeWithTag("waveVideoButton").assertIsNotEnabled()
        // a tap on "Video attached" takes it off again
        click("waveVideo")
        pump { !exists("waveVideo") }
        compose.onNodeWithTag("waveVideoButton").assertIsEnabled()
        compose.runOnUiThread { composer.setVideoEmbed(embed) }
        waitFor("waveVideo")
        compose.onNodeWithTag("waveText").performTextInput("A short for the native app")
        click("wavePublish")
        pump { node.broadcasts().isNotEmpty() || composer.current.error != null }
        assertEquals(null, composer.current.error)
        val tx = node.broadcasts().single()
        assertTrue(tx, "A short for the native app\\n\\n$embed" in tx)
        assertTrue(tx, "comment_options" in tx && "threespeakfund" in tx)
        pump { !exists("waveComposer") }
    }

    @Test
    fun aWaveRefusedForTheMissingPostingAuthorityOffersTheGrantAndASkipKeepsTheDraft() {
        // a HiveSigner account: its waves go out with the token, which HiveSigner refuses without ecency.app's authority
        val vault = com.ecency.core.secure.KeyVault(node.store)
        vault.save(com.ecency.core.secure.VaultAccount("tester", com.ecency.core.secure.AuthType.HIVE_SIGNER, accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
        var refused = 0
        node.auth.rest = { r ->
            if (r.url.endsWith("/api/broadcast")) {
                refused++
                HttpResult(401, """{"error":"unauthorized_client","error_description":"The app @ecency.app doesn't have permission to broadcast for @tester"}""")
            } else {
                null
            }
        }
        launch()
        openWaves()
        click("createFab")
        waitFor("waveComposer")
        compose.onNodeWithTag("waveText").performTextInput("A wave that HiveSigner will not take yet")
        click("wavePublish")
        // RN: "Enable Faster Posting", not the raw error
        waitFor("authorityPrompt")
        assertFalse(exists("waveError"))
        click("authoritySkip")
        pump { !exists("authorityPrompt") }
        assertTrue(exists("waveComposer"))
        assertEquals("A wave that HiveSigner will not take yet", env.waves.composer().current.text)
        assertEquals(null, env.waves.composer().current.error)
        assertEquals(1, refused)
        assertTrue(node.broadcasts().isEmpty())
    }

    @Test
    fun theComposerClosesWhenTheAccountChanges() {
        node.signIn()
        launch()
        openWaves()
        click("createFab")
        waitFor("waveComposer")
        compose.onNodeWithTag("waveText").performTextInput("Written as tester, never to be sent by anyone else")
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) { env.session.signOut() }
        pump { !exists("waveComposer") && env.session.current.username == null }
        assertTrue(node.broadcasts().isEmpty())
        // the next composer (after signing in again) starts empty
        assertEquals("", env.waves.composer().current.text)
    }

    @Test
    fun aShareLinkOpensTheComposerAfterLoginAndNeverOverwritesADraft() {
        launch()
        openWaves()
        // signed out: the reader is sent to log in, the text waits
        compose.runOnUiThread { env.waves.openLink("https://ecency.com/waves?text=Shared%20from%20a%20link") }
        waitFor("login")
        assertEquals("Shared from a link", env.waves.pendingComposeText)
        assertFalse(exists("waveComposer"))
        // signed in: the composer opens with it
        node.signIn()
        compose.runOnUiThread { env.session.start() }
        pump { env.session.current.username == "tester" }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        pump { exists("waveComposer") }
        assertEquals("Shared from a link", env.waves.composer().current.text)
        // another link while that draft is there: the draft stays
        compose.runOnUiThread { env.waves.openLink("https://ecency.com/waves?text=Second") }
        pump { env.waves.pendingComposeText == null }
        assertEquals("Shared from a link", env.waves.composer().current.text)
    }

    @Test
    fun aClosingPlayerNeverWipesWebDataUnderALiveHiveSignerPage() {
        var wipes = 0
        assertTrue(com.ecency.app.ui.WebData.wipeAfterPlayer { wipes++ })
        com.ecency.app.ui.WebData.authViews.incrementAndGet()
        try {
            assertFalse(com.ecency.app.ui.WebData.wipeAfterPlayer { wipes++ })
        } finally {
            com.ecency.app.ui.WebData.authViews.decrementAndGet()
        }
        assertTrue(com.ecency.app.ui.WebData.wipeAfterPlayer { wipes++ })
        assertEquals(2, wipes)
    }

    @Test
    fun aTappedSourceBecomesATabAndUnpinningReturnsToForYou() {
        launch()
        openWaves()
        click("waves:add")
        waitFor("waves:picker")
        waitFor("waves:trending:art")
        click("waves:source:leothreads")
        click("waves:trending:art")
        waitFor("waves:tab:container:leothreads")
        waitFor("waves:tab:tag:art")
        waitFor("waves:pinned:art")
        assertEquals("art", env.prefs.getString(com.ecency.core.app.WavesTabsModel.TAGS_KEY))
        click("waves:pinned:art")
        waitGone("waves:tab:tag:art")
    }

    @Test
    fun onlyTheReelInViewHasAPlayerAndItGoesWhenTheTabOrTheAppLeaves() {
        env.prefs.putString(com.ecency.core.app.WavesTabsModel.CONTAINERS_KEY, "shorts")
        launch()
        openWaves()
        click("waves:tab:container:shorts")
        waitFor("reel:carol/s1")
        compose.await("playing.isNotEmpty()") { playing.isNotEmpty() }
        assertEquals(listOf("https://play.3speak.tv/hls?u=v-s1"), playing.toList())
        assertFalse("no compose button over the reels", exists("createFab"))
        // the app goes to the background: nothing keeps playing
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.await("playing.isEmpty()") { playing.isEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.await("playing.size == 1") { playing.size == 1 }
        // another waves tab: the reels leave the screen and the player with them
        click("waves:tab:for-you")
        waitFor("wave:alice/w1")
        compose.await("playing.isEmpty()") { playing.isEmpty() }
        assertEquals(1, wavesRequests.count { "api/embed" in it })
    }
}
