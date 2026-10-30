package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.HasSocket
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted push service: a token that changes when deleted, a permission prompt answered once. */
class FakePushPlatform : PushPlatform {
    override val system: String = "fcm-android"
    override var isConfigured = true

    /** What the user answers when the system prompt shows. */
    var promptAnswer = true
    var decided = false
    var granted = false
    var prompts = 0
    var token: String? = "device-1"
    private var minted = 1
    var deletes = 0
    var deleteWorks = true
    var cleared = 0
    val log = mutableListOf<String>()

    override fun permission(request: Boolean, done: (Boolean) -> Unit) {
        if (request && !decided) {
            prompts++
            decided = true
            granted = promptAnswer
        }
        done(granted)
    }

    override fun token(done: (String?) -> Unit) = done(token)

    /** The platform never calls back (a stalled Play services call). */
    var deleteHangs = false

    override fun deleteToken(done: (Boolean) -> Unit) {
        if (deleteHangs) return
        deletes++
        log += "delete"
        // the next getToken mints a new token, as FCM does
        if (deleteWorks && token != null) token = "device-${++minted}"
        done(deleteWorks)
    }

    override fun clearDelivered() {
        cleared++
    }
}

class PushTest {
    private val alice = NotificationFixture.ALICE
    private val bob = NotificationFixture.BOB
    private val allTypes = "[1,2,3,4,5,6,10,13,15,19,20,21,22,23]"

    private class Push(scope: TestScope, users: List<String> = listOf(NotificationFixture.ALICE), pin: String? = null) {
        val f = NotificationFixture(scope, users, pin)
        val platform = FakePushPlatform()
        val backend get() = f.backend
        val env get() = f.env
        val push get() = f.env.push

        init {
            f.env.pushPlatform = platform
        }

        fun start() {
            f.start()
            f.env.push.start()
            f.idle()
        }

        fun registrations() = backend.of("register-device")

        /** "username:allows_notify" per register-device call, in order. */
        fun writes() = registrations().map { "${it.text("username")}:${it.text("allows_notify")}" }
    }

    // ---- registration ----

