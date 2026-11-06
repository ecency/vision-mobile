package com.ecency.core.app

import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.Signature
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

/** RN dappBrowser and useHiveBridgeHandler: tabs, the URL bar, and the bridge's rules. */
class DappBrowserModelTest {
    private val alice = "spike-alice"
    private val origin = "https://dapp.example"

    private suspend fun signedIn(scope: TestScope, account: VaultAccount? = VaultAccount(
        alice, AuthType.MASTER_PASSWORD,
        postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE), accessToken = "access-$alice",
    )): TestApp {
        val app = appWithAccounts(scope)
        if (account != null) KeyVault(app.store).run { save(account); setCurrentUsername(account.username) }
        app.env.session.start()
        scope.testScheduler.advanceUntilIdle()
        return app
    }

    /** Every answer the page got, parsed. */
    private class Page {
        val answers = mutableListOf<JsonObject>()
        val reply = DappBrowserModel.BridgeReply { answers += Json.parseToJsonElement(it).jsonObject }
        val last: JsonObject get() = answers.last()
        val lastResponse: JsonObject get() = last["response"]!!.jsonObject
        fun error(): String? = lastResponse["error"]?.jsonPrimitive?.contentOrNull
    }

    private fun browser(app: TestApp, url: String? = "$origin/app") = app.env.dappBrowserModel(url)

    private fun keychain(type: String, extra: String, id: Int = 1, domain: String = "https://ecency.com") =
        """{"name":"swRequest_hive","request_id":$id,"data":{"type":"$type","domain":"$domain"$extra}}"""

    private fun send(b: DappBrowserModel, page: Page, message: String, frame: String = origin, main: Boolean = true) =
        b.bridgeMessage(b.current.activeTabId, frame, main, message, page.reply)

    // ---- tabs and URL bar ----

    @Test
    fun theUrlBarNormalisesAndTabsBehaveAsRn() = runTest {
        val app = signedIn(this)
        val b = browser(app, null)
        assertTrue(b.current.showsHome)
        b.setUrlText("peakd.com")
        b.submitUrl()
        assertEquals("https://peakd.com", b.current.activeTab.url)
        assertEquals("peakd.com", b.current.displayUrl)
        assertTrue(b.current.isSecure)
        val firstLoad = b.current.activeTab.load
        b.navigate("hive dapps")
        assertEquals("https://www.google.com/search?q=hive%20dapps", b.current.activeTab.url)
        assertTrue(b.current.activeTab.load > firstLoad, "a typed address asks the web view to load")
        // typed schemes that are not web pages never load
        b.navigate("javascript:alert(1)")
        assertTrue(b.current.activeTab.url!!.startsWith("https://www.google.com/search"))
        b.navigate("file:///etc/hosts")
        assertTrue(b.current.activeTab.url!!.startsWith("https://www.google.com/search"))

        b.addTab()
        assertEquals(2, b.current.tabs.size)
        assertTrue(b.current.showsHome)
        val second = b.current.activeTabId
        b.closeTab(second)
        assertEquals(1, b.current.tabs.size)
        assertFalse(b.current.canCloseTabs)
        b.closeTab(b.current.activeTabId)
        assertEquals(1, b.current.tabs.size, "the last tab closing leaves a fresh home tab")
        assertTrue(b.current.showsHome)
        assertFalse(b.home(), "home on the home screen closes the browser")
    }

    @Test
    fun onlyWebPagesLoadAndAppLinksNeedATap() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val tab = b.current.activeTabId
        b.pageCommitted(tab, "$origin/app")
        assertTrue(b.shouldLoad(tab, "https://dapp.example/x", true, false))
        assertTrue(b.shouldLoad(tab, "http://dapp.example/x", true, false))
        assertFalse(b.shouldLoad(tab, "javascript:alert(document.cookie)", true, true))
        assertFalse(b.shouldLoad(tab, "file:///data/data/app.esteem.mobile.android/shared_prefs/x.xml", true, true))
        assertFalse(b.shouldLoad(tab, "content://app.esteem.mobile.android.files/x", true, true))
        assertFalse(b.shouldLoad(tab, "intent://scan/#Intent;scheme=zxing;end", true, true))
        assertFalse(b.shouldLoad(tab, "data:text/html,<script>1</script>", true, false))
        assertTrue(b.shouldLoad(tab, "about:blank", false, false), "frames may be blank")
        // a page cannot fire a signing link by itself
        val link = "hive://sign/op/" + "x"
        assertFalse(b.shouldLoad(tab, link, true, false))
        testScheduler.advanceUntilIdle()
        assertNull(app.env.authLinks.current.prompt)
        assertFalse(b.shouldLoad(tab, "ecency://transfer?to=spike-bob&amount=1&asset=HIVE", true, true))
        testScheduler.advanceUntilIdle()
        val linkPrompt = assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt, "a tapped link goes to the link handling, which asks first")
        assertEquals("the page dapp.example", linkPrompt.requester, "the prompt names the page that fired the link")
    }

    @Test
    fun aPagesLinkNeverAnswersItsCallbackBeforeTheUserWasAsked() = runTest {
        // a page is content: naming an account in a login link must not tell it whether that account is here
        val app = signedIn(this)
        val b = browser(app, "https://dapp.example/")
        val tab = b.current.activeTabId
        b.pageCommitted(tab, "https://dapp.example/")
        assertFalse(b.shouldLoad(tab, "ecency://login?username=spike-nobody&callback=dappapp://cb", true, true))
        testScheduler.advanceUntilIdle()
        assertNull(app.env.authLinks.current.callback, "not_logged_in stays on the device")
        // a signing link still asks and names the page; a decline tells the page nothing either
        assertFalse(b.shouldLoad(tab, "ecency://transfer?to=spike-bob&amount=1&asset=HIVE&callback=dappapp://cb", true, true))
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt)
        app.env.authLinks.decline()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.authLinks.current.callback, "a page's request never hears a decline")
    }

    @Test
    fun aLinkNamesTheCommittedPageNotOneThatOnlyStarted() = runTest {
        // evil.example starts a navigation to a page that never answers, then fires a signing link
        val app = signedIn(this)
        val b = browser(app, "https://evil.example/")
        val tab = b.current.activeTabId
        b.pageCommitted(tab, "https://evil.example/")
        b.pageChanged(tab, "https://ecency.com:8443/", null, false, false)
        assertEquals("https://ecency.com:8443/", b.current.activeTab.url, "the provisional URL is shown")
        assertFalse(b.shouldLoad(tab, "ecency://transfer?to=spike-bob&amount=1&asset=HIVE", true, true))
        testScheduler.advanceUntilIdle()
        assertEquals("the page evil.example", assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt).requester)
        app.env.authLinks.decline()
        // the web view's own report of the source frame wins over the tab's state
        assertFalse(b.shouldLoad(tab, "ecency://transfer?to=spike-bob&amount=1&asset=HIVE", true, true, "widget.example"))
        testScheduler.advanceUntilIdle()
        assertEquals("the page widget.example", assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt).requester)
    }

    // ---- who may talk to the bridge ----

    @Test
    fun messagesCountOnlyFromTheActiveTabsHttpsMainFrameOrigin() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val vote = keychain("vote", ""","author":"a","permlink":"p","weight":10000""")
        send(b, page, vote, frame = "https://evil.example")
        send(b, page, vote, main = false)
        send(b, page, vote, frame = "http://dapp.example")
        b.bridgeMessage(b.current.activeTabId, origin, true, vote.replace("\"weight\"", "\"x\"").take(10), page.reply)
        testScheduler.advanceUntilIdle()
        assertTrue(page.answers.isEmpty(), "another origin, a frame, http and garbage are ignored")
        assertNull(b.current.prompt)

        val signer = browser(app, "https://hivesigner.com/login")
        signer.bridgeMessage(signer.current.activeTabId, "https://hivesigner.com", true, vote, page.reply)
        testScheduler.advanceUntilIdle()
        assertTrue(page.answers.isEmpty(), "never on HiveSigner's pages")
        assertNull(signer.current.prompt)
    }

    @Test
    fun theHandshakeIsAnsweredAndSignedOutRequestsAreRefused() = runTest {
        val app = signedIn(this, account = null)
        val b = browser(app)
        val page = Page()
        send(b, page, """{"name":"swHandshake_hive","request_id":1,"data":{}}""")
        assertEquals("handshake", page.last["kind"]!!.jsonPrimitive.content)
        send(b, page, keychain("vote", ""","author":"a","permlink":"p","weight":10000"""))
        testScheduler.advanceUntilIdle()
        assertEquals(DappBrowserModel.NO_ACCOUNT, page.error())
        assertEquals(1L, page.last["id"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun anotherAccountsRequestAndUnsupportedTypesAreRefused() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("vote", ""","username":"spike-bob","author":"a","permlink":"p","weight":10000"""))
        assertEquals(DappBrowserModel.mismatch("spike-bob", alice), page.error())
        send(b, page, keychain("addAccountAuthority", ""","username":"$alice","authorizedUsername":"evil","role":"Active","weight":1"""))
        assertEquals("Operation \"addAccountAuthority\" is not yet supported in Ecency browser.", page.error())
        send(b, page, keychain("signTx", ""","username":"$alice","tx":{}"""))
        assertTrue(page.error()!!.contains("not yet supported"))
        testScheduler.advanceUntilIdle()
        assertNull(b.current.prompt)
        assertTrue(app.broadcasts().isEmpty())
    }

    // ---- confirmation and signing ----

    @Test
    fun aVoteIsSignedOnlyAfterConfirmAndTheOriginComesFromTheWebView() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("vote", ""","username":"$alice","author":"spike-author","permlink":"p","weight":5000""", domain = "https://ecency.com/trust-me"))
        testScheduler.advanceUntilIdle()
        val prompt = assertNotNull(b.current.prompt)
        assertEquals("Vote", prompt.title)
        assertEquals("dapp.example", prompt.host, "the frame's origin, never the domain the page sent")
        assertEquals("POSTING", prompt.authority)
        assertEquals(alice, prompt.username)
        assertEquals("5000", prompt.operations.single().fields.first { it.name == "weight" }.value)
        assertTrue(page.answers.isEmpty())
        assertTrue(app.broadcasts().isEmpty(), "nothing before confirm")

        b.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertEquals(true, page.lastResponse["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1L, page.lastResponse["request_id"]!!.jsonPrimitive.content.toLong())
        assertNotNull(page.lastResponse["result"]!!.jsonObject["id"])
    }

    @Test
    fun rejectingAnswersAndSignsNothing() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("transfer", ""","to":"spike-bob","amount":"1","currency":"HIVE","memo":"""""))
        testScheduler.advanceUntilIdle()
        assertEquals("ACTIVE", b.current.prompt!!.authority)
        assertEquals("1.000 HIVE", b.current.prompt!!.operations.single().fields.first { it.name == "amount" }.value, "shown as it will be signed")
        b.reject()
        assertEquals(DappBrowserModel.REJECTED, page.error())
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun oneRequestAtATime() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("vote", ""","author":"a","permlink":"p","weight":100""", id = 1))
        testScheduler.advanceUntilIdle()
        send(b, page, keychain("vote", ""","author":"a","permlink":"p2","weight":100""", id = 2))
        testScheduler.advanceUntilIdle()
        assertEquals(DappBrowserModel.BUSY, page.error())
        assertEquals(2L, page.lastResponse["request_id"]!!.jsonPrimitive.content.toLong())
        assertEquals("p", b.current.prompt!!.operations.single().fields.first { it.name == "permlink" }.value)
    }

    @Test
    fun aPageThatMovedOnHearsNothingAndItsPromptGoes() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("vote", ""","author":"a","permlink":"p","weight":100"""))
        testScheduler.advanceUntilIdle()
        assertNotNull(b.current.prompt)
        b.pageChanged(b.current.activeTabId, "https://other.example/", "Other", true, false)
        assertNull(b.current.prompt)
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(page.answers.isEmpty())
        assertTrue(app.broadcasts().isEmpty())
        // the same origin on another path keeps it
        val b2 = browser(app)
        send(b2, page, keychain("vote", ""","author":"a","permlink":"p","weight":100"""))
        testScheduler.advanceUntilIdle()
        b2.pageChanged(b2.current.activeTabId, "$origin/other", "Other", true, false)
        assertNotNull(b2.current.prompt)
    }

    @Test
    fun ownerAndKeyChangesAreRefusedBeforeAnyPrompt() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val ops = listOf(
            """[["change_recovery_account",{"account_to_recover":"$alice","new_recovery_account":"evil","extensions":[]}]]""",
            """[["account_update",{"account":"$alice","posting":{"weight_threshold":1,"account_auths":[["evil",1]],"key_auths":[]},"memo_key":"STM1","json_metadata":""}]]""",
            """[["account_update2",{"account":"$alice","memo_key":"STM1","json_metadata":"","posting_json_metadata":"","extensions":[]}]]""",
            """[["decline_voting_rights",{"account":"$alice","decline":true}]]""",
        )
        ops.forEachIndexed { i, json ->
            send(b, page, keychain("broadcast", ""","method":"Active","operations":$json""", id = i + 1))
            testScheduler.advanceUntilIdle()
            assertNull(b.current.prompt, json)
            assertTrue(page.error() == BridgeGuards.OWNER_REFUSED || page.error() == BridgeGuards.KEYS_REFUSED || page.error() == BridgeGuards.NOT_SUPPORTED, "$json: ${page.error()}")
        }
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun witnessKeyAndPropertyChangesAreRefusedBeforeAnyPrompt() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val ops = listOf(
            """[["witness_update",{"owner":"$alice","url":"https://x","block_signing_key":"STM1","props":{"account_creation_fee":"3.000 HIVE","maximum_block_size":65536,"hbd_interest_rate":0},"fee":"0.000 HIVE"}]]""",
            """[["witness_set_properties",{"owner":"$alice","props":[["key","00"]],"extensions":[]}]]""",
        )
        ops.forEachIndexed { i, json ->
            send(b, page, keychain("broadcast", ""","method":"Active","operations":$json""", id = i + 1))
            testScheduler.advanceUntilIdle()
            assertNull(b.current.prompt, json)
            assertEquals(BridgeGuards.KEYS_REFUSED, page.error(), json)
            assertEquals(BridgeGuards.KEYS_REFUSED, BridgeGuards.refusal(Json.parseToJsonElement(json).jsonArray, signsHere = false), json)
        }
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun ownerAndKeyChangesAreRefusedForAccountsThatSignElsewhereToo() = runTest {
        // a HiveSigner account would sign anything on HiveSigner's page: the bridge's own refusal is the only guard
        val app = signedIn(this, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("broadcast", ""","operations":[["change_recovery_account",{"account_to_recover":"$alice","new_recovery_account":"evil","extensions":[]}]]""", id = 1))
        testScheduler.advanceUntilIdle()
        assertEquals(BridgeGuards.OWNER_REFUSED, page.error())
        send(b, page, keychain("broadcast", ""","operations":[["account_update",{"account":"$alice","posting":{"weight_threshold":1,"account_auths":[["evil",1]],"key_auths":[]},"memo_key":"STM1","json_metadata":""}]]""", id = 2))
        testScheduler.advanceUntilIdle()
        assertEquals(BridgeGuards.KEYS_REFUSED, page.error())
        send(b, page, keychain("broadcast", ""","operations":[["account_update2",{"account":"$alice","posting":{"weight_threshold":1,"account_auths":[["evil",1]],"key_auths":[]},"json_metadata":"","posting_json_metadata":"","extensions":[]}]]""", id = 3))
        testScheduler.advanceUntilIdle()
        assertEquals(BridgeGuards.KEYS_REFUSED, page.error())
        assertNull(b.current.prompt)
    }

    @Test
    fun peakVaultCannotDisguiseAnOperationByNumberOrSpelling() = runTest {
        // a HiveSigner account signs on HiveSigner's page, so nothing else would stop it: hived reads [10, {...}] as
        // account_update; the sheet would have said "Vote", POSTING
        val app = signedIn(this, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val b = browser(app)
        val page = Page()
        val active = """{"weight_threshold":1,"account_auths":[["evil",1]],"key_auths":[]}"""
        send(b, page, peakVault("""[[10,{"account":"$alice","active":$active,"memo_key":"STM1","json_metadata":""}]]""", keyRole = "active", resId = 1))
        testScheduler.advanceUntilIdle()
        assertEquals("No valid operations found.", page.error())
        send(b, page, peakVault("""[["account_update_operation",{"account":"$alice","active":$active,"memo_key":"STM1","json_metadata":""}]]""", keyRole = "active", resId = 2))
        testScheduler.advanceUntilIdle()
        assertEquals(BridgeGuards.NOT_SUPPORTED, page.error())
        // the same through the Keychain broadcast
        send(b, page, keychain("broadcast", ""","operations":[["vote_operation",{"voter":"$alice","author":"a","permlink":"p","weight":1}]]""", id = 3))
        testScheduler.advanceUntilIdle()
        assertEquals(BridgeGuards.NOT_SUPPORTED, page.error())
        assertNull(b.current.prompt)
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun theGuardsTakeOnlyNamedKnownOperations() {
        fun ops(json: String) = Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonArray
        assertEquals("No valid operations found.", BridgeGuards.refusal(ops("""[[10,{"account":"a"}]]"""), signsHere = false))
        assertEquals("No valid operations found.", BridgeGuards.refusal(ops("""[["vote"]]"""), signsHere = false))
        // #3633 review: a raw broadcast cannot send a `#` memo as plain text either
        assertEquals(BridgeGuards.HASH_MEMO_REFUSED, BridgeGuards.refusal(ops("""[["transfer",{"from":"a","to":"b","amount":"1.000 HIVE","memo":"#secret"}]]"""), signsHere = false))
        assertEquals(null, BridgeGuards.refusal(ops("""[["transfer",{"from":"a","to":"b","amount":"1.000 HIVE","memo":"public"}]]"""), signsHere = false))
        assertEquals(BridgeGuards.NOT_SUPPORTED, BridgeGuards.refusal(ops("""[["account_update_operation",{}]]"""), signsHere = false))
        assertNull(BridgeGuards.refusal(ops("""[["vote",{"voter":"a","author":"b","permlink":"p","weight":1}]]"""), signsHere = false))
    }

    @Test
    fun operationsTheSdkMapLeavesOutNeedTheActiveKey() = runTest {
        val app = signedIn(this, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("broadcast", ""","operations":[["feed_publish",{"publisher":"$alice","exchange_rate":{"base":"0.300 HBD","quote":"1.000 HIVE"}}]]"""))
        testScheduler.advanceUntilIdle()
        assertEquals("ACTIVE", b.current.prompt!!.authority, "never shown as posting")
        b.reject()
        // links and the SDK rule agree on the owner operation the map leaves out
        val decline = Json.parseToJsonElement("""["decline_voting_rights",{"account":"a","decline":true}]""") as kotlinx.serialization.json.JsonArray
        assertEquals(com.ecency.core.hive.KeyRole.OWNER, com.ecency.core.auth.OperationAuthority.of(decline))
        assertEquals(com.ecency.core.hive.KeyRole.OWNER, com.ecency.core.auth.OperationAuthority.forHiveUri(decline))
        listOf("limit_order_create2", "witness_update", "feed_publish", "escrow_transfer", "escrow_release").forEach {
            assertEquals(com.ecency.core.hive.KeyRole.ACTIVE, com.ecency.core.auth.OperationAuthority.of(Json.parseToJsonElement("""["$it",{}]""") as kotlinx.serialization.json.JsonArray), it)
        }
    }

    @Test
    fun aBroadcastIsSignedWithTheAuthorityItsOperationsNeed() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        // the page says posting, the transfer needs active: the confirmation and the signature say active
        send(b, page, keychain("broadcast", ""","method":"Posting","operations":[["transfer",{"from":"$alice","to":"spike-bob","amount":"1.000 HIVE","memo":""}]]"""))
        testScheduler.advanceUntilIdle()
        assertEquals("ACTIVE", b.current.prompt!!.authority)
        assertEquals("Transfer", b.current.prompt!!.operations.single().title)
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertTrue(page.lastResponse["success"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun everyConfirmationIsItsOwnAction() = runTest {
        val app = signedIn(this)
        val ids = mutableListOf<String>()
        var n = 0
        app.env.idempotencyKey = { "action-${n++}".also { ids += it } }
        val b = browser(app)
        val page = Page()
        repeat(2) { i ->
            send(b, page, keychain("transfer", ""","to":"spike-bob","amount":"1","currency":"HIVE","memo":""""", id = i + 1))
            testScheduler.advanceUntilIdle()
            b.confirm()
            testScheduler.advanceUntilIdle()
        }
        assertEquals(listOf("action-0", "action-1"), ids, "one id per confirmation")
        assertEquals(2, app.broadcasts().size, "the same transfer twice is two transfers")
    }

    @Test
    fun aKeyAccountIsRefusedWhatItCannotSerialize() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        // (limit_order_create2 has a serializer since #3635; an escrow transfer has none)
        send(b, page, keychain("broadcast", ""","operations":[["escrow_transfer",{"from":"$alice","to":"spike-bob","agent":"spike-agent","escrow_id":1,"hbd_amount":"0.000 HBD","hive_amount":"1.000 HIVE","fee":"0.001 HIVE","ratification_deadline":"2030-01-01T00:00:00","escrow_expiration":"2030-02-01T00:00:00","json_meta":""}]]"""))
        testScheduler.advanceUntilIdle()
        assertEquals(BridgeGuards.NOT_SUPPORTED, page.error())
        assertNull(b.current.prompt)
    }

    @Test
    fun theConfirmationWarnsOnListedPhishingDomains() = runTest {
        val app = signedIn(this)
        val b = browser(app, "https://leodefi.blogspot.com/")
        val page = Page()
        b.bridgeMessage(b.current.activeTabId, "https://leodefi.blogspot.com", true, keychain("vote", ""","author":"a","permlink":"p","weight":100"""), page.reply)
        testScheduler.advanceUntilIdle()
        assertEquals("leodefi.blogspot.com", b.current.prompt!!.blacklisted)
        assertEquals("leodefi.blogspot.com", DappBlacklist.check("WWW.leodefi.blogspot.com"))
        assertEquals("atomichub.in", DappBlacklist.check("x.atomichub.in"))
        assertNull(DappBlacklist.check("notatomichub.in"))
        assertNull(DappBlacklist.check("peakd.com"))
    }

    @Test
    fun signBufferAnswersAVerifiableSignatureAfterConfirm() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val message = "ecency-login-1791288000"
        send(b, page, keychain("signBuffer", ""","username":"$alice","message":"$message","method":"Posting""""))
        testScheduler.advanceUntilIdle()
        val prompt = b.current.prompt!!
        assertEquals("Sign Message", prompt.title)
        assertEquals(message, prompt.details.single { it.name == DappBrowserModel.MESSAGE }.value)
        assertTrue(page.answers.isEmpty())
        b.confirm()
        testScheduler.advanceUntilIdle()
        val response = page.lastResponse
        assertTrue(response["success"]!!.jsonPrimitive.content.toBoolean())
        val signature = response["result"]!!.jsonPrimitive.content
        assertEquals(AppTestKeys.pub(alice, KeyRole.POSTING), response["publicKey"]!!.jsonPrimitive.content)
        assertEquals(AppTestKeys.pub(alice, KeyRole.POSTING), Signature.fromHex(signature).recover(BridgeSignatures.digest(message)).toString())
        assertTrue(app.broadcasts().isEmpty(), "a message signature broadcasts nothing")
    }

    @Test
    fun anotherAppsHiveSignerLoginMessageIsSignedAfterTheConfirmation() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val messages = listOf("login", "posting", "offline").map {
            """{"signed_message":{"type":"$it","app":"other.app"},"authors":["$alice"],"timestamp":1}"""
        } + """{"signed_message":{"type":"login","app":"ecency.app","app":"other.app"},"authors":["$alice"],"timestamp":1}""" +
            """{"signed_message":{"type":"login","app":"other.app"},"authors":["$alice"],"timestamp":1"""
        messages.forEachIndexed { i, message ->
            send(b, page, keychain("signBuffer", ""","message":${JsonPrimitive(message)},"method":"Posting"""", id = i + 1))
            testScheduler.advanceUntilIdle()
            assertNotNull(b.current.prompt, "asked, not refused: $message")
            b.confirm()
            testScheduler.advanceUntilIdle()
            assertTrue(page.lastResponse["success"]!!.jsonPrimitive.content.toBoolean(), message)
        }
        assertFalse(BridgeSignatures.reserved("""{"signed_message":{"type":"login","app":"other.app"}}"""))
    }

    @Test
    fun anotherAppsPostingOrOfflineMessageWarnsOfLastingPermission() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        fun message(type: String, app: String = "other.app") = """{"signed_message":{"type":"$type","app":"$app"},"authors":["$alice"],"timestamp":1}"""
        val cases = listOf(
            message("login") to null,
            message("posting") to "This gives other.app lasting permission to post, vote and comment as you.",
            message(" Offline ", " peak.app ") to "This gives peak.app lasting permission to post, vote and comment as you.",
            // inside a JSON string too
            JsonPrimitive(message("posting")).toString() to "This gives other.app lasting permission to post, vote and comment as you.",
            "plain text" to null,
        )
        cases.forEachIndexed { i, (text, warning) ->
            send(b, page, keychain("signBuffer", ""","message":${JsonPrimitive(text)},"method":"Posting"""", id = i + 1))
            testScheduler.advanceUntilIdle()
            assertEquals(warning, b.current.prompt!!.warning, text)
            b.reject()
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun aMessageShapedLikeAnEcencySignedMessageIsRefusedBeforeAPrompt() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val exact = com.ecency.core.auth.HiveSignerMessages.message("code", null, alice, 1_791_288_000_000)
        val reordered = """{ "timestamp" : 1791288000, "authors" : [ "$alice" ],
            "signed_message" : { "app" : "ecency.app", "type" : "code" } }"""
        val escaped = """{"\u0073igned_message":{"type":"code","app":"ecency\u002eapp"},"authors":["$alice"],"timestamp":1}"""
        val otherAppCode = """{"signed_message":{"type":"code","app":"other.app"},"authors":["$alice"],"timestamp":1}"""
        val otherAppRefresh = """{"signed_message":{"type":" Refresh ","app":"other.app"},"authors":["$alice"],"timestamp":1}"""
        val ecencyCased = """{"signed_message":{"type":"offline","app":" Ecency.App "},"authors":["$alice"],"timestamp":1}"""
        val noApp = """{"signed_message":{"type":"code"},"authors":["$alice"],"timestamp":1}"""
        val notAnObject = """{"signed_message":"login","authors":["$alice"]}"""
        val arrayApp = """{"signed_message":{"type":"login","app":["ecency.app"]},"authors":["$alice"]}"""
        // a duplicate key reads as its last value, as JSON.parse keeps it
        val duplicateApp = """{"signed_message":{"type":"login","app":"other.app","app":"ecency.app"},"authors":["$alice"]}"""
        val duplicateType = """{"signed_message":{"type":"login","type":"code","app":"other.app"},"authors":["$alice"]}"""
        val unparsed = """{"signed_message":{"type":"code","app":"other.app"}"""
        val messages = listOf(exact, reordered, escaped, otherAppCode, otherAppRefresh, ecencyCased, noApp, notAnObject, arrayApp, duplicateApp, duplicateType, unparsed) +
            listOf("code", "posting", "login", "refresh", "offline").map { com.ecency.core.auth.HiveSignerMessages.message(it, "x.app".takeIf { _ -> it == "login" }, alice, 1_000) } +
            JsonPrimitive(exact).toString() + JsonPrimitive(otherAppCode).toString()
        messages.forEachIndexed { i, message ->
            send(b, page, keychain("signBuffer", ""","message":${JsonPrimitive(message)},"method":"Posting"""", id = i + 1))
            testScheduler.advanceUntilIdle()
            assertNull(b.current.prompt, "refused before a prompt: $message")
            assertEquals(DappBrowserModel.RESERVED_MESSAGE, page.error(), message)
        }
        // the Peak Vault path refuses it too
        send(b, page, peakVault("""[["peakvault-sign_buffer",{"message":${JsonPrimitive(exact)}}]]""", resId = 50))
        testScheduler.advanceUntilIdle()
        assertNull(b.current.prompt)
        assertEquals(DappBrowserModel.RESERVED_MESSAGE, page.error())
        assertTrue(app.broadcasts().isEmpty())
    }

    /**
     * Owner report: ecency.com's own login (a `code` for ecency.app) and Raidstead's wallet sign-in (a `login` for
     * ecency.app with an audience) are Ecency's messages on Ecency's site: from the top frame of https://ecency.com
     * they reach the confirmation, as in RN. Any other site, a look-alike host or a subdomain is still refused.
     */
    @Test
    fun ecencyComsOwnLoginMessagesReachThePromptOnlyThere() = runTest {
        val app = signedIn(this)
        val code = com.ecency.core.auth.HiveSignerMessages.message("code", null, alice, 1_791_288_000_000)
        val raidstead = """{"signed_message":{"type":"login","app":"ecency.app","audience":"raidstead"},"authors":["$alice"],"timestamp":1791288000}"""
        val ecency = browser(app, "https://ecency.com/raidstead")
        val page = Page()
        listOf(code, raidstead).forEachIndexed { i, message ->
            send(ecency, page, keychain("signBuffer", ""","message":${JsonPrimitive(message)},"method":"Posting"""", id = i + 1), frame = "https://ecency.com")
            testScheduler.advanceUntilIdle()
            assertNotNull(ecency.current.prompt, "asked on ecency.com: $message")
            ecency.confirm()
            testScheduler.advanceUntilIdle()
            assertTrue(page.lastResponse["success"]!!.jsonPrimitive.content.toBoolean(), message)
            val signature = page.lastResponse["result"]!!.jsonPrimitive.content
            assertEquals(AppTestKeys.pub(alice, KeyRole.POSTING), Signature.fromHex(signature).recover(BridgeSignatures.digest(message)).toString())
        }
        // on ecency.com too, only those two shapes with the posting key: never a token message or another Ecency message
        val refusedThere = listOf(
            com.ecency.core.auth.HiveSignerMessages.message("refresh", null, alice, 1_791_288_000_000) to "Posting",
            com.ecency.core.auth.HiveSignerMessages.message("posting", null, alice, 1_791_288_000_000) to "Posting",
            com.ecency.core.auth.HiveSignerMessages.message("offline", null, alice, 1_791_288_000_000) to "Posting",
            """{"signed_message":{"type":"code","app":"someapp"},"authors":["$alice"],"timestamp":1791288000}""" to "Posting",
            """{"signed_message":{"type":"code","app":"ecency.app","audience":"raidstead"},"authors":["$alice"],"timestamp":1791288000}""" to "Posting",
            """{"signed_message":{"type":"login","app":"ecency.app"},"authors":["$alice"],"timestamp":1791288000}""" to "Posting",
            code to "Active",
            raidstead to "Active",
        )
        refusedThere.forEachIndexed { i, (message, method) ->
            send(ecency, page, keychain("signBuffer", ""","message":${JsonPrimitive(message)},"method":"$method"""", id = 20 + i), frame = "https://ecency.com")
            testScheduler.advanceUntilIdle()
            assertNull(ecency.current.prompt, "$method $message")
            assertEquals(DappBrowserModel.RESERVED_MESSAGE, page.error(), "$method $message")
        }
        for (site in listOf("https://ecency.com.evil.io", "https://alpha.ecency.com", "https://peakd.com")) {
            val other = browser(app, "$site/login")
            val otherPage = Page()
            send(other, otherPage, keychain("signBuffer", ""","message":${JsonPrimitive(code)},"method":"Posting"""", id = 9, domain = site), frame = site)
            testScheduler.advanceUntilIdle()
            assertNull(other.current.prompt, site)
            assertEquals(DappBrowserModel.RESERVED_MESSAGE, otherPage.error(), site)
        }
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun ordinaryLoginMessagesStillReachThePrompt() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        listOf("Log in to dapp.example #1791288000", """{"login":"dapp.example","nonce":"abc","account":"$alice"}""", "[1,2]").forEachIndexed { i, message ->
            send(b, page, keychain("signBuffer", ""","message":${JsonPrimitive(message)},"method":"Posting"""", id = i + 1))
            testScheduler.advanceUntilIdle()
            assertEquals(message, b.current.prompt!!.details.single { it.name == DappBrowserModel.MESSAGE }.value)
            b.reject()
        }
        send(b, page, peakVault("""[["peakvault-sign_buffer",{"message":"hello"}]]""", resId = 9))
        testScheduler.advanceUntilIdle()
        assertEquals("hello", b.current.prompt!!.details.single { it.name == DappBrowserModel.MESSAGE }.value)
    }

    // ---- what is shown is what is signed ----

    private fun field(b: DappBrowserModel, name: String) = b.current.prompt!!.operations.single().fields.single { it.name == name }.value

    @Test
    fun paddingCannotPushACustomJsonsTransferOutOfView() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val payload = """{"contractName":"tokens","contractAction":"transfer","contractPayload":{"symbol":"SWAP.HIVE","to":"spike-mallory","quantity":"1000"}}"""
        val json = " ".repeat(2_001) + payload
        send(b, page, keychain("custom", ""","id":"ssc-mainnet-hive","method":"Active","json":${JsonPrimitive(json)},"display_msg":${JsonPrimitive(" ".repeat(2_001) + "send 1000 to spike-mallory")}"""))
        testScheduler.advanceUntilIdle()
        val shown = field(b, "json")
        assertTrue("spike-mallory" in shown && "\"quantity\":\"1000\"" in shown, "the recipient and amount are shown: $shown")
        assertTrue(DappBrowserModel.blankRun(2_001) in shown, "the padding shows as a marker")
        assertTrue(shown.length < 400, "the padding does not fill the view")
        val said = b.current.prompt!!.details.single().value
        assertTrue("spike-mallory" in said && said.length < 200, "the page's message is shown whole: $said")
        b.confirm()
        testScheduler.advanceUntilIdle()
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals(json, op[1].jsonObject["json"]!!.jsonPrimitive.content, "what is signed is what the page sent, padding included")
    }

    @Test
    fun bidiControlsShowAsMarkersAndStayInWhatIsSigned() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val json = "{\"to\":\"\u202Eyrallam-ekips\",\"quantity\":\"1000\"}"
        send(b, page, keychain("custom", ""","id":"ssc-mainnet-hive","json":${JsonPrimitive(json)}"""))
        testScheduler.advanceUntilIdle()
        val shown = field(b, "json")
        assertTrue("[U+202E]yrallam-ekips" in shown, "the override shows as a marker, the name in reading order: $shown")
        assertFalse('\u202E' in shown)
        listOf('\u202A', '\u202B', '\u202C', '\u202D', '\u2066', '\u2067', '\u2068', '\u2069', '\u200E', '\u200F', '\u061C').forEach { c ->
            val marker = "[U+" + c.code.toString(16).uppercase().padStart(4, '0') + "]"
            assertEquals("a${marker}b", BridgeDisplay.shown("a${c}b"), "U+${c.code.toString(16)}")
        }
        b.confirm()
        testScheduler.advanceUntilIdle()
        val op = app.broadcasts().single().params.jsonArray.single().jsonObject["operations"]!!.jsonArray.single().jsonArray
        assertEquals(json, op[1].jsonObject["json"]!!.jsonPrimitive.content, "the signed json keeps the character")
    }

    @Test
    fun aLongMemoIsShownWholeAndShortSpacingStays() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val memo = "a  b " + "x".repeat(3_000) + "\n\n" + "\t".repeat(50) + "#end-of-memo"
        send(b, page, keychain("transfer", ""","to":"spike-bob","amount":"1","currency":"HIVE","memo":${JsonPrimitive(memo)}"""))
        testScheduler.advanceUntilIdle()
        val shown = field(b, "memo")
        assertTrue(shown.startsWith("a  b "), "short spacing is kept")
        assertTrue(shown.endsWith(DappBrowserModel.blankRun(52) + "#end-of-memo"), "nothing is cut: ${shown.takeLast(80)}")
    }

    @Test
    fun paddingCannotPushASignedMessageOutOfView() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val message = "Log in to dapp.example" + "\n".repeat(2_001) + "and approve 1000 HIVE to spike-mallory"
        send(b, page, keychain("signBuffer", ""","message":${JsonPrimitive(message)},"method":"Active""""))
        testScheduler.advanceUntilIdle()
        val shown = b.current.prompt!!.details.single { it.name == DappBrowserModel.MESSAGE }.value
        assertTrue(shown.endsWith("approve 1000 HIVE to spike-mallory"), "the whole message is shown: ${shown.takeLast(80)}")
        assertTrue(DappBrowserModel.blankRun(2_001) in shown && shown.length < 200, "the line breaks show as a marker")
        b.confirm()
        testScheduler.advanceUntilIdle()
        val signature = page.lastResponse["result"]!!.jsonPrimitive.content
        assertEquals(AppTestKeys.pub(alice, KeyRole.ACTIVE), Signature.fromHex(signature).recover(BridgeSignatures.digest(message)).toString(), "the signed message is the page's, unchanged")
    }

    @Test
    fun aRequestTooLargeToShowIsRefusedAndTheUserIsTold() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("custom", ""","id":"x","json":${JsonPrimitive("y".repeat(DappBrowserModel.MAX_SHOWN + 1))}"""))
        testScheduler.advanceUntilIdle()
        assertNull(b.current.prompt, "nothing to confirm")
        assertEquals(DappBrowserModel.TOO_LARGE, page.error())
        assertEquals(DappBrowserModel.tooLargeNotice, b.current.notice, "the user is told")
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun signBufferNeedsThePinWhenTheLockIsOn() = runTest {
        val app = signedIn(this)
        PinLock(KeyVault(app.store), 16).setPin("1234")
        app.env.appLock.refresh()
        testScheduler.advanceUntilIdle()
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("signBuffer", ""","message":"hello""""))
        testScheduler.advanceUntilIdle()
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(PinPurpose.VERIFY, app.env.appLock.current.prompt?.purpose)
        assertTrue(page.answers.isEmpty(), "no signature before the PIN")
        app.env.appLock.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(DappBrowserModel.REJECTED, page.error())
    }

    @Test
    fun signBufferWithoutTheKeyIsRefusedBeforeAPrompt() = runTest {
        val app = signedIn(this, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("signBuffer", ""","message":"hello","method":"Active""""))
        testScheduler.advanceUntilIdle()
        assertEquals("Active key not available.", page.error())
        send(b, page, keychain("signBuffer", ""","message":"hello","method":"Memo"""", id = 2))
        assertEquals("Only posting and active signatures are supported.", page.error())
        send(b, page, keychain("signBuffer", ""","message":{"type":"Buffer"}""", id = 3))
        assertEquals("Invalid message.", page.error())
        assertNull(b.current.prompt)
    }

    @Test
    fun anAccountSwitchBeforeConfirmSignsNothing() = runTest {
        val app = signedIn(this)
        KeyVault(app.store).save(VaultAccount("spike-bob", AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif("spike-bob", KeyRole.POSTING), accessToken = "access-spike-bob"))
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("vote", ""","author":"a","permlink":"p","weight":100"""))
        testScheduler.advanceUntilIdle()
        app.env.session.switchTo("spike-bob")
        testScheduler.advanceUntilIdle()
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(DappBrowserModel.ACCOUNT_CHANGED, page.error())
        assertTrue(app.broadcasts().isEmpty())
    }

    // ---- Peak Vault ----

    private fun peakVault(ops: String, keyRole: String = "posting", broadcast: Boolean = true, account: String = alice, resId: Int = 0) =
        """{"name":"pvRequest","data":{"id":"sign-request","resId":$resId,"account":"$account","operations":$ops,"keyRole":"$keyRole","broadcast":$broadcast,"displayMessage":{"title":"Vote"},"sourceUrl":"https://spoofed.example"}}"""

    @Test
    fun peakVaultConnectSignOnlyAndBroadcast() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        send(b, page, peakVault("""[["peakvault-connect",{}]]""", account = ""))
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("peakvault", page.last["kind"]!!.jsonPrimitive.content)
        assertEquals(alice, page.lastResponse["result"]!!.toString().trim('[', ']', '"'))

        send(b, page, peakVault("""[["vote",{"voter":"$alice","author":"a","permlink":"p","weight":100}]]""", broadcast = false, resId = 1))
        assertEquals("Sign-only mode is not supported in Ecency browser. Please use requestBroadcast instead.", page.error())
        send(b, page, peakVault("""[["vote",{"voter":"$alice","author":"a","permlink":"p","weight":100}]]""", keyRole = "owner", resId = 2))
        assertEquals("Invalid keyRole: \"owner\"", page.error())
        send(b, page, peakVault("""[["peakvault-decode",{"secret":"#x"}]]""", keyRole = "memo", resId = 3))
        assertTrue(page.error()!!.contains("not yet supported"))

        send(b, page, peakVault("""[["vote",{"voter":"$alice","author":"a","permlink":"p","weight":100}]]""", resId = 4))
        testScheduler.advanceUntilIdle()
        assertEquals("dapp.example", b.current.prompt!!.host, "never the page's sourceUrl")
        // the page's title is shown as the page's words, never as the sheet's title
        assertEquals("Broadcast", b.current.prompt!!.title)
        assertEquals(LinkField("Message from dapp.example", "\u201CVote\u201D"), b.current.prompt!!.details.single())
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertEquals(4L, page.last["id"]!!.jsonPrimitive.content.toLong())
        assertEquals("", page.lastResponse["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun peakVaultConnectAsksOncePerOriginBeforeNamingTheAccount() = runTest {
        val app = signedIn(this)
        KeyVault(app.store).save(VaultAccount("spike-bob", AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif("spike-bob", KeyRole.POSTING), accessToken = "access-spike-bob"))
        val b = browser(app)
        val page = Page()
        val connect = peakVault("""[["peakvault-connect",{}]]""", account = "")
        send(b, page, connect)
        testScheduler.advanceUntilIdle()
        assertTrue(page.answers.isEmpty(), "the page learns nothing before the user answers")
        val prompt = b.current.prompt!!
        assertEquals(DappBrowserModel.connectTitle("dapp.example"), prompt.title)
        assertEquals(alice, prompt.username)
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, page.lastResponse["result"]!!.toString().trim('[', ']', '"'))
        // asked once: the same origin hears it again without a prompt
        send(b, page, connect.replace("\"resId\":0", "\"resId\":1"))
        testScheduler.advanceUntilIdle()
        assertNull(b.current.prompt)
        assertEquals(2, page.answers.size)
        assertEquals(alice, page.lastResponse["result"]!!.toString().trim('[', ']', '"'))
        // another account: asked again
        app.env.session.switchTo("spike-bob")
        testScheduler.advanceUntilIdle()
        send(b, page, connect)
        testScheduler.advanceUntilIdle()
        assertEquals("spike-bob", b.current.prompt!!.username)
        b.reject()
        assertEquals(DappBrowserModel.REJECTED, page.error())
        // a refusal holds for this page load: the page cannot ask again and again
        send(b, page, connect)
        testScheduler.advanceUntilIdle()
        assertNull(b.current.prompt, "no new question on the same page load")
        assertEquals(DappBrowserModel.REJECTED, page.error())
        // the page reloading itself (a new document on the same site) does not get to ask again
        b.documentStarted(b.current.activeTabId, "$origin/again")
        send(b, page, connect)
        testScheduler.advanceUntilIdle()
        assertNull(b.current.prompt, "a reload of the same site keeps the refusal")
        // after the tab left the site, it may ask again, and the user answers again
        b.documentStarted(b.current.activeTabId, "https://elsewhere.example/")
        b.documentStarted(b.current.activeTabId, "$origin/")
        send(b, page, connect)
        testScheduler.advanceUntilIdle()
        assertEquals("spike-bob", b.current.prompt!!.username, "asked again after a new page load")
        b.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("spike-bob", page.lastResponse["result"]!!.toString().trim('[', ']', '"'))
        // back to the first account: its approval was forgotten with the account change
        app.env.session.switchTo(alice)
        testScheduler.advanceUntilIdle()
        send(b, page, connect)
        testScheduler.advanceUntilIdle()
        assertEquals(alice, b.current.prompt!!.username, "an approval is per account")
        // a refusal goes with an account change too
        b.reject()
        app.env.session.switchTo("spike-bob")
        testScheduler.advanceUntilIdle()
        app.env.session.switchTo(alice)
        testScheduler.advanceUntilIdle()
        send(b, page, connect)
        testScheduler.advanceUntilIdle()
        assertEquals(alice, b.current.prompt?.username, "asked again after the account changed")
    }

    @Test
    fun closingTheBrowserRejectsAWaitingRequest() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        send(b, page, keychain("vote", ""","author":"a","permlink":"p","weight":100"""))
        testScheduler.advanceUntilIdle()
        b.close()
        assertEquals(DappBrowserModel.REJECTED, page.error())
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun requestsFromABackgroundTabAreRefused() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        val page = Page()
        val first = b.current.activeTabId
        b.addTab()
        b.bridgeMessage(first, origin, true, keychain("vote", ""","author":"a","permlink":"p","weight":100"""), page.reply)
        assertEquals(DappBrowserModel.NOT_IN_FRONT, page.error())
        assertNull(b.current.prompt)
    }

    @Test
    fun nothingIsAskedBehindTheLock() = runTest {
        val app = signedIn(this)
        val b = browser(app)
        PinLock(KeyVault(app.store), 16).setPin("1234")
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.appLock.current.hidesContent)
        val page = Page()
        send(b, page, keychain("vote", ""","author":"a","permlink":"p","weight":100"""))
        testScheduler.advanceUntilIdle()
        assertEquals(DappBrowserModel.LOCKED, page.error())
        assertNull(b.current.prompt)
    }

    @Test
    fun originsAreParsedStrictly() {
        assertEquals("https://dapp.example", BrowserPolicy.httpsOrigin("https://Dapp.Example/path?q#f"))
        assertEquals("https://dapp.example:8443", BrowserPolicy.httpsOrigin("https://dapp.example:8443/"))
        assertEquals("https://dapp.example", BrowserPolicy.httpsOrigin("https://dapp.example:443"))
        assertNull(BrowserPolicy.httpsOrigin("https://user@dapp.example/"))
        assertNull(BrowserPolicy.httpsOrigin("http://dapp.example/"))
        assertNull(BrowserPolicy.httpsOrigin("https:///x"))
        assertNull(BrowserPolicy.httpsOrigin("https://dapp.example:99999/"))
        assertNull(BrowserPolicy.bridgeOrigin("https://dapp.example", true, "https://dapp.example.evil.com/"))
        assertNull(BrowserPolicy.bridgeOrigin("https://a.hivesigner.com", true, "https://a.hivesigner.com/"))
        assertEquals("https://dapp.example", BrowserPolicy.bridgeOrigin("https://dapp.example/", true, "https://dapp.example/x"))
        // a frame never counts (Kotlin/Native once compiled this guard away; see BrowserPolicy.bridgeOrigin)
        assertNull(BrowserPolicy.bridgeOrigin("https://dapp.example", false, "https://dapp.example/x"))
        assertEquals("peakd.com", BrowserPolicy.displayHost("https://peakd.com/@alice"))
    }

    @Test
    fun theBridgeScriptStaysOutOfFramesHttpAndHiveSigner() {
        DappBridgeScript.Platform.entries.forEach { p ->
            val js = DappBridgeScript.source(p)
            assertTrue("if (window.top !== window) return;" in js)
            assertTrue("if (window.location.protocol !== 'https:') return;" in js)
            assertTrue("hivesigner" in js)
            assertFalse("ReactNativeWebView" in js)
            assertFalse("__TRANSPORT__" in js)
        }
        assertTrue("messageHandlers.${DappBridgeScript.CHANNEL}" in DappBridgeScript.source(DappBridgeScript.Platform.IOS))
        assertTrue("window.${DappBridgeScript.CHANNEL}" in DappBridgeScript.source(DappBridgeScript.Platform.ANDROID))
    }

    @Test
    fun answersAreJsonTheReceiverParses() = runTest {
        val app = signedIn(this, account = null)
        val b = browser(app)
        val page = Page()
        // a request id or text that tries to break out of the answer stays data
        send(b, page, """{"name":"swRequest_hive","request_id":7,"data":{"type":"vote\"</script><script>alert(1)//"}}""")
        testScheduler.advanceUntilIdle()
        assertEquals(DappBrowserModel.NO_ACCOUNT, page.error())
        assertEquals(JsonPrimitive(7L).content, page.lastResponse["request_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun bridgeRefusesOwnerAccountUpdatesAndMixedCustomJson() {
        fun ops(s: String) = Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonArray
        val auth = """{"weight_threshold":1,"account_auths":[],"key_auths":[["STM1",1]]}"""
        assertEquals(BridgeGuards.OWNER_REFUSED, BridgeGuards.refusal(ops("""[["account_update2",{"account":"a","owner":$auth,"json_metadata":"","posting_json_metadata":""}]]"""), signsHere = false))
        assertEquals(
            com.ecency.core.auth.OperationAuthority.MIXED_AUTHORITIES,
            BridgeGuards.refusal(ops("""[["custom_json",{"required_auths":["a"],"required_posting_auths":["a"],"id":"x","json":"{}"}]]"""), signsHere = false),
        )
        assertNull(BridgeGuards.refusal(ops("""[["custom_json",{"required_auths":[],"required_posting_auths":["a"],"id":"x","json":"{}"}]]"""), signsHere = false))
        assertEquals(
            com.ecency.core.auth.OperationAuthority.MIXED_AUTHORITIES,
            BridgeGuards.refusal(ops("""[["vote",{"voter":"a","author":"b","permlink":"c","weight":1}],["transfer",{"from":"a","to":"b","amount":"1.000 HIVE","memo":""}]]"""), signsHere = false),
        )
    }
}
