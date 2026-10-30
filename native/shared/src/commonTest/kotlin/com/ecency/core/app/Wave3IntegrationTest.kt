package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.ecency.ActivityNotification
import com.ecency.core.api.ecency.ImageUploadTransport
import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.api.ecency.UploadFile
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.EcencyPrivateApi
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What only shows once notifications and the editor live in one app: one teardown path for logout, logout-all
 * and the wipe, account changes over every new screen, notification targets that open the real editor, bounded
 * private API calls and uploads, and one naming scheme for preferences.
 */
class Wave3IntegrationTest {
    private val alice = NotificationFixture.ALICE
    private val bob = NotificationFixture.BOB

    /** Drafts on the device that can be made to fail. */
    private class Drafts : DraftStorage {
        val inner = InMemoryDraftStorage()
        var broken = false
        override fun read(account: String, slot: String) = inner.read(account, slot)
        override fun write(account: String, slot: String, value: String?) = inner.write(account, slot, value)
        override fun clear(account: String) = if (broken) throw IllegalStateException("disk") else inner.clear(account)
        override fun clearAll() = if (broken) throw IllegalStateException("disk") else inner.clearAll()
    }

    /** Preferences whose writes fail (a full or read-only disk): the push model's pending mark cannot be written. */
    private class BrokenPrefs : Prefs {
        override fun getString(key: String): String? = null
        override fun putString(key: String, value: String?) = throw IllegalStateException("prefs")
        override fun keys(): Set<String> = throw IllegalStateException("prefs")
    }

    private class World(scope: TestScope, users: List<String>, pin: String? = null) {
        val f = NotificationFixture(scope, users, pin)
        val platform = FakePushPlatform()
        val drafts = Drafts()
        val env get() = f.env

        init {
            env.pushPlatform = platform
            env.draftStorage = drafts
            users.forEach { drafts.inner.write(it, LocalDrafts.NEW_POST, LocalDraft(title = "by $it", updated = 1).encode()) }
        }

        fun start() {
            f.start()
            env.push.start()
            f.idle()
            f.backend.calls.clear()
        }

        fun draftOwners() = drafts.inner.values.keys.map { it.substringAfter("draft/").substringBefore('/') }.toSet()

        /** "username:allows_notify" per register-device call, in order. */
        fun pushWrites() = f.backend.of("register-device").map { "${it.text("username")}:${it.text("allows_notify")}" }
    }

    // ---- 1. logout, logout-all and wipe: one path, no step skips another ----

    @Test
    fun aLogoutWhosePushReleaseFailsStillClearsTheDraftsAndTheSession() = runTest {
        val w = World(this, listOf(alice, bob))
        w.start()
        w.env.votes.vote("someone", "post", 10000)
        w.env.prefs = BrokenPrefs() // the release throws at its first step
        w.env.session.logout(alice)
        w.f.idle()
        assertEquals(setOf(bob), w.draftOwners(), "alice's drafts are gone although her push release failed")
        assertEquals(bob, w.env.session.current.username)
        assertTrue(w.env.votes.current.statuses.isEmpty(), "the session state was forgotten")
    }

    @Test
    fun aLogoutWhosePushReleaseNeverAnswersDoesNotWaitForItAndClearsTheDrafts() = runTest {
        val w = World(this, listOf(alice, bob))
        w.start()
        w.f.app.authBackend.gate = { request -> if (request.url.endsWith("/register-device")) CompletableDeferred<Unit>().await() }
        w.platform.deleteHangs = true
        val before = currentTime
        w.env.session.logout(alice)
        assertEquals(before, currentTime, "the logout took no time: nothing waited for the network or the push service")
        assertEquals(setOf(bob), w.draftOwners())
        assertEquals(bob, w.env.session.current.username)
        assertTrue(w.env.push.hasPendingRelease, "the release is remembered for later")
    }

    @Test
    fun aLogoutWhoseDraftClearFailsStillReleasesPush() = runTest {
        val w = World(this, listOf(alice, bob))
        w.start()
        w.drafts.broken = true
        w.env.session.logout(alice)
        w.f.idle()
        assertTrue("$alice:0" in w.pushWrites(), "alice's push row was disabled although her drafts could not be deleted")
        assertEquals(bob, w.env.session.current.username, "and the logout completed")
        assertNull(w.env.session.current.error)
    }

    @Test
    fun aLogoutThatFailsAfterTheAccountLeftTheVaultStillCleansUpAndThenReportsTheFailure() = runTest {
        val w = World(this, listOf(alice, bob))
        w.start()
        w.env.votes.vote("someone", "post", 10000)
        // the vault can remove the account but not list what is left: the step after the removal throws
        w.f.app.store.namesUnavailable = true
        val error = runCatching { w.env.session.logout(alice) }.exceptionOrNull()
        w.f.app.store.namesUnavailable = false
        w.f.idle()
        assertNotNull(error, "the failure is not swallowed")
        assertNull(KeyVault(w.f.app.store).account(alice), "alice is gone from the vault")
        assertEquals(setOf(bob), w.draftOwners(), "so her drafts went")
        assertTrue("$alice:0" in w.pushWrites(), "her push row was disabled")
        assertTrue(w.env.votes.current.statuses.isEmpty(), "and her session state was forgotten")
    }

    @Test
    fun logoutAllRunsBothStepsWhicheverFails() = runTest {
        val a = World(this, listOf(alice, bob))
        a.start()
        a.env.prefs = BrokenPrefs()
        a.env.session.logoutAll()
        a.f.idle()
        assertTrue(a.draftOwners().isEmpty(), "every draft is gone although the push release failed")
        assertNull(a.env.session.current.username)

        val b = World(this, listOf(alice, bob))
        b.start()
        b.drafts.broken = true
        b.env.session.logoutAll()
        b.f.idle()
        assertEquals(setOf("$alice:0", "$bob:0"), b.pushWrites().toSet(), "both rows were disabled although the drafts could not be deleted")
        assertEquals(1, b.platform.deletes, "and with no account left the device token went")
        assertNull(b.env.session.current.username)
    }

