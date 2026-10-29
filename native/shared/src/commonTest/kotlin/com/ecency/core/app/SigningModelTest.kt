package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.auth.AuthException
import com.ecency.core.auth.AuthUpgradeChoice
import com.ecency.core.auth.HiveUri
import com.ecency.core.auth.KeyLogin
import com.ecency.core.hive.PrivateKey
import com.ecency.core.auth.SigningMethod
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.Transfer
import com.ecency.core.hive.Vote
import com.ecency.core.hive.Asset
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every broadcast goes through SigningModel: routing per auth type, prompts, the auth upgrade. */
class SigningModelTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private fun vote(voter: String = alice) = JsonArray(listOf(OperationJson.encode(Vote(voter, "author", "permlink", 10000))))
    private fun transfer(from: String = alice) = JsonArray(listOf(OperationJson.encode(Transfer(from, "spike-bob", Asset.parse("1.000 HIVE"), ""))))

    private fun store(app: TestApp, account: VaultAccount) = KeyVault(app.store).run {
        save(account)
        setCurrentUsername(account.username)
    }

    private fun postingAccount(user: String = alice) =
        VaultAccount(user, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(user, KeyRole.POSTING), accessToken = "access-$user")

    @Test
    fun keyAccountsSignLocallyAndARetryResendsTheSameTransaction() = runTest {
        val app = appWithAccounts(this)
        store(app, postingAccount())
        val first = app.env.signing.broadcast(alice, vote())
        assertEquals(SigningMethod.KEY, first.method)
        assertEquals(1, app.broadcasts().size)
        assertTrue(app.authBackend.sent.none { it.url.contains("/api/broadcast") })
    }

    @Test
    fun hiveSignerAccountsPostWithTheirToken() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val r = app.env.signing.broadcast(alice, vote())
        assertEquals(SigningMethod.HIVESIGNER_TOKEN, r.method)
        assertEquals("hs-tx-1", r.txId)
        val request = app.authBackend.sent.single { it.url.endsWith("/api/broadcast") }
        assertEquals("access-$alice", request.headers["Authorization"])
        assertTrue(app.broadcasts().isEmpty(), "nothing signed on the device")
    }

    @Test
    fun hiveSignerAccountsSignActiveOperationsOnTheHiveSignerPage() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val result = async { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") }
        testScheduler.advanceUntilIdle()
        val prompt = assertIs<SigningPrompt.HiveSignerHot>(app.env.signing.current.prompt)
        assertTrue(prompt.url.startsWith("https://hivesigner.com/sign/ops/"))
        // a navigation elsewhere says nothing; the redirect with an id finishes
        assertEquals(false, app.env.signing.hotSignNavigation("https://hivesigner.com/sign/ops/abc"))
        assertEquals(false, app.env.signing.hotSignNavigation("https://evil.example/sign/success"))
        assertEquals(true, app.env.signing.hotSignNavigation("http://127.0.0.1:3000/auth?id=tx-9&block=5"))
        val r = result.await()
        assertEquals(SigningMethod.HIVESIGNER_HOT, r.method)
        assertEquals("tx-9", r.txId)
        assertNull(app.env.signing.current.prompt)
    }

    @Test
    fun closingTheHiveSignerPageCancels() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val result = async { runCatching { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") } }
        testScheduler.advanceUntilIdle()
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertIs<AuthException.Cancelled>(result.await().exceptionOrNull())
        assertNull(app.env.signing.current.prompt)
    }

    @Test
    fun hiveAuthNeverSendsAnOwnerOperationToTheWallet() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.HIVE_AUTH, hiveAuthKey = "3f0c2e9a-1b7d-4c55-9e2a-6d8f0b1c2a3e", hiveAuthToken = "has-token", hiveAuthExpiry = TestApp.NOW + 60_000_000, accessToken = "access-$alice"))
        val owner = kotlinx.serialization.json.Json.parseToJsonElement(
            """[["account_update2",{"account":"$alice","owner":{"weight_threshold":1,"account_auths":[],"key_auths":[["STM1",1]]},"json_metadata":"","posting_json_metadata":""}]]""",
        ) as JsonArray
        assertFailsWith<AuthException.UnsupportedOperation> { app.env.signing.broadcast(alice, owner) }
        assertTrue(app.relay.sent.none { it["cmd"]?.jsonPrimitive?.content == "sign_req" })
    }

    @Test
    fun hiveAuthAccountsSignInTheWallet() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.HIVE_AUTH, hiveAuthKey = "3f0c2e9a-1b7d-4c55-9e2a-6d8f0b1c2a3e", hiveAuthToken = "has-token", hiveAuthExpiry = TestApp.NOW + 60_000_000, accessToken = "access-$alice"))
        app.relay.autoApproveSign = false
        val result = async { app.env.signing.broadcast(alice, vote()) }
        // the relay deadline runs on virtual time: only run what is due now
        repeat(5) { testScheduler.runCurrent() }
        val prompt = assertIs<SigningPrompt.HiveAuthBroadcast>(app.env.signing.current.prompt)
        assertEquals(HiveAuthStep.WAITING, prompt.step)
        assertEquals("has://sign_req", prompt.link)
        assertTrue(prompt.openWallet)
        app.env.signing.walletOpened()
        assertEquals(false, (app.env.signing.current.prompt as SigningPrompt.HiveAuthBroadcast).openWallet)
        app.relay.push(kotlinx.serialization.json.buildJsonObject {
            put("cmd", kotlinx.serialization.json.JsonPrimitive("sign_ack")); put("uuid", kotlinx.serialization.json.JsonPrimitive("sign-1"))
            put("broadcast", kotlinx.serialization.json.JsonPrimitive(true)); put("data", kotlinx.serialization.json.JsonPrimitive("has-tx-1"))
        })
        val r = result.await()
        assertEquals(SigningMethod.HIVEAUTH, r.method)
        assertEquals("has-tx-1", r.txId)
        assertEquals(alice, app.relay.sent.single { it["cmd"]!!.jsonPrimitive.content == "sign_req" }["account"]!!.jsonPrimitive.content)
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun activeOperationOfAPostingKeyAccountAsksForTheActiveKey() = runTest {
        val app = appWithAccounts(this)
        store(app, postingAccount())
        val result = async { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") }
        testScheduler.advanceUntilIdle()
        val prompt = assertIs<SigningPrompt.AuthUpgrade>(app.env.signing.current.prompt)
        assertEquals(KeyRole.ACTIVE, prompt.authority)
        assertTrue(prompt.keyEntry)

        // the posting key is not the active key; a stranger's key is not either
        app.env.signing.submitUpgradeKey(AppTestKeys.wif(alice, KeyRole.POSTING))
        testScheduler.advanceUntilIdle()
        assertEquals("This is your posting key; this operation needs your active key.", (app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)
        // a public key (what the keys screen shows and copies) says so
        app.env.signing.submitUpgradeKey(com.ecency.core.hive.PrivateKey.fromWif(AppTestKeys.wif(alice, KeyRole.ACTIVE)).publicKey().toString())
        testScheduler.advanceUntilIdle()
        assertEquals(AppError.PUBLIC_KEY_GIVEN, (app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)
        // a password that derives nothing of the account still gets the plain mismatch
        app.env.signing.submitUpgradeKey("not-the-password")
        testScheduler.advanceUntilIdle()
        assertEquals(SigningModel.KEY_MISMATCH, (app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)
        app.env.signing.submitUpgradeKey("")
        testScheduler.advanceUntilIdle()
        assertEquals(SigningModel.INVALID_KEY, (app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)

        // the master password derives the active key
        app.env.signing.submitUpgradeKey(AppTestKeys.PASSWORD)
        val r = result.await()
        assertEquals(SigningMethod.KEY, r.method)
        assertEquals(1, app.broadcasts().size)
        assertNull(app.env.signing.tempActiveKey.take(alice), "the entered key is used once")
        assertNull(KeyVault(app.store).account(alice)!!.activeKey, "and never stored")
    }

    @Test
    fun theUpgradeKeyIsBoundToItsAccount() = runTest {
        val app = appWithAccounts(this)
        store(app, postingAccount(bob))
        store(app, postingAccount(alice))
        // an active key held for alice must not sign for bob
        app.env.signing.tempActiveKey.set(alice, AppTestKeys.wif(alice, KeyRole.ACTIVE))
        val result = async { runCatching { app.env.signing.broadcast(bob, transfer(bob), KeyRole.ACTIVE, "action-1") } }
        testScheduler.advanceUntilIdle()
        assertIs<SigningPrompt.AuthUpgrade>(app.env.signing.current.prompt)
        app.env.signing.cancel()
        assertIs<AuthException.Cancelled>(result.await().exceptionOrNull())
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun aPinWipeClosesTheOpenPromptAndEveryWaitingOne() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val first = async { runCatching { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") } }
        testScheduler.advanceUntilIdle()
        assertIs<SigningPrompt.HiveSignerHot>(app.env.signing.current.prompt)
        // a second send waits its turn behind the open page
        val second = async { runCatching { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-2") } }
        testScheduler.advanceUntilIdle()
        app.env.resetAfterWipe()
        testScheduler.advanceUntilIdle()
        // neither the waiting page nor the upgrade sheet a closed page falls back to redraws after the unlock
        assertNull(app.env.signing.current.prompt)
        assertTrue(first.isCompleted && second.isCompleted, "no broadcast keeps waiting on a prompt")
        assertIs<AuthException.Cancelled>(first.await().exceptionOrNull())
        assertIs<AuthException.Cancelled>(second.await().exceptionOrNull())
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun aSendStartedBeforeAPinWipeNeverPromptsAfterIt() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        // the token post is on its way when the wipe runs; it comes back refused, which falls back to the page
        val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
        app.authBackend.gate = { if (it.url.endsWith("/api/broadcast")) answer.await() }
        app.authBackend.refuse = true
        val result = async { runCatching { app.env.signing.broadcast(alice, vote()) } }
        testScheduler.advanceUntilIdle()
        app.env.resetAfterWipe()
        answer.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(app.env.signing.current.prompt, "the HiveSigner page never opens for a send from before the wipe")
        assertIs<AuthException.Cancelled>(result.await().exceptionOrNull())
    }

    @Test
    fun aRetryKeptAfterANetworkErrorLeavesWithItsAccount() = runTest {
        var down = true
        val app = TestApp(this) { req ->
            if (req.method == "condenser_api.broadcast_transaction" && down) kotlinx.coroutines.awaitCancellation()
            TestApp.chain(req) ?: if (req.method == "condenser_api.broadcast_transaction") com.ecency.core.api.FakeTransport.result(req, "null") else com.ecency.core.api.rpc.HttpResult(500, "")
        }
        KeyVault(app.store).run {
            save(postingAccount(bob))
            save(postingAccount(alice))
            setCurrentUsername(alice)
        }
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertFailsWith<com.ecency.core.api.broadcast.BroadcastException.Network> { app.env.signing.broadcast(alice, vote(alice)) }
        assertFailsWith<com.ecency.core.api.broadcast.BroadcastException.Network> { app.env.signing.broadcast(bob, vote(bob)) }
        down = false
        val broadcaster = app.env.api.broadcaster
        assertTrue(broadcaster.keeps("ops/$alice/${vote(alice)}"), "kept for the retry")
        app.env.session.logout(alice)
        testScheduler.advanceUntilIdle()
        assertTrue(!broadcaster.keeps("ops/$alice/${vote(alice)}"), "its key left with the account")
        assertTrue(broadcaster.keeps("ops/$bob/${vote(bob)}"), "the account that stays keeps its retry")
        // the wipe drops the rest
        app.env.resetAfterWipe()
        assertTrue(!broadcaster.keeps("ops/$bob/${vote(bob)}"))
    }

    @Test
    fun aLoggedOutAccountsSendWaitingBehindAnotherPromptNeverShows() = runTest {
        val app = appWithAccounts(this)
        KeyVault(app.store).run {
            save(VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
            save(VaultAccount(bob, AuthType.HIVE_SIGNER, accessToken = "access-$bob"))
            setCurrentUsername(bob)
        }
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val bobs = async { runCatching { app.env.signing.broadcast(bob, transfer(bob), KeyRole.ACTIVE, "action-b") } }
        testScheduler.advanceUntilIdle()
        assertEquals(bob, app.env.signing.current.prompt?.username)
        // alice's send waits its turn behind bob's page, then alice signs out
        val alices = async { runCatching { app.env.signing.broadcast(alice, transfer(alice), KeyRole.ACTIVE, "action-a") } }
        testScheduler.advanceUntilIdle()
        app.env.session.logout(alice)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, app.env.signing.current.prompt?.username, "bob's page stays")
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.signing.current.prompt, "alice's page never opens")
        assertIs<AuthException.Cancelled>(alices.await().exceptionOrNull())
        assertIs<AuthException.Cancelled>(bobs.await().exceptionOrNull())

        // signed in again, alice's next send prompts as usual
        KeyVault(app.store).save(VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val again = async { runCatching { app.env.signing.broadcast(alice, transfer(alice), KeyRole.ACTIVE, "action-a2") } }
        testScheduler.advanceUntilIdle()
        assertEquals(alice, app.env.signing.current.prompt?.username)
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        again.await()
    }

    @Test
    fun theUpgradeOffersHiveSignerInstead() = runTest {
        val app = appWithAccounts(this)
        store(app, postingAccount())
        val result = async { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") }
        testScheduler.advanceUntilIdle()
        app.env.signing.chooseUpgrade(AuthUpgradeChoice.HIVESIGNER)
        testScheduler.advanceUntilIdle()
        assertIs<SigningPrompt.HiveSignerHot>(app.env.signing.current.prompt)
        app.env.signing.hotSignNavigation("http://127.0.0.1:3000/auth?id=tx-2")
        assertEquals(SigningMethod.HIVESIGNER_HOT, result.await().method)
    }

    @Test
    fun ownerOperationsAreNeverSignedInApp() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING)))
        val ops = HiveUri.ops("change_recovery_account" to kotlinx.serialization.json.buildJsonObject {
            put("account_to_recover", kotlinx.serialization.json.JsonPrimitive(alice))
            put("new_recovery_account", kotlinx.serialization.json.JsonPrimitive("spike-bob"))
            put("extensions", JsonArray(emptyList()))
        })
        assertFailsWith<AuthException.UnsupportedOperation> { app.env.signing.broadcast(alice, ops) }
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun aCancelledVoteRollsBackSilently() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.HIVE_SIGNER))
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        app.env.votes.vote("author", "permlink", 10000)
        testScheduler.advanceUntilIdle()
        assertIs<SigningPrompt.HiveSignerHot>(app.env.signing.current.prompt)
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.votes.current.statuses.isEmpty(), "no error for a cancel")
        assertNull(app.env.localVotes.get(com.ecency.core.api.model.PostKey("author", "permlink")))
    }

    /** A node where alice's active authority needs two signatures (threshold 2, her key and bob's at weight 1). */
    private fun multisigApp(scope: kotlinx.coroutines.test.TestScope, activeThreshold: Int, aliceWeight: Int) = TestApp(scope) { req ->
        if (req.method == "condenser_api.get_accounts") {
            fun auth(role: KeyRole, threshold: Int, weight: Int) =
                """{"weight_threshold":$threshold,"account_auths":[],"key_auths":[["${AppTestKeys.pub(alice, role)}",$weight],["${AppTestKeys.pub("spike-bob", role)}",1]]}"""
            com.ecency.core.api.FakeTransport.result(
                req,
                """[{"name":"$alice","owner":${auth(KeyRole.OWNER, 1, 1)},"active":${auth(KeyRole.ACTIVE, activeThreshold, aliceWeight)},"posting":${auth(KeyRole.POSTING, 1, 1)},"memo_key":"${AppTestKeys.pub(alice, KeyRole.MEMO)}"}]""",
            )
        } else {
            TestApp.chain(req) ?: if (req.method == "condenser_api.broadcast_transaction") com.ecency.core.api.FakeTransport.result(req, "null") else com.ecency.core.api.rpc.HttpResult(500, "")
        }
    }

    @Test
    fun theUpgradeSheetRefusesAnActiveKeyThatCannotSignAlone() = runTest {
        val app = multisigApp(this, activeThreshold = 2, aliceWeight = 1)
        store(app, postingAccount())
        val result = async { runCatching { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") } }
        testScheduler.advanceUntilIdle()
        assertIs<SigningPrompt.AuthUpgrade>(app.env.signing.current.prompt)
        // her real active key, on the account, but half of what the authority needs
        app.env.signing.submitUpgradeKey(AppTestKeys.wif(alice, KeyRole.ACTIVE))
        testScheduler.advanceUntilIdle()
        assertEquals(AppError.KEY_BELOW_THRESHOLD, (app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)
        assertNull(app.env.signing.tempActiveKey.take(alice), "nothing is held for a key the chain would refuse")
        assertTrue(app.broadcasts().isEmpty())
        // the master password derives the same key: refused the same way
        app.env.signing.submitUpgradeKey(AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        assertEquals(AppError.KEY_BELOW_THRESHOLD, (app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)
        app.env.signing.cancel()
        result.await()
    }

    @Test
    fun theUpgradeSheetTakesAKeyWhoseWeightReachesTheThreshold() = runTest {
        val app = multisigApp(this, activeThreshold = 2, aliceWeight = 2)
        store(app, postingAccount())
        val result = async { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") }
        testScheduler.advanceUntilIdle()
        app.env.signing.submitUpgradeKey(AppTestKeys.wif(alice, KeyRole.ACTIVE))
        assertEquals(SigningMethod.KEY, result.await().method)
        assertEquals(1, app.broadcasts().size)
    }

    @Test
    fun anActionIdScopesTheRetryOfAValueMovingBroadcast() = runTest {
        var down = true
        val app = TestApp(this) { req ->
            when {
                req.method == "condenser_api.get_accounts" -> com.ecency.core.api.FakeTransport.result(req, "[${AppTestKeys.accountJson(alice, emptyList())}]")
                req.method == "condenser_api.broadcast_transaction" -> if (down) kotlinx.coroutines.awaitCancellation() else com.ecency.core.api.FakeTransport.result(req, "null")
                else -> TestApp.chain(req) ?: com.ecency.core.api.rpc.HttpResult(500, "")
            }
        }
        store(app, VaultAccount(alice, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE), accessToken = "a"))
        val firstError = runCatching { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") }.exceptionOrNull()
        assertIs<com.ecency.core.api.broadcast.BroadcastException.Network>(firstError, "got $firstError, sent ${app.broadcasts().size}")
        assertTrue(app.broadcasts().isNotEmpty(), "the first action was sent")
        down = false
        // every signature reads the chain head first: the count says how many transactions were signed
        val signed = { app.transport.sent.count { it.method == "condenser_api.get_dynamic_global_properties" } }
        assertEquals(1, signed())
        app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-2")
        assertEquals(2, signed(), "a second action signs its own transaction")
        val before = app.broadcasts().size
        app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1")
        assertEquals(2, signed(), "the retry of the first action re-sent its own transaction")
        assertEquals(before + 1, app.broadcasts().size)
    }

    @Test
    fun repeatSafeOperations() {
        val safe = com.ecency.core.api.broadcast.RepeatSafeOps
        assertTrue(safe.all(listOf(Vote(alice, "a", "p", 1))))
        assertTrue(!safe.all(listOf(Transfer(alice, bob, Asset.parse("1.000 HIVE"), ""))))
        assertTrue(safe.all(listOf(com.ecency.core.hive.CustomJson(emptyList(), listOf(alice), "follow", "[]"))))
        assertTrue(!safe.all(listOf(com.ecency.core.hive.CustomJson(listOf(alice), emptyList(), "ssc-mainnet-hive", "{}"))))
        assertTrue(!safe.all(listOf(com.ecency.core.hive.CustomJson(listOf(alice), emptyList(), "follow", "[]"))), "active auths: not a social action")
        assertTrue(!safe.all(emptyList()))
        assertTrue(!safe.all(listOf(Vote(alice, "a", "p", 1), Transfer(alice, bob, Asset.parse("1.000 HIVE"), ""))))
    }

    @Test
    fun aValueMovingBroadcastWithoutAnActionIdIsRefusedBeforeSigning() = runTest {
        val app = appWithAccounts(this)
        store(app, VaultAccount(alice, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE), accessToken = "a"))
        val e = assertFailsWith<com.ecency.core.api.broadcast.BroadcastException.InvalidOperation> { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE) }
        assertEquals(ACTION_ID_REQUIRED, e.message)
        assertTrue(app.broadcasts().isEmpty())
        // repeat-safe operations need none
        app.env.signing.broadcast(alice, vote())
        assertEquals(1, app.broadcasts().size)
    }

    @Test
    fun theActionKeyNamesTheAccount() {
        assertTrue(actionKey(alice, "id-1", transfer()) != actionKey(bob, "id-1", transfer()))
        assertTrue(actionKey(alice, "id-1", transfer()) != actionKey(alice, "id-2", transfer()))
        assertTrue(actionKey(alice, "id-1", transfer()).startsWith("act/$alice/id-1/"))
    }

    /** RN #3600 on the "Authorization Required" sheet: chat app characters, public keys and broken keys. */
    @Test
    fun theUpgradeKeyIsNormalisedAndAPublicOrBrokenKeyIsNamed() = runTest {
        val app = appWithAccounts(this)
        store(app, postingAccount())
        val result = async { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") }
        testScheduler.advanceUntilIdle()
        fun error() = (app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error
        val active = AppTestKeys.wif(alice, KeyRole.ACTIVE)
        listOf("${AppTestKeys.pub(alice, KeyRole.ACTIVE)}\u200B", "\uFEFF${AppTestKeys.pub(alice, KeyRole.ACTIVE)}", "${AppTestKeys.pub(alice, KeyRole.POSTING)}\u00A0\n").forEach {
            app.env.signing.submitUpgradeKey(it)
            testScheduler.advanceUntilIdle()
            assertEquals(AppError.PUBLIC_KEY_GIVEN, error(), "a public key is named, not a mismatch")
        }
        app.env.signing.submitUpgradeKey("${AppTestKeys.wif(alice, KeyRole.POSTING)}\u200B")
        testScheduler.advanceUntilIdle()
        assertEquals(SigningModel.wrongRole(KeyRole.POSTING, KeyRole.ACTIVE), error(), "the posting key is named")
        // shaped like a private key but its checksum fails: not a password that matches nothing
        val broken = active.dropLast(1) + (if (active.last() == 'a') 'b' else 'a')
        app.env.signing.submitUpgradeKey(broken)
        testScheduler.advanceUntilIdle()
        assertEquals(SigningModel.INVALID_KEY, error())
        // the active key as a chat app hands it over
        app.env.signing.submitUpgradeKey("\u00A0$active\u200B\n")
        val r = result.await()
        assertEquals(SigningMethod.KEY, r.method)
        assertEquals(1, app.broadcasts().size)
    }

    /**
     * Review: a chosen master password may look like a WIF (5 and 50 base58 characters) without its checksum.
     * Sign-in derives from it, so the upgrade sheet must too; INVALID_KEY only when the derived key matches nothing.
     */
    @Test
    fun aMasterPasswordShapedLikeABrokenWifStillSignsOnTheUpgradeSheet() = runTest {
        val real = AppTestKeys.wif(alice, KeyRole.ACTIVE)
        val password = real.dropLast(1) + (if (real.last() == 'a') 'b' else 'a')
        assertTrue(KeyLogin.looksLikeWif(password) && !KeyLogin.isWif(password))
        fun pub(role: KeyRole) = PrivateKey.fromLogin(alice, password, role).publicKey().toString()
        fun auth(role: KeyRole) = """{"weight_threshold":1,"account_auths":[],"key_auths":[["${pub(role)}",1]]}"""
        val account = """{"name":"$alice","owner":${auth(KeyRole.OWNER)},"active":${auth(KeyRole.ACTIVE)},"posting":${auth(KeyRole.POSTING)},""" +
            """"memo_key":"${pub(KeyRole.MEMO)}","json_metadata":"","posting_json_metadata":"","reputation":0,"post_count":1}"""
        val app = TestApp(this) { req ->
            if (req.method == "condenser_api.get_accounts") FakeTransport.result(req, "[$account]")
            else TestApp.chain(req) ?: if (req.method == "condenser_api.broadcast_transaction") FakeTransport.result(req, "null") else com.ecency.core.api.rpc.HttpResult(500, "")
        }
        store(app, VaultAccount(alice, AuthType.POSTING_KEY, postingKey = PrivateKey.fromLogin(alice, password, KeyRole.POSTING).toWif(), accessToken = "access-$alice"))
        // sign-in takes this password as a password
        assertEquals(AuthType.MASTER_PASSWORD, KeyLogin.classify(alice, password, app.env.chain.authorities(alice)!!).authType)
        val result = async { app.env.signing.broadcast(alice, transfer(), KeyRole.ACTIVE, "action-1") }
        testScheduler.advanceUntilIdle()
        // a broken WIF that derives nothing of this account is still named as an invalid key
        app.env.signing.submitUpgradeKey(AppTestKeys.wif(bob, KeyRole.ACTIVE).let { it.dropLast(1) + (if (it.last() == 'a') 'b' else 'a') })
        testScheduler.advanceUntilIdle()
        assertEquals(SigningModel.INVALID_KEY, (app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)
        app.env.signing.submitUpgradeKey(password)
        testScheduler.advanceUntilIdle()
        assertNull((app.env.signing.current.prompt as? SigningPrompt.AuthUpgrade)?.error, "the password derives the active key")
        val r = result.await()
        assertEquals(SigningMethod.KEY, r.method)
        assertEquals(1, app.broadcasts().size)
    }
}
