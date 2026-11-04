package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.waves.WavesGoldens
import com.ecency.core.auth.AuthException
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RN usePostSubmitter: a quick post (a wave, a quick reply) that HiveSigner refuses with `unauthorized_client`
 * because ecency.app has no posting authority opens the grant prompt. A scripted HiveSigner and node; nothing
 * is broadcast anywhere.
 */
class AuthorityRecoveryTest {
    private val user = "spike-alice"
    private val other = "spike-other"
    private val refusal = """{"error":"unauthorized_client","error_description":"The app @ecency.app doesn't have permission to broadcast for @$user"}"""

    private inner class World(val scope: TestScope) {
        /** HiveSigner's answers to `/api/broadcast`, in order; then success. */
        val broadcasts = ArrayDeque<HttpResult>()
        var tokenBroadcasts = 0
        var postingApps = emptyList<String>()
        val app = TestApp(scope) { req -> chain(req) }

        init {
            app.authBackend.rest = { r ->
                when {
                    r.url.endsWith("/api/broadcast") -> {
                        tokenBroadcasts++
                        broadcasts.removeFirstOrNull() ?: HttpResult(200, """{"result":{"id":"hs-tx-1"}}""")
                    }
                    r.url.endsWith("/private-api/public/bots") -> HttpResult(200, "[]")
                    else -> null
                }
            }
            app.authBackend.privateApi = { HttpResult(200, "{}") }
            listOf(user, other).forEach { KeyVault(app.store).save(VaultAccount(it, AuthType.HIVE_SIGNER, accessToken = "token-$it")) }
            KeyVault(app.store).setCurrentUsername(user)
            app.env.session.start()
            idle()
        }

        private fun chain(req: Sent): HttpResult = when (req.method) {
            "bridge.get_account_posts" ->
                if (req.params.jsonObject.getValue("account").jsonPrimitive.content == "ecency.waves") {
                    FakeTransport.result(req, """[{"author":"ecency.waves","permlink":"waves-20261006","json_metadata":{"tags":["waves-20261006"]}}]""")
                } else {
                    FakeTransport.result(req, "[]")
                }
            "bridge.get_post" -> FakeTransport.result(req, "null")
            "condenser_api.get_following" -> FakeTransport.result(req, "[]")
            "condenser_api.get_accounts" -> FakeTransport.result(req, "[${accountRow()}]")
            "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "{}")
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }

        /** The account's active authority needs this many (its one key weighs 1). */
        var activeThreshold = 1

        private fun accountRow() = AppTestKeys.accountJson(user, postingApps)
            .replace(""""active":{"weight_threshold":1""", """"active":{"weight_threshold":$activeThreshold""")

        /** The account_update operations that reached the node. */
        fun grants() = app.broadcasts().map { it.params.jsonArray[0].jsonObject.getValue("operations").jsonArray.single().jsonArray }
            .filter { it[0].jsonPrimitive.content == "account_update" }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        fun wave(text: String = "A wave from a HiveSigner account, long enough"): WaveComposeModel {
            val composer = app.env.waves.composer()
            composer.setText(text)
            composer.send()
            idle()
            return composer
        }
    }

    @Test
    fun theErrorTestIsRns() {
        WavesGoldens.authorityErrors.forEach { c ->
            assertEquals(c.missing, PostingAuthorityPrompt.matches(c.code, c.description, c.message), "$c")
        }
        // through the exception the HiveSigner client throws, also when it is wrapped
        val api = AuthException.HiveSignerApi(401, "unauthorized_client", "The app @ecency.app doesn't have permission to broadcast for @x")
        assertTrue(PostingAuthorityPrompt.isMissingAuthority(api))
        assertTrue(PostingAuthorityPrompt.isMissingAuthority(IllegalStateException("wrapped", api)))
        assertFalse(PostingAuthorityPrompt.isMissingAuthority(AuthException.HiveSignerApi(401, "unauthorized_client", "The token has expired")))
        assertFalse(PostingAuthorityPrompt.isMissingAuthority(null))
    }