    @Test
    fun aWipeRunsBothStepsWhicheverFails() = runTest {
        val a = World(this, listOf(alice, bob), pin = "1234")
        a.start()
        a.env.prefs = BrokenPrefs()
        val events = mutableListOf<AppEvent>()
        val watcher = a.env.watchEvents { events += it }
        a.env.appLock.forgotPin()
        a.f.idle()
        assertTrue(a.draftOwners().isEmpty(), "the wipe cleared every draft although the push mark could not be written")
        assertTrue(AppEvent.DataWiped in events && AppEvent.SessionChanged(null) in events, "and the UIs were told")
        assertFalse(a.env.appLock.current.hidesContent)
        watcher.close()

        val b = World(this, listOf(alice, bob), pin = "1234")
        b.start()
        b.drafts.broken = true
        b.env.appLock.forgotPin()
        b.f.idle()
        assertEquals(1, b.platform.deletes, "the device token went although the drafts could not be deleted")
        assertFalse(b.env.push.hasPendingRelease)
        assertNull(b.env.session.current.username)
        assertFalse(b.env.appLock.current.hidesContent, "and the wipe finished")
    }

    @Test
    fun aWipeNeverWaitsForThePushService() = runTest {
        val w = World(this, listOf(alice), pin = "1234")
        w.start()
        w.platform.deleteHangs = true
        w.env.resetAfterWipe()
        w.f.step()
        assertTrue(w.draftOwners().isEmpty())
        assertNull(w.env.session.current.username)
        assertTrue(w.env.push.hasPendingRelease, "the token delete is retried later")
    }

    // ---- screens that close after the teardown flush their text: it must not come back ----

    /** An app with a dirty editor, a quick reply with text and a wave composer with text, all open, all alice's. */
    private class OpenComposers(val scope: TestScope, pin: String? = null) {
        val drafts = Drafts()
        val app = TestApp(scope) { req ->
            when (req.method) {
                "bridge.get_post" -> FakeTransport.result(req, entry(NotificationFixture.BOB, "their-post"))
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }
        val env get() = app.env
        lateinit var editor: EditorModel
        lateinit var reply: ComposeReplyModel
        lateinit var wave: WaveComposeModel

        init {
            val vault = KeyVault(app.store)
            listOf(NotificationFixture.ALICE, NotificationFixture.BOB).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$it")) }
            vault.setCurrentUsername(NotificationFixture.ALICE)
            if (pin != null) com.ecency.core.secure.PinLock(vault, 16).setPin(pin)
            env.draftStorage = drafts
            app.authBackend.privateApi = { HttpResult(200, "{}") }
            env.session.start()
            // the lock is left unread: these models are the ones a screen holds when the teardown runs
            idle()
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        suspend fun open() {
            editor = env.editorModel(EditorArgs(EditorKind.NEW_POST))
            editor.start()
            idle()
            editor.setTitle("A title nobody else may read")
            editor.setBody("Private words", com.ecency.core.editor.TextSelection(0))
            val parent = env.api.posts.getPost(NotificationFixture.BOB, "their-post")!!
            reply = env.replyModel(parent.key, parent)
            reply.setText("half a reply")
            wave = env.waves.composer()
            wave.setText("half a wave")
            // typed, not yet autosaved: the text is only in memory when the account leaves
            scope.testScheduler.runCurrent()
        }

        /** What the platforms do on the session event that follows the teardown: the screens go, the models close. */
        fun closeScreens() {
            editor.close()
            reply.setText("typed while the sheet was closing")
            reply.close()
            wave.setText("typed while the sheet was closing")
            idle()
        }

        fun onDisk(account: String) = drafts.inner.values.keys.filter { it.startsWith("draft/$account/") }
    }

    @Test
    fun aLogoutLeavesNothingOnDiskWhenOpenComposersCloseAfterwards() = runTest {
        val o = OpenComposers(this)
        o.open()
        o.env.session.logout(alice)
        o.closeScreens()
        assertEquals(emptyList(), o.onDisk(alice), "the closing editor and reply sheet wrote nothing back")
        assertTrue(o.env.localDrafts.hasLeft(alice))
        assertFalse(o.env.localDrafts.save(alice, LocalDrafts.NEW_POST, LocalDraft(title = "late", updated = 1)), "any later writer is refused, and told so")
        // bob, who stayed, still saves
        assertTrue(o.env.localDrafts.save(bob, LocalDrafts.NEW_POST, LocalDraft(title = "bob's", updated = 1)))
        assertEquals(1, o.onDisk(bob).size)
    }

    @Test
    fun anAutosaveThatWasAlreadyRunningDoesNotOutliveTheLogout() = runTest {
        val o = OpenComposers(this)
        o.open()
        // the autosave timers fire while the logout is under way and after it
        o.env.session.logout(alice)
        testScheduler.advanceTimeBy(60_000)
        o.idle()
        assertEquals(emptyList(), o.onDisk(alice))
    }

    @Test
    fun aWipeLeavesNothingOnDiskWhenOpenComposersCloseAfterwards() = runTest {
        val o = OpenComposers(this, pin = "1234")
        o.open()
        o.env.localDrafts.save(bob, LocalDrafts.NEW_POST, LocalDraft(title = "bob's", updated = 1))
        o.env.resetAfterWipe()
        o.closeScreens()
        assertTrue(o.drafts.inner.values.isEmpty(), "nothing of anyone is on disk after the wipe: ${o.drafts.inner.values.keys}")
        assertFalse(o.env.localDrafts.save(bob, LocalDrafts.NEW_POST, LocalDraft(title = "late", updated = 1)), "every account left")
    }

    @Test
    fun anAccountThatSignsInAgainKeepsItsDraftsFromThenOn() = runTest {
        val app = appWithAccounts(this)
        val storage = InMemoryDraftStorage()
        app.env.draftStorage = storage
        val drafts = app.env.localDrafts
        val draft = LocalDraft(title = "mine", updated = 1)
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        app.env.session.loginWithKey(bob, AppTestKeys.PASSWORD)
        assertTrue(drafts.save(alice, LocalDrafts.NEW_POST, draft))
        // a logout: refused until she signs in again
        app.env.session.logout(alice)
        assertFalse(drafts.save(alice, LocalDrafts.NEW_POST, draft))
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        assertTrue(drafts.save(alice, LocalDrafts.NEW_POST, draft))
        // a wipe: everyone is gone; the one who signs in again is back, the other is not
        app.env.resetAfterWipe()
        assertTrue(storage.values.isEmpty())
        assertFalse(drafts.save(alice, LocalDrafts.NEW_POST, draft))
        app.env.session.loginWithKey(bob, AppTestKeys.PASSWORD)
        assertTrue(drafts.save(bob, LocalDrafts.NEW_POST, draft))
        assertFalse(drafts.save(alice, LocalDrafts.NEW_POST, draft), "alice did not come back")
        // and leaving again after a wipe is still leaving
        app.env.session.logout(bob)
        assertFalse(drafts.save(bob, LocalDrafts.NEW_POST, draft))
    }

    /** A dispatcher whose threads are all stuck: nothing handed to it ever runs. */
    private object Stuck : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    @Test
    fun aStuckDiskHoldsALogoutOrAWipeForABoundedTimeOnly() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val drafts = LocalDrafts(InMemoryDraftStorage(), AppDispatchers(main, main, Stuck))
        assertFalse(drafts.clearAll(), "not cleared, and said so")
        assertEquals(LocalDrafts.CLEAR_BOUND_MILLIS, currentTime)
        assertFalse(drafts.clear(alice))
        assertEquals(2 * LocalDrafts.CLEAR_BOUND_MILLIS, currentTime)
    }

