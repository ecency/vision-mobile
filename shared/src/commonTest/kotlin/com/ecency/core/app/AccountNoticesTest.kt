package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthException
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the user is told when the storage rule, a changed key or a shared key leaves an account with less than expected. */
class AccountNoticesTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    /** A node whose accounts have the given public key per role (default: the test password's own). */
    private fun TestScope.app(keys: (user: String, role: KeyRole) -> String = { u, r -> AppTestKeys.pub(u, r) }): TestApp = TestApp(this) { req ->
        if (req.method == "condenser_api.get_accounts") {
            val found = listOf(alice, bob).filter { "\"$it\"" in req.body }
            fun auth(user: String, role: KeyRole) = """{"weight_threshold":1,"account_auths":[],"key_auths":[["${keys(user, role)}",1]]}"""
            FakeTransport.result(req, found.joinToString(",", "[", "]") { u ->
                """{"name":"$u","owner":${auth(u, KeyRole.OWNER)},"active":${auth(u, KeyRole.ACTIVE)},"posting":${auth(u, KeyRole.POSTING)},"memo_key":"${keys(u, KeyRole.MEMO)}"}"""
            })
        } else HttpResult(500, "")
    }

    private fun TestScope.start(app: TestApp) {
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
    }

    /** An entry as an earlier build wrote it for an owner-key sign-in: the owner key and a token, nothing else. */
    private fun ownerOnlyEntry(app: TestApp, user: String, authType: String = "owner") = app.store.putString(
        "account.$user",
        """{"v":1,"username":"$user","authType":"$authType","ownerKey":"${AppTestKeys.wif(user, KeyRole.OWNER)}","accessToken":"t","hiveAuthExpiry":0}""",
    )

    private val stranger = com.ecency.core.hive.PrivateKey.fromSeed("ecency-native-notices-test-stranger").publicKey().toString()

    // ---- (a) an account left without a signing key ----

    @Test
    fun anAccountTheCleaningLeftWithoutASigningKeyIsToldOnceNamingIt() = runTest {
        val app = app()
        ownerOnlyEntry(app, alice)
        KeyVault(app.store).setCurrentUsername(alice)
        start(app)
        val notice = app.env.accountNotices.current.notice!!
        assertEquals(AccountNoticeKind.NO_SIGNING_KEY, notice.kind)
        assertEquals(alice, notice.account)
        assertEquals("Sign in again as @spike-alice", notice.title)
        assertTrue("@spike-alice has no posting or active key" in notice.body)
        assertTrue("Manage Authorities (Import Private)" in notice.body && "Sign in again" in notice.body)
        assertFalse(AppTestKeys.wif(alice, KeyRole.OWNER) in notice.toString())
        assertEquals(listOf(alice), app.env.accountNotices.pendingNoSigningKey(), "remembered until it was dismissed")
        assertEquals(alice, app.env.session.current.username, "still signed in to Ecency")

        app.env.accountNotices.dismiss()
        assertNull(app.env.accountNotices.current.notice)
        assertTrue(app.env.accountNotices.pendingNoSigningKey().isEmpty())
        assertNull(app.env.prefs.getString(AccountNoticesModel.PENDING))
        // the next start of the app, over the same vault and preferences: the entry is still keyless, and it is not told again
        val next = nextStart(app)
        assertNull(next.env.accountNotices.current.notice, "dismissed once, not shown at the next start")
        assertTrue(next.env.accountNotices.pendingNoSigningKey().isEmpty())
    }

    /** The app started again: a new environment over the same vault entries and the same device preferences. */
    private fun TestScope.nextStart(earlier: TestApp, prepare: (TestApp) -> Unit = {}): TestApp {
        val next = app()
        next.store.entries.putAll(earlier.store.entries)
        next.env.prefs = earlier.env.prefs
        prepare(next)
        start(next)
        return next
    }

    @Test
    fun aNameWhoseEntryCouldNotBeReadStaysPendingAndIsToldAtALaterStart() = runTest {
        val app = app()
        KeyVault(app.store).save(VaultAccount(alice, AuthType.OWNER_KEY, accessToken = "t"))
        KeyVault(app.store).save(VaultAccount(bob, AuthType.OWNER_KEY, accessToken = "t"))
        KeyVault(app.store).setCurrentUsername(alice)
        app.env.prefs.putString(AccountNoticesModel.PENDING, "$alice,$bob")
        // bob's entry cannot be read at this start (the secure storage answers with an error for it)
        app.store.failingReads["account.$bob"] = com.ecency.core.secure.SecureStoreException.Unavailable(-25308)
        start(app)
        assertEquals(alice, app.env.accountNotices.current.notice?.account)
        assertEquals(0, app.env.accountNotices.current.queued, "no notice for an account that was not read")
        assertEquals(setOf(alice, bob), app.env.accountNotices.pendingNoSigningKey().toSet(), "a failed read drops no name")
        app.env.accountNotices.dismiss()
        assertEquals(listOf(bob), app.env.accountNotices.pendingNoSigningKey())
        // the storage answers again at the next start: bob is told now
        val next = nextStart(app)
        assertEquals(bob, next.env.accountNotices.current.notice?.account)
        assertEquals(AccountNoticeKind.NO_SIGNING_KEY, next.env.accountNotices.current.notice?.kind)
    }

    @Test
    fun aNoticeTheUserNeverSawIsShownAtTheNextStart() = runTest {
        val app = app()
        // the earlier start cleaned the entry and ended before the notice was dismissed
        KeyVault(app.store).save(VaultAccount(alice, AuthType.OWNER_KEY, accessToken = "t"))
        KeyVault(app.store).setCurrentUsername(alice)
        app.env.prefs.putString(AccountNoticesModel.PENDING, alice)
        start(app)
        assertEquals(alice, app.env.accountNotices.current.notice?.account)
        assertEquals(AccountNoticeKind.NO_SIGNING_KEY, app.env.accountNotices.current.notice?.kind)
    }

    @Test
    fun accountsThatKeepASigningKeyOrNeverHadKeysGetNoNotice() = runTest {
        val app = app()
        // an old master entry: cleaned, but it keeps posting and active
        app.store.putString(
            "account.$alice",
            """{"v":1,"username":"$alice","authType":"master","masterPassword":"${AppTestKeys.PASSWORD}","postingKey":"${AppTestKeys.wif(alice, KeyRole.POSTING)}",""" +
                """"ownerKey":"${AppTestKeys.wif(alice, KeyRole.OWNER)}","accessToken":"t","hiveAuthExpiry":0}""",
        )
        // a HiveSigner account that happened to carry an owner key field: it signs through HiveSigner, not with keys
        ownerOnlyEntry(app, bob, authType = "hs")
        KeyVault(app.store).setCurrentUsername(alice)
        start(app)
        assertNull(app.env.accountNotices.current.notice)
        assertTrue(app.env.accountNotices.pendingNoSigningKey().isEmpty())
        // and a stale pending name for an account that has keys (or is gone) is dropped at the next start
        app.env.prefs.putString(AccountNoticesModel.PENDING, "$alice,nobody-here")
        val next = nextStart(app)
        assertNull(next.env.accountNotices.current.notice)
        assertTrue(next.env.accountNotices.pendingNoSigningKey().isEmpty(), "read as having a signing key, or as gone: forgotten")
        assertNull(next.env.prefs.getString(AccountNoticesModel.PENDING))
    }

    @Test
    fun noticesDoNotStackAndATapAnswersTheNoticeItWasShownFor() = runTest {
        val app = app { u, r -> if (r == KeyRole.POSTING) stranger else AppTestKeys.pub(u, r) }
        start(app)
        // the same sign-in twice: one notice of its kind for the account
        repeat(2) {
            app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
            testScheduler.advanceUntilIdle()
        }
        val first = app.env.accountNotices.current.notice!!
        assertEquals(AccountNoticeKind.PASSWORD_MISSING_ROLES, first.kind)
        assertEquals(0, app.env.accountNotices.current.queued, "not one per sign-in")
        // a tap for a notice that is no longer the one in front changes nothing
        app.env.accountNotices.dismiss(first.id + 1000)
        assertEquals(first.id, app.env.accountNotices.current.notice?.id)
        app.env.accountNotices.noSigningKey(listOf(bob))
        val stale = first.id
        app.env.accountNotices.dismiss(stale)
        assertEquals(bob, app.env.accountNotices.current.notice?.account, "the answered one went, the other stays")
        app.env.accountNotices.dismiss(stale)
        assertEquals(bob, app.env.accountNotices.current.notice?.account, "a second tap for the same notice does not take the next one")
    }

    @Test
    fun aHiveSignerSignInAsThatAccountVoidsItsKeylessNotice() = runTest {
        val app = app()
        ownerOnlyEntry(app, alice)
        ownerOnlyEntry(app, bob)
        KeyVault(app.store).setCurrentUsername(alice)
        start(app)
        assertEquals(setOf(alice, bob), app.env.accountNotices.pendingNoSigningKey().toSet())
        // alice signs in through HiveSigner: the entry is hers again, with a way to sign
        app.env.session.loginWithHiveSignerCode("hs-code-$alice")
        testScheduler.advanceUntilIdle()
        assertEquals(AuthType.HIVE_SIGNER, KeyVault(app.store).account(alice)?.authType, "a keyless key entry becomes a HiveSigner account: it can sign again")
        assertEquals(bob, app.env.accountNotices.current.notice?.account)
        assertEquals(0, app.env.accountNotices.current.queued)
        assertEquals(listOf(bob), app.env.accountNotices.pendingNoSigningKey())
    }

    @Test
    fun aNoticeGoesWhenItsAccountSignsInAgainLeavesOrTheDeviceIsWiped() = runTest {
        val app = app()
        ownerOnlyEntry(app, alice)
        ownerOnlyEntry(app, bob)
        KeyVault(app.store).setCurrentUsername(alice)
        start(app)
        assertEquals(1, app.env.accountNotices.current.queued)
        // alice signs in again with her active key: her notice is void
        app.env.session.loginWithKey(alice, AppTestKeys.wif(alice, KeyRole.ACTIVE))
        testScheduler.advanceUntilIdle()
        assertEquals(bob, app.env.accountNotices.current.notice?.account)
        assertEquals(listOf(bob), app.env.accountNotices.pendingNoSigningKey())
        // bob is logged out: nothing about him is left to show
        app.env.session.logout(bob)
        testScheduler.advanceUntilIdle()
        assertNull(app.env.accountNotices.current.notice)
    }

    // ---- (b) a master password that no longer matches every signing role ----

    @Test
    fun aPasswordThatNoLongerMatchesThePostingKeySaysSoAfterTheSignIn() = runTest {
        val app = app { u, r -> if (r == KeyRole.POSTING) stranger else AppTestKeys.pub(u, r) }
        start(app)
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        val stored = KeyVault(app.store).account(alice)!!
        assertNull(stored.postingKey)
        assertEquals(AppTestKeys.wif(alice, KeyRole.ACTIVE), stored.activeKey)
        val notice = app.env.accountNotices.current.notice!!
        assertEquals(AccountNoticeKind.PASSWORD_MISSING_ROLES, notice.kind)
        assertEquals("Some keys were not added", notice.title)
        assertEquals(
            "Your password no longer matches @spike-alice's posting key: it was changed on the account since the password was set. " +
                "Stored on this device: the active and memo key. Until you add the posting key under Settings, Manage Authorities (Import Private), " +
                "this app cannot post, vote or comment with keys for @spike-alice.",
            notice.body,
        )
        assertFalse(AppTestKeys.PASSWORD in notice.toString())
        app.env.accountNotices.dismiss()
        assertNull(app.env.accountNotices.current.notice)
    }

    @Test
    fun theReverseCaseNamesTheActiveKeyAndAFullMatchSaysNothing() = runTest {
        val app = app { u, r -> if (r == KeyRole.ACTIVE && u == alice) stranger else AppTestKeys.pub(u, r) }
        start(app)
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        val body = app.env.accountNotices.current.notice!!.body
        assertTrue("no longer matches @spike-alice's active key" in body && "send transfers or change account settings" in body)
        assertTrue("the posting and memo key" in body)
        app.env.accountNotices.dismiss()
        app.env.session.loginWithKey(bob, AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        assertNull(app.env.accountNotices.current.notice, "every role matched: nothing to say")
        app.env.session.loginWithKey(alice, AppTestKeys.wif(alice, KeyRole.POSTING))
        testScheduler.advanceUntilIdle()
        assertNull(app.env.accountNotices.current.notice, "a private key is not a password")
    }

    // ---- a typed key that is also the owner key ----

    @Test
    fun aKeySharedWithTheOwnerRoleIsKeptAndWarnedAboutAtSignIn() = runTest {
        val app = app { u, r -> if (r == KeyRole.ACTIVE) AppTestKeys.pub(u, KeyRole.OWNER) else AppTestKeys.pub(u, r) }
        start(app)
        app.env.session.loginWithKey(alice, AppTestKeys.wif(alice, KeyRole.OWNER))
        testScheduler.advanceUntilIdle()
        assertEquals(AppTestKeys.wif(alice, KeyRole.OWNER), KeyVault(app.store).account(alice)!!.activeKey, "kept, as the active key")
        val notice = app.env.accountNotices.current.notice!!
        assertEquals(AccountNoticeKind.KEY_SHARED_WITH_OWNER, notice.kind)
        assertEquals("This key also controls the owner role", notice.title)
        assertTrue("@spike-alice uses the same key for its owner role and its active role" in notice.body)
        assertTrue("Give the active role a key of its own" in notice.body)
    }

    @Test
    fun withTheSwitchOnTheSharedKeyIsRefusedAtSignInAndNothingIsStored() = runTest {
        val app = app { u, r -> if (r == KeyRole.ACTIVE) AppTestKeys.pub(u, KeyRole.OWNER) else AppTestKeys.pub(u, r) }
        start(app)
        com.ecency.core.auth.KeyLogin.refuseKeySharedWithOwner = true
        try {
            val failure = runCatching { app.env.session.loginWithKey(alice, AppTestKeys.wif(alice, KeyRole.OWNER)) }.exceptionOrNull() as AppException
            assertEquals(AppError.KEY_SHARED_WITH_OWNER_REFUSED, failure.error.message)
            assertTrue(failure.cause is AuthException.KeySharedWithOwner)
            assertNull(KeyVault(app.store).account(alice))
            assertNull(app.env.accountNotices.current.notice)
        } finally {
            com.ecency.core.auth.KeyLogin.refuseKeySharedWithOwner = false
        }
    }

    @Test
    fun theKeysScreenWarnsWhileTheAccountSharesItsOwnerKey() = runTest {
        val app = app { u, r -> if (r == KeyRole.ACTIVE || r == KeyRole.POSTING) AppTestKeys.pub(u, KeyRole.OWNER) else AppTestKeys.pub(u, r) }
        val vault = KeyVault(app.store)
        vault.save(VaultAccount(alice, AuthType.ACTIVE_KEY, activeKey = AppTestKeys.wif(alice, KeyRole.OWNER)))
        vault.setCurrentUsername(alice)
        PinLock(vault, 16).setPin("1234")
        start(app)
        fun type() { "1234".forEach { app.env.appLock.enterDigit(it.digitToInt()) }; testScheduler.advanceUntilIdle() }
        type()
        val model = app.env.backupKeysModel()
        model.start()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.sharedOwnerWarning, "nothing before the keys show")
        type()
        assertEquals(
            "This account uses the same key for its owner role and its active and posting role. Whoever has that key controls the whole account. " +
                "Give the active and posting role a key of its own and keep the owner key offline.",
            model.current.sharedOwnerWarning,
        )
        // an account with a key per role has no warning
        val plain = app()
        KeyVault(plain.store).apply { save(VaultAccount(bob, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(bob, KeyRole.POSTING))); setCurrentUsername(bob); PinLock(this, 16).setPin("1234") }
        start(plain)
        "1234".forEach { plain.env.appLock.enterDigit(it.digitToInt()) }; testScheduler.advanceUntilIdle()
        val other = plain.env.backupKeysModel()
        other.start(); testScheduler.advanceUntilIdle()
        "1234".forEach { plain.env.appLock.enterDigit(it.digitToInt()) }; testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.SHOWN, other.current.phase)
        assertNull(other.current.sharedOwnerWarning)
    }

    // ---- (c) the error at first use ----

    @Test
    fun aMissingKeyErrorSaysWhereToAddTheKey() {
        assertEquals(
            "posting key is required to perform this transaction. Add it under Settings, Manage Authorities (Import Private), or sign in again with it.",
            AppError.from(AuthException.MissingKey(KeyRole.POSTING)).message,
        )
        assertTrue(AppError.from(AuthException.MissingKey(KeyRole.ACTIVE)).message.startsWith("active key is required to perform this transaction"))
    }
}