    @Test
    fun theConfirmationNamesWhatIsGrantedAndWhatItNeeds() {
        val copy = PostingAuthorityPrompt.DESCRIPTION
        assertTrue("ecency.app" in copy, "the account that gets the posting authority")
        assertTrue("posting permission" in copy)
        assertTrue("active key" in copy, "what signs the grant")
        // RN's three benefits and its security note stay
        assertEquals(
            listOf(
                "Post, comment, and vote instantly without Keychain prompts",
                "Seamless experience across all posting operations",
                "Secure - only allows posting actions, not transfers",
            ),
            PostingAuthorityPrompt.BENEFITS,
        )
        assertEquals("Safe: Cannot transfer funds or change keys. You can revoke this anytime.", PostingAuthorityPrompt.SECURITY_NOTE)
    }

    @Test
    fun aRefusedWaveOpensThePromptAndSkippingKeepsTheDraftQuietly() = runTest {
        val w = World(this)
        w.broadcasts += HttpResult(401, refusal)
        val composer = w.wave()
        assertTrue(composer.current.authorityPrompt, "RN: the grant sheet, not the raw unauthorized_client JSON")
        assertNull(composer.current.error)
        assertFalse(composer.current.isSending)
        assertNull(w.app.env.signing.current.prompt, "nothing is signed before the answer")
        // "Skip for Now": RN returns false without an alert; the text stays for another try
        composer.answerAuthorityPrompt(grant = false)
        w.idle()
        assertFalse(composer.current.authorityPrompt)
        assertNull(composer.current.error)
        assertNull(composer.current.sent)
        assertEquals("A wave from a HiveSigner account, long enough", composer.current.text)
        assertEquals(1, w.tokenBroadcasts, "nothing is sent again by itself")
    }

    @Test
    fun grantingSendsTheWaveOnceMoreAndTheRetryNeverAsksAgain() = runTest {
        val w = World(this)
        w.broadcasts += HttpResult(401, refusal)
        val composer = w.wave()
        assertTrue(composer.current.authorityPrompt)
        // the authority is there by the time the grant looks (granted elsewhere meanwhile): no account_update, one retry
        w.postingApps = listOf("ecency.app")
        composer.answerAuthorityPrompt(grant = true)
        w.idle()
        assertNotNull(composer.current.sent)
        assertEquals(2, w.tokenBroadcasts)
        assertNull(w.app.env.signing.current.prompt)

        // the retry is refused the same way: now the error shows, the prompt does not come back
        val w2 = World(this)
        w2.broadcasts += HttpResult(401, refusal)
        w2.broadcasts += HttpResult(401, refusal)
        val again = w2.wave()
        w2.postingApps = listOf("ecency.app")
        again.answerAuthorityPrompt(grant = true)
        w2.idle()
        assertFalse(again.current.authorityPrompt)
        assertNotNull(again.current.error)
        assertNull(again.current.sent)
        assertEquals(2, w2.tokenBroadcasts)
        // a later publish by the user may ask again
        again.clearError()
        w2.broadcasts += HttpResult(401, refusal)
        again.send()
        w2.idle()
        assertTrue(again.current.authorityPrompt)
    }

    @Test
    fun theGrantIsTheGuardedOneWithTheActiveAuthorityAndClosingItSendsNothing() = runTest {
        val w = World(this)
        w.broadcasts += HttpResult(401, refusal)
        val composer = w.wave()
        composer.answerAuthorityPrompt(grant = true)
        w.idle()
        // ecency.app is missing: the account_update needs the active authority, so a signing prompt shows
        assertNotNull(w.app.env.signing.current.prompt, "never a silent authority change")
        assertEquals(1, w.tokenBroadcasts)
        w.app.env.signing.cancel()
        w.idle()
        assertNull(composer.current.sent)
        assertNull(composer.current.error, "a closed prompt is not an error")
        assertFalse(composer.current.isSending)
        assertEquals(1, w.tokenBroadcasts, "RN: not granted, no retry")
    }

    // ---- the grant per kind of account, with the vault as it is now: no owner key, no master password ----

