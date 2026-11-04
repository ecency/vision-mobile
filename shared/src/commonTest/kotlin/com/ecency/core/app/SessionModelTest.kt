package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthLink
import com.ecency.core.auth.LoginMethod
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A node that knows [known] accounts (every role's key from [AppTestKeys]) and the chain state for signing. */
fun appWithAccounts(
    scope: TestScope,
    known: Set<String> = setOf("spike-alice", "spike-bob"),
    postingApps: List<String> = emptyList(),
    separateIo: Boolean = false,
) =
    TestApp(scope, separateIo = separateIo) { req ->
        if (req.method == "condenser_api.get_accounts") {
            val found = known.filter { "\"$it\"" in req.body }
            FakeTransport.result(req, found.joinToString(",", "[", "]") { AppTestKeys.accountJson(it, postingApps) })
        } else {
            TestApp.chain(req) ?: if (req.method == "condenser_api.broadcast_transaction") FakeTransport.result(req, "null") else HttpResult(500, "")
        }
    }

/** SessionModel over the shared AccountManager: sign-in, accounts, logout and the Ecency session. */
class SessionModelTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private fun app(scope: TestScope) = appWithAccounts(scope)

    private fun events(scope: TestScope, app: TestApp): MutableList<AppEvent> {
        val seen = mutableListOf<AppEvent>()
        scope.backgroundScope.launch(app.main) { app.env.events.collect { seen += it } }
        return seen
    }

    private fun lock(app: TestApp) = PinLock(KeyVault(app.store), 16)

    /**
     * Android starts the session again on every activity recreation (a rotation). Wherever that start lands in
     * [op] (after [k] scheduler turns, the vault on its own IO dispatcher), [op] announces its change once, after
     * its cleanup, and the start announces nothing.
     */
    private fun TestScope.rotatingDuring(accounts: List<String>, op: suspend (SessionModel) -> Unit, expected: String, turns: Int = 40) {
        for (k in 0..turns) {
            val app = appWithAccounts(this, separateIo = true)
            KeyVault(app.store).run {
                accounts.forEach { save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, KeyRole.POSTING), accessToken = "access-$it")) }
                setCurrentUsername(accounts.first())
            }
            app.env.session.start()
            flush(app)
            val seen = events(this, app)
            testScheduler.runCurrent()
            val voted = PostKey("x", "y")
            app.env.localVotes.set(voted, LocalVote(10000, false))
            val job = launch(app.main) { op(app.env.session) }
            launch(app.main) {
                repeat(k) { kotlinx.coroutines.yield() }
                app.env.session.start()
            }
            flush(app)
            assertTrue(job.isCompleted, "k=$k")
            assertEquals(expected, app.env.session.current.username, "k=$k")
            assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(expected)), seen.filterIsInstance<AppEvent.SessionChanged>(), "k=$k")
            assertNull(app.env.localVotes.get(voted), "k=$k: the previous account's state was forgotten")
        }
    }

    private fun TestScope.storeAlice(app: TestApp) = KeyVault(app.store).run {
        save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), accessToken = "access-$alice"))
        setCurrentUsername(alice)
    }

    @Test
    fun aRecreationAfterAFailedFirstReadAnnouncesTheAccountItFinds() = runTest {
        val app = appWithAccounts(this, separateIo = true)
        storeAlice(app)
        val seen = events(this, app)
        // the first read fails (an Android Keystore that wants the device unlocked after a reboot)
        app.store.namesUnavailable = true
        app.env.session.start()
        app.env.wallet.start()
        flush(app)
        assertNull(app.env.session.current.username)
        assertNotNull(app.env.session.current.error)
        // the activity is recreated and the store answers again
        app.store.namesUnavailable = false
        app.env.session.start()
        flush(app)
        assertEquals(alice, app.env.session.current.username)
        assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(alice)), seen.filterIsInstance<AppEvent.SessionChanged>())
        assertEquals(alice, app.env.wallet.current.account, "the wallet loads the account the read found")
        // later recreations after a good read announce nothing
        app.env.session.start()
        flush(app)
        assertEquals(1, seen.filterIsInstance<AppEvent.SessionChanged>().size)
    }

    /** Runs what is due, the background event collector included (it only runs while a foreground task exists). */
    private fun TestScope.flush(app: TestApp) {
        testScheduler.advanceUntilIdle()
        // an event emitted by the last foreground task waits in the background collector: one more foreground turn
        repeat(2) {
            launch(app.main) { kotlinx.coroutines.yield() }
            testScheduler.advanceUntilIdle()
        }
    }

    private fun TestScope.storeBoth(app: TestApp, current: String, names: List<String> = listOf(alice, bob)) = KeyVault(app.store).run {
        names.forEach { save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, KeyRole.POSTING), accessToken = "access-$it")) }
        setCurrentUsername(current)
    }

    @Test
    fun aSignInOrSwitchAfterAFailedReadIsNotAnnouncedAgainByARecreation() = runTest {
        for (switching in listOf(false, true)) {
            val app = appWithAccounts(this, separateIo = true)
            if (switching) storeBoth(app, alice)
            val seen = events(this, app)
            app.store.namesUnavailable = true
            app.env.session.start()
            flush(app)
            assertNotNull(app.env.session.current.error)
            app.store.namesUnavailable = false
            if (switching) app.env.session.switchTo(bob) else app.env.session.loginWithKey(bob, AppTestKeys.wif(bob, KeyRole.POSTING))
            flush(app)
            // the activity is recreated: the sign-in or switch already told the screens
            app.env.session.start()
            flush(app)
            assertEquals(bob, app.env.session.current.username)
            assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(bob)), seen.filterIsInstance<AppEvent.SessionChanged>(), "switching=$switching")
        }
    }

    @Test
    fun aWipeAfterAFailedReadIsNotAnnouncedAgainByARecreation() = runTest {
        val app = appWithAccounts(this, separateIo = true)
        storeAlice(app)
        app.env.session.start()
        flush(app)
        assertEquals(alice, app.env.session.current.username)
        val seen = events(this, app)
        flush(app)
        app.store.namesUnavailable = true
        app.env.session.start()
        flush(app)
        assertNotNull(app.env.session.current.error, "the re-read failed")
        app.store.namesUnavailable = false
        // the PIN wipe: the vault goes, the session is told signed out once
        app.store.wipe()
        app.env.resetAfterWipe()
        flush(app)
        app.env.session.start()
        flush(app)
        assertNull(app.env.session.current.username)
        assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(null)), seen.filterIsInstance<AppEvent.SessionChanged>())
    }

    @Test
    fun aRecreationAfterALogoutWhoseReadFailedAnnouncesTheAccountThatStayed() = runTest {
        val app = appWithAccounts(this, separateIo = true)
        storeBoth(app, alice)
        app.env.session.start()
        flush(app)
        val seen = events(this, app)
        flush(app)
        // the logout makes bob current in the vault, then its read of the vault fails
        app.store.afterPut = {
            if (KeyVault(app.store).currentUsername() == bob) {
                app.store.namesUnavailable = true
                app.store.afterPut = null
            }
        }
        assertFailsWith<Exception> { app.env.session.logout(alice) }
        flush(app)
        assertEquals(alice, app.env.session.current.username, "the state is stale: the read failed")
        assertTrue(seen.filterIsInstance<AppEvent.SessionChanged>().isEmpty())
        app.store.namesUnavailable = false
        app.env.session.start()
        flush(app)
        assertEquals(bob, app.env.session.current.username)
        assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(bob)), seen.filterIsInstance<AppEvent.SessionChanged>())
    }

    @Test
    fun aQuietLogoutOfAnotherAccountKeepsAnArmedAnnouncement() = runTest {
        val carol = "spike-carol"
        val app = appWithAccounts(this, separateIo = true)
        storeBoth(app, alice, listOf(alice, bob, carol))
        app.env.session.start()
        flush(app)
        val seen = events(this, app)
        flush(app)
        // logout(alice) makes bob current in the vault, then its read fails: the screens still see alice
        app.store.afterPut = {
            if (KeyVault(app.store).currentUsername() == bob) {
                app.store.namesUnavailable = true
                app.store.afterPut = null
            }
        }
        assertFailsWith<Exception> { app.env.session.logout(alice) }
        app.store.namesUnavailable = false
        // carol was not current: her logout reads bob quietly and tells nothing
        app.env.session.logout(carol)
        flush(app)
        assertEquals(bob, app.env.session.current.username)
        app.env.session.start()
        flush(app)
        assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(bob)), seen.filterIsInstance<AppEvent.SessionChanged>())
    }

    @Test
    fun aLogoutOrLogoutAllAfterAFailedReadIsNotAnnouncedAgainByARecreation() = runTest {
        for (all in listOf(false, true)) {
            val app = appWithAccounts(this, separateIo = true)
            storeBoth(app, alice)
            app.env.session.start()
            flush(app)
            val seen = events(this, app)
            flush(app)
            app.store.namesUnavailable = true
            app.env.session.start()
            flush(app)
            assertNotNull(app.env.session.current.error)
            app.store.namesUnavailable = false
            if (all) app.env.session.logoutAll() else app.env.session.logout(alice)
            flush(app)
            app.env.session.start()
            flush(app)
            val expected = if (all) null else bob
            assertEquals(expected, app.env.session.current.username)
            assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(expected)), seen.filterIsInstance<AppEvent.SessionChanged>(), "all=$all")
        }
    }

    @Test
    fun aRecreationAfterAFailedImportReadAnnouncesTheImportedAccount() = runTest {
        val app = appWithAccounts(this, separateIo = true)
        val seen = events(this, app)
        app.env.session.start()
        flush(app)
        assertNull(app.env.session.current.username)
        // the import stored the account, and its re-read fails
        storeAlice(app)
        app.store.namesUnavailable = true
        app.env.session.startAfterImport()
        flush(app)
        assertNull(app.env.session.current.username)
        app.store.namesUnavailable = false
        app.env.session.start()
        flush(app)
        assertEquals(alice, app.env.session.current.username)
        assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(alice)), seen.filterIsInstance<AppEvent.SessionChanged>())
    }

    @Test
    fun aRotationDuringLogoutAnnouncesNothingOfItsOwn() = runTest {
        rotatingDuring(listOf(alice, bob), { it.logout(alice) }, bob)
    }

    @Test
    fun aRotationDuringASwitchAnnouncesNothingOfItsOwn() = runTest {
        rotatingDuring(listOf(alice, bob), { it.switchTo(bob) }, bob)
    }

    @Test
    fun aRotationDuringASignInNeitherAnnouncesNorHidesTheChange() = runTest {
        rotatingDuring(listOf(alice), { it.loginWithKey(bob, AppTestKeys.wif(bob, KeyRole.POSTING)) }, bob)
    }

    @Test
    fun masterPasswordLoginStoresEveryKeyAndTheSession() = runTest {
        val app = app(this)
        val session = app.env.session
        session.start()
        testScheduler.advanceUntilIdle()
        assertFalse(session.current.isLoggedIn)

        assertEquals(alice, session.loginWithKey("  @Spike-Alice ", AppTestKeys.PASSWORD))
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AuthType.MASTER_PASSWORD, stored.authType)
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), stored.postingKey)
        assertEquals("access-$alice", stored.accessToken)
        assertEquals(alice, session.current.username)
        assertEquals(listOf(alice), session.current.accounts.map { it.username })
        assertTrue(session.current.accounts.single().isCurrent)
        assertFalse(session.current.isSigningIn)
    }

    @Test
    fun keyLoginErrorsUseRnCopyAndStoreNothing() = runTest {
        val app = app(this)
        val session = app.env.session
        val wrong = assertFailsWith<AppException> { session.loginWithKey(alice, "not the password") }
        assertEquals(AppError.INVALID_CREDENTIALS, wrong.error.message)
        assertEquals(AppError.INVALID_CREDENTIALS, session.current.error?.message)
        val stranger = PrivateKey.fromSeed("app-layer-stranger-throwaway").toWif()
        assertEquals(AppError.INVALID_CREDENTIALS, assertFailsWith<AppException> { session.loginWithKey(alice, stranger) }.error.message)
        assertEquals(AppError.INVALID_USERNAME, assertFailsWith<AppException> { session.loginWithKey("nobody-here", AppTestKeys.PASSWORD) }.error.message)
        assertEquals(AppError.INVALID_USERNAME, assertFailsWith<AppException> { session.loginWithKey("A", AppTestKeys.PASSWORD) }.error.message)
        assertEquals(AppError.MEMO_KEY_ONLY, assertFailsWith<AppException> { session.loginWithKey(alice, AppTestKeys.wif(alice, KeyRole.MEMO)) }.error.message)
        assertTrue(KeyVault(app.store).usernames().isEmpty())
        assertFalse(session.current.isLoggedIn)
    }

    @Test
    fun networkFailureIsRetryable() = runTest {
        val app = TestApp(this) { HttpResult(503, "") }
        val e = assertFailsWith<AppException> { app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD) }
        assertEquals(ErrorKind.NETWORK, e.error.kind)
        assertTrue(e.error.retryable)
        assertFalse(app.env.session.current.isSigningIn)
    }

    @Test
    fun tokenEndpointDownFailsTheLoginWithoutStoring() = runTest {
        val app = app(this)
        app.authBackend.down = true
        assertEquals(ErrorKind.NETWORK, assertFailsWith<AppException> { app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD) }.error.kind)
        assertTrue(KeyVault(app.store).usernames().isEmpty())
    }

    @Test
    fun secondAccountSwitchAndPerAccountLogout() = runTest {
        val app = app(this)
        val seen = events(this, app)
        val session = app.env.session
        session.loginWithKey(alice, AppTestKeys.PASSWORD)
        session.loginWithKey(bob, AppTestKeys.wif(bob, KeyRole.POSTING))
        testScheduler.advanceUntilIdle()
        assertEquals(bob, session.current.username)
        assertEquals(listOf(alice, bob), session.current.accounts.map { it.username })
        assertEquals(LoginMethod.KEY, session.current.currentAccount?.method)

        val voted = PostKey("x", "y")
        app.env.localVotes.set(voted, LocalVote(10000, false))
        session.switchTo(alice)
        testScheduler.advanceUntilIdle()
        assertEquals(alice, session.current.username)
        assertNull(app.env.localVotes.get(voted), "the previous account's votes are gone")
        assertEquals(listOf<String?>(alice, bob, alice), seen.filterIsInstance<AppEvent.SessionChanged>().map { it.username })

        // a non-current account: the current one stays
        session.logout(bob)
        assertEquals(alice, session.current.username)
        assertNull(KeyVault(app.store).account(bob))
        session.logout(alice)
        assertNull(session.current.username)
        assertTrue(session.current.accounts.isEmpty())
        assertTrue(KeyVault(app.store).usernames().isEmpty())
    }

    @Test
    fun signingInAnotherAccountForgetsThePreviousAccountsState() = runTest {
        val app = app(this)
        val session = app.env.session
        session.loginWithKey(alice, AppTestKeys.PASSWORD)
        val voted = PostKey("x", "y")
        app.env.localVotes.set(voted, LocalVote(10000, false))
        // the same account again (re-login after an expired session) keeps what it did
        session.loginWithKey(alice, AppTestKeys.PASSWORD)
        assertEquals(LocalVote(10000, false), app.env.localVotes.get(voted))
        // another account: alice's optimistic votes and drafts are gone
        session.loginWithKey(bob, AppTestKeys.PASSWORD)
        assertNull(app.env.localVotes.get(voted))
    }

    @Test
    fun aVoteIsNeverSignedAfterTheSessionChangedInTheSameTurn() = runTest {
        val app = app(this)
        val session = app.env.session
        session.loginWithKey(alice, AppTestKeys.PASSWORD)
        session.loginWithKey(bob, AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        app.env.votes.vote("author", "permlink", 10000) // as bob
        // a switch lands before the vote's coroutine ran (same main-loop turn): the vote is dropped
        app.env.votes.reset()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "never signed as the next account")

        // a vote that did start signs as the account that tapped it, even if a switch follows
        app.env.votes.vote("author", "permlink", 10000)
        testScheduler.runCurrent()
        session.switchTo(alice)
        testScheduler.advanceUntilIdle()
        val op = app.broadcasts().single().params.toString()
        assertTrue("\"voter\":\"$bob\"" in op, op)
    }

    @Test
    fun logoutOfTheCurrentAccountMovesToTheNext() = runTest {
        val app = app(this)
        val session = app.env.session
        session.loginWithKey(alice, AppTestKeys.PASSWORD)
        session.loginWithKey(bob, AppTestKeys.PASSWORD)
        session.signOut()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, session.current.username)
        assertEquals(alice, KeyVault(app.store).currentUsername())
    }

    @Test
    fun theLockStaysWhileAnyAccountRemainsAndGoesOffAfterTheLast() = runTest {
        val app = app(this)
        val session = app.env.session
        session.loginWithKey(alice, AppTestKeys.PASSWORD)
        session.loginWithKey(bob, AppTestKeys.PASSWORD)
        lock(app).setPin("1234")
        session.logout(bob)
        assertTrue(lock(app).isEnabled(), "an account remains: the lock stays")
        session.logout(alice)
        assertFalse(lock(app).isEnabled(), "the last account is gone: the lock goes off")
    }

    @Test
    fun logoutAllRemovesEveryAccountSessionAndTheLock() = runTest {
        val app = app(this)
        val seen = events(this, app)
        val session = app.env.session
        session.loginWithKey(alice, AppTestKeys.PASSWORD)
        session.loginWithKey(bob, AppTestKeys.PASSWORD)
        lock(app).setPin("1234")
        session.logoutAll()
        // advanceUntilIdle leaves background work (the event collector) alone; runCurrent delivers it
        testScheduler.runCurrent()
        assertTrue(KeyVault(app.store).usernames().isEmpty())
        assertTrue(app.store.entries.keys.none { it.startsWith("auth.session.") })
        assertFalse(lock(app).isEnabled())
        assertNull(session.current.username)
        assertNull((seen.last() as AppEvent.SessionChanged).username, seen.toString())
    }

    @Test
    fun logoutDropsAHeldActiveKey() = runTest {
        val app = app(this)
        app.env.session.loginWithKey(alice, AppTestKeys.wif(alice, KeyRole.POSTING))
        app.env.session.loginWithKey(bob, AppTestKeys.wif(bob, KeyRole.POSTING))
        // a key held for alice while bob is current: logging alice out drops it too
        app.env.signing.tempActiveKey.set(alice, AppTestKeys.wif(alice, KeyRole.ACTIVE))
        app.env.session.logout(alice)
        assertFalse(app.env.signing.tempActiveKey.isHeld)
    }

    @Test
    fun hiveSignerCodeLogin() = runTest {
        val app = app(this)
        assertEquals(alice, app.env.session.loginWithHiveSignerCode("hs-code-$alice"))
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AuthType.HIVE_SIGNER, stored.authType)
        assertNull(stored.postingKey)
        assertEquals(LoginMethod.HIVESIGNER, app.env.session.current.currentAccount?.method)
    }

    @Test
    fun hiveAuthLoginWaitsForTheWalletThenStoresTheSession() = runTest {
        val app = app(this)
        val session = app.env.session
        session.startHiveAuth("@Spike-Alice")
        testScheduler.runCurrent()
        val waiting = session.current.hiveAuth!!
        assertEquals(HiveAuthStep.WAITING, waiting.step)
        assertTrue(waiting.link!!.startsWith("has://auth_req/"))
        assertTrue(waiting.openWallet, "the wallet opens by itself once")
        session.walletOpened()
        assertFalse(session.current.hiveAuth!!.openWallet, "and never again for this request")
        assertNull(session.current.username, "nothing is stored before the wallet approves")

        app.relay.approveAuth(waiting.link!!, alice, AppTestKeys.key(alice, KeyRole.POSTING))
        testScheduler.runCurrent()
        assertEquals(HiveAuthStep.SUCCESS, session.current.hiveAuth!!.step)
        assertEquals(alice, session.current.username)
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AuthType.HIVE_AUTH, stored.authType)
        assertEquals("has-token-$alice", stored.hiveAuthToken)
        assertNotNull(stored.hiveAuthKey)
        session.cancelHiveAuth()
        assertNull(session.current.hiveAuth)
    }

    @Test
    fun hiveAuthRejectionAndCancel() = runTest {
        val app = app(this)
        val session = app.env.session
        session.startHiveAuth(alice)
        testScheduler.runCurrent()
        app.relay.rejectAuth(session.current.hiveAuth!!.link!!)
        testScheduler.runCurrent()
        assertEquals(HiveAuthStep.FAILED, session.current.hiveAuth!!.step)
        assertEquals(ErrorKind.AUTH, session.current.hiveAuth!!.error!!.kind)

        session.startHiveAuth(alice)
        testScheduler.runCurrent()
        session.cancelHiveAuth()
        testScheduler.advanceUntilIdle()
        assertNull(session.current.hiveAuth)
        assertTrue(KeyVault(app.store).usernames().isEmpty())
    }

    @Test
    fun startRefreshesTheSessionAndFlagsAnExpiredOne() = runTest {
        val app = app(this)
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        val before = app.authBackend.sent.size
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertTrue(app.authBackend.sent.drop(before).any { it.body!!.contains("refresh-$alice") }, "RN refreshes at start")
        assertNull(app.env.session.current.expiredSession)

        // refresh refused and the token expired: ask to verify the login
        app.authBackend.refuse = true
        app.now += 8L * 24 * 60 * 60 * 1000
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, app.env.session.current.expiredSession)
        app.env.session.requestLogin(alice)
        assertEquals(alice, app.env.session.current.loginPrefill)
        assertNull(app.env.session.current.expiredSession)
    }

    @Test
    fun aKeyAccountWithoutATokenSignsAFreshCodeAtStart() = runTest {
        val app = app(this)
        KeyVault(app.store).apply {
            save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING)))
            setCurrentUsername(alice)
        }
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertEquals("access-$alice", KeyVault(app.store).account(alice)!!.accessToken)
    }

    @Test
    fun startingTwiceNeverRunsTwoLoadsAtOnceAndCountsOneSession() = runTest {
        val app = app(this)
        KeyVault(app.store).apply {
            save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING)))
            setCurrentUsername(alice)
        }
        // both platforms start the session, and the app lock starts it again after a migration
        app.env.session.start()
        app.env.session.start()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, app.env.session.current.username)
        assertEquals(1, app.authBackend.sent.size, "one load at a time: the fresh session code is exchanged once, not once per call")
        assertEquals("1", app.env.prefs.getString(AppRating.SESSIONS), "one app start is one session")
    }

    @Test
    fun aStartWhileOneRunsReadsTheVaultAgainAfterwards() = runTest {
        val app = app(this)
        val vault = KeyVault(app.store)
        // alice has no token yet: the first load is held in its token exchange
        vault.save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING)))
        vault.setCurrentUsername(alice)
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        app.authBackend.gate = { held.await() }
        app.env.session.start()
        testScheduler.runCurrent()
        assertEquals(alice, app.env.session.current.username)
        // the vault changes while the first load is on its way (a migration finished), and start is called again
        vault.save(VaultAccount(bob, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(bob, KeyRole.POSTING), accessToken = "t"))
        vault.setCurrentUsername(bob)
        app.env.session.start()
        testScheduler.runCurrent()
        assertEquals(alice, app.env.session.current.username, "no second load runs beside the first")
        held.complete(Unit)
        app.authBackend.gate = null
        testScheduler.advanceUntilIdle()
        assertEquals(bob, app.env.session.current.username, "the last state comes from the last read")
        assertEquals(listOf(alice, bob), app.env.session.current.accounts.map { it.username })
        // and a later start, with nothing running, loads again
        vault.setCurrentUsername(alice)
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, app.env.session.current.username)
    }

    @Test
    fun linkLoginsGoThroughTheTokenEndpoint() = runTest {
        val app = app(this)
        app.env.session.loginWithAuthTransfer(AuthLink.AuthTransfer(alice, "planted-access", "refresh-$alice", 100))
        assertEquals("access-$alice", KeyVault(app.store).account(alice)!!.accessToken, "a link never plants its own token")

        val mismatch = AuthLink.AuthTransfer(bob, "x", "refresh-$alice", 100)
        assertEquals(ErrorKind.AUTH, assertFailsWith<AppException> { app.env.session.loginWithAuthTransfer(mismatch) }.error.kind)
        assertNull(KeyVault(app.store).account(bob))
    }

    @Test
    fun postLoginHooksRunAfterEverySignIn() = runTest {
        val app = app(this)
        val ran = mutableListOf<String>()
        app.env.postLoginHooks += PostLoginHook { ran += it }
        app.env.postLoginHooks += PostLoginHook { throw IllegalStateException("prefetch failed") }
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(alice), ran)
        assertEquals(alice, app.env.session.current.username)
    }
}