    // ---- 2. account change over the new screens ----

    private val editors = listOf(
        AppScreen.Editor(),
        AppScreen.Editor.of("hive-125125"),
        AppScreen.Editor.of("spike-alice/a-post"),
        AppScreen.Editor.of("reply:spike-bob/their-post"),
        AppScreen.Editor.of("draft:d1"),
        AppScreen.Editor.of("template:t1"),
    )
    private val gated: List<AppScreen> = editors + listOf(AppScreen.Drafts(0), AppScreen.Drafts(1), AppScreen.Drafts(2), AppScreen.AiImageGenerator)

    @Test
    fun everyEditorAndDraftsScreenLeavesOnAnAccountChangeAndIsGatedForGuests() {
        assertEquals(setOf(EditorKind.NEW_POST, EditorKind.EDIT_POST, EditorKind.REPLY), editors.map { it.args.kind }.toSet())
        val public = AppScreen.Post("spike-bob", "their-post")
        for (screen in gated) {
            assertTrue(screen.route.requiresLogin && screen.route.built, "$screen")
            val open = NavState().selectTab(AppTab.NOTIFICATION).push(public).push(screen)
            // a switch, a sign-out, a logout that moved on, a link login as someone else: all one transition
            val after = open.afterAccountChange()
            assertEquals(listOf<AppScreen>(public), after.screens, "$screen belonged to the previous account")
            assertEquals(AppTab.NOTIFICATION, after.tab, "the tab stays (it rebinds to the new account)")
            assertEquals(AppScreen.Login, NavState().pushGated(screen, loggedIn = false).top, "$screen needs an account")
            assertEquals(AppScreen.Login, NavState().open(NavEntry.screen(screen), loggedIn = false).top)
            assertEquals(screen, NavState().open(NavEntry.screen(screen), loggedIn = true).top)
        }
        assertTrue(NavState().push(gated.first()).push(gated.last()).reset().atRoot, "a wipe drops them all")
    }

    @Test
    fun restoredNavigationKeepsGatedScreensOnlyForTheAccountThatOpenedThem() {
        for (screen in gated) {
            val saved = NavState().selectTab(AppTab.NOTIFICATION).push(AppScreen.Post("spike-bob", "their-post")).push(screen).encode()
            val restored = NavState.decode(saved)
            assertEquals(screen, restored.top, "ids survive the round trip")
            assertEquals(screen, restored.restoredFor(owner = alice, current = alice).top, "same account: where it was")
            for (current in listOf(bob, null)) {
                val other = restored.restoredFor(owner = alice, current = current)
                assertEquals(listOf<AppScreen>(AppScreen.Post("spike-bob", "their-post")), other.screens, "$screen is not shown to $current")
                assertEquals(AppTab.NOTIFICATION, other.tab)
            }
            assertTrue(restored.restoredFor(owner = null, current = alice).screens.none { it.route.requiresLogin }, "saved by nobody: not trusted")
            assertTrue(restored.restoredFor(owner = null, current = null).screens.none { it.route.requiresLogin }, "saved by nobody, nobody signed in: a guest gets no saved editor")
        }
        // the saved navigation carries ids only, never a title, a body or a tag the writer typed
        val line = NavState().push(AppScreen.Editor.of("draft:d1")).encode().last()
        assertTrue("d1" in line && "NEW_POST" in line)
    }

    @Test
    fun anAccountChangeDropsBannersVotesKeysAndReplyComposersInOneStep() = runTest {
        val w = World(this, listOf(alice, bob))
        w.start()
        w.env.push.received(mapOf("type" to "reply", "source" to bob, "target" to alice, "permlink1" to "re-post", "id" to "b-1"), null, null)
        w.f.step()
        assertNotNull(w.env.ui.current.banner, "alice's banner shows")
        w.env.session.switchTo(bob)
        w.f.step()
        assertNull(w.env.ui.current.banner, "nothing of alice's shows for bob")
        assertNull(w.env.notifications.current.account.takeIf { it == alice })
    }

    // ---- 3. notification targets open the real editor ----