    private fun grantOp(w: World): String = w.grants().single()[1].toString()

    private fun assertGrantsEcency(w: World) {
        val posting = kotlinx.serialization.json.Json.parseToJsonElement(grantOp(w)).jsonObject.getValue("posting").jsonObject
        assertEquals("""[["ecency.app",1]]""", posting.getValue("account_auths").toString())
        assertEquals("""[["${AppTestKeys.pub(user, KeyRole.POSTING)}",1]]""", posting.getValue("key_auths").toString(), "the account's own posting key stays")
    }

    @Test
    fun aPostingOnlyAccountGrantsThroughTheUpgradeSheetWithItsActiveKey() = runTest {
        val w = World(this)
        KeyVault(w.app.store).save(VaultAccount(user, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(user, KeyRole.POSTING), accessToken = "token-$user"))
        val recovery = AuthorityRecovery(w.app.env)
        var failure: Throwable? = null
        val job = launch { runCatching { recovery.grant(user) }.onFailure { failure = it } }
        w.idle()
        val prompt = w.app.env.signing.current.prompt as SigningPrompt.AuthUpgrade
        assertEquals(KeyRole.ACTIVE, prompt.authority)
        assertTrue(prompt.keyEntry)
        assertTrue(w.grants().isEmpty(), "nothing is signed with the posting key")
        // the posting key is not the active key
        w.app.env.signing.submitUpgradeKey(AppTestKeys.wif(user, KeyRole.POSTING))
        w.idle()
        assertNotNull((w.app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)
        assertTrue(w.grants().isEmpty())
        w.app.env.signing.submitUpgradeKey(AppTestKeys.wif(user, KeyRole.ACTIVE))
        w.idle()
        assertTrue(job.isCompleted)
        assertNull(failure)
        assertNull(w.app.env.signing.current.prompt)
        assertGrantsEcency(w)
        assertTrue(recovery.takeRetry(), "the publish is sent once more")
        assertNull(KeyVault(w.app.store).account(user)!!.activeKey, "the active key was used once and is not stored")
    }

    @Test
    fun anActiveKeyBelowTheAccountsThresholdIsRefusedInTheUpgradeSheet() = runTest {
        val w = World(this)
        w.activeThreshold = 2
        KeyVault(w.app.store).save(VaultAccount(user, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(user, KeyRole.POSTING), accessToken = "token-$user"))
        val job = launch { runCatching { AuthorityRecovery(w.app.env).grant(user) } }
        w.idle()
        w.app.env.signing.submitUpgradeKey(AppTestKeys.wif(user, KeyRole.ACTIVE))
        w.idle()
        assertEquals(AppError.KEY_BELOW_THRESHOLD, (w.app.env.signing.current.prompt as SigningPrompt.AuthUpgrade).error)
        assertTrue(w.grants().isEmpty())
        w.app.env.signing.cancel()
        w.idle()
        assertTrue(job.isCompleted)
        assertTrue(w.grants().isEmpty())
    }

    @Test
    fun aMasterPasswordAccountGrantsWithItsStoredActiveKeyAndNoPrompt() = runTest {
        val w = World(this)
        // what a master password sign-in leaves in the vault: the posting and active keys, nothing else
        KeyVault(w.app.store).save(
            VaultAccount(user, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(user, KeyRole.POSTING), activeKey = AppTestKeys.wif(user, KeyRole.ACTIVE), accessToken = "token-$user"),
        )
        val recovery = AuthorityRecovery(w.app.env)
        recovery.grant(user)
        w.idle()
        assertNull(w.app.env.signing.current.prompt)
        assertGrantsEcency(w)
        assertTrue(recovery.takeRetry())
    }

    @Test
    fun aHiveSignerAccountIsSentToHiveSignersOwnPageForTheGrantNeverSilently() = runTest {
        // SigningRouter: a HiveSigner account has no upgrade sheet, an active operation goes to HiveSigner's signing page.
        // This covers the way in and a closed page; a grant completed on that page is the general hot-sign path (SigningModelTest).
        val w = World(this)
        val recovery = AuthorityRecovery(w.app.env)
        var failure: Throwable? = null
        val job = launch { runCatching { recovery.grant(user) }.onFailure { failure = it } }
        w.idle()
        val prompt = assertIs<SigningPrompt.HiveSignerHot>(w.app.env.signing.current.prompt, "HiveSigner's own page")
        assertEquals(user, prompt.username)
        assertEquals(0, w.tokenBroadcasts, "the token cannot change an authority")
        assertTrue(w.grants().isEmpty())
        w.app.env.signing.cancel()
        w.idle()
        assertTrue(job.isCompleted)
        assertNotNull(failure, "a closed page is not a grant")
        assertFalse(recovery.takeRetry(), "and the publish is not sent again")
        assertEquals(0, w.tokenBroadcasts)
        assertTrue(w.grants().isEmpty())

        // signed on HiveSigner's page (its success redirect): the grant counts, and the publish goes once more
        val w2 = World(this)
        val granted = AuthorityRecovery(w2.app.env)
        var failed: Throwable? = null
        val signing = launch { runCatching { granted.grant(user) }.onFailure { failed = it } }
        w2.idle()
        assertIs<SigningPrompt.HiveSignerHot>(w2.app.env.signing.current.prompt)
        assertTrue(w2.app.env.signing.hotSignNavigation("http://127.0.0.1:3000/auth?id=tx-9&block=5"))
        w2.idle()
        assertTrue(signing.isCompleted)
        assertNull(failed)
        assertNull(w2.app.env.signing.current.prompt)
        assertTrue(granted.takeRetry())
        assertEquals(0, w2.tokenBroadcasts, "HiveSigner broadcast it itself: nothing went through the token or the node")
        assertTrue(w2.grants().isEmpty())
    }

    @Test
    fun anExpiredTokenIsAnErrorNotAGrantPromptAndThePromptIsTheOwners() = runTest {
        val w = World(this)
        w.broadcasts += HttpResult(401, """{"error":"unauthorized_client","error_description":"The token has expired"}""")
        val composer = w.wave()
        assertFalse(composer.current.authorityPrompt)
        assertNotNull(composer.current.error)
        // a prompt left open across an account change never grants or sends as the other account
        w.app.env.session.switchTo(other)
        w.idle()
        val stale = WaveComposeModel(w.app.env, other)
        stale.setText("Left open across an account change, long enough")
        w.broadcasts += HttpResult(401, refusal)
        stale.send()
        w.idle()
        assertTrue(stale.current.authorityPrompt)
        w.app.env.session.switchTo(user)
        w.idle()
        val before = w.tokenBroadcasts
        stale.answerAuthorityPrompt(grant = true)
        w.idle()
        assertEquals(WaveComposeModel.ACCOUNT_CHANGED, stale.current.error?.message)
        assertEquals(before, w.tokenBroadcasts)
        assertNull(w.app.env.signing.current.prompt)
    }

    @Test
    fun aRefusedQuickReplyFollowsTheSameRule() = runTest {
        val w = World(this)
        val parent = Post(author = "spike-bob", permlink = "a-post", category = "hive-1", body = "Parent", depth = 0)
        val reply = w.app.env.replyModel(PostKey("spike-bob", "a-post"), parent)
        reply.setText("A quick reply")
        w.broadcasts += HttpResult(401, refusal)
        reply.send()
        w.idle()
        assertTrue(reply.current.authorityPrompt)
        assertNull(reply.current.error)
        reply.answerAuthorityPrompt(grant = false)
        w.idle()
        assertEquals("A quick reply", reply.current.text)
        assertNull(reply.current.sent)
        assertEquals(1, w.tokenBroadcasts)
        // granted: one retry, which goes through
        w.broadcasts += HttpResult(401, refusal)
        reply.send()
        w.idle()
        assertTrue(reply.current.authorityPrompt)
        w.postingApps = listOf("ecency.app")
        reply.answerAuthorityPrompt(grant = true)
        w.idle()
        assertNotNull(reply.current.sent)
        assertEquals(3, w.tokenBroadcasts)
    }
}
