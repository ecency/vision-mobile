package com.ecency.core.auth

import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SigningRouterTest {
    private val vote = Json.parseToJsonElement("""[["vote",{"voter":"spike-user","author":"a","permlink":"p","weight":1}]]""").jsonArray
    private val transfer = Json.parseToJsonElement("""[["transfer",{"from":"spike-user","to":"a","amount":"1.000 HIVE","memo":""}]]""").jsonArray
    private val recovery = Json.parseToJsonElement("""[["change_recovery_account",{"account_to_recover":"spike-user"}]]""").jsonArray

    private class Fake(var account: VaultAccount?) : SigningBackends {
        val calls = mutableListOf<String>()
        var postingAuth = false
        var upgrade: AuthUpgradeChoice? = null
        var upgradeGate: CompletableDeferred<Unit>? = null
        var keyError: Exception? = null
        var tokenError: Exception? = null
        var signedWith: PrivateKey? = null
        var ownerConfirm = false
        val upgradeGates = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun account(username: String) = account
        override suspend fun hasPostingAuthorization(username: String) = postingAuth
        override suspend fun broadcastWithKey(username: String, ops: JsonArray, key: PrivateKey, authority: KeyRole): String? {
            calls += "key:${authority.value}"
            keyError?.let { throw it }
            signedWith = key
            return "tx-key"
        }
        override suspend fun broadcastWithHiveSignerToken(username: String, ops: JsonArray, accessToken: String): String? {
            calls += "token"
            tokenError?.let { throw it }
            return "tx-token"
        }
        override suspend fun broadcastWithHiveSignerHot(username: String, ops: JsonArray, authority: KeyRole): HiveSignerSignResult {
            calls += "hot:${authority.value}"
            return HiveSignerSignResult("tx-hot", 5)
        }
        override suspend fun broadcastWithHiveAuth(username: String, ops: JsonArray, authority: KeyRole): String? {
            calls += "hiveauth:${authority.value}"
            return "tx-has"
        }
        override suspend fun confirmOwnerSigning(username: String, operationName: String): Boolean {
            calls += "confirm-owner:$operationName"
            return ownerConfirm
        }
        override suspend fun showAuthUpgrade(username: String, authority: KeyRole, operationName: String): AuthUpgradeChoice? {
            calls += "upgrade:$username:${authority.value}:$operationName"
            upgradeGate?.await()
            upgradeGates[username]?.await()
            return upgrade
        }
    }

    private val user = AuthGoldenVectors.USER
    private fun keyAccount(type: AuthType = AuthType.POSTING_KEY, posting: Boolean = true, active: Boolean = false, @Suppress("UNUSED_PARAMETER") owner: Boolean = false, token: String? = "tok") =
        VaultAccount(
            user, type,
            postingKey = TestKeys.wif(KeyRole.POSTING).takeIf { posting },
            activeKey = TestKeys.wif(KeyRole.ACTIVE).takeIf { active },
            accessToken = token,
        )

    private fun router(fake: Fake, now: () -> Long = { 0 }) = SigningRouter(fake, TempActiveKey(now))

    @Test
    fun loginTypeFollowsTheMobileAdapter() {
        val r = router(Fake(null))
        assertEquals(LoginMethod.KEY, r.loginType(keyAccount(), KeyRole.POSTING))
        assertEquals(LoginMethod.KEY, r.loginType(keyAccount(AuthType.ACTIVE_KEY, posting = false, active = true), KeyRole.ACTIVE))
        assertEquals(LoginMethod.HIVESIGNER, r.loginType(keyAccount(AuthType.ACTIVE_KEY, posting = false, active = true), KeyRole.POSTING))
        assertEquals(LoginMethod.KEY, r.loginType(keyAccount(AuthType.ACTIVE_KEY, posting = false, active = true, token = null), KeyRole.POSTING))
        assertEquals(LoginMethod.HIVESIGNER, r.loginType(VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "t"), KeyRole.POSTING))
        assertNull(r.loginType(VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "t"), KeyRole.ACTIVE))
        assertEquals(LoginMethod.HIVEAUTH, r.loginType(VaultAccount(user, AuthType.HIVE_AUTH), KeyRole.ACTIVE))
        assertNull(r.loginType(VaultAccount(user, null), KeyRole.POSTING))
    }

    @Test
    fun postingKeySignsPostingOps() = runTest {
        val fake = Fake(keyAccount())
        val result = router(fake).broadcast(user, vote)
        assertEquals(SignedBroadcast(SigningMethod.KEY, "tx-key"), result)
        assertEquals(TestKeys.pub(KeyRole.POSTING), fake.signedWith!!.publicKey().toString())
    }

    @Test
    fun missingActiveKeyShowsTheUpgradeAndUsesTheEnteredKeyOnce() = runTest {
        val fake = Fake(keyAccount())
        val r = router(fake)
        fake.upgrade = AuthUpgradeChoice.KEY
        // The sheet stores the entered key before answering KEY.
        fake.upgradeGate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        r.tempActiveKey.set(user, TestKeys.wif(KeyRole.ACTIVE))
        // With a temp key held, the first attempt already signs with it.
        assertEquals(SigningMethod.KEY, r.broadcast(user, transfer).method)
        assertEquals(TestKeys.pub(KeyRole.ACTIVE), fake.signedWith!!.publicKey().toString())
        assertEquals(listOf("key:active"), fake.calls)

        // Without one, the key path fails with MissingKey and the upgrade sheet decides.
        fake.calls.clear()
        fake.upgrade = AuthUpgradeChoice.HIVEAUTH
        assertEquals(SigningMethod.HIVEAUTH, r.broadcast(user, transfer).method)
        assertEquals(listOf("upgrade:spike-user:active:transfer", "hiveauth:active"), fake.calls)
    }

    @Test
    fun upgradeHiveSignerChoiceMeansHotSigning() = runTest {
        val fake = Fake(keyAccount())
        fake.upgrade = AuthUpgradeChoice.HIVESIGNER
        val result = router(fake).broadcast(user, transfer)
        assertEquals(SignedBroadcast(SigningMethod.HIVESIGNER_HOT, "tx-hot", 5), result)
        assertEquals(listOf("upgrade:spike-user:active:transfer", "hot:active"), fake.calls)
    }

    @Test
    fun declinedUpgradeIsCancelled() = runTest {
        val fake = Fake(keyAccount())
        assertFailsWith<AuthException.Cancelled> { router(fake).broadcast(user, transfer) }
    }

    @Test
    fun nonAuthErrorsAreNotRetried() = runTest {
        val fake = Fake(keyAccount())
        fake.keyError = Exception("Please wait to transact, or power up HIVE")
        assertFailsWith<Exception> { router(fake).broadcast(user, vote) }
        assertEquals(listOf("key:posting"), fake.calls)
    }

    @Test
    fun chainMissingAuthorityTriggersTheUpgrade() = runTest {
        val fake = Fake(keyAccount())
        fake.keyError = Exception("missing required posting authority")
        fake.upgrade = AuthUpgradeChoice.HIVESIGNER
        assertEquals(SigningMethod.HIVESIGNER_HOT, router(fake).broadcast(user, vote).method)
    }

    @Test
    fun activeOnlyKeyUserPostsThroughHiveSigner() = runTest {
        val fake = Fake(keyAccount(AuthType.ACTIVE_KEY, posting = false, active = true))
        assertEquals(SigningMethod.HIVESIGNER_TOKEN, router(fake).broadcast(user, vote).method)
        assertEquals(SigningMethod.KEY, router(fake).broadcast(user, transfer).method)
    }

    @Test
    fun hiveSignerUsers() = runTest {
        val fake = Fake(VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "tok"))
        val r = router(fake)
        assertEquals(SigningMethod.HIVESIGNER_TOKEN, r.broadcast(user, vote).method)
        // Active: no sheet, straight to the HiveSigner page.
        assertEquals(SigningMethod.HIVESIGNER_HOT, r.broadcast(user, transfer).method)
        // Owner is refused before any page opens (review L5; it used to go to the page).
        assertFailsWith<AuthException.UnsupportedOperation> { r.broadcast(user, recovery) }
        assertEquals(listOf("token", "hot:active"), fake.calls)
        // An expired token falls back to the page.
        fake.calls.clear()
        fake.tokenError = AuthException.HiveSignerApi(401, "invalid_grant", null)
        assertEquals(SigningMethod.HIVESIGNER_HOT, r.broadcast(user, vote).method)
        assertEquals(listOf("token", "hot:posting"), fake.calls)
        // A token user without a token goes to the page.
        fake.account = VaultAccount(user, AuthType.HIVE_SIGNER)
        fake.calls.clear()
        assertEquals(SigningMethod.HIVESIGNER_HOT, r.broadcast(user, vote).method)
    }

    @Test
    fun hiveAuthUsersUseTheTokenWhenEcencyMayPost() = runTest {
        val fake = Fake(VaultAccount(user, AuthType.HIVE_AUTH, accessToken = "tok"))
        val r = router(fake)
        assertEquals(SigningMethod.HIVEAUTH, r.broadcast(user, vote).method)
        fake.postingAuth = true
        assertEquals(SigningMethod.HIVESIGNER_TOKEN, r.broadcast(user, vote).method)
        assertEquals(SigningMethod.HIVEAUTH, r.broadcast(user, transfer).method)
        assertEquals(listOf("hiveauth:posting", "token", "hiveauth:active"), fake.calls)
    }

    @Test
    fun mixedAuthorityCustomJsonIsRefusedBeforeAnySigner() = runTest {
        val mixed = Json.parseToJsonElement("""[["custom_json",{"required_auths":["spike-user"],"required_posting_auths":["spike-user"],"id":"x","json":"{}"}]]""").jsonArray
        for (account in listOf(keyAccount(AuthType.MASTER_PASSWORD, active = true), VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "t"), VaultAccount(user, AuthType.HIVE_AUTH))) {
            val fake = Fake(account)
            assertFailsWith<AuthException.UnsupportedOperation> { router(fake).broadcast(user, mixed) }
            assertTrue(fake.calls.isEmpty(), "nothing signed or prompted: ${fake.calls}")
            assertNull(fake.signedWith)
        }
    }

    @Test
    fun postingAndActiveOperationsTogetherAreRefusedBeforeAnySigner() = runTest {
        val both = JsonArray(vote + transfer)
        val fake = Fake(keyAccount(AuthType.MASTER_PASSWORD, active = true))
        assertFailsWith<AuthException.UnsupportedOperation> { router(fake).broadcast(user, both) }
        assertTrue(fake.calls.isEmpty())
        assertNull(fake.signedWith)
    }

    @Test
    fun ownerOperationsAreRefusedForHiveSignerAndHiveAuthAccountsToo() = runTest {
        // review L5: a HiveSigner account would have opened the hot signing page
        for (account in listOf(VaultAccount(user, AuthType.HIVE_SIGNER, accessToken = "t"), VaultAccount(user, AuthType.HIVE_AUTH))) {
            val fake = Fake(account)
            fake.upgrade = AuthUpgradeChoice.HIVESIGNER
            val e = assertFailsWith<AuthException.UnsupportedOperation> { router(fake).broadcast(user, recovery) }
            assertEquals(SigningRouter.OWNER_NEVER, e.message)
            assertTrue(fake.calls.isEmpty(), "${account.authType}: nothing opened: ${fake.calls}")
            // an explicit OWNER authority too
            assertFailsWith<AuthException.UnsupportedOperation> { router(fake).broadcast(user, vote, KeyRole.OWNER) }
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test
    fun accountUpdateSettingOwnerIsAnOwnerOperation() = runTest {
        val owner = Json.parseToJsonElement("""[["account_update",{"account":"spike-user","owner":{"weight_threshold":1,"account_auths":[],"key_auths":[["STM1",1]]},"memo_key":"STM1","json_metadata":""}]]""").jsonArray
        val fake = Fake(keyAccount(AuthType.MASTER_PASSWORD, active = true))
        assertFailsWith<AuthException.UnsupportedOperation> { router(fake).broadcast(user, owner) }
        assertNull(fake.signedWith, "never signed with the active key")
    }

    @Test
    fun ownerOperationsAreRefusedBeforeAnyKeyOrPrompt() = runTest {
        // no owner key is ever stored, and nothing asks to confirm an owner signature any more
        val fake = Fake(keyAccount(AuthType.MASTER_PASSWORD, active = true))
        fake.ownerConfirm = true
        assertFailsWith<AuthException.UnsupportedOperation> { router(fake).broadcast(user, recovery) }
        assertTrue(fake.calls.isEmpty(), "no confirmation, no upgrade sheet, no broadcast: ${fake.calls}")
        assertNull(fake.signedWith)
        fake.account = keyAccount()
        assertFailsWith<AuthException.UnsupportedOperation> { router(fake).broadcast(user, recovery) }
    }

    @Test
    fun unknownLoginTypeForPostingTriesHiveSigner() = runTest {
        val fake = Fake(VaultAccount(user, null, accessToken = "tok"))
        assertEquals(SigningMethod.HIVESIGNER_TOKEN, router(fake).broadcast(user, vote).method)
        fake.upgrade = AuthUpgradeChoice.HIVEAUTH
        assertEquals(SigningMethod.HIVEAUTH, router(fake).broadcast(user, transfer).method)
        fake.account = null
        assertFailsWith<AuthException.NotSignedIn> { router(fake).broadcast(user, vote) }
    }

    @Test
    fun aTempKeyEnteredForAnotherAccountIsNotUsed() = runTest {
        val fake = Fake(keyAccount())
        fake.upgrade = AuthUpgradeChoice.HIVEAUTH
        val r = router(fake)
        r.tempActiveKey.set("other-user", TestKeys.wif(KeyRole.ACTIVE))
        assertEquals(SigningMethod.HIVEAUTH, r.broadcast(user, transfer).method)
        assertNull(fake.signedWith)
    }

    @Test
    fun upgradesForDifferentAccountsGetTheirOwnSheet() = runTest {
        val accounts = mapOf(user to keyAccount(), "other-user" to keyAccount().copy(username = "other-user"))
        val inner = Fake(null)
        val backends = object : SigningBackends by inner {
            override suspend fun account(username: String) = accounts[username]
        }
        inner.upgrade = AuthUpgradeChoice.HIVEAUTH
        val gate = CompletableDeferred<Unit>()
        inner.upgradeGate = gate
        val r = SigningRouter(backends, TempActiveKey({ 0 }))
        val a = async { r.broadcast(user, transfer) }
        val b = async { r.broadcast("other-user", transfer) }
        val c = async { r.broadcast(user, vote, KeyRole.POSTING) }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        a.await(); b.await(); c.await()
        assertEquals(2, inner.calls.count { it.startsWith("upgrade") })
        assertEquals(setOf("upgrade:spike-user:active:transfer", "upgrade:other-user:active:transfer"), inner.calls.filter { it.startsWith("upgrade") }.toSet())
    }

    @Test
    fun concurrentUpgradesShareOneSheet() = runTest {
        val fake = Fake(keyAccount())
        fake.upgrade = AuthUpgradeChoice.HIVEAUTH
        val gate = CompletableDeferred<Unit>()
        fake.upgradeGate = gate
        val r = router(fake)
        val a = async { r.broadcast(user, transfer) }
        val b = async { r.broadcast(user, transfer) }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        assertEquals(SigningMethod.HIVEAUTH, a.await().method)
        assertEquals(SigningMethod.HIVEAUTH, b.await().method)
        assertEquals(1, fake.calls.count { it.startsWith("upgrade") })
    }
}