    @Test
    fun startAsksOnceClearsTheTrayAndRegistersEveryAccount() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        assertEquals(1, p.platform.prompts)
        assertEquals(1, p.platform.cleared)
        assertEquals(true, p.push.current.available)
        assertNull(p.push.current.unavailableNotice)
        val calls = p.registrations()
        assertEquals(setOf(alice, bob), calls.map { it.text("username") }.toSet())
        val a = calls.first { it.text("username") == alice }
        assertEquals("token-$alice", a.code, "each account registers with its own access token")
        assertEquals("device-1", a.text("token"))
        assertEquals("fcm-android", a.text("system"))
        assertEquals("1", a.text("allows_notify"))
        assertEquals(allTypes, a.body["notify_types"].toString(), "every type is on until the user turns one off")
        assertEquals("token-$bob", calls.first { it.text("username") == bob }.code)
    }

    @Test
    fun withoutAFirebaseConfigurationNothingIsAskedOrSent() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.platform.isConfigured = false
        p.start()
        p.push.onTokenRefreshed()
        p.push.onForeground()
        p.push.setType(PushType.VOTE, false)
        p.f.idle()
        p.env.session.logout(alice)
        p.f.idle()
        assertEquals(0, p.platform.prompts)
        assertEquals(0, p.platform.deletes)
        assertTrue(p.registrations().isEmpty())
        assertEquals(false, p.push.current.available)
        assertNotNull(p.push.current.unavailableNotice)
        assertEquals(bob, p.env.session.current.username, "logout itself is unaffected")
    }

    @Test
    fun theDefaultPlatformIsInactive() = runTest {
        val f = NotificationFixture(this)
        f.start()
        f.env.push.start()
        f.idle()
        assertTrue(f.backend.of("register-device").isEmpty())
        assertEquals(false, f.env.push.current.available)
    }

    @Test
    fun aDeniedPermissionRegistersNothing() = runTest {
        val p = Push(this)
        p.platform.promptAnswer = false
        p.start()
        assertEquals(1, p.platform.prompts)
        assertTrue(p.registrations().isEmpty())
        assertEquals(false, p.push.current.available)
        // allowed later in the system settings: the next foreground registers
        p.platform.granted = true
        p.push.onForeground(); p.f.idle()
        assertEquals(listOf("$alice:1"), p.writes())
        assertEquals(true, p.push.current.available)
        assertEquals(1, p.platform.prompts, "the prompt never shows twice")
    }

    @Test
    fun accountsWithoutAnAccessTokenAreSkipped() = runTest {
        val p = Push(this)
        KeyVault(p.f.app.store).save(VaultAccount("spike-carol", AuthType.POSTING_KEY, postingKey = com.ecency.core.hive.GoldenVectors.WIF))
        p.start()
        assertEquals(listOf("$alice:1"), p.writes())
    }

    @Test
    fun anAccountThatBecomesCurrentRegistersRightAway() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        p.env.session.switchTo(bob)
        p.f.idle()
        assertEquals(listOf("$bob:1"), p.writes())
    }

    @Test
    fun aRefreshedTokenRegistersEveryAccountAgain() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        p.platform.token = "device-rotated"
        p.push.onTokenRefreshed(); p.f.idle()
        assertEquals(2, p.registrations().size)
        assertTrue(p.registrations().all { it.text("token") == "device-rotated" })
    }

    @Test
    fun reconnectRegistersAtMostOnceAMinuteAndForegroundOnceADay() = runTest {
        val p = Push(this)
        p.start()
        p.backend.calls.clear()

        p.env.connectivity.setOnline(false); p.f.idle()
        p.env.connectivity.setOnline(true); p.f.idle()
        assertTrue(p.registrations().isEmpty(), "inside the 60 s window")
        p.f.app.now += PushModel.DEDUPE_MILLIS + 1
        p.env.connectivity.setOnline(false); p.f.idle()
        p.env.connectivity.setOnline(true); p.f.idle()
        assertEquals(1, p.registrations().size)

        p.push.onForeground(); p.f.idle()
        assertEquals(1, p.registrations().size, "a resume within a day does not register")
        p.f.app.now += PushModel.REFRESH_MILLIS + 1
        p.push.onForeground(); p.f.idle()
        assertEquals(2, p.registrations().size)
    }

    @Test
    fun aRefusedAccountRegistersAgainOnlyOnceItsAccessTokenChanged() = runTest {
        val p = Push(this)
        p.backend.registerStatus[alice] = 401
        p.start()
        assertEquals(1, p.registrations().size)
        p.push.onForeground(); p.f.idle()
        assertEquals(1, p.registrations().size, "the same token is not tried again")

        val vault = KeyVault(p.f.app.store)
        vault.save(vault.account(alice)!!.copy(accessToken = "renewed-$alice"))
        p.backend.registerStatus.remove(alice)
        p.push.onForeground(); p.f.idle()
        assertEquals(2, p.registrations().size)
        assertEquals("renewed-$alice", p.registrations().last().code)
        p.push.onForeground(); p.f.idle()
        assertEquals(2, p.registrations().size, "accepted: no further retries")
    }

    @Test
    fun aNetworkFailureIsLeftToTheNextTrigger() = runTest {
        val p = Push(this)
        p.backend.registerStatus[alice] = 503
        p.start()
        p.push.onForeground(); p.f.idle()
        assertEquals(1, p.registrations().size)
        assertEquals(true, p.push.current.available)
    }

    // ---- logout ----

    @Test
    fun logoutDisablesTheLeavingRowWithItsOwnTokenAndKeepsTheDeviceToken() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        p.env.session.logout(alice)
        p.f.idle()
        val call = p.registrations().single { it.text("username") == alice }
        assertEquals("token-$alice", call.code, "the access token was read before the account left the vault")
        assertEquals("0", call.text("allows_notify"))
        assertEquals("[]", call.body["notify_types"].toString())
        assertEquals("device-1", call.text("token"))
        assertEquals(0, p.platform.deletes, "another account still uses the device token")
        assertNull(KeyVault(p.f.app.store).account(alice))
        // bob became current and registered as such; alice is never switched back on
        assertEquals("$alice:0", p.writes().last { it.startsWith(alice) })
    }

    @Test
    fun logoutOfTheLastAccountDisablesThenDeletesTheToken() = runTest {
        val p = Push(this)
        p.start()
        p.backend.calls.clear()
        p.f.app.authBackend.privateApi = { request ->
            if (request.url.endsWith("/register-device")) p.platform.log += "disable"
            p.backend.answer(request)
        }
        p.env.session.signOut()
        p.f.idle()
        assertEquals(listOf("$alice:0"), p.writes())
        assertEquals("device-1", p.registrations().single().text("token"), "the row is disabled under the token it was registered with")
        assertEquals(listOf("disable", "delete"), p.platform.log, "deleting first would disable the wrong row")
        assertEquals(1, p.platform.deletes)
    }

    @Test
    fun whenARowCannotBeDisabledTheTokenGoesAndTheOthersRegisterTheNewOne() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        p.backend.registerStatus[alice] = 500
        p.env.session.logout(alice)
        p.f.idle()
        assertEquals(1, p.platform.deletes)
        val last = p.registrations().last()
        assertEquals(bob, last.text("username"))
        assertEquals("device-2", last.text("token"))
        assertEquals("1", last.text("allows_notify"))
    }

    @Test
    fun logoutAllDisablesEveryAccountAndDeletesTheToken() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        p.env.session.logoutAll()
        p.f.idle()
        assertEquals(setOf("$alice:0", "$bob:0"), p.writes().toSet())
        assertEquals(1, p.platform.deletes)
    }

    @Test
    fun logoutNeverWaitsForTheNetwork() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.f.app.authBackend.gate = { request -> if (request.url.endsWith("/register-device")) CompletableDeferred<Unit>().await() }
        p.env.session.logout(alice)
        assertEquals(bob, p.env.session.current.username)
    }

    @Test
    fun aRegistrationWaitsForARunningLogoutSoTheDisableIsNotUndone() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        val held = CompletableDeferred<Unit>()
        p.f.app.authBackend.gate = { request ->
            if (request.url.endsWith("/register-device") && "\"allows_notify\":0" in request.body.orEmpty()) held.await()
        }
        p.env.session.logout(alice)
        p.push.onTokenRefreshed()
        testScheduler.advanceTimeBy(5_000)
        assertTrue(p.registrations().isEmpty(), "bob waits while alice's row is being disabled")
        held.complete(Unit)
        p.f.idle()
        assertEquals("$alice:0", p.writes().first())
        assertTrue(p.writes().drop(1).all { it == "$bob:1" } && p.writes().size >= 2)
        assertFalse("$alice:1" in p.writes(), "the leaving account is never registered again")
    }

    @Test
    fun anAccountThatLeavesWhileItsRegistrationWaitsIsNotRegistered() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        val held = CompletableDeferred<Unit>()
        p.f.app.authBackend.gate = { request ->
            if (request.url.endsWith("/register-device") && "\"allows_notify\":0" in request.body.orEmpty()) held.await()
        }
        p.env.session.logout(bob)
        p.push.onTokenRefreshed() // alice's registration now waits for bob's logout
        testScheduler.advanceTimeBy(1_000)
        p.env.session.logout(alice)
        testScheduler.advanceTimeBy(1_000)
        held.complete(Unit)
        p.f.idle()
        assertFalse("$alice:1" in p.writes(), "she left while waiting: nothing switches her row back on")
        assertEquals(setOf("$bob:0", "$alice:0"), p.writes().toSet())
    }

    @Test
    fun aRegistrationAlreadySendingLandsBeforeTheDisable() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        val held = CompletableDeferred<Unit>()
        p.f.app.authBackend.gate = { request ->
            if (request.url.endsWith("/register-device") && "\"allows_notify\":1" in request.body.orEmpty() && "\"username\":\"$alice\"" in request.body.orEmpty()) {
                withContext(NonCancellable) { held.await() }
            }
        }
        p.push.onTokenRefreshed()
        p.f.idle() // alice's registration is on the wire
        p.env.session.logout(alice)
        testScheduler.advanceTimeBy(5_000)
        assertFalse("$alice:0" in p.writes(), "the disable waits for the registration in flight")
        held.complete(Unit)
        p.f.idle()
        assertEquals("$alice:0", p.writes().last { it.startsWith(alice) }, "the disable is the last write for the account that left")
    }

    @Test
    fun aLogoutRequestThatNeverAnswersDoesNotHoldRegistrationsForever() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        // a stalled socket: the disable request is never answered
        p.f.app.authBackend.gate = { request ->
            if (request.url.endsWith("/register-device") && "\"allows_notify\":0" in request.body.orEmpty()) CompletableDeferred<Unit>().await()
        }
        p.env.session.logout(alice)
        p.push.onTokenRefreshed()
        testScheduler.advanceTimeBy(5_000)
        assertTrue(p.writes().none { it.startsWith(bob) }, "bob waits while alice's row is being disabled")
        // the request itself gives up after 30 s (long before the release's own bound): the row counts as not
        // disabled, so the token goes and bob registers the new one
        testScheduler.advanceTimeBy(com.ecency.core.api.ecency.NotificationsApi.TIMEOUT_MILLIS)
        assertEquals(1, p.platform.deletes)
        p.f.idle()
        assertEquals(1, p.platform.deletes)
        assertFalse(p.push.hasPendingRelease)
        val last = p.registrations().last()
        assertEquals(bob, last.text("username"))
        assertEquals("device-2", last.text("token"))
        assertFalse("$alice:1" in p.writes())
    }

    @Test
    fun switchesImportedDuringTheFirstRegistrationAreTheLastWrite() = runTest {
        val p = Push(this)
        // the first launch's registration (every switch on) is still on its way when the RN import lands
        val held = CompletableDeferred<Unit>()
        var first = true
        p.f.app.authBackend.gate = { request ->
            if (request.url.endsWith("/register-device") && first) {
                first = false
                held.await()
            }
        }
        p.f.start()
        p.push.start()
        // until the first registration is on the wire with the defaults
        var waited = 0
        while (first && waited++ < 200) testScheduler.advanceTimeBy(50)
        assertFalse(first, "the first registration reached the backend")
        PushSettings(enabled = true, off = setOf(PushType.VOTE)).save(p.env.prefs)
        p.push.reloadSettings()
        repeat(20) { testScheduler.advanceTimeBy(50) }
        held.complete(Unit)
        p.f.idle()
        val last = p.registrations().last()
        assertEquals("[2,3,4,5,6,10,13,15,19,20,21,22,23]", last.body["notify_types"].toString(), "the imported switches, not the defaults")
    }

    @Test
    fun aSendWaitingItsTurnSkipsAnAccountThatLoggedOut() = runTest {
        val p = Push(this, listOf(alice, bob))
        val held = CompletableDeferred<Unit>()
        var first = true
        p.f.app.authBackend.gate = { request ->
            if (request.url.endsWith("/register-device") && first && "\"$alice\"" in request.body.orEmpty()) {
                first = false
                held.await()
            }
        }
        p.f.start()
        p.push.start()
        var waited = 0
        while (first && waited++ < 200) testScheduler.advanceTimeBy(50)
        assertFalse(first)
        // a second send for alice queues behind the held one, then alice leaves
        p.push.reloadSettings()
        PushSettings(enabled = true, off = setOf(PushType.VOTE)).save(p.env.prefs)
        p.push.reloadSettings()
        repeat(10) { testScheduler.advanceTimeBy(50) }
        p.env.session.logout(alice)
        held.complete(Unit)
        p.f.idle()
        val aliceWrites = p.writes().filter { it.startsWith("$alice:") }
        assertEquals("$alice:1", aliceWrites.first(), "the held send")
        assertTrue(aliceWrites.drop(1).none { it == "$alice:1" }, "nothing registers alice after her logout: $aliceWrites")
    }

    @Test
    fun aReleaseThatCannotFinishFreesTheLockAndStaysPending() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        // nothing answers at all: not the API, and the platform's delete never calls back
        p.f.app.authBackend.gate = { request -> if (request.url.endsWith("/register-device")) CompletableDeferred<Unit>().await() }
        p.platform.deleteHangs = true
        p.env.session.logout(alice)
        p.push.onTokenRefreshed()
        testScheduler.advanceTimeBy(PushModel.RELEASE_BOUND_MILLIS + PushModel.RELEASE_WAIT_MILLIS + 60_000)
        assertTrue(p.push.hasPendingRelease, "still to do")
        assertTrue(p.writes().isEmpty())

        // the network is back: the next foreground finishes it and bob registers
        p.f.app.authBackend.gate = null
        p.platform.deleteHangs = false
        p.push.onForeground()
        p.f.idle()
        assertFalse(p.push.hasPendingRelease)
        assertEquals("$bob:1", p.writes().last())
        assertEquals("device-2", p.registrations().last().text("token"))
    }

    @Test
    fun oneReleaseHoldsTheLockForABoundedTimeHoweverManyAccountsLeave() = runTest {
        val users = listOf(alice, bob, "spike-carol", "spike-dave", "spike-erin", "spike-frank")
        val p = Push(this, users)
        p.start()
        var attempts = 0
        p.f.app.authBackend.gate = { request ->
            if (request.url.endsWith("/register-device")) {
                attempts++
                CompletableDeferred<Unit>().await()
            }
        }
        p.platform.deleteHangs = true
        p.env.session.logoutAll()
        testScheduler.advanceTimeBy(PushModel.RELEASE_BOUND_MILLIS + 600_000)
        assertTrue(attempts in 1 until users.size, "six requests of 30 s each do not fit: the release stopped at its bound ($attempts)")
        assertTrue(p.push.hasPendingRelease)
    }

    @Test
    fun aWipeDeletesTheDeviceToken() = runTest {
        val p = Push(this)
        p.start()
        p.push.opened(mapOf("type" to "vote", "target" to alice, "permlink1" to "a-post"))
        p.push.consumeOpen()
        p.env.resetAfterWipe()
        p.f.idle()
        assertEquals(1, p.platform.deletes)
        assertNull(p.push.current.open)
    }

    // ---- releases that could not finish ----

    @Test
    fun anOfflineLogoutIsRememberedWithoutSecretsAndFinishedOnReconnect() = runTest {
        val p = Push(this)
        p.start()
        p.backend.calls.clear()
        // offline: the row cannot be disabled and the token cannot be deleted
        p.backend.registerStatus[alice] = 503
        p.platform.deleteWorks = false
        p.env.session.signOut()
        p.f.idle()
        assertTrue(p.push.hasPendingRelease)
        val stored = p.env.prefs.getString("push.pendingRelease")!!
        assertEquals("device-1\n$alice", stored, "the account name and the device token, nothing else")
        assertFalse("token-$alice" in stored, "never the access token")
        assertNull(KeyVault(p.f.app.store).account(alice))

        // still offline at the next foreground: tried again, still pending
        val attempts = p.platform.deletes
        p.push.onForeground(); p.f.idle()
        assertTrue(p.platform.deletes > attempts)
        assertTrue(p.push.hasPendingRelease)

        // back online: the token is rotated, which stops the pushes of the account that left
        p.platform.deleteWorks = true
        p.env.connectivity.setOnline(false); p.f.idle()
        p.env.connectivity.setOnline(true); p.f.idle()
        assertFalse(p.push.hasPendingRelease)
        assertEquals("device-2", p.platform.token)
        assertNull(p.env.prefs.getString("push.pendingRelease"))
    }

    @Test
    fun aReleaseLeftByAnEarlierRunIsFinishedAtStartBeforeAnyoneRegisters() = runTest {
        val p = Push(this)
        // what a process killed mid-logout leaves behind
        p.env.prefs.putString("push.pendingRelease", "device-1\nspike-zed")
        p.platform.log.clear()
        p.f.app.authBackend.privateApi = { request ->
            if (request.url.endsWith("/register-device")) p.platform.log += "register"
            p.backend.answer(request)
        }
        p.start()
        assertFalse(p.push.hasPendingRelease)
        assertEquals("delete", p.platform.log.first(), "the old token goes before anything registers")
        assertTrue(p.registrations().isNotEmpty())
        assertTrue(p.registrations().all { it.text("token") == "device-2" }, "nobody registers the token that had to go")
    }

    @Test
    fun whileAReleaseIsStuckNothingRegistersAndAfterwardsOnlyTheNewToken() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()
        p.backend.registerStatus[alice] = 500
        p.platform.deleteWorks = false
        p.env.session.logout(alice)
        p.f.idle()
        assertTrue(p.push.hasPendingRelease)
        p.backend.registerStatus.remove(alice)
        p.push.onTokenRefreshed(); p.f.idle()
        assertTrue(p.writes().none { it == "$bob:1" }, "registering under the old token would keep it alive for the account that left")

        p.platform.deleteWorks = true
        p.push.onForeground(); p.f.idle()
        assertFalse(p.push.hasPendingRelease)
        val after = p.registrations().filter { it.text("allows_notify") == "1" }
        assertTrue(after.isNotEmpty())
        assertTrue(after.all { it.text("username") == bob && it.text("token") == "device-2" })
        assertFalse("$alice:1" in p.writes())
    }

    @Test
    fun signingInAgainAfterAStuckLogoutRegistersOnlyAfterTheRotation() = runTest {
        val p = Push(this)
        p.start()
        p.backend.calls.clear()
        p.backend.registerStatus[alice] = 500
        p.platform.deleteWorks = false
        p.env.session.signOut()
        p.f.idle()
        assertTrue(p.push.hasPendingRelease)
        // the same account comes back while the release is still stuck
        val vault = KeyVault(p.f.app.store)
        vault.save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = com.ecency.core.hive.GoldenVectors.WIF, accessToken = "token-$alice"))
        p.backend.registerStatus.remove(alice)
        p.push.onTokenRefreshed(); p.f.idle()
        assertTrue(p.writes().none { it == "$alice:1" })
        p.platform.deleteWorks = true
        p.push.onForeground(); p.f.idle()
        assertEquals("device-2", p.registrations().last().text("token"))
        assertEquals("$alice:1", p.writes().last())
    }

    @Test
    fun aReleaseWhoseTokenIsAlreadyGoneNeedsNoDelete() = runTest {
        val p = Push(this)
        p.env.prefs.putString("push.pendingRelease", "device-old\nspike-zed")
        p.start()
        assertFalse(p.push.hasPendingRelease)
        assertEquals(0, p.platform.deletes, "the device token is no longer the remembered one")
        assertEquals("device-1", p.registrations().last().text("token"))
    }

    @Test
    fun aWipeThatCannotDeleteTheTokenRetriesUntilItCan() = runTest {
        val p = Push(this)
        p.start()
        p.platform.deleteWorks = false
        p.env.resetAfterWipe()
        p.f.idle()
        assertTrue(p.push.hasPendingRelease)
        assertFalse(alice in p.env.prefs.getString("push.pendingRelease")!!, "a wipe leaves no account name behind")
        p.platform.deleteWorks = true
        p.push.onForeground(); p.f.idle()
        assertFalse(p.push.hasPendingRelease)
        assertEquals("device-2", p.platform.token)
    }

    // ---- pushes for another account ----

    @Test
    fun aForegroundPushForAnotherAccountShowsAndChangesNothing() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.unread[alice] = 5
        val before = p.backend.calls.size
        p.push.received(mapOf("type" to "reply", "source" to "spike-carol", "target" to bob, "permlink1" to "re-post", "id" to "r-1"), "t", "b")
        p.f.idle()
        assertNull(p.env.ui.current.banner, "bob's activity is not shown in alice's session")
        assertEquals(before, p.backend.calls.size, "and her count is not touched")
        assertEquals(0, p.env.badges.current.notifications)
        // the same push for the current account does both
        p.push.received(mapOf("type" to "reply", "source" to "spike-carol", "target" to alice, "permlink1" to "re-post", "id" to "r-2"), "t", "b")
        p.f.idle()
        assertNotNull(p.env.ui.current.banner)
        assertEquals(5, p.env.badges.current.notifications)
    }

    @Test
    fun aTappedPushForAnotherAccountOpensItsPostButMarksNothing() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.backend.rows[alice] = listOf(FakeNotificationsBackend.vote("r-1"))
        p.start()
        val before = p.backend.calls.size
        p.push.opened(mapOf("type" to "reply", "source" to "spike-carol", "target" to bob, "permlink1" to "re-post", "id" to "r-1"))
        p.f.idle()
        assertEquals(NavEntry.screen(AppScreen.Post("spike-carol", "re-post")), p.push.current.open, "public content still opens")
        assertEquals(before, p.backend.calls.size, "nothing is sent with alice's access token for bob's notification")
        // no addressee at all: not marked either
        p.push.consumeOpen()
        p.push.opened(mapOf("type" to "reply", "source" to "spike-carol", "permlink1" to "re-post", "id" to "r-1"))
        p.f.idle()
        assertTrue(p.backend.of("notifications/mark").isEmpty())
    }

    @Test
    fun aHiveUriTapOnlyReachesTheSigningConfirmationAndSendsNoMalformedId() = runTest {
        val p = Push(this)
        p.start()
        // RN `hiveuri` (linkProcessor.handleLink); also what another app can put into the launcher intent's extras:
        // either way it only gets as far as the confirmation the same link from another app gets
        val vote = com.ecency.core.auth.HiveUri.encodeOps(
            kotlinx.serialization.json.JsonArray(listOf(com.ecency.core.api.broadcast.OperationJson.encode(com.ecency.core.hive.Vote("__signer", "author", "permlink", 10000)))),
            com.ecency.core.auth.HiveUri.Params(),
        )
        p.push.opened(mapOf("type" to "hiveuri", "hiveUri" to vote, "id" to "guessed-id"))
        p.f.idle()
        assertNull(p.push.current.open)
        assertIs<LinkPrompt.SignConfirm>(p.env.authLinks.current.prompt)
        assertTrue(p.backend.of("notifications/mark").isEmpty(), "no addressee: nothing is marked")
        p.env.authLinks.decline()
        p.f.idle()
        // a login link in a push: whether an account is here never goes back to its requester
        p.push.opened(mapOf("type" to "hiveuri", "hiveUri" to "ecency://login?username=spike-nobody&callback=otherapp://done"))
        p.f.idle()
        assertNull(p.env.authLinks.current.callback)
        // addressed to another account signed in here: not even the question
        p.push.opened(mapOf("type" to "hiveuri", "target" to bob, "hiveUri" to vote))
        p.f.idle()
        assertNull(p.env.authLinks.current.prompt)
        // a link the app refuses (here an operation it cannot sign) shows nothing at all from a push: any app can
        // hand the exported activity a push-shaped intent
        p.push.opened(mapOf("type" to "hiveuri", "hiveUri" to "hive://sign/op/WyJ2b3RlIix7fV0.", "id" to "guessed"))
        p.f.idle()
        assertNull(p.env.authLinks.current.prompt)
        // the same link from content still says why it was refused
        p.env.authLinks.handleFromContent("hive://sign/op/WyJ2b3RlIix7fV0.")
        p.f.idle()
        assertIs<LinkPrompt.Refused>(p.env.authLinks.current.prompt)
        p.push.opened(mapOf("type" to "vote", "target" to alice, "permlink1" to "my-post", "id" to "n1\",\"x\":\"y"))
        p.f.idle()
        assertTrue(p.backend.of("notifications/mark").isEmpty(), "an id that is not id-shaped never reaches the API")
    }

    // ---- settings ----

    @Test
    fun switchesAreSavedOnTheDeviceAndSentForEveryAccount() = runTest {
        val p = Push(this, listOf(alice, bob))
        p.start()
        p.backend.calls.clear()

        p.push.setType(PushType.VOTE, false); p.f.idle()
        assertEquals(2, p.registrations().size)
        for (call in p.registrations()) {
            assertEquals("[2,3,4,5,6,10,13,15,19,20,21,22,23]", call.body["notify_types"].toString())
            assertEquals("1", call.text("allows_notify"), "turning one type off must not turn push off (RN sends 0 here)")
        }
        p.backend.calls.clear()
        p.push.setEnabled(false); p.f.idle()
        assertTrue(p.registrations().all { it.text("allows_notify") == "0" })
        assertEquals(2, p.registrations().size)
        p.backend.calls.clear()
        p.push.setEnabled(false); p.f.idle()
        assertTrue(p.registrations().isEmpty(), "no change, no request")

        val saved = PushSettings.load(p.env.prefs)
        assertFalse(saved.enabled)
        assertEquals(setOf(PushType.VOTE), saved.off)
        p.push.setType(PushType.VOTE, true)
        assertTrue(PushSettings.load(p.env.prefs).off.isEmpty())
    }

    @Test
    fun settingsDefaultsAndRnOrder() {
        val defaults = PushSettings.load(InMemoryPrefs())
        assertTrue(defaults.enabled)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 10, 13, 15, 19, 20, 21, 22, 23), defaults.notifyTypes())
        assertEquals(
            listOf("Follow", "Vote", "Comment", "Mention", "Favorites", "Bookmarks", "Tags", "Reblog", "Transfers",
                "Scheduled post published", "Delegations", "Payouts", "Account updates", "Weekly earnings"),
            PushType.entries.map { it.label },
        )
        assertEquals(mapOf("vote" to 1, "mention" to 2, "follow" to 3, "comment" to 4, "reblog" to 5, "transfers" to 6, "favorite" to 13,
            "bookmark" to 15, "tags" to 23, "delegations" to 10, "payouts" to 19, "accountUpdate" to 20, "weeklyEarnings" to 21,
            "scheduledPublished" to 22), PushType.entries.associate { it.key to it.id })
    }

    // ---- tapped push: routing ----

    private fun opened(vararg data: Pair<String, String>) = PushRouting.opened(PushPayload.of(mapOf(*data), null, null))

    private fun post(author: String, permlink: String) = PushAction(NavEntry.screen(AppScreen.Post(author, permlink)))

    @Test
    fun tappedPushesRouteLikeRn() {
        for (type in listOf("vote", "unvote", "reblog")) {
            assertEquals(post(alice, "my-post"), opened("type" to type, "source" to bob, "target" to alice, "permlink1" to "my-post"), type)
        }
        for (type in listOf("mention", "scheduled_published", "favorite", "bookmark", "reply")) {
            assertEquals(post(bob, "their-post"), opened("type" to type, "source" to bob, "target" to alice, "permlink1" to "their-post"), type)
        }
        for (type in listOf("follow", "unfollow", "ignore")) {
            assertEquals(PushAction(NavEntry.screen(AppScreen.Profile(bob))), opened("type" to type, "source" to bob, "target" to alice), type)
        }
        assertEquals(post(bob, "a-b-c"), opened("type" to "reply", "source" to bob, "permlink1" to "a-", "permlink2" to "b-", "permlink3" to "c"), "long permlinks arrive in three parts")
        assertEquals(post(bob, "tagged"), opened("type" to "tag", "source" to bob, "permlink1" to "tagged", "tag" to "hive"))
        assertEquals(PushAction(NavEntry.screen(AppScreen.TagResult("hive"))), opened("type" to "tags", "source" to bob, "tag" to "hive"))
        assertEquals(PushAction(NavEntry.tab(AppTab.WALLET)), opened("type" to "transfer", "source" to bob))
        assertEquals(RouteName.EDITOR, opened("type" to "inactive")!!.entry!!.screens.single().route)
        assertEquals(RouteName.PERKS, opened("type" to "spin")!!.entry!!.screens.single().route)
        // types RN does not route from a tap
        for (type in listOf("delegation", "payout", "account_update", "weekly_earnings", "blacklist", "", "something_new")) {
            assertNull(opened("type" to type, "source" to bob, "target" to alice, "permlink1" to "x"), type)
        }
    }

    @Test
    fun hostilePayloadsOpenNothing() {
        // account names and permlinks must validate; nothing is trimmed or repaired into a target
        assertNull(opened("type" to "vote", "target" to "Alice", "permlink1" to "my-post"))
        assertNull(opened("type" to "vote", "target" to "$alice/../x", "permlink1" to "my-post"))
        assertNull(opened("type" to "vote", "target" to alice, "permlink1" to "my post"))
        assertNull(opened("type" to "vote", "target" to alice, "permlink1" to "x?redirect=https://evil.example"))
        assertNull(opened("type" to "vote", "target" to alice, "permlink1" to ""))
        assertNull(opened("type" to "reply", "source" to "https://evil.example", "permlink1" to "p"))
        assertNull(opened("type" to "follow", "source" to "javascript:alert(1)"))
        assertNull(opened("type" to "tag", "source" to bob, "tag" to "../wallet"))
        assertNull(opened("type" to "tag", "source" to bob, "tag" to "x".repeat(33)))
        assertNull(opened("type" to "vote", "target" to alice, "permlink1" to "a".repeat(2000)), "an oversized field is dropped, not cut")
        // a payload cannot name a screen, a url or an account to act as
        assertNull(opened("type" to "navigate", "route" to "BackupKeysScreen", "url" to "https://evil.example", "screen" to "settings"))
        assertNull(opened("type" to "url", "url" to "https://evil.example"))
        // RN `hiveuri`: only a hive:// or ecency:// link, and only for the link model (which asks first)
        assertEquals(PushAction(null, "hive://sign/op/WyJ2b3RlIix7fV0."), opened("type" to "hiveuri", "hiveUri" to "hive://sign/op/WyJ2b3RlIix7fV0."))
        assertEquals(PushAction(null, "ecency://login?callback=evil://x"), opened("type" to "hiveuri", "hiveUri" to "ecency://login?callback=evil://x"))
        assertNull(opened("type" to "hiveuri", "hiveUri" to "https://evil.example/sign"))
        assertNull(opened("type" to "hiveuri", "hiveUri" to "javascript://alert(1)"))
        assertNull(opened("type" to "hiveuri", "hiveUri" to "hive://sign op"))
        assertNull(opened("type" to "hiveuri"))
        assertNull(opened("type" to "vote", "target" to alice, "hiveUri" to "hive://sign/op/x"), "only the hiveuri type reads the link")
        // ids go back to the API only when they look like ids
        assertEquals("", PushPayload.of(mapOf("id" to "1\"},{\"x"), null, null).id)
        assertEquals("", PushPayload.of(mapOf("id" to "a".repeat(65)), null, null).id)
        assertEquals("m-1234", PushPayload.of(mapOf("id" to "m-1234"), null, null).id)
    }

    @Test
    fun aTappedPushWaitsBehindTheLockAndShowsNothingThere() = runTest {
        val p = Push(this, pin = "1234")
        p.backend.markUnread = 0
        p.start()
        assertTrue(p.env.appLock.current.isLocked)

        p.push.opened(mapOf("type" to "reply", "source" to bob, "target" to alice, "permlink1" to "re-post", "id" to "r-77"))
        p.f.idle()
        assertNull(p.push.current.open, "nothing routes while locked")
        assertTrue(p.backend.of("notifications/mark").isEmpty(), "and nothing is sent for the account")
        assertNull(p.env.ui.current.banner)
        assertNull(p.env.ui.current.toast)
        // the lock prompt itself says nothing about the notification
        assertEquals("Enter PIN to unlock", p.env.appLock.current.prompt!!.message)

        // a wrong PIN does not release it
        "0000".forEach { p.env.appLock.enterDigit(it.digitToInt()) }
        p.f.idle()
        assertNull(p.push.current.open)

        p.f.unlock()
        assertEquals(NavEntry.screen(AppScreen.Post(bob, "re-post")), p.push.current.open)
        assertEquals("r-77", p.backend.of("notifications/mark").single().text("id"))
        p.push.consumeOpen()
        assertNull(p.push.current.open)
        // it routes once: a relock and unlock does not open it again
        p.env.appLock.onBackground()
        p.f.app.monotonic += 61_000
        p.env.appLock.onForeground(); p.f.idle()
        p.f.unlock()
        assertNull(p.push.current.open)
    }

    @Test
    fun onlyTheLatestTapIsKeptWhileLocked() = runTest {
        val p = Push(this, pin = "1234")
        p.start()
        p.push.opened(mapOf("type" to "reply", "source" to bob, "permlink1" to "first"))
        p.push.opened(mapOf("type" to "follow", "source" to bob))
        p.f.unlock()
        assertEquals(NavEntry.screen(AppScreen.Profile(bob)), p.push.current.open)
    }

    @Test
    fun aTapThatStartsTheAppWaitsForTheAccounts() = runTest {
        val p = Push(this)
        p.env.push.opened(mapOf("type" to "vote", "target" to alice, "permlink1" to "my-post", "id" to "v-1"))
        assertNull(p.push.current.open, "the accounts were not read yet")
        p.start()
        assertEquals(NavEntry.screen(AppScreen.Post(alice, "my-post")), p.push.current.open)
        assertEquals("v-1", p.backend.of("notifications/mark").single().text("id"))
    }

    @Test
    fun signedOutATapStillRoutesButMarksNothing() = runTest {
        val p = Push(this, users = emptyList())
        p.start()
        p.push.opened(mapOf("type" to "transfer", "source" to bob, "id" to "t-1"))
        p.f.idle()
        // the navigation gates the wallet tab behind its sign-in prompt
        assertEquals(NavEntry.tab(AppTab.WALLET), p.push.current.open)
        assertTrue(p.backend.calls.isEmpty())
        assertNull(p.env.ui.current.toast)
    }

    @Test
    fun aWipeWhileLockedDropsTheWaitingTap() = runTest {
        val p = Push(this, pin = "1234")
        p.start()
        p.push.opened(mapOf("type" to "reply", "source" to bob, "permlink1" to "re-post", "id" to "r-1"))
        p.env.appLock.forgotPin()
        p.f.idle()
        assertFalse(p.env.appLock.current.hidesContent, "the wipe opened the signed-out app")
        assertNull(p.push.current.open, "the tap belonged to an account that is gone")
        assertTrue(p.backend.of("notifications/mark").isEmpty())
    }

    // ---- foreground push: banner and count ----

    private fun banner(type: String, title: String? = null, body: String? = null, vararg data: Pair<String, String>) =
        PushRouting.banner(PushPayload.of(mapOf("type" to type, "source" to bob, "target" to alice, "id" to "x-1", *data), title, body))

    @Test
    fun bannersUseRnCopyAndRouting() {
        var b = banner("reply", null, null, "permlink1" to "re-post")!!
        assertEquals("New reply on @$alice", b.title)
        assertEquals("Tap to continue conversation...", b.body)
        assertEquals(bob, b.avatarUser)
        assertEquals(NavEntry.screen(AppScreen.Post(bob, "re-post")), b.entry)
        b = banner("mention", null, null, "permlink1" to "a-post")!!
        assertEquals("New mention on @$alice", b.title)
        b = banner("transfer", null, null, "amount" to "1.000 HIVE")!!
        assertEquals("@$bob transferred", b.title)
        assertEquals("1.000 HIVE", b.body)
        assertEquals(NavEntry.tab(AppTab.WALLET), b.entry)
        assertEquals("Amount unavailable", banner("transfer")!!.body)
        for (type in listOf("delegation", "delegations")) {
            b = banner(type, null, null, "amount" to "10 HP")!!
            assertEquals("@$bob delegated", b.title)
            assertEquals(NavEntry.tab(AppTab.WALLET), b.entry)
        }
        b = banner("scheduled_published", "ignored", "My scheduled post", "permlink1" to "sched")!!
        assertEquals("Scheduled post published", b.title)
        assertEquals("My scheduled post", b.body)
        assertEquals("Your scheduled post is now live, tap to view it", banner("scheduled_published")!!.body)
        b = banner("tag", "@spike-bob posted in #hive", "A title", "permlink1" to "tagged")!!
        assertEquals("@spike-bob posted in #hive", b.title)
        assertEquals(NavEntry.screen(AppScreen.Post(bob, "tagged")), b.entry)
        assertEquals("New posts in a tag you follow", banner("tags")!!.title)
        assertEquals(NavEntry.screen(AppScreen.TagResult("hive")), banner("tags", null, null, "tag" to "hive")!!.entry)
        assertEquals("Payout received: \$1.25", banner("payout", null, null, "amount" to "1.25")!!.title)
        assertEquals("Rewards received", banner("payouts", "Rewards received", "1.25")!!.title)
        assertEquals("You earned \$4 this week", banner("weekly_earnings", null, null, "amount" to "4")!!.title)
        b = banner("account_update")!!
        assertEquals("Your account or profile were updated", b.title)
        assertNull(b.entry, "informational only")
        for ((type, text) in listOf("follow" to "followed you", "unfollow" to "unfollowed you", "ignore" to "ignored you", "blacklist" to "blacklisted you")) {
            b = banner(type)!!
            assertEquals("@$bob $text", b.title)
            assertEquals(NavEntry.screen(AppScreen.Profile(bob)), b.entry)
        }
        // types without a banner
        for (type in listOf("vote", "reblog", "favorite", "bookmark", "spin", "inactive", "hiveuri", "")) assertNull(banner(type), type)
        // texts are capped single lines; a hostile source gets no avatar and no destination
        b = PushRouting.banner(PushPayload.of(mapOf("type" to "follow", "source" to "x\n<script>", "id" to "1"), null, null))!!
        assertEquals("", b.avatarUser)
        assertNull(b.entry)
        assertFalse('\n' in b.title)
        assertTrue(banner("tag", "t".repeat(5000), "b".repeat(5000))!!.let { it.title.length <= 300 && it.body.length <= 300 })
    }

    @Test
    fun aForegroundPushRefreshesTheCountAndShowsTheBannerOnce() = runTest {
        val p = Push(this)
        p.start()
        p.backend.unread[alice] = 1
        val data = mapOf("type" to "reply", "source" to bob, "target" to alice, "permlink1" to "re-post", "id" to "r-1")
        p.push.received(data, "ignored", "ignored")
        p.f.idle()
        assertEquals(1, p.env.badges.current.notifications)
        val banner = p.env.ui.current.banner!!
        assertEquals("New reply on @$alice", banner.title)
        assertEquals(NavEntry.screen(AppScreen.Post(bob, "re-post")), banner.entry)
        assertEquals(bob, banner.avatarUser)
        assertEquals(1, p.env.ui.current.queued)
        // FCM and the websocket can both deliver the same event
        p.push.received(data, null, null)
        p.f.idle()
        assertEquals(1, p.env.ui.current.queued)

        // a vote refreshes nothing and shows nothing (RN's foreground list)
        val before = p.backend.of("notifications/unread").size
        p.push.received(mapOf("type" to "vote", "source" to bob, "id" to "v-9"), "t", "b"); p.f.idle()
        assertEquals(before, p.backend.of("notifications/unread").size)
        assertEquals(1, p.env.ui.current.queued)
        // delegation and payout come singular on push and still count
        p.push.received(mapOf("type" to "delegation", "source" to bob, "id" to "d-1", "amount" to "1 HP"), null, null); p.f.idle()
        assertEquals(before + 1, p.backend.of("notifications/unread").size)
    }

    @Test
    fun nothingShowsOrIsFetchedForAPushWhileLockedOrSignedOut() = runTest {
        val locked = Push(this, pin = "1234")
        locked.start()
        locked.push.received(mapOf("type" to "reply", "source" to bob, "target" to alice, "permlink1" to "p", "id" to "r-1"), "t", "b")
        locked.f.idle()
        assertNull(locked.env.ui.current.banner)
        assertTrue(locked.backend.of("notifications/unread").isEmpty())

        val out = Push(this, users = emptyList())
        out.start()
        out.push.received(mapOf("type" to "reply", "source" to bob, "target" to alice, "permlink1" to "p", "id" to "r-1"), "t", "b")
        out.f.idle()
        assertNull(out.env.ui.current.banner)
        assertTrue(out.backend.calls.isEmpty())
    }

    // ---- websocket fallback ----

    private fun event(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("event", "notify"); put("type", type); put("source", bob); put("target", alice)
        putJsonObject("extra", block)
    }

    @Test
    fun socketEventsBecomeRnsBannerTexts() {
        fun of(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = PushPayload.fromSocket(event(type, block).toString(), 42)!!
        var s = of("reply") { put("body", "x".repeat(300)); put("permlink", "re-post") }
        assertEquals("@$bob replied to @$alice", s.title)
        assertEquals(100, s.body.length)
        assertEquals("re-post", s.permlink)
        assertEquals("ws-42", s.id)
        assertEquals("@$bob mentioned @$alice", of("mention").title)
        s = of("transfer") { put("amount", "1.000 HIVE") }
        assertEquals("@$bob transferred to @$alice", s.title)
        assertEquals("1.000 HIVE", s.amount)
        assertEquals("@$bob delegated to @$alice", of("delegations").title)
        assertEquals("Scheduled post published", of("scheduled_published").title)
        assertEquals("Rewards received", of("payouts").title)
        assertEquals("Your account details were updated", of("account_update").body)
        assertEquals("\$4.20", of("weekly_earnings") { put("total_usd", "4.20") }.body)
        assertEquals("@$bob blacklisted @$alice", of("blacklist").title)
        s = of("tags") { put("tags", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("hive")))); put("title", "A post") }
        assertEquals("@$bob posted in #hive", s.title)
        assertEquals("hive", s.tag)
        assertEquals("7 new posts in #art", of("tags") { put("tag", "art"); put("count", 7) }.title)
        // anything else is ignored
        assertNull(PushPayload.fromSocket("ping", 1))
        assertNull(PushPayload.fromSocket("""{"event":"notify","type":"vote","source":"a"}""", 1), "not in the websocket list")
        assertNull(PushPayload.fromSocket("""{"event":"other","type":"reply"}""", 1))
        assertNull(PushPayload.fromSocket("""[1,2]""", 1))
    }

    @Test
    fun theSocketRunsOnlyWherePushIsUnavailableAndOnlyWhileUnlockedAndSignedIn() = runTest {
        val p = Push(this, listOf(alice, bob), pin = "1234")
        p.platform.token = null // no Play services: push cannot work on this device
        p.env.activityWebsocketUrl = "wss://ws.example.invalid/ws"
        p.start()
        assertEquals(0, p.push.socketConnects, "nothing connects behind the lock")
        p.f.unlock()
        assertEquals(false, p.push.current.available)
        assertEquals(1, p.push.socketConnects)

        p.backend.unread[alice] = 2
        p.f.app.relay.push(event("reply") { put("permlink", "re-post") })
        p.f.idle()
        assertEquals("New reply on @$alice", p.env.ui.current.banner!!.title)
        assertEquals(2, p.env.badges.current.notifications)

        p.push.onBackground(); p.f.idle()
        p.push.onForeground(); p.f.idle()
        assertEquals(2, p.push.socketConnects, "background closes it, foreground connects again")

        p.env.session.switchTo(bob); p.f.idle()
        assertEquals(3, p.push.socketConnects, "the other account gets its own connection")

        p.env.session.logoutAll(); p.f.idle()
        val after = p.push.socketConnects
        p.push.onForeground(); p.f.idle()
        assertEquals(after, p.push.socketConnects, "signed out: no connection")
    }

    @Test
    fun noSocketWhilePushWorksOrWithoutAnAddress() = runTest {
        val working = Push(this)
        working.env.activityWebsocketUrl = "wss://ws.example.invalid/ws"
        working.start()
        assertEquals(true, working.push.current.available)
        assertEquals(0, working.push.socketConnects)

        val noUrl = Push(this)
        noUrl.platform.token = null
        noUrl.start()
        assertEquals(0, noUrl.push.socketConnects)

        val plain = Push(this)
        plain.platform.token = null
        plain.env.activityWebsocketUrl = "ws://ws.example.invalid/ws"
        plain.start()
        assertEquals(0, plain.push.socketConnects, "only wss")
    }

    @Test
    fun theSocketReconnectsWithRnsBackoffThenGivesUp() = runTest {
        val attempts = mutableListOf<Long>()
        val addresses = mutableListOf<String>()
        val factory = HasSocketFactory { address ->
            attempts += currentTime
            addresses += address
            throw IllegalStateException("refused")
        }
        val socket = ActivitySocket(CoroutineScope(StandardTestDispatcher(testScheduler)), { factory }, { "wss://ws.example.invalid/ws" }, { currentTime }) {}
        socket.update(alice)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0L, 2_000, 6_000, 14_000, 30_000, 60_000), attempts)
        assertEquals("wss://ws.example.invalid/ws?user=$alice", addresses.first())
        // the next update (foreground, unlock) tries again
        socket.update(alice)
        testScheduler.advanceUntilIdle()
        assertEquals(12, attempts.size)
        socket.update(null)
        // a name that is not an account never reaches the address
        socket.update("x&admin=1")
        testScheduler.advanceUntilIdle()
        assertEquals(12, attempts.size)
    }

    @Test
    fun aDroppedSocketReconnectsAndStopsWhenToldTo() = runTest {
        var opens = 0
        val channels = mutableListOf<Channel<String>>()
        val factory = HasSocketFactory {
            opens++
            val ch = Channel<String>(Channel.UNLIMITED).also { channels += it }
            object : HasSocket {
                override suspend fun send(text: String) {}
                override val incoming: ReceiveChannel<String> = ch
                override suspend fun close() { ch.close() }
            }
        }
        val events = mutableListOf<PushPayload>()
        val socket = ActivitySocket(CoroutineScope(StandardTestDispatcher(testScheduler)), { factory }, { "wss://ws.example.invalid/ws" }, { currentTime }) { events += it }
        socket.update(alice)
        testScheduler.runCurrent()
        assertEquals(1, opens)
        channels[0].trySend(event("follow").toString())
        testScheduler.runCurrent()
        assertEquals("follow", events.single().type)
        channels[0].close() // the server dropped us
        testScheduler.advanceTimeBy(2_001)
        assertEquals(2, opens)
        socket.update(null)
        testScheduler.advanceUntilIdle()
        assertTrue(channels[1].isClosedForSend, "stopping closes the connection")
        assertEquals(2, opens)
    }
}
