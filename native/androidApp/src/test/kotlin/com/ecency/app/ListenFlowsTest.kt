package com.ecency.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.render.blocks.SpeakPlayback
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * RN Listen on Android: the bar above the post body (log in, the confirmed unlock and its errors, the audio being made),
 * the player (scripted engine: play, speeds, languages, the lock screen, renewed URLs), one player app-wide with the
 * 3Speak voice players, the playback service declared for the background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun share(text: String) {}
    }

    /** listen-status and listen requests, as sent. */
    private val calls: MutableList<Pair<String, JsonObject>> = Collections.synchronizedList(mutableListOf())
    @Volatile private var status: (JsonObject) -> HttpResult = { HttpResult(200, LOCKED) }
    @Volatile private var unlock: (JsonObject) -> HttpResult = { HttpResult(200, PREPARING) }

    private class FakeStream(var item: ListenItem, val events: ListenEvents) : ListenStream {
        /** Sources loaded into this same stream after it opened (renewed URLs, other languages). */
        val loads = mutableListOf<String>()
        var position = 0.0
        var playing = false
        var rate = 1.0
        var released = false
        val seeks = mutableListOf<Double>()
        override val positionSeconds get() = position
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seek(seconds: Double) {
            seeks += seconds
            position = seconds
        }
        override fun setSpeed(speed: Double) { rate = speed }
        override fun retitle(item: ListenItem) { this.item = item }
        override fun load(item: ListenItem) {
            this.item = item
            loads += item.url
            playing = false
            position = 0.0
            seeks.clear()
            events.loadStarted()
        }
        override fun release() {
            released = true
            playing = false
        }
    }

    private val streams = mutableListOf<FakeStream>()
    private val engine = ListenEngine { _: Context, item, events ->
        events.loadStarted()
        FakeStream(item, events).also { streams += it }
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
        node.auth.rest = { r ->
            val path = r.url.substringAfter("/private-api/", "")
            if (path == "listen-status" || path == "listen") {
                val o = Json.parseToJsonElement(r.body ?: "{}").jsonObject
                calls += path to o
                if (path == "listen") unlock(o) else status(o)
            } else null
        }
    }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
        // the coordinator is process-wide: whatever a test left playing is stopped and forgotten
        val last = SpeakPlayback.Token {}
        SpeakPlayback.activate(last)
        SpeakPlayback.deactivate(last)
    }

    private fun signInWithToken() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "token-tester"))
        vault.setCurrentUsername("tester")
    }

    private fun launch() = compose.setContent {
        CompositionLocalProvider(LocalListenEngine provides engine) { EcencyTheme(dark = false) { EcencyApp(env, host) } }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("exists(tag)") { exists(tag) }
    private fun click(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }
    /** Waits while the main looper's clock moves too (work resumed on the main thread after a vault read). */
    private fun pumpUntil(condition: () -> Boolean) = compose.await("the condition (main looper clock moving)") {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        compose.waitForIdle()
        condition()
    }

    private fun unlockCalls() = calls.filter { it.first == "listen" }.map { it.second }
    private fun statusCalls() = calls.filter { it.first == "listen-status" }.map { it.second }

    private fun openPost() {
        launch()
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
    }

    @Test
    fun aVisitorIsAskedToLogIn() {
        openPost()
        waitFor("listenLogin")
        compose.onNodeWithTag("listenSubtitle").assertTextEquals("English · ~3 min")
        assertTrue("anonymous", statusCalls().all { it["code"] == null })
        click("listenLogin")
        waitFor("login")
    }

    @Test
    fun theUnlockIsConfirmedFirstThenThePlaceInLineShows() {
        signInWithToken()
        openPost()
        waitFor("listenUnlock")
        click("listenUnlock")
        waitFor("listenConfirmDialog")
        assertTrue("nothing spent before the confirmation", unlockCalls().isEmpty())
        click("listenCancel")
        assertFalse(exists("listenConfirmDialog"))
        assertTrue(unlockCalls().isEmpty())
        click("listenUnlock")
        click("listenConfirm")
        compose.await("unlockCalls().isNotEmpty()") { unlockCalls().isNotEmpty() }
        val sent = unlockCalls().single()
        assertEquals("token-tester", sent["code"]?.jsonPrimitive?.content)
        assertEquals("trending-one", sent["permlink"]?.jsonPrimitive?.content)
        waitFor("listenPreparing")
        assertTrue(compose.onAllNodes(hasTestTag("listenPreparing")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun aReaderShortOfPointsIsOfferedToGetThem() {
        signInWithToken()
        unlock = { HttpResult(402, """{"error":"insufficient_points","required":50,"available":3}""") }
        openPost()
        click("listenUnlock")
        click("listenConfirm")
        waitFor("listenNotEnough")
        compose.onNodeWithTag("listenNotEnough").assertExists()
        click("listenGetPoints")
        compose.await("!exists(\"postScreen\")") { !exists("postScreen") }
    }

    @Test
    fun thePlayerPlaysInTheBackgroundOfTheListAndStopsWithThePostScreen() {
        signInWithToken()
        status = { HttpResult(200, READY) }
        node.postBodyExtra = (1..60).joinToString("") { "\\n\\nParagraph $it of a long post that keeps the list scrolling for a while." }
        openPost()
        waitFor("listenPlayer")
        assertTrue("nothing loads before the first play", streams.isEmpty())
        compose.onNodeWithTag("listenDuration").assertTextEquals("2:48")
        click("listenPlay")
        val stream = streams.single()
        assertEquals("https://audio.example/en.mp3?sig=1", stream.item.url)
        assertEquals("Title trending-one", stream.item.title)
        assertEquals("@alice", stream.item.artist)
        assertTrue(stream.playing)
        assertTrue(SpeakPlayback.hasActive)
        compose.runOnIdle { stream.events.loaded(170.0) }
        compose.onNodeWithTag("listenDuration").assertTextEquals("2:50")

        // speeds: 1x, 1.25x, ... and the stream follows
        click("listenSpeed")
        compose.onNodeWithTag("listenSpeed").assertTextEquals("1.25x")
        assertEquals(1.25, stream.rate, 0.0)

        // the lock screen paused it: the button follows
        compose.runOnIdle { stream.events.playingChanged(false) }
        compose.waitForIdle()
        assertFalse(SpeakPlayback.hasActive)
        click("listenPlay")
        assertTrue(stream.playing)

        // the bar is a lazy item: scrolling it away must not stop the audio (the stack entry owns the player)
        compose.scrollListTo("postList", hasTestTag("comment:carol/re-one"))
        compose.waitForIdle()
        assertFalse(exists("listenPlayer"))
        assertTrue(stream.playing && !stream.released)

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.await("!exists(\"postScreen\")") { !exists("postScreen") }
        compose.waitForIdle()
        assertTrue("leaving the post stops the audio", stream.released)
        assertFalse(SpeakPlayback.hasActive)
    }

    @Test
    fun aSecondLanguageAndAnExpiredUrlReopenTheSourceWhereItWas() {
        signInWithToken()
        status = { HttpResult(200, READY) }
        openPost()
        click("listenPlay")
        val first = streams.single()
        compose.runOnIdle {
            first.events.loaded(168.0)
            first.position = 42.0
        }
        // the progress tick (500 ms) runs on the main looper, not on the compose clock
        org.robolectric.shadows.ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        compose.waitForIdle()
        compose.onNodeWithTag("listenTime").assertTextEquals("0:42")

        // the signed URL expired: the status is asked again and the player picks up at 0:42
        val asked = statusCalls().size
        status = { HttpResult(200, READY.replace("sig=1", "sig=2")) }
        compose.runOnIdle { first.events.failed() }
        pumpUntil { first.loads.isNotEmpty() }
        compose.waitForIdle()
        assertEquals("only the renewed URL loads, never the expired one again", listOf("https://audio.example/en.mp3?sig=2"), first.loads)
        assertTrue(statusCalls().size > asked)
        assertEquals("the same stream (and media session) takes the new URL", 1, streams.size)
        assertFalse(first.released)
        assertEquals("https://audio.example/en.mp3?sig=2", first.item.url)
        assertTrue(first.playing)
        compose.runOnIdle { first.events.loaded(168.0) }
        assertEquals(listOf(42.0), first.seeks)

        // German: from its start, in the same stream
        click("listenLang:de")
        pumpUntil { first.loads.size == 2 }
        assertEquals("https://audio.example/de.mp3?sig=2", first.item.url)
        compose.runOnIdle { first.events.loaded(70.0) }
        assertEquals(listOf(0.0), first.seeks)
        assertEquals(1, streams.size)
    }

    @Test
    fun aUrlThatCannotBeRenewedSaysSoAndOffersATry() {
        signInWithToken()
        status = { HttpResult(200, READY) }
        openPost()
        click("listenPlay")
        // the status no longer brings audio and there is no newer token: the player shows the error
        status = { HttpResult(500, "") }
        compose.runOnIdle { streams.single().events.failed() }
        waitFor("listenFailed")
        val stream = streams.single()
        assertTrue("paused while the audio cannot be had", !stream.playing && stream.loads.isEmpty())
        // the lock screen asks to play the failed source: refused
        compose.runOnIdle {
            stream.playing = true
            stream.events.playingChanged(true)
        }
        compose.waitForIdle()
        assertFalse("the failed source is paused again", stream.playing)
        assertTrue(exists("listenFailed"))
        status = { HttpResult(200, READY) }
        click("listenRetry")
        pumpUntil { !exists("listenFailed") && stream.loads.size == 1 }
        assertFalse("try again does not start playing by itself", stream.playing)
    }

    @Test
    fun theAppInTheBackgroundStopsThePollingAndTheForegroundAsksAgain() {
        signInWithToken()
        status = { HttpResult(200, PREPARING) }
        openPost()
        waitFor("listenPreparing")
        val process = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        compose.runOnIdle { process.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START) }
        val before = statusCalls().size
        compose.runOnIdle { process.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP) }
        org.robolectric.shadows.ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.SECONDS)
        compose.waitForIdle()
        assertEquals("no polling in the background", before, statusCalls().size)
        compose.runOnIdle { process.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START) }
        pumpUntil { statusCalls().size > before }
    }

    @Test
    fun closingThePostScreenStopsFollowingTheApp() {
        signInWithToken()
        val process = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        val before = process.observerCount
        openPost()
        waitFor("listenUnlock")
        assertEquals("the screen's Listen follows the app", before + 1, process.observerCount)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.await("!exists(\"postScreen\")") { !exists("postScreen") }
        compose.waitForIdle()
        assertEquals("and lets go of it when the screen closes", before, process.observerCount)
    }

    @Test
    fun thePlaybackServiceRegistersItsForegroundListenerWhenCreated() {
        val controller = Robolectric.buildService(ListenPlaybackService::class.java).create()
        val getter = androidx.media3.session.MediaSessionService::class.java.getDeclaredMethod("getListener").apply { isAccessible = true }
        val listener = getter.invoke(controller.get()) as androidx.media3.session.MediaSessionService.Listener?
        assertNotNull("Media3 calls it when a foreground start is refused", listener)
        listener!!.onForegroundServiceStartNotAllowedException()
        val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(android.app.NotificationManager::class.java)
        assertNotNull(org.robolectric.Shadows.shadowOf(manager).getNotification(ListenPlaybackService.RESUME_NOTIFICATION))
        controller.destroy()
    }

    @Test
    fun aRefusedForegroundStartPostsANotificationToResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildService(ListenPlaybackService::class.java).create()
        controller.get().notifyResume(null)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val posted = org.robolectric.Shadows.shadowOf(manager).getNotification(ListenPlaybackService.RESUME_NOTIFICATION)
        assertNotNull(posted)
        assertEquals("Listen paused", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        controller.destroy()
    }

    @Test
    fun theLockScreenShowsTheSharedCardAndAccountsLeavingStopTheAudio() {
        signInWithToken()
        status = { HttpResult(200, READY) }
        openPost()
        click("listenPlay")
        val stream = streams.single()
        assertTrue(stream.playing)
        assertEquals("@alice", stream.item.artist)
        assertTrue("no PIN lock: the post image or the avatar", stream.item.artwork?.startsWith("https://") == true)
        assertEquals(1, env.backgroundSessions.count)
        // the PIN lock turned on while it plays: the artwork goes from the card at once, the audio goes on
        compose.runOnIdle { env.appLock.startEnable() }
        pumpUntil { env.appLock.current.prompt?.step == com.ecency.core.app.PinStep.NEW }
        compose.runOnIdle { "1234".forEach { d -> env.appLock.enterDigit(d.digitToInt()) } }
        pumpUntil { env.appLock.current.prompt?.step == com.ecency.core.app.PinStep.CONFIRM }
        compose.runOnIdle { "1234".forEach { d -> env.appLock.enterDigit(d.digitToInt()) } }
        pumpUntil { env.appLock.current.enabled }
        pumpUntil { stream.item.artwork == null }
        assertTrue(stream.playing && stream.loads.isEmpty())
        assertEquals("@alice", stream.item.artist)
        // the teardown of a logout or wipe (AppEnvironment.accountsLeft)
        compose.runOnIdle { env.backgroundSessions.stopAll() }
        compose.waitForIdle()
        assertTrue("the stream and its media notification went", stream.released)
        assertFalse(SpeakPlayback.hasActive)
        // the player survived (another account signed in): its next stream is torn down by the next logout too
        click("listenPlay")
        val again = streams.last()
        assertTrue(again !== stream && again.playing)
        assertEquals(1, env.backgroundSessions.count)
        compose.runOnIdle { env.backgroundSessions.stopAll() }
        compose.waitForIdle()
        assertTrue("a second logout stops it too", again.released)
    }

    @Test
    fun theSessionOffersFifteenSecondSkipsInPlaceOfPreviousAndNext() {
        val commands = ListenPlaybackService.controllerCommands()
        assertTrue(commands.contains(androidx.media3.common.Player.COMMAND_SEEK_BACK))
        assertTrue(commands.contains(androidx.media3.common.Player.COMMAND_SEEK_FORWARD))
        assertTrue(commands.contains(androidx.media3.common.Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        for (c in listOf(
            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS, androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )) assertFalse(commands.contains(c))
        val buttons = ListenPlaybackService.skipButtons()
        assertEquals(listOf(ListenPlaybackService.SKIP_BACK, ListenPlaybackService.SKIP_FORWARD), buttons.map { it.sessionCommand })
        val controller = Robolectric.buildService(ListenPlaybackService::class.java).create()
        val session = controller.get().onGetSession(androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            ApplicationProvider.getApplicationContext<Context>().packageName, 0, 0, 0, 0, false, android.os.Bundle.EMPTY,
        ))!!
        assertEquals(15_000L, session.player.seekBackIncrement)
        assertEquals(15_000L, session.player.seekForwardIncrement)
        // what media3's notification (and the Android 13+ lock screen, which draws its actions) really shows
        val provider = object : androidx.media3.session.DefaultMediaNotificationProvider(ApplicationProvider.getApplicationContext()) {
            fun buttons() = getMediaButtons(session, commands, com.google.common.collect.ImmutableList.copyOf(buttons), true)
        }
        val shown = provider.buttons().map { it.displayName.toString() }
        assertTrue("$shown", shown.containsAll(listOf("Back 15 seconds", "Forward 15 seconds")))
        // and the skips act on the player
        val unknown = androidx.media3.session.SessionCommand("other", android.os.Bundle.EMPTY)
        assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS, ListenPlaybackService.handleSkip(session.player, ListenPlaybackService.SKIP_FORWARD))
        assertEquals(androidx.media3.session.SessionResult.RESULT_ERROR_NOT_SUPPORTED, ListenPlaybackService.handleSkip(session.player, unknown))
        assertTrue(ListenPlaybackService.sessionCommands().contains(ListenPlaybackService.SKIP_BACK))
        controller.destroy()
    }

    @Test
    fun oneAudioSourceAppWide() {
        signInWithToken()
        status = { HttpResult(200, READY) }
        openPost()
        click("listenPlay")
        val stream = streams.single()
        assertTrue(stream.playing)
        // a 3Speak voice player starts somewhere: Listen pauses
        var speakStopped = false
        val speak = SpeakPlayback.Token { speakStopped = true }
        compose.runOnIdle { SpeakPlayback.activate(speak) }
        compose.waitForIdle()
        assertFalse("Listen paused for the other player", stream.playing)
        compose.onNodeWithTag("listenPlay").assertExists()
        // and Listen playing again stops the voice player
        click("listenPlay")
        assertTrue(stream.playing)
        assertTrue(speakStopped)
    }

    @Test
    fun thePlaybackServiceIsDeclaredForTheBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getServiceInfo(ComponentName(context, ListenPlaybackService::class.java), PackageManager.GET_META_DATA)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(requested.containsAll(setOf("android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")))
        // the service makes its session (the media notification's) and lets it go
        val controller = Robolectric.buildService(ListenPlaybackService::class.java).create()
        val service = controller.get()
        assertNotNull(service.onGetSession(androidx.media3.session.MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            context.packageName, 0, 0, 0, 0, false, android.os.Bundle.EMPTY,
        )))
        controller.destroy()
    }

    companion object {
        const val LOCKED = """{"cost":50,"unlocked":false,"status":"none","languages":[{"lang":"en","share":1,"seconds":168}]}"""
        const val PREPARING = """{"cost":50,"unlocked":true,"status":"preparing","languages":[{"lang":"en","share":1,"seconds":168}],"queued_ahead":1}"""
        const val READY = """{"cost":50,"unlocked":true,"status":"ready","languages":[{"lang":"en","share":0.7,"seconds":168},{"lang":"de","share":0.3,"seconds":70}],""" +
            """"tracks":[{"lang":"en","seconds":168,"url":"https://audio.example/en.mp3?sig=1"},{"lang":"de","seconds":70,"url":"https://audio.example/de.mp3?sig=1"}]}"""
    }
}
