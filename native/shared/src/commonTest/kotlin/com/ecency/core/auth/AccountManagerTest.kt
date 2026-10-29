package com.ecency.core.auth

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.InMemorySecureStore
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountManagerTest {
    private class Rig(scope: TestScope) {
        var http: (AuthHttpRequest) -> HttpResult = { FakeAuthHttp.tokens() }
        val secure = InMemorySecureStore()
        val vault = KeyVault(secure)
        val store = VaultAccountStore(vault, secure)
        val fake = FakeAuthHttp { http(it) }
        var now = 1_000_000L
        val chain = mutableMapOf(AuthGoldenVectors.USER to TestKeys.authorities())

        /** What the vault wrote for [username], as stored. */
        fun raw(username: String): String = secure.entries.getValue("account.$username").decodeToString()
        val manager = AccountManager(
            store,
            { chain[it] },
            EcencyTokenApi(fake, retryDelaysMillis = emptyList()),
            { now },
            StandardTestDispatcher(scope.testScheduler),
        )

        fun other(name: String) {
            chain[name] = TestKeys.authorities().copy(username = name)
        }

        fun sentCode(index: Int = fake.sent.lastIndex) =
            Json.parseToJsonElement(fake.sent[index].body!!).jsonObject["code"]!!.jsonPrimitive.content
    }

    @Test
    fun keyLoginStoresKeysSessionAndSelectsTheAccount() = runTest {
        val rig = Rig(this)
        val summary = rig.manager.loginWithKey("@Spike-User", AuthGoldenVectors.PASSWORD)
        assertEquals(AuthType.MASTER_PASSWORD, summary.authType)
        assertEquals(setOf(KeyRole.POSTING, KeyRole.ACTIVE, KeyRole.MEMO), summary.storedKeys, "never the owner key")
        assertTrue(summary.isCurrent && summary.hasAccessToken)

        // The exchanged code is a HiveSigner code signed by the posting key: the least powerful key that works.
        val decoded = HiveSignerMessages.decode(rig.sentCode())
        assertEquals("code", decoded.type)
        assertEquals(TestKeys.pub(KeyRole.POSTING), HiveSignerMessages.recoverSigner(decoded.unsignedJson, decoded.signatures.single()).toString())

        val stored = rig.vault.account("spike-user")!!
        assertEquals(TestKeys.wif(KeyRole.POSTING), stored.postingKey)
        assertEquals(TestKeys.wif(KeyRole.ACTIVE), stored.activeKey)
        assertEquals(TestKeys.wif(KeyRole.MEMO), stored.memoKey)
        // neither the password nor the owner key it derives is anywhere in what the vault wrote
        val written = rig.raw("spike-user")
        assertFalse(AuthGoldenVectors.PASSWORD in written)
        assertFalse(TestKeys.wif(KeyRole.OWNER) in written)
        assertFalse("masterPassword" in written || "ownerKey" in written)
        assertEquals("access-1", stored.accessToken)
        assertEquals("spike-user", rig.vault.currentUsername())
        assertEquals(TokenSession("spike-user", "access-1", "refresh-1", 1_000_000 + 604_800_000), rig.store.session("spike-user"))
        assertEquals("access-1", rig.manager.code("spike-user"))
    }

    @Test
    fun keyLoginFailuresStoreNothing() = runTest {
        val rig = Rig(this)
        assertFailsWith<AuthException.UnknownAccount> { rig.manager.loginWithKey("nobody-here", AuthGoldenVectors.PASSWORD) }
        assertFailsWith<AuthException.InvalidUsername> { rig.manager.loginWithKey("x", AuthGoldenVectors.PASSWORD) }
        assertFailsWith<AuthException.WrongPassword> { rig.manager.loginWithKey("spike-user", "wrong") }
        rig.http = { HttpResult(500, "") }
        assertFailsWith<AuthException.TokenExchangeFailed> { rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING)) }
        assertFalse(rig.manager.hasAnyAccount())
        assertTrue(rig.secure.entries.isEmpty())
    }

    @Test
    fun appLockAppliesWhileAnyAccountRemains() = runTest {
        val rig = Rig(this)
        rig.other("second-user")
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(username = "second-user") }
        rig.manager.loginWithKey("second-user", TestKeys.wif(KeyRole.POSTING))
        assertEquals("second-user", rig.manager.current()?.username)

        // Signing out of the current account moves to the remaining one: the lock must stay on.
        assertEquals("spike-user", rig.manager.logout()?.username)
        assertTrue(rig.manager.hasAnyAccount())
        assertNull(rig.store.session("second-user"))
        assertFalse(rig.secure.entries.keys.any { it.endsWith("second-user") })

        assertNull(rig.manager.logout())
        assertFalse(rig.manager.hasAnyAccount())
        assertNull(rig.manager.current())
    }

    @Test
    fun switchAndRemove() = runTest {
        val rig = Rig(this)
        rig.other("second-user")
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(username = "second-user") }
        rig.manager.loginWithKey("second-user", TestKeys.wif(KeyRole.POSTING))
        assertEquals("spike-user", rig.manager.switchTo("spike-user").username)
        assertFailsWith<AuthException.UnknownStoredAccount> { rig.manager.switchTo("third-user") }
        assertEquals("spike-user", rig.manager.remove("second-user")?.username)
        assertEquals(listOf("spike-user"), rig.manager.accounts().map { it.username })
        rig.manager.logoutAll()
        assertFalse(rig.manager.hasAnyAccount())
        assertTrue(rig.secure.entries.keys.none { it.startsWith("account.") || it.startsWith("auth.session.") })
    }

    @Test
    fun hiveSignerCodeLoginUsesTheExchangedUsername() = runTest {
        val rig = Rig(this)
        val s = rig.manager.loginWithHiveSignerCode("oauth-code")
        assertEquals(AuthType.HIVE_SIGNER, s.authType)
        assertEquals(LoginMethod.HIVESIGNER, s.method)
        assertEquals(emptySet(), s.storedKeys)
        assertEquals("oauth-code", rig.sentCode())
        rig.http = { FakeAuthHttp.tokens(username = "ghost-user") }
        assertFailsWith<AuthException.UnknownAccount> { rig.manager.loginWithHiveSignerCode("c2") }
    }

    private fun transferLink(username: String = "spike-user") =
        AuthLinks.parse("ecency://auth-transfer?username=$username&accessToken=planted&refreshToken=r0&expiresIn=60") as AuthLink.AuthTransfer

    @Test
    fun authTransferRedeemsTheRefreshTokenBeforeStoring() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithAuthTransfer(transferLink())
        // The handed-over refresh token is redeemed; the fresh session is stored, never the link's access token.
        assertEquals("r0", rig.sentCode())
        assertEquals(TokenSession("spike-user", "access-1", "refresh-1", 1_000_000 + 604_800_000), rig.store.session("spike-user"))
        assertEquals(AuthType.HIVE_SIGNER, rig.vault.account("spike-user")!!.authType)
    }

    @Test
    fun authTransferForAnotherAccountIsRefused() = runTest {
        val rig = Rig(this)
        rig.http = { FakeAuthHttp.tokens(username = "attacker") }
        val e = assertFailsWith<AuthException.AccountMismatch> { rig.manager.loginWithAuthTransfer(transferLink()) }
        assertEquals("spike-user", e.expected)
        assertEquals("attacker", e.actual)
        assertTrue(rig.secure.entries.isEmpty())
        // Unredeemable tokens store nothing either.
        rig.http = { HttpResult(401, """{"error":"invalid_grant"}""") }
        assertFailsWith<AuthException.TokenExchangeFailed> { rig.manager.loginWithAuthTransfer(transferLink()) }
        assertFalse(rig.manager.hasAnyAccount())
    }

    @Test
    fun authTransferNeverWipesAStoredKeyAccount() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", AuthGoldenVectors.PASSWORD)
        assertEquals(AuthType.MASTER_PASSWORD, rig.manager.storedAccount("spike-user")?.authType)
        rig.http = { FakeAuthHttp.tokens(access = "access-2", refresh = "refresh-2") }
        val merged = rig.manager.loginWithAuthTransfer(transferLink())
        assertEquals(AuthType.MASTER_PASSWORD, merged.authType)
        assertEquals(setOf(KeyRole.POSTING, KeyRole.ACTIVE, KeyRole.MEMO), merged.storedKeys)
        val stored = rig.vault.account("spike-user")!!
        assertEquals(TestKeys.wif(KeyRole.ACTIVE), stored.activeKey)
        assertEquals("access-2", stored.accessToken)
        assertEquals("refresh-2", rig.store.session("spike-user")!!.refreshToken)
        assertNull(rig.manager.storedAccount("other-user"))
    }

    @Test
    fun hiveSignerCodeLoginKeepsAStoredKeyAccountsKeys() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(access = "access-2") }
        val s = rig.manager.loginWithHiveSignerCode("oauth-code")
        assertEquals(AuthType.POSTING_KEY, s.authType)
        assertEquals(TestKeys.wif(KeyRole.POSTING), rig.vault.account("spike-user")!!.postingKey)
        assertEquals("access-2", rig.vault.account("spike-user")!!.accessToken)
    }

    @Test
    fun webCodeLoginIsBoundToTheLinkAccount() = runTest {
        val rig = Rig(this)
        val v = AuthGoldenVectors.messages.first { it.type == "code" }
        rig.now = v.millis
        val ok = AuthLink.CodeLogin("spike-user", v.code)
        assertEquals("spike-user", rig.manager.loginWithWebCode(ok).username)
        assertEquals(v.code, rig.sentCode())

        // Signed for spike-user, linked as someone else: refused before any exchange.
        rig.fake.sent.clear()
        rig.other("other-user")
        assertFailsWith<AuthException.InvalidLink> { rig.manager.loginWithWebCode(AuthLink.CodeLogin("other-user", v.code)) }
        assertTrue(rig.fake.sent.isEmpty())
        // The exchange must also answer for the link account.
        rig.http = { FakeAuthHttp.tokens(username = "other-user") }
        assertFailsWith<AuthException.AccountMismatch> { rig.manager.loginWithWebCode(ok) }
        assertFailsWith<AuthException.AccountMismatch> { rig.manager.loginWithHiveSignerCode("c", "spike-user") }
        // Expired, future and malformed codes.
        rig.http = { FakeAuthHttp.tokens() }
        rig.now = v.millis + 7 * 86_400_000L + 1
        assertFailsWith<AuthException.InvalidLink> { rig.manager.loginWithWebCode(ok) }
        rig.now = v.millis - 60 * 60_000 - 1
        assertFailsWith<AuthException.InvalidLink> { rig.manager.loginWithWebCode(ok) }
        assertFailsWith<AuthException.InvalidLink> { rig.manager.loginWithWebCode(AuthLink.CodeLogin("spike-user", null)) }
        assertFailsWith<AuthException.InvalidUsername> { rig.manager.loginWithWebCode(AuthLink.CodeLogin(null, v.code)) }
    }

    @Test
    fun corruptedEntriesKeepTheLockOnAndAreRemovedByLogoutAll() = runTest {
        val rig = Rig(this)
        rig.secure.putString("account.broken-user", "not json")
        assertTrue(rig.manager.accounts().isEmpty())
        assertTrue(rig.manager.hasAnyAccount())
        rig.manager.logoutAll()
        assertFalse(rig.manager.hasAnyAccount())
        assertTrue(rig.secure.entries.keys.none { it.startsWith("account.") })
    }

    @Test
    fun hiveAuthLoginStoresTheSession() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithHiveAuth(HiveAuthLogin("spike-user", "code", "auth-key", "has-token", 5_000_000))
        val a = rig.vault.account("spike-user")!!
        assertEquals(AuthType.HIVE_AUTH, a.authType)
        assertEquals("auth-key", a.hiveAuthKey)
        assertEquals("has-token", a.hiveAuthToken)
        assertEquals(5_000_000, a.hiveAuthExpiry)
        rig.manager.updateHiveAuthSession("spike-user", null, 9_000_000)
        assertEquals("has-token", rig.vault.account("spike-user")!!.hiveAuthToken)
        assertEquals(9_000_000, rig.vault.account("spike-user")!!.hiveAuthExpiry)

        rig.http = { FakeAuthHttp.tokens(username = "someone-else") }
        assertFailsWith<AuthException.UnknownAccount> { rig.manager.loginWithHiveAuth(HiveAuthLogin("spike-user", "c", "k", null, 1)) }
    }

    @Test
    fun refreshFollowsRnRules() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))

        rig.http = { FakeAuthHttp.tokens(access = "access-2", refresh = "refresh-2", expiresIn = 10) }
        assertEquals("access-2", rig.manager.refreshSession("spike-user"))
        assertEquals("refresh-1", rig.sentCode())
        assertEquals(TokenSession("spike-user", "access-2", "refresh-2", rig.now + 10_000), rig.store.session("spike-user"))

        // A failed refresh keeps a token that is still valid, and reports an expired one.
        rig.http = { HttpResult(500, "") }
        assertEquals("access-2", rig.manager.refreshSession("spike-user"))
        rig.now += 11_000
        assertFailsWith<AuthException.SessionExpired> { rig.manager.refreshSession("spike-user") }
        assertNull(rig.manager.refreshSession("nobody-here"))
    }

    @Test
    fun refreshForAnotherAccountIsNeverStored() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(username = "attacker", access = "evil") }
        assertEquals("access-1", rig.manager.refreshSession("spike-user"))
        assertEquals("access-1", rig.vault.account("spike-user")!!.accessToken)
        rig.now += 604_800_001
        assertFailsWith<AuthException.SessionExpired> { rig.manager.refreshSession("spike-user") }
        assertEquals("access-1", rig.vault.account("spike-user")!!.accessToken)
    }

    @Test
    fun migratedAccountWithoutRefreshTokenKeepsItsToken() = runTest {
        val rig = Rig(this)
        rig.vault.save(VaultAccount("spike-user", AuthType.HIVE_SIGNER, accessToken = "legacy"))
        assertEquals("legacy", rig.manager.refreshSession("spike-user"))
        assertTrue(rig.fake.sent.isEmpty())
    }

    @Test
    fun keyAccountWithoutTokenGetsOne() = runTest {
        val rig = Rig(this)
        rig.vault.save(VaultAccount("spike-user", AuthType.POSTING_KEY, postingKey = TestKeys.wif(KeyRole.POSTING)))
        assertEquals("access-1", rig.manager.ensureKeySession("spike-user"))
        assertEquals("access-1", rig.vault.account("spike-user")!!.accessToken)
        assertEquals("access-1", rig.manager.ensureKeySession("spike-user"))
        assertEquals(1, rig.fake.sent.size)
        rig.vault.save(VaultAccount("other-user", AuthType.HIVE_SIGNER))
        assertNull(rig.manager.ensureKeySession("other-user"))
    }

    @Test
    fun ensureKeySessionNeverSignsWithTheMemoKeyOrABrokenKey() = runTest {
        val rig = Rig(this)
        rig.vault.save(VaultAccount("spike-user", AuthType.MEMO_KEY, memoKey = TestKeys.wif(KeyRole.MEMO)))
        assertNull(rig.manager.ensureKeySession("spike-user"))
        rig.vault.save(VaultAccount("spike-user", AuthType.POSTING_KEY, activeKey = "garbage", postingKey = TestKeys.wif(KeyRole.POSTING)))
        assertEquals("access-1", rig.manager.ensureKeySession("spike-user"))
        val decoded = HiveSignerMessages.decode(rig.sentCode())
        assertEquals(TestKeys.pub(KeyRole.POSTING), HiveSignerMessages.recoverSigner(decoded.unsignedJson, decoded.signatures.single()).toString())
        assertEquals(1, rig.fake.sent.size)
    }

    @Test
    fun anOwnerKeySignInIsRefusedAndStoresNothing() = runTest {
        val rig = Rig(this)
        assertFailsWith<AuthException.OwnerKeyOnly> { rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.OWNER)) }
        assertTrue(rig.vault.usernames().isEmpty())
        assertTrue(rig.fake.sent.isEmpty(), "no session is asked for")
        assertTrue(rig.secure.entries.values.none { TestKeys.wif(KeyRole.OWNER) in it.decodeToString() })
    }

    @Test
    fun nothingTheAccountManagerWritesEverHoldsAnOwnerKeyOrAMasterPassword() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", AuthGoldenVectors.PASSWORD)
        rig.manager.importKeys("spike-user", AuthGoldenVectors.PASSWORD)
        rig.manager.importKeys("spike-user", TestKeys.wif(KeyRole.ACTIVE))
        assertFailsWith<AuthException.OwnerKeyOnly> { rig.manager.importKeys("spike-user", TestKeys.wif(KeyRole.OWNER)) }
        rig.manager.refreshSession("spike-user")
        val everything = rig.secure.entries.values.joinToString("\n") { it.decodeToString() }
        assertFalse(AuthGoldenVectors.PASSWORD in everything)
        assertFalse(TestKeys.wif(KeyRole.OWNER) in everything)
        assertFalse("masterPassword" in everything || "ownerKey" in everything)
        assertTrue(TestKeys.wif(KeyRole.ACTIVE) in everything && TestKeys.wif(KeyRole.POSTING) in everything && TestKeys.wif(KeyRole.MEMO) in everything)
    }

    @Test
    fun ensureKeySessionSignsWithPostingFirstAndActiveOnlyWithoutIt() = runTest {
        val rig = Rig(this)
        rig.vault.save(VaultAccount("spike-user", AuthType.MASTER_PASSWORD, postingKey = TestKeys.wif(KeyRole.POSTING), activeKey = TestKeys.wif(KeyRole.ACTIVE)))
        rig.manager.ensureKeySession("spike-user")
        var decoded = HiveSignerMessages.decode(rig.sentCode())
        assertEquals(TestKeys.pub(KeyRole.POSTING), HiveSignerMessages.recoverSigner(decoded.unsignedJson, decoded.signatures.single()).toString())
        rig.vault.save(VaultAccount("spike-user", AuthType.ACTIVE_KEY, activeKey = TestKeys.wif(KeyRole.ACTIVE)))
        rig.manager.ensureKeySession("spike-user")
        decoded = HiveSignerMessages.decode(rig.sentCode(1))
        assertEquals(TestKeys.pub(KeyRole.ACTIVE), HiveSignerMessages.recoverSigner(decoded.unsignedJson, decoded.signatures.single()).toString())
    }

    // ---- a renewal racing a removal (RN storage/authWriteGuard, 4ebf0f3da4 b3102e1dd7 db5b539d43) ----

    /** Starts a renewal of spike-user's session and holds its token request open until [release] completes. */
    private suspend fun TestScope.renewalHeldOpen(rig: Rig, call: suspend () -> String?): Pair<kotlinx.coroutines.Deferred<String?>, kotlinx.coroutines.CompletableDeferred<Unit>> {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        rig.fake.beforeAnswer = { started.complete(Unit); release.await() }
        val renewal = async { call() }
        started.await()
        rig.fake.beforeAnswer = null
        return renewal to release
    }

    @Test
    fun aRenewalAnsweredAfterASignOutWritesNothing() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(access = "access-2", refresh = "refresh-2") }
        val (renewal, release) = renewalHeldOpen(rig) { rig.manager.refreshSession("spike-user") }
        rig.manager.logout()
        release.complete(Unit)
        assertNull(renewal.await(), "a token that was not stored is not handed out")
        assertNull(rig.vault.account("spike-user"))
        assertTrue(rig.secure.entries.keys.none { "spike-user" in it }, "no entry is written back: ${rig.secure.entries.keys}")
    }

    @Test
    fun aRenewalNeverWritesOverTheSameAccountSignedInAgain() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(access = "stale-access", refresh = "stale-refresh") }
        val (renewal, release) = renewalHeldOpen(rig) { rig.manager.refreshSession("spike-user") }
        rig.manager.logout()
        rig.http = { FakeAuthHttp.tokens(access = "fresh-access", refresh = "fresh-refresh") }
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(access = "stale-access", refresh = "stale-refresh") }
        release.complete(Unit)
        assertNull(renewal.await())
        assertEquals(TokenSession("spike-user", "fresh-access", "fresh-refresh", rig.now + 604_800_000), rig.store.session("spike-user"))
    }

    @Test
    fun logoutAllAndTheWipeVoidEveryRenewalInFlight() = runTest {
        for (wipe in listOf(false, true)) {
            val rig = Rig(this)
            rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
            rig.http = { FakeAuthHttp.tokens(access = "stale-access", refresh = "stale-refresh") }
            val (renewal, release) = renewalHeldOpen(rig) { rig.manager.refreshSession("spike-user") }
            if (wipe) {
                rig.vault.wipe()
                rig.manager.accountsWiped()
            } else {
                rig.manager.logoutAll()
            }
            rig.http = { FakeAuthHttp.tokens(access = "fresh-access", refresh = "fresh-refresh") }
            rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
            release.complete(Unit)
            assertNull(renewal.await(), "wipe=$wipe")
            assertEquals("fresh-refresh", rig.store.session("spike-user")?.refreshToken, "wipe=$wipe")
        }
    }

    @Test
    fun anotherAccountLeavingKeepsThisRenewal() = runTest {
        val rig = Rig(this)
        rig.other("other-user")
        rig.manager.loginWithKey("other-user", TestKeys.wif(KeyRole.POSTING))
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(access = "access-2", refresh = "refresh-2") }
        val (renewal, release) = renewalHeldOpen(rig) { rig.manager.refreshSession("spike-user") }
        rig.manager.remove("other-user")
        release.complete(Unit)
        assertEquals("access-2", renewal.await())
        assertEquals("refresh-2", rig.store.session("spike-user")?.refreshToken)
    }

    @Test
    fun aKeySessionSignedForAnAccountThatLeftIsNotStored() = runTest {
        val rig = Rig(this)
        rig.vault.save(VaultAccount("spike-user", AuthType.POSTING_KEY, postingKey = TestKeys.wif(KeyRole.POSTING)))
        rig.vault.setCurrentUsername("spike-user")
        rig.http = { FakeAuthHttp.tokens(access = "stale-access", refresh = "stale-refresh") }
        val (renewal, release) = renewalHeldOpen(rig) { rig.manager.ensureKeySession("spike-user") }
        rig.manager.logout()
        rig.http = { FakeAuthHttp.tokens(access = "fresh-access", refresh = "fresh-refresh") }
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        release.complete(Unit)
        assertNull(renewal.await())
        assertEquals("fresh-access", rig.vault.account("spike-user")?.accessToken)
        assertEquals("fresh-refresh", rig.store.session("spike-user")?.refreshToken)
    }

    @Test
    fun aFailedRenewalOfAnAccountThatLeftReportsNoExpiredSession() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.now += 604_800_001 // expired: a failed renewal would say "session expired"
        rig.http = { HttpResult(500, "") }
        val (renewal, release) = renewalHeldOpen(rig) { rig.manager.refreshSession("spike-user") }
        rig.manager.logout()
        release.complete(Unit)
        assertNull(renewal.await())
    }

    @Test
    fun theWipeVoidsARenewalBeforeItRemovesAnything() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        rig.http = { FakeAuthHttp.tokens(access = "stale-access", refresh = "stale-refresh") }
        val (renewal, release) = renewalHeldOpen(rig) { rig.manager.refreshSession("spike-user") }
        val lock = com.ecency.core.secure.PinLock(rig.vault, 16)
        var accountsAtHook = -1
        lock.beforeWipe = { accountsAtHook = rig.vault.usernames().size; rig.manager.accountsWiped() }
        lock.wipeAll() // no mark after it: the one before must be enough
        assertEquals(1, accountsAtHook, "the hook runs before anything is removed")
        rig.http = { FakeAuthHttp.tokens(access = "fresh-access", refresh = "fresh-refresh") }
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        release.complete(Unit)
        assertNull(renewal.await())
        assertEquals("fresh-refresh", rig.store.session("spike-user")?.refreshToken)
    }

    // ---- RN #3600: key input and what an import keeps ----

    @Test
    fun aPublicKeyAtSignInIsNamedAndNotInvalidCredentials() = runTest {
        val rig = Rig(this)
        listOf(TestKeys.pub(KeyRole.ACTIVE), "${TestKeys.pub(KeyRole.POSTING)}\u200B", "\u00A0${TestKeys.pub(KeyRole.OWNER)}\n").forEach { input ->
            assertFailsWith<AuthException.PublicKeyGiven> { rig.manager.loginWithKey("spike-user", input) }
        }
        assertTrue(rig.vault.usernames().isEmpty())
        assertTrue(rig.fake.sent.isEmpty(), "no session is asked for")
    }

    @Test
    fun aPastedKeyWithChatAppCharactersSignsInAsThatKey() = runTest {
        val rig = Rig(this)
        val summary = rig.manager.loginWithKey("\u200B@Spike-User ", "\uFEFF${TestKeys.wif(KeyRole.POSTING)}\u200B\n")
        assertEquals(AuthType.POSTING_KEY, summary.authType)
        assertEquals(TestKeys.wif(KeyRole.POSTING), rig.vault.account("spike-user")!!.postingKey)
    }

    @Test
    fun importingAKeyKeepsTheTokenTheSessionAndHowAKeyAccountSignedIn() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        val before = rig.store.session("spike-user")!!
        val imported = rig.manager.importKeys("spike-user", "${TestKeys.wif(KeyRole.ACTIVE)}\u200B")
        assertEquals(TestKeys.wif(KeyRole.ACTIVE), imported.activeKey)
        val stored = rig.vault.account("spike-user")!!
        assertEquals(AuthType.POSTING_KEY, stored.authType)
        assertEquals(TestKeys.wif(KeyRole.POSTING), stored.postingKey)
        assertEquals("access-1", stored.accessToken, "RN #3600 item 2: the import wrote an empty token over it")
        assertEquals(before, rig.store.session("spike-user"), "refresh token and expiry stay")
    }

    @Test
    fun importingAKeyKeepsAHiveAuthAccountsMethodKeyTokenAndExpiry() = runTest {
        val rig = Rig(this)
        val hiveAuth = VaultAccount("spike-user", AuthType.HIVE_AUTH, accessToken = "ecency-token", hiveAuthKey = "has-key", hiveAuthToken = "has-token", hiveAuthExpiry = 9_000_000_000L)
        rig.vault.save(hiveAuth)
        rig.vault.setCurrentUsername("spike-user")
        rig.manager.importKeys("spike-user", TestKeys.wif(KeyRole.ACTIVE))
        rig.manager.importKeys("spike-user", AuthGoldenVectors.PASSWORD)
        assertEquals(
            hiveAuth.copy(postingKey = TestKeys.wif(KeyRole.POSTING), activeKey = TestKeys.wif(KeyRole.ACTIVE), memoKey = TestKeys.wif(KeyRole.MEMO)),
            rig.vault.account("spike-user"),
            "RN #3600 item 3: still a HiveAuth account, with its key, token and expiry",
        )
    }

    @Test
    fun importingAKeyKeepsAHiveSignerAccountsMethodAndToken() = runTest {
        val rig = Rig(this)
        rig.vault.save(VaultAccount("spike-user", AuthType.HIVE_SIGNER, accessToken = "hs-token"))
        rig.vault.setCurrentUsername("spike-user")
        rig.manager.importKeys("spike-user", AuthGoldenVectors.PASSWORD)
        val stored = rig.vault.account("spike-user")!!
        assertEquals(AuthType.HIVE_SIGNER, stored.authType, "a full master import does not turn it into a key account")
        assertEquals("hs-token", stored.accessToken)
    }

    @Test
    fun aPublicKeyAtImportIsNamedAndNothingChanges() = runTest {
        val rig = Rig(this)
        rig.manager.loginWithKey("spike-user", TestKeys.wif(KeyRole.POSTING))
        val before = rig.vault.account("spike-user")
        assertFailsWith<AuthException.PublicKeyGiven> { rig.manager.importKeys("spike-user", "${TestKeys.pub(KeyRole.ACTIVE)}\u200B") }
        assertEquals(before, rig.vault.account("spike-user"))
    }
}