    @Test
    fun editorNotificationsOpenTheRealEditorNotAPlaceholder() = runTest {
        val row = ActivityNotification.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"i-1","type":"inactive","source":"ecency","read":0,"timestamp":1}""") as kotlinx.serialization.json.JsonObject,
        )!!
        val fromList = NotificationFormat.target(row)!!.screens.single()
        val fromPush = PushRouting.opened(PushPayload.of(mapOf("type" to "inactive", "target" to alice), null, null))!!.entry!!.screens.single()
        for (screen in listOf(fromList, fromPush)) {
            assertIs<AppScreen.Editor>(screen, "the built editor, not AppScreen.Pending")
            assertEquals(EditorArgs(EditorKind.NEW_POST), screen.args)
        }
        // signed out, the same tap asks to sign in first
        assertEquals(AppScreen.Login, NavState().open(NavEntry.screen(fromPush), loggedIn = false).top)
        // a published scheduled post opens the post itself, as RN
        val scheduled = PushRouting.opened(PushPayload.of(mapOf("type" to "scheduled_published", "source" to alice, "target" to alice, "permlink1" to "my-post"), null, null))
        assertEquals(AppScreen.Post(alice, "my-post"), scheduled!!.entry!!.screens.single())
    }

    @Test
    fun notificationsAboutAWaveOpenItsThreadLikeAWavesLink() {
        // RN has no notification type that opens the Waves tab; a reply, mention or vote on a wave opens the
        // post screen, which is the wave's thread here (the same screen a waves link opens)
        val thread = ContentLinks.entry("https://ecency.com/waves/spike-alice/wave-1")
        assertEquals(NavEntry.screen(AppScreen.Post("spike-alice", "wave-1")), thread)
        fun push(type: String, vararg data: Pair<String, String>) = PushRouting.opened(PushPayload.of(mapOf("type" to type, *data), null, null))?.entry
        assertEquals(thread, push("reply", "source" to "spike-alice", "target" to bob, "permlink1" to "wave-1"))
        assertEquals(thread, push("mention", "source" to "spike-alice", "target" to bob, "permlink1" to "wave-1"))
        assertEquals(thread, push("vote", "source" to bob, "target" to "spike-alice", "permlink1" to "wave-1"))
        val row = ActivityNotification.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"w-1","type":"reply","source":"spike-alice","read":0,"timestamp":1,"author":"spike-alice","permlink":"wave-1"}""") as kotlinx.serialization.json.JsonObject,
        )!!
        assertEquals(thread, NotificationFormat.target(row))
        // nothing a notification can carry selects the Waves tab
        assertTrue(listOf("reply", "mention", "vote", "follow", "transfer", "inactive", "spin").none { push(it, "source" to bob, "target" to alice, "permlink1" to "p")?.tab == AppTab.WAVES })
    }

    @Test
    fun aTappedEditorPushForAnotherAccountDoesNotOpenTheCurrentAccountsEditor() = runTest {
        val w = World(this, listOf(alice, bob))
        w.start()
        // addressed to bob, alice is signed in
        w.env.push.opened(mapOf("type" to "inactive", "target" to bob))
        w.f.idle()
        assertNull(w.env.push.current.open, "bob's reminder never opens alice's editor")
        // addressed to the current account: the editor
        w.env.push.opened(mapOf("type" to "inactive", "target" to alice))
        w.f.idle()
        assertIs<AppScreen.Editor>(w.env.push.current.open!!.screens.single())
        w.env.push.consumeOpen()
        // a public target for another account still opens (a post is the same for everyone)
        w.env.push.opened(mapOf("type" to "reply", "source" to alice, "target" to bob, "permlink1" to "re-post"))
        w.f.idle()
        assertEquals(AppScreen.Post(alice, "re-post"), w.env.push.current.open!!.screens.single())
    }

    @Test
    fun aTapThatWaitedBehindTheLockIsJudgedAgainstTheAccountSignedInWhenItOpens() = runTest {
        val w = World(this, listOf(alice, bob), pin = "1234")
        w.start()
        w.env.push.opened(mapOf("type" to "inactive", "target" to alice))
        w.f.idle()
        assertNull(w.env.push.current.open, "waits behind the lock")
        // the account changes while it waits (a link login is handled outside the lock gate)
        w.env.session.switchTo(bob)
        w.f.unlock()
        assertNull(w.env.push.current.open, "alice's reminder does not open bob's editor")
    }

    // ---- 5. a reply from a post a notification opened ----

    @Test
    fun aReplyFromAPostOpenedByANotificationIsSentByTheAccountThatOpenedIt() = runTest {
        val app = TestApp(this) { req ->
            when (req.method) {
                "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "{}")
                "bridge.get_post" -> FakeTransport.result(req, entry(bob, "re-post"))
                "bridge.get_discussion" -> FakeTransport.result(req, """{"$bob/re-post":${entry(bob, "re-post")}}""")
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }
        val vault = KeyVault(app.store)
        listOf(TestApp.USER, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$it")) }
        vault.setCurrentUsername(TestApp.USER)
        app.env.session.start()
        app.env.appLock.start()
        app.env.push.start()
        testScheduler.advanceUntilIdle()

        app.env.push.opened(mapOf("type" to "reply", "source" to bob, "target" to TestApp.USER, "permlink1" to "re-post", "id" to "r-1"))
        testScheduler.advanceUntilIdle()
        val target = assertIs<AppScreen.Post>(app.env.push.current.open!!.screens.single())
        app.env.push.consumeOpen()
        val post = app.env.postModel(target.author, target.permlink)
        post.start()
        testScheduler.advanceUntilIdle()
        val parent = assertNotNull(post.current.post)
        val reply = app.env.replyModel(PostKey(target.author, target.permlink), parent)
        reply.setText("Thanks for the reply")
        reply.send()
        testScheduler.advanceUntilIdle()
        assertNotNull(reply.current.sent, reply.current.error?.message)
        assertEquals(1, app.broadcasts().size)
        assertTrue("\"${TestApp.USER}\"" in app.broadcasts().single().params.toString(), "signed and sent as the account that opened the composer")

        // a composer left open across an account change never sends as the other account
        val second = app.env.replyModel(PostKey(target.author, target.permlink), parent)
        second.setText("half written")
        app.env.session.switchTo(alice)
        testScheduler.advanceUntilIdle()
        second.send()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size, "nothing of the first account goes out as the second")
        post.close()
    }

    // ---- 4. no private API call or upload waits forever ----

    private fun silentBackend(scope: TestScope): TestApp {
        val app = TestApp(scope) { req -> TestApp.chain(req) ?: HttpResult(500, "") }
        app.signIn()
        KeyVault(app.store).save(VaultAccount(TestApp.USER, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token"))
        // a stalled connection: no request is ever answered (the Android client has no timeout of its own)
        app.authBackend.gate = { CompletableDeferred<Unit>().await() }
        app.env.session.start()
        scope.testScheduler.runCurrent()
        return app
    }

    @Test
    fun editorPrivateApiCallsGiveUpAfterTheBound() = runTest {
        val app = silentBackend(this)
        val api = app.env.editorApi
        val user = TestApp.USER
        val empty = kotlinx.serialization.json.JsonObject(emptyMap())
        val calls: List<Pair<String, suspend () -> Any?>> = listOf(
            "drafts" to { api.drafts(user, 0) },
            "all drafts" to { api.allDrafts(user) },
            "draft add" to { api.addDraft(user, "t", "b", "tag", empty) },
            "draft update" to { api.updateDraft(user, "d1", "t", "b", "tag", empty) },
            "draft delete" to { api.deleteDraft(user, "d1") },
            "schedules" to { api.schedules(user, 0) },
            "schedule add" to { api.addSchedule(user, "p", "t", "b", empty, empty, "2026-10-07T00:00:00+00:00") },
            "schedule delete" to { api.deleteSchedule(user, "s1") },
            "schedule move" to { api.moveScheduleToDrafts(user, "s1") },
            "images" to { api.images(user, 0) },
            "snippets" to { api.snippets(user, 0) },
            "snippet add" to { api.addSnippet(user, "t", "b") },
            "snippet update" to { api.updateSnippet(user, "f1", "t", "b") },
            "snippet delete" to { api.deleteSnippet(user, "f1") },
            "support settings" to { api.supportSettings(user) },
        )
        for ((name, call) in calls) {
            val started = currentTime
            val result = async { runCatching { call() } }
            testScheduler.advanceTimeBy(EcencyPrivateApi.TIMEOUT_MILLIS - 1)
            assertTrue(result.isActive, "$name is still waiting just before the bound")
            testScheduler.advanceTimeBy(2)
            val error = assertIs<EcencyHttpException>(result.await().exceptionOrNull(), "$name gives up")
            assertEquals(0, error.status)
            assertTrue(error.isNetwork, "$name reads as a network failure (a write may have landed)")
            assertTrue(currentTime - started <= EcencyPrivateApi.TIMEOUT_MILLIS + 1)
        }
    }

    @Test
    fun theQuestChipGivesUpQuietlyAfterTheBound() = runTest {
        val app = silentBackend(this)
        val chip = async { QuestChip.load(app.env, TestApp.USER) }
        testScheduler.advanceTimeBy(EcencyPrivateApi.TIMEOUT_MILLIS - 1)
        assertTrue(chip.isActive)
        testScheduler.advanceTimeBy(2)
        assertNull(chip.await(), "no chip, no error")
    }

    @Test
    fun aDraftsListOnAStalledConnectionStopsLoading() = runTest {
        val app = silentBackend(this)
        val drafts = app.env.draftsModel(0)
        drafts.start()
        testScheduler.advanceTimeBy(EcencyPrivateApi.TIMEOUT_MILLIS - 1)
        assertTrue(drafts.current.loadingDrafts || drafts.current.loadingSchedules, "still asking")
        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertFalse(drafts.current.loadingDrafts, "the drafts list is not a spinner forever")
        assertFalse(drafts.current.loadingSchedules)
        drafts.close()
    }

    @Test
    fun aSignInWhoseTokenExchangeTimesOutShowsAnErrorAndFreesTheButton() = runTest {
        val app = appWithAccounts(this)
        // what the transport does on a stalled connection: nothing for its whole bound, then a timeout
        app.authBackend.gate = { request ->
            if (request.url.endsWith(com.ecency.core.auth.EcencyTokenApi.PATH)) {
                delay(com.ecency.core.auth.KtorAuthHttp.TIMEOUT_MILLIS)
                throw com.ecency.core.api.EcencyTimeoutException()
            }
        }
        val started = currentTime
        val login = async { runCatching { app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD) } }
        testScheduler.advanceTimeBy(com.ecency.core.auth.KtorAuthHttp.TIMEOUT_MILLIS - 1)
        assertTrue(app.env.session.current.isSigningIn, "the button shows progress while it waits")
        testScheduler.advanceUntilIdle()
        assertTrue(login.await().isFailure)
        assertFalse(app.env.session.current.isSigningIn, "the button is back")
        assertNotNull(app.env.session.current.error, "with an error to show")
        assertNull(app.env.session.current.username)
        assertTrue(currentTime - started < 2 * com.ecency.core.auth.KtorAuthHttp.TIMEOUT_MILLIS, "one bound, not four")
    }

    private val file = UploadFile(ByteArray(1_000), "a.jpg", "image/jpeg")

    @Test
    fun anUploadThatKeepsMovingIsNotCutOffAndOneThatStallsIs() = runTest {
        // 1 byte in 10 every 50 s: far beyond RN's flat 120 s, but never 60 s without a byte
        val slow = ImageUploadTransport { _, _, f, progress ->
            for (step in 1..10) {
                delay(50_000)
                progress(f.bytes.size.toLong() * step / 10, f.bytes.size.toLong())
            }
            HttpResult(200, """{"url":"https://images.example/a.jpg"}""")
        }
        assertEquals("https://images.example/a.jpg", ImageUploader(slow, "https://images.example").upload(file, "token"))
        assertEquals(500_000, currentTime)

        // half way, then silence: every try gives up 60 s after its last byte
        var tries = 0
        val stalls = ImageUploadTransport { _, _, f, progress ->
            tries++
            delay(10_000)
            progress(f.bytes.size.toLong() / 2, f.bytes.size.toLong())
            // repeating the same count is not progress
            delay(30_000)
            progress(f.bytes.size.toLong() / 2, f.bytes.size.toLong())
            CompletableDeferred<HttpResult>().await()
        }
        val started = currentTime
        val error = runCatching { ImageUploader(stalls, "https://images.example", retryDelayMillis = { 0 }).upload(file, "token") }.exceptionOrNull()
        assertEquals("timeout", assertIs<EcencyHttpException>(error).message)
        assertEquals(1 + ImageUploader.MAX_RETRIES, tries, "a stall is retried like RN's timeout")
        assertEquals((1 + ImageUploader.MAX_RETRIES) * (10_000 + ImageUploader.STALL_MILLIS), currentTime - started)
    }

    @Test
    fun anUploadThatNeverSendsAByteGivesUpAfterTheStallBoundAndACancelStaysACancel() = runTest {
        var tries = 0
        val dead = ImageUploadTransport { _, _, _, _ ->
            tries++
            CompletableDeferred<HttpResult>().await()
        }
        val started = currentTime
        val error = runCatching { ImageUploader(dead, "https://images.example", retryDelayMillis = { 0 }).upload(file, "token") }.exceptionOrNull()
        assertIs<EcencyHttpException>(error)
        assertEquals(3 * ImageUploader.STALL_MILLIS, currentTime - started)

        // the user backs out: a cancellation, not a "timeout" that would be retried
        tries = 0
        val upload = async { ImageUploader(dead, "https://images.example").upload(file, "token") }
        testScheduler.advanceTimeBy(1_000)
        upload.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(upload.isCancelled)
        assertEquals(1, tries)
    }

    // ---- 9. the build's User-Agent goes to Ecency's own hosts only ----

    @Test
    fun theBuildUserAgentGoesToEcencyHostsAndToNoOneElse() {
        val identity = com.ecency.core.auth.ClientIdentity
        fun sent(url: String) = identity.headers(com.ecency.core.auth.AuthHttpRequest("POST", url, mapOf("Content-Type" to "application/json"), "{}"), "SecretBuild/9")
        // every Ecency host the merged app calls: private API, search, token endpoint, waves, translation, polls, images
        val env = TestApp(TestScope()) { HttpResult(500, "") }.env
        val firstParty = listOf(
            "${env.auth.tokenHost}/private-api/drafts", "${env.auth.tokenHost}/search-api/search", "${env.auth.tokenHost}/auth-api/hs-token-refresh",
            "${env.auth.tokenHost}/private-api/waves/feed", "${env.rest.translateHost}/translate", "${env.rest.pollsHost}/rpc/poll",
            "${env.imageHost}/hs/token", "${com.ecency.core.render.ImageProxy.DEFAULT_BASE}/p/x", "HTTPS://ECENCY.COM/private-api/quests", "https://ecency.com",
            // HiveSigner's API, which sign-in goes through
            "https://hivesigner.com/api/me", "https://hivesigner.com/api/oauth2/token", "https://api.hivesigner.com/x", "HTTPS://HIVESIGNER.COM/api/broadcast",
        )
        for (url in firstParty) assertEquals("SecretBuild/9", sent(url)["User-Agent"], url)
        // everyone else: no header from the app, so the platform's default
        val others = listOf(
            com.ecency.core.api.BadActors.URL,
            "https://evilhivesigner.com/api/me", "https://hivesigner.com.evil.example/api/me", "https://hivesigner.co/x", "http://hivesigner.com/api/me",
            "https://hivesigner.com:8443/api/me", "https://user@hivesigner.com/api/me", "https://hivesigner.com.ecency.co/x", "https://3speak.tv/apiv2/x", "https://play.3speak.tv/embed", "https://api.coingecko.com/api/v3/ping",
            "https://www.youtube.com/oembed", "https://api.hive.blog", "https://example.com/preview",
            // look-alikes, other schemes, userinfo tricks
            "https://ecency.com.evil.example/x", "https://evil-ecency.com/x", "https://notecency.com/x",
            "http://ecency.com/x", "https://ecency.com@evil.example/x", "https://evil.example/https://ecency.com/",
            "https://evil.example?https://ecency.com/", "https://evil.example#@ecency.com", "https://ecency.com\\@evil.example/", "wss://ecency.com/ws", "", "ecency.com",
        )
        for (url in others) {
            val headers = sent(url)
            assertTrue(headers.keys.none { it.equals("User-Agent", ignoreCase = true) }, "$url is not told the build")
            assertEquals("application/json", headers["Content-Type"], "its own headers stay")
        }
        // one list, and only the first-party domains on it
        assertEquals(listOf("ecency.com", "hivesigner.com"), identity.FIRST_PARTY_HOSTS)
    }

    // ---- waves: the composer's image goes through the editor's upload ----

    /** The image host, scripted: records who each upload was signed for. */
    private class ImageHost : ImageUploadTransport {
        val signers = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var answer: () -> HttpResult = { HttpResult(200, """{"url":"https://images.example/DQm/photo.jpg"}""") }

        override suspend fun upload(url: String, authorization: String, file: UploadFile, onProgress: (Long, Long) -> Unit): HttpResult {
            signers += com.ecency.core.auth.HiveSignerMessages.decode(url.substringAfter("/hs/")).authors.single()
            gate?.await()
            return answer()
        }
    }

    private class WaveWorld(scope: TestScope) {
        val app = TestApp(scope) { req -> TestApp.chain(req) ?: HttpResult(500, "") }
        val host = ImageHost()
        val attacher: WaveUploadAttacher
        val asked = mutableListOf<WaveImageSource>()

        init {
            val vault = KeyVault(app.store)
            listOf(NotificationFixture.ALICE, NotificationFixture.BOB).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$it")) }
            vault.setCurrentUsername(NotificationFixture.ALICE)
            app.env.imageTransport = host
            app.env.imageHost = "https://images.example"
            app.env.uploadRetryDelay = { 0 }
            attacher = WaveUploadAttacher.install(app.env)
            attacher.onPick = { asked += it }
            app.env.session.start()
            scope.testScheduler.advanceUntilIdle()
        }
    }

    private fun photo(size: Int = 10) = PickedImage(ByteArray(size), "photo.jpg", "image/jpeg", 1200, 800)

    @Test
    fun aWaveImageIsPickedPreparedByThePlatformAndUploadedForTheComposersOwner() = runTest {
        val w = WaveWorld(this)
        val composer = w.app.env.waves.composer()
        composer.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(WaveImageSource.GALLERY), w.asked, "the platform's picker was asked for")
        assertTrue(composer.current.isAttaching && w.attacher.isPicking)
        assertFalse(composer.current.canPublish, "PUBLISH waits for the image")
        assertTrue(w.host.signers.isEmpty(), "nothing is uploaded before an image was picked")
        w.attacher.picked(photo())
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(alice), w.host.signers, "signed for the composer's owner")
        assertEquals("https://images.example/DQm/photo.jpg", composer.current.imageUrl)
        assertFalse(composer.current.isAttaching || w.attacher.isPicking)
        assertEquals(1.5, w.app.env.waves.imageRatios!!.ratio("https://images.example/DQm/photo.jpg"), "the ratio known from the upload goes into image_ratios")
        // one image a wave: another pick replaces it
        w.host.answer = { HttpResult(200, """{"url":"https://images.example/DQm/second.jpg"}""") }
        composer.attach(WaveImageSource.CAMERA)
        testScheduler.advanceUntilIdle()
        w.attacher.picked(photo())
        testScheduler.advanceUntilIdle()
        assertEquals("https://images.example/DQm/second.jpg", composer.current.imageUrl)
        assertEquals(listOf(WaveImageSource.GALLERY, WaveImageSource.CAMERA), w.asked)
    }

    @Test
    fun aCancelledOrFailedWaveImageLeavesTheComposerUsable() = runTest {
        val w = WaveWorld(this)
        val composer = w.app.env.waves.composer()
        composer.setText("Still here")
        // the picker was closed without an image
        composer.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        w.attacher.picked(null)
        testScheduler.advanceUntilIdle()
        assertFalse(composer.current.isAttaching)
        assertNull(composer.current.imageUrl)
        assertNull(composer.current.error)
        assertTrue(w.host.signers.isEmpty())
        // the host is down (three tries, as the editor): an error, and nothing attached
        w.host.answer = { HttpResult(503, "") }
        composer.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        w.attacher.picked(photo())
        testScheduler.advanceUntilIdle()
        assertEquals(1 + ImageUploader.MAX_RETRIES, w.host.signers.size)
        assertNotNull(composer.current.error)
        assertFalse(composer.current.isAttaching)
        assertNull(composer.current.imageUrl)
        composer.clearError()
        // an address that is not on the image host is refused, as for the editor
        w.host.answer = { HttpResult(200, """{"url":"https://evil.example/x.jpg"}""") }
        composer.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        w.attacher.picked(photo())
        testScheduler.advanceUntilIdle()
        assertNull(composer.current.imageUrl)
        assertNotNull(composer.current.error)
        composer.clearError()
        // an image over the size limit never leaves the device
        val before = w.host.signers.size
        composer.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        w.attacher.picked(photo(ImageUploader.MAX_BYTES + 1))
        testScheduler.advanceUntilIdle()
        assertEquals(before, w.host.signers.size)
        assertNotNull(composer.current.error)
        composer.clearError()
        // and after all that the composer still attaches and keeps its text
        w.host.answer = { HttpResult(200, """{"url":"https://images.example/DQm/photo.jpg"}""") }
        composer.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        w.attacher.picked(photo())
        testScheduler.advanceUntilIdle()
        assertEquals("https://images.example/DQm/photo.jpg", composer.current.imageUrl)
        assertEquals("Still here", composer.current.text)
        assertTrue(composer.current.canPublish)
    }

    @Test
    fun aWaveImageUploadBelongsToTheOwnerEvenWhenTheAccountChangesMeanwhile() = runTest {
        val w = WaveWorld(this)
        val composer = w.app.env.waves.composer()
        w.host.gate = CompletableDeferred()
        composer.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        w.attacher.picked(photo())
        // the host has not answered yet (advancing time would run into the stall timeout)
        testScheduler.runCurrent()
        assertEquals(listOf(alice), w.host.signers)
        w.app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        w.host.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(alice), w.host.signers, "never signed as the account that came after")
        val bobs = w.app.env.waves.composer()
        assertEquals(bob, bobs.owner)
        assertNull(bobs.current.imageUrl, "alice's image does not land in bob's composer")
    }

    @Test
    fun theAttacherSignsForTheAccountItWasAskedForNotForWhoeverIsSignedIn() = runTest {
        val w = WaveWorld(this)
        // the seam itself, with the session on another account than the one the upload is for: the picker was
        // opened for alice's composer, bob is signed in by the time the image comes back
        w.app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, w.app.env.session.current.username)
        val url = async { w.attacher.attach(alice, WaveImageSource.GALLERY) }
        testScheduler.runCurrent()
        assertTrue(w.attacher.isPicking)
        w.attacher.picked(photo())
        testScheduler.advanceUntilIdle()
        assertEquals("https://images.example/DQm/photo.jpg", url.await())
        assertEquals(listOf(alice), w.host.signers, "signed with alice's key, although bob is the session's account")
        // and an account that is not on the device cannot be uploaded for at all
        val stranger = async { runCatching { w.attacher.attach("spike-nobody", WaveImageSource.GALLERY) } }
        testScheduler.runCurrent()
        w.attacher.picked(photo())
        testScheduler.advanceUntilIdle()
        assertTrue(stranger.await().isFailure)
        assertEquals(listOf(alice), w.host.signers)
    }

    @Test
    fun withoutAPickerOnScreenTheAttachSaysSoInsteadOfWaiting() = runTest {
        val w = WaveWorld(this)
        w.attacher.onPick = null
        val composer = w.app.env.waves.composer()
        composer.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        assertFalse(composer.current.isAttaching)
        assertEquals(WaveComposeModel.NO_UPLOAD, composer.current.error?.message)
    }

    @Test
    fun aProfilesWavesTabEmptiesAndLoadsAgainForTheNewAccount() = runTest {
        val w = WaveWorld(this)
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var permlink = "w-for-alice"
        w.app.env.waves.api = com.ecency.core.api.waves.WavesApi(
            com.ecency.core.auth.AuthHttp { request ->
                calls += request.url
                val body = """[{"id":1,"author":"spike-carol","permlink":"$permlink","title":"","body":"A wave","json_metadata":{"tags":["waves"]},"timestamp":"2026-10-06T11:00:00+00:00","pending_payout_value":"0.100 HBD","net_votes":1,"children":0,"tip_count":0,"host":"ecency.waves","_cursor":"c-1"}]"""
                gate?.await()
                if ("/waves/feed" in request.url) HttpResult(200, body) else HttpResult(200, "[]")
            },
        )
        val profile = w.app.env.profileModel("spike-carol", "")
        val waves = profile.waves()
        waves.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w-for-alice"), waves.current.items.map { it.key.permlink })
        val before = calls.count { "/waves/feed" in it }
        // the answer asked for as alice is held back, then the account changes
        gate = CompletableDeferred()
        waves.refresh()
        testScheduler.runCurrent()
        permlink = "w-for-bob"
        w.app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        assertTrue(waves.current.items.isEmpty(), "nothing listed for alice stays on screen for bob")
        gate!!.complete(Unit)
        gate = null
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w-for-bob"), waves.current.items.map { it.key.permlink }, "the late answer for alice never shows")
        assertTrue(calls.count { "/waves/feed" in it } >= before + 2)
        profile.close()
    }

    @Test
    fun aShareLinksTextWaitsForOneSignInAndNeverSurfacesForSomeoneElseOrMuchLater() = runTest {
        val app = appWithAccounts(this)
        val waves = app.env.waves
        // signed out: it waits for the sign-in the Waves tab sends the reader to
        waves.requestCompose("shared while signed out")
        assertNull(waves.takeComposeText())
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        assertEquals("shared while signed out", waves.takeComposeText())
        assertNull(waves.takeComposeText(), "once")
        // a text that arrived for alice does not open in bob's composer
        waves.requestCompose("for alice")
        app.env.session.loginWithKey(bob, AppTestKeys.PASSWORD)
        assertNull(waves.takeComposeText(), "another account signed in")
        waves.requestCompose("for bob")
        app.env.session.switchTo(alice)
        assertNull(waves.takeComposeText(), "a switch")
        waves.requestCompose("for alice again")
        app.env.session.logout(alice)
        assertNull(waves.takeComposeText(), "a logout that moved to the next account")
        // arrived signed out, taken along by the first sign-in but not used: it does not move on to a second account
        app.env.session.logoutAll()
        waves.requestCompose("for whoever signs in next")
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        app.env.session.loginWithKey(bob, AppTestKeys.PASSWORD)
        assertNull(waves.takeComposeText(), "the second account never sees it")
        // it does not wait forever
        waves.requestCompose("stale")
        app.now += WavesService.COMPOSE_TEXT_MILLIS + 1
        assertNull(waves.takeComposeText())
        assertNull(waves.pendingComposeText)
        waves.requestCompose("fresh")
        app.now += WavesService.COMPOSE_TEXT_MILLIS - 1
        assertEquals("fresh", waves.takeComposeText())
        // signed out, the login it waited for is closed without signing in (the platform says so), or never finished
        app.env.session.logoutAll()
        waves.requestCompose("waiting for a login")
        waves.dropComposeText()
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        assertNull(waves.takeComposeText())
        app.env.session.logoutAll()
        waves.requestCompose("waiting too long")
        app.now += WavesService.COMPOSE_TEXT_MILLIS + 1
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        assertNull(waves.takeComposeText())
        // a wipe
        waves.requestCompose("before the wipe")
        app.env.resetAfterWipe()
        app.env.session.loginWithKey(bob, AppTestKeys.PASSWORD)
        assertNull(waves.takeComposeText())
    }

    @Test
    fun anUploadFailureNeverCarriesTheUploadAddress() = runTest {
        val leaky = ImageUploadTransport { url, _, _, _ -> throw IllegalStateException("Request timeout has expired [url=$url]", RuntimeException("connect $url")) }
        val error = runCatching { ImageUploader(leaky, "https://images.example", retryDelayMillis = { 0 }).upload(file, "SECRET-UPLOAD-TOKEN") }.exceptionOrNull()!!
        var e: Throwable? = error
        while (e != null) {
            assertFalse("SECRET-UPLOAD-TOKEN" in e.toString(), "no token in $e")
            e = e.cause
        }
        assertEquals(0, assertIs<EcencyHttpException>(error).status)
        assertFalse("SECRET-UPLOAD-TOKEN" in ImageUploader.message(error))
    }

    // ---- 6. preferences: one naming scheme, no collisions ----

    @Test
    fun wave3PreferenceKeysAreAreaPrefixedAndDistinct() {
        val keys = listOf(
            PushSettings.KEY_ENABLED, PushSettings.KEY_OFF, PushModel.KEY_PENDING,
            NotificationsModel.KEY_SHOW_IMAGES,
            EditorModel.PREF_DEFAULT_REWARD,
            AppRating.FIRST_USE, AppRating.SESSIONS, AppRating.REQUESTED,
        )
        assertEquals(keys.size, keys.toSet().size, "no two settings share a key")
        val scheme = Regex("^(push|content|editor|rating)\\.[a-z][A-Za-z]+$")
        keys.forEach { assertTrue(scheme.matches(it), "$it is <area>.<camelCaseName>") }
        // and none of them is a key an earlier area already uses
        val earlier = setOf(FeedTabs.PREFS_KEY, NsfwMode.PREFS_KEY, "tts_settings")
        assertTrue(keys.none { it in earlier })
    }
}
