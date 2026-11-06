package com.ecency.core.app

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.auth.HiveSignerMessages
import com.ecency.core.auth.HiveUri
import com.ecency.core.hive.KeyRole
import com.ecency.core.hive.Signature
import com.ecency.core.hive.Transaction
import com.ecency.core.hive.Vote
import com.ecency.core.render.url.WebUrl
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

/** Link decisions: confirm before anything happens, callbacks only as the parser accepted them, owner refused. */
class AuthLinkModelTest {
    private val alice = "spike-alice"
    private val cb = "otherapp://done"

    private suspend fun signedIn(scope: TestScope, account: VaultAccount = VaultAccount(
        alice, AuthType.MASTER_PASSWORD,
        postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE), accessToken = "access-$alice",
    )): TestApp {
        val app = appWithAccounts(scope)
        KeyVault(app.store).run { save(account); setCurrentUsername(account.username) }
        app.env.session.start()
        scope.testScheduler.advanceUntilIdle()
        return app
    }

    private fun params(url: String) = WebUrl.parse(url)!!.searchParams.toMap()

    private fun voteLink(callback: String? = cb) =
        HiveUri.encodeOps(JsonArray(listOf(OperationJson.encode(Vote("__signer", "author", "permlink", 10000)))), HiveUri.Params(callback = callback))

    @Test
    fun nonAuthLinksAreLeftToTheRouter() = runTest {
        val app = signedIn(this)
        assertFalse(app.env.authLinks.handle("https://ecency.com/@alice/post"))
    }

    @Test
    fun aSignLinkBroadcastsOnlyAfterConfirmAndAnswersTheCallback() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        assertTrue(links.handle(voteLink()))
        testScheduler.advanceUntilIdle()
        val prompt = assertIs<LinkPrompt.SignConfirm>(links.current.prompt)
        assertEquals("vote", prompt.operation)
        assertEquals("posting", prompt.authority)
        assertEquals(alice, prompt.fields.first { it.name == "voter" }.value, "the signer placeholder shows the account")
        assertEquals("otherapp://done", prompt.requester)
        assertTrue(app.broadcasts().isEmpty(), "nothing before confirm")

        links.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertEquals("success", params(links.current.callback!!)["status"])
        assertEquals(AuthLinkModel.SUCCESSFUL, links.current.notice)
    }

    @Test
    fun theRecipientAndAmountComeFirstAndPaddingCannotHideThem() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        val memo = "\n".repeat(500) + "x".repeat(1_500)
        val link = HiveUri.encodeOps(
            HiveUri.ops(
                "transfer" to buildJsonObject {
                    put("memo", JsonPrimitive(memo)); put("from", JsonPrimitive("__signer"))
                    put("to", JsonPrimitive("spike-mallory")); put("amount", JsonPrimitive("1000.000 HIVE"))
                },
            ),
        )
        assertTrue(links.handle(link))
        testScheduler.advanceUntilIdle()
        val prompt = assertIs<LinkPrompt.SignConfirm>(links.current.prompt)
        assertEquals(listOf("to", "amount"), prompt.fields.take(2).map { it.name }, "the recipient and amount lead: ${prompt.fields.map { it.name }}")
        val shown = prompt.fields.single { it.name == "memo" }.value
        assertEquals(DappBrowserModel.blankRun(500) + "x".repeat(1_500), shown, "the line breaks show as a marker")
        links.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(JsonPrimitive(memo).toString() in app.broadcasts().single().params.toString(), "the signed memo is the link's, unchanged")
    }

    @Test
    fun theAccountAnUpdateChangesLeadsALongMetadataField() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        val link = HiveUri.encodeOps(
            HiveUri.ops(
                "account_update2" to buildJsonObject {
                    put("json_metadata", JsonPrimitive("{\"about\":\"" + "x".repeat(2_000) + "\"}")); put("posting_json_metadata", JsonPrimitive(""))
                    put("extensions", JsonArray(emptyList())); put("account", JsonPrimitive("__signer"))
                },
            ),
        )
        assertTrue(links.handle(link))
        testScheduler.advanceUntilIdle()
        val prompt = assertIs<LinkPrompt.SignConfirm>(links.current.prompt)
        assertEquals("account", prompt.fields.first().name, "the account leads: ${prompt.fields.map { it.name }}")
        links.decline()
    }

    @Test
    fun aLinkFromContentNeverTellsItsRequesterAnythingBeforeTheUserWasAsked() = runTest {
        val app = signedIn(this, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val links = app.env.authLinks
        // a login link in a post body: its author hears nothing, not even that Ecency is installed
        assertTrue(links.handleFromContent("ecency://login?username=$alice&callback=$cb"))
        testScheduler.advanceUntilIdle()
        links.dismiss()
        assertNull(links.current.callback, "not_supported stays on the device")
        // a malformed transfer (AuthLink.Invalid / invalid_params) answers nothing either
        links.handleFromContent("ecency://transfer?to=bob&amount=abc&callback=$cb")
        testScheduler.advanceUntilIdle()
        assertNull(links.current.callback)
        // the same link from another app is answered, once the user saw why
        links.handle("ecency://login?username=spike-nobody&callback=$cb")
        testScheduler.advanceUntilIdle()
        links.dismiss()
        assertEquals("not_supported", params(links.current.callback!!)["error"])
    }

    @Test
    fun callbackMessagesStayEnglishInAnyLanguage() = runTest {
        val app = signedIn(this, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val links = app.env.authLinks
        try {
            com.ecency.core.l10n.PseudoLocales.install(com.ecency.core.l10n.PseudoLocales.ACCENTED)
            links.handle("ecency://login?username=$alice&callback=$cb")
            testScheduler.advanceUntilIdle()
            links.dismiss()
            assertEquals(
                "Ecency no longer shares posting private keys with other apps. Sign in with HiveSigner or HiveAuth instead.",
                params(links.answers().single())["message"],
            )
        } finally {
            com.ecency.core.l10n.L10n.setLanguage(com.ecency.core.l10n.L10n.SOURCE)
        }
    }

    /** Every callback answer given, oldest first (the queue drained). */
    private fun AuthLinkModel.answers(): List<String> = buildList {
        while (true) {
            add(current.callback ?: break)
            consumeCallback()
        }
    }

    @Test
    fun aPushRefusalStaysSilentAndAUserLinkRefusalShowsWhenTheyOverlap() = runTest {
        for (pushFirst in listOf(true, false)) {
            var holder: HoldingDispatcher? = null
            val app = TestApp(this, ioWrap = { HoldingDispatcher(it).also { h -> holder = h } }) { TestApp.chain(it) ?: com.ecency.core.api.rpc.HttpResult(500, "") }
            KeyVault(app.store).run {
                save(VaultAccount(alice, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), accessToken = "access-$alice"))
                setCurrentUsername(alice)
            }
            app.env.session.start()
            testScheduler.advanceUntilIdle()
            val links = app.env.authLinks
            val io = holder!!
            // an operation the app cannot sign: refused after the account's vault read
            val unsupported = "hive://sign/op/WyJ2b3RlIix7fV0."
            io.hold = true
            if (pushFirst) links.handleFromPush(unsupported) else links.handle(unsupported)
            testScheduler.runCurrent()
            assertEquals(1, io.heldCount, "the first link waits in its vault read")
            if (pushFirst) links.handle(unsupported) else links.handleFromPush(unsupported)
            testScheduler.runCurrent()
            assertEquals(2, io.heldCount)
            // the push's route goes on first, the user's second, and the other way round
            io.releaseOne()
            testScheduler.runCurrent()
            if (pushFirst) assertNull(links.current.prompt, "the push refuses without a word") else assertIs<LinkPrompt.Refused>(links.current.prompt)
            links.dismiss()
            io.releaseOne()
            io.hold = false
            testScheduler.advanceUntilIdle()
            if (pushFirst) assertIs<LinkPrompt.Refused>(links.current.prompt, "the user's own link says why") else assertNull(links.current.prompt, "the push stays silent")
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.interleaved(contentFirst: Boolean): List<String> {
        var holder: HoldingDispatcher? = null
        val app = TestApp(this, ioWrap = { HoldingDispatcher(it).also { h -> holder = h } }) { TestApp.chain(it) ?: com.ecency.core.api.rpc.HttpResult(500, "") }
        KeyVault(app.store).run {
            save(VaultAccount(alice, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), accessToken = "access-$alice"))
            setCurrentUsername(alice)
        }
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val links = app.env.authLinks
        val io = holder!!
        io.hold = true
        // an operation the app cannot sign: refused after the account's vault read, answered `not_supported`
        val unsignable = JsonArray(listOf(JsonArray(listOf(JsonPrimitive("vote"), buildJsonObject {}))))
        val content = HiveUri.encodeOps(unsignable, HiveUri.Params(callback = "postapp://cb"))
        val external = HiveUri.encodeOps(unsignable, HiveUri.Params(callback = "otherapp://ext"))
        // the first link stops in its vault read, the second starts and stops there too, then each goes on
        if (contentFirst) links.handleFromContent(content) else links.handle(external)
        testScheduler.runCurrent()
        assertEquals(1, io.heldCount)
        if (contentFirst) links.handle(external) else links.handleFromContent(content)
        testScheduler.runCurrent()
        assertEquals(2, io.heldCount)
        io.releaseOne()
        testScheduler.runCurrent()
        io.releaseOne()
        io.hold = false
        testScheduler.advanceUntilIdle()
        links.dismiss()
        testScheduler.advanceUntilIdle()
        return links.answers()
    }

    @Test
    fun aContentLinkAndAnExternalLinkRoutedTogetherKeepTheirOwnOrigin() = runTest {
        // only the external requester ever hears not_supported, whichever link started first and resumed first
        for (contentFirst in listOf(true, false)) {
            val answer = interleaved(contentFirst).single()
            assertTrue(answer.startsWith("otherapp://ext"), "contentFirst=$contentFirst: $answer")
            assertEquals("not_supported", params(answer)["error"])
        }
    }

    @Test
    fun aContentLinkTheUserDeclinedAnswersNoOne() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        links.handleFromContent(voteLink())
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(links.current.prompt)
        assertNull(links.current.callback)
        links.decline()
        testScheduler.advanceUntilIdle()
        // a decline would tell the post's author that this account is here and signs with a key
        assertTrue(links.answers().isEmpty(), "a quiet request hears nothing, a decline included")
        // a page's auth request too
        links.handle("ecency://auth-request?callback=otherapp://page&request_id=p", pageHost = "dapp.example")
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.AuthRequest>(links.current.prompt)
        links.decline()
        testScheduler.advanceUntilIdle()
        assertTrue(links.answers().isEmpty())
    }

    @Test
    fun aSignQuestionReplacedByANewLinkAnswersNoOne() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        // a page's signing link that names the current account, then any other link
        links.handle(voteLink("https://evil.example/cb"), pageHost = "dapp.example")
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(links.current.prompt)
        links.handle("ecency://auth-request?callback=otherapp://second&request_id=b")
        testScheduler.advanceUntilIdle()
        links.answers().let { assertTrue(it.isEmpty(), "the page learns nothing: $it") }
        // an external signing link replaced the same way answers no one either: only the user's own decline answers
        links.decline()
        assertEquals("b", params(links.answers().single())["request_id"], "the user declined the second one")
        links.handle(voteLink())
        testScheduler.advanceUntilIdle()
        links.handle("ecency://auth-request?callback=otherapp://third&request_id=c")
        testScheduler.advanceUntilIdle()
        links.answers().let { assertTrue(it.isEmpty(), "replaced, not declined: $it") }
        links.decline()
        assertEquals("c", params(links.answers().single())["request_id"], "the user's decline answers")
    }

    @Test
    fun aSignLinkTappedInAPostBodyStillAsksFirst() = runTest {
        val app = signedIn(this)
        // the body's link, as the block builder makes it, through the router the screens use
        val target = com.ecency.core.render.blocks.SafeTarget.external(voteLink(callback = null))!!
        val route = assertIs<Route.AppLink>(LinkRouter.route(target))
        assertTrue(app.env.authLinks.handleFromContent(route.uri))
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt)
        assertTrue(app.broadcasts().isEmpty(), "nothing is signed from a tap alone")
        app.env.authLinks.decline()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun declineAnswersUserCancelled() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        links.handle("ecency://transfer?to=spike-bob&amount=1&asset=HIVE&callback=$cb&request_id=r1")
        testScheduler.advanceUntilIdle()
        assertEquals("active", (links.current.prompt as LinkPrompt.SignConfirm).authority)
        links.decline()
        val p = params(links.current.callback!!)
        assertEquals("user_cancelled", p["error"])
        assertEquals("r1", p["request_id"])
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun ownerOperationsFromLinksAreRefused() = runTest {
        val app = signedIn(this, VaultAccount(alice, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING)))
        val op = HiveUri.ops("change_recovery_account" to buildJsonObject {
            put("account_to_recover", JsonPrimitive(alice)); put("new_recovery_account", JsonPrimitive("spike-bob")); put("extensions", JsonArray(emptyList()))
        })
        app.env.authLinks.handle(HiveUri.encodeOps(op))
        testScheduler.advanceUntilIdle()
        assertEquals(AuthLinkModel.OWNER_REFUSED, assertIs<LinkPrompt.Refused>(app.env.authLinks.current.prompt).message)
        app.env.authLinks.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun anUnacceptableCallbackIsNeverOpened() = runTest {
        val app = signedIn(this)
        app.env.authLinks.handle(voteLink("http://plain.example/steal"))
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.Refused>(app.env.authLinks.current.prompt)
        // even approving whatever is showing signs nothing and answers no one
        app.env.authLinks.confirm()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.authLinks.current.callback)
        assertTrue(app.broadcasts().isEmpty())
        app.env.authLinks.handle("ecency://login?username=$alice&callback=javascript:alert(1)")
        testScheduler.advanceUntilIdle()
        app.env.authLinks.confirm()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.authLinks.current.callback)
    }

    @Test
    fun aLoginLinkHandsOverNoKeyAndTellsItsAppToUseHiveSignerOrHiveAuth() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        // the same answer whether the account is here with a posting key, or not here at all
        for ((account, id) in listOf(alice to "r1", "spike-nobody" to "r2")) {
            links.handle("ecency://login?username=$account&callback=$cb&request_id=$id")
            testScheduler.advanceUntilIdle()
            val prompt = assertIs<LinkPrompt.Refused>(links.current.prompt, "never a question")
            assertEquals(AuthLinkModel.KEY_REQUEST_TITLE, prompt.title)
            assertEquals(AuthLinkModel.keyRequestRetired("otherapp"), prompt.message)
            assertTrue(prompt.message.startsWith("An app outside Ecency (otherapp)"), prompt.message)
            assertNull(links.current.callback, "nothing before the user saw why")
            links.confirm()
            testScheduler.advanceUntilIdle()
            val p = params(links.answers().single())
            assertEquals(mapOf("status" to "error", "error" to "not_supported", "message" to CallbackText.KEYS_NOT_SHARED, "request_id" to id), p)
        }
        assertTrue(app.transport.sent.none { it.method == "condenser_api.get_accounts" }, "nothing is read for it")
    }

    @Test
    fun aLoginLinkFromContentNeverHandsOverTheKey() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        val link = "ecency://login?username=$alice&callback=$cb"
        for (open in listOf<() -> Unit>({ links.handleFromContent(link) }, { links.handle(link, pageHost = "dapp.example") }, { links.handleFromPush(link) })) {
            open()
            testScheduler.advanceUntilIdle()
            (links.current.prompt as? LinkPrompt.Refused)?.let { assertEquals(AuthLinkModel.KEY_REQUEST_CONTENT, it.message) }
            links.confirm()
            links.dismiss()
            testScheduler.advanceUntilIdle()
            assertTrue(links.answers().isEmpty(), "a link from content hears nothing")
        }
    }

    @Test
    fun authRequestAnswersWithAProofNotAKey() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        links.handle("ecency://auth-request?callback=$cb&request_id=r3")
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.AuthRequest>(links.current.prompt)
        links.confirm()
        testScheduler.advanceUntilIdle()
        val p = params(links.current.callback!!)
        assertEquals(alice, p["username"])
        assertNull(p["posting_key"])
        val proof = HiveSignerMessages.decode(p["code"]!!)
        assertEquals("login", proof.type)
        assertEquals("otherapp://done", proof.audience)
    }

    @Test
    fun authTransferAsksFirstAndSaysWhenTheAccountIsHere() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        links.handle("ecency://auth-transfer?username=$alice&accessToken=a&refreshToken=refresh-$alice&expiresIn=100")
        testScheduler.advanceUntilIdle()
        assertTrue(assertIs<LinkPrompt.AuthTransfer>(links.current.prompt).accountStored)
        links.decline()
        assertEquals(AuthType.MASTER_PASSWORD, KeyVault(app.store).account(alice)!!.authType)

        links.handle("ecency://auth-transfer?username=$alice&accessToken=a&refreshToken=refresh-$alice&expiresIn=100")
        testScheduler.advanceUntilIdle()
        links.confirm()
        testScheduler.advanceUntilIdle()
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AuthType.MASTER_PASSWORD, stored.authType, "a stored key account keeps its keys")
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), stored.postingKey)
    }

    @Test
    fun webCodeLoginChecksTheCodeFirst() = runTest {
        val app = appWithAccounts(this)
        app.env.session.start()
        val links = app.env.authLinks
        val old = HiveSignerMessages.code(alice, AppTestKeys.key(alice, KeyRole.POSTING), TestApp.NOW - 8L * 24 * 3600 * 1000)
        links.handle("https://ecency.com/auth?username=$alice&code=$old")
        testScheduler.advanceUntilIdle()
        assertEquals(AuthLinkModel.DEEP_LOGIN_EXPIRED, assertIs<LinkPrompt.Refused>(links.current.prompt).message)

        val fresh = HiveSignerMessages.code(alice, AppTestKeys.key(alice, KeyRole.POSTING), TestApp.NOW)
        links.handle("https://ecency.com/auth?username=$alice&code=$fresh")
        testScheduler.advanceUntilIdle()
        assertEquals(alice, assertIs<LinkPrompt.CodeLogin>(links.current.prompt).username)
        assertNull(app.env.session.current.username)
        links.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(alice, app.env.session.current.username)
    }

    @Test
    fun signedOutSignLinksGoToLoginAndSignupOpensTheWeb() = runTest {
        val app = appWithAccounts(this)
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        app.env.authLinks.handle(voteLink())
        testScheduler.advanceUntilIdle()
        assertIs<LinkNavigation.Login>(app.env.authLinks.current.navigation)
        app.env.authLinks.handle("https://ecency.com/signup?referral=spike-bob")
        testScheduler.advanceUntilIdle()
        assertEquals("https://ecency.com/signup?referral=spike-bob", assertIs<LinkNavigation.Signup>(app.env.authLinks.current.navigation).url)
    }

    @Test
    fun keyAccountsAreRefusedOperationsTheAppCannotSignBeforeAnyPrompt() = runTest {
        val app = signedIn(this)
        // (every operation links accept has a serializer since #3635; a recurrent transfer's pair id extension has none)
        val op = HiveUri.ops("recurrent_transfer" to buildJsonObject {
            put("from", JsonPrimitive("__signer")); put("to", JsonPrimitive("spike-bob")); put("amount", JsonPrimitive("1.000 HIVE")); put("memo", JsonPrimitive(""))
            put("recurrence", JsonPrimitive(24)); put("executions", JsonPrimitive(2))
            put("extensions", Json.parseToJsonElement("""[[1,{"pair_id":1}]]"""))
        })
        app.env.authLinks.handle(HiveUri.encodeOps(op, HiveUri.Params(callback = cb)))
        testScheduler.advanceUntilIdle()
        assertEquals(AuthLinkModel.NOT_SUPPORTED, assertIs<LinkPrompt.Refused>(app.env.authLinks.current.prompt).message)
        assertEquals("not_supported", params(app.env.authLinks.current.callback!!)["error"])
    }

    @Test
    fun accountControlLinksAreRefusedForEveryAccountType() = runTest {
        // #3635: the dApp bridge's account-control rule, for key, HiveSigner and HiveAuth accounts alike, before any prompt
        val ops = listOf(
            """["account_create",{"fee":"3.000 HIVE","creator":"__signer","new_account_name":"spike-new","owner":{},"active":{},"posting":{},"memo_key":"STM1","json_metadata":""}]""",
            """["create_claimed_account",{"creator":"__signer","new_account_name":"spike-new","owner":{},"active":{},"posting":{},"memo_key":"STM1","json_metadata":"","extensions":[]}]""",
            """["claim_account",{"creator":"__signer","fee":"0.000 HIVE","extensions":[]}]""",
            """["change_recovery_account",{"account_to_recover":"__signer","new_recovery_account":"spike-bob","extensions":[]}]""",
            """["witness_update",{"owner":"__signer","url":"x","block_signing_key":"STM1","props":{},"fee":"0.000 HIVE"}]""",
            """["witness_set_properties",{"owner":"__signer","props":[],"extensions":[]}]""",
        )
        for (account in listOf(
            VaultAccount(alice, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE), accessToken = "access-$alice"),
            VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"),
            VaultAccount(alice, AuthType.HIVE_AUTH, hiveAuthKey = "k", hiveAuthToken = "t", hiveAuthExpiry = TestApp.NOW + 60_000_000, accessToken = "access-$alice"),
        )) {
            for (op in ops) {
                val app = signedIn(this, account)
                app.env.authLinks.handle(HiveUri.encodeOps(JsonArray(listOf(Json.parseToJsonElement(op))), HiveUri.Params(callback = cb)))
                testScheduler.advanceUntilIdle()
                val what = "${account.authType} $op"
                assertEquals(AuthLinkModel.INVALID_OP_TITLE, assertIs<LinkPrompt.Refused>(app.env.authLinks.current.prompt, what).title, what)
                app.env.authLinks.confirm()
                testScheduler.advanceUntilIdle()
                assertTrue(app.broadcasts().isEmpty(), what)
                assertNull(app.env.authLinks.current.prompt as? LinkPrompt.SignConfirm, what)
            }
        }
    }

    @Test
    fun proposalAndOrderLinksReachTheConfirmationAndSignExactlyWhatItShows() = runTest {
        // #3635: key accounts sign these on the device now; the confirmation lists exactly the fields that are signed
        // ("125.5 HBD" is signed as "125.500 HBD", and that is what it shows)
        val links = listOf(
            "create_proposal" to """{"creator":"__signer","receiver":"spike-bob","start_date":"2026-11-01T00:00:00","end_date":"2027-05-01T00:00:00","daily_pay":"125.5 HBD","subject":"A proposal","permlink":"a-proposal","extensions":[]}""",
            "limit_order_create2" to """{"owner":"","orderid":7,"amount_to_sell":"10.000 HIVE","exchange_rate":{"base":"1.000 HIVE","quote":"0.250 HBD"},"fill_or_kill":false,"expiration":"2026-11-02T12:00:00"}""",
        )
        for ((name, body) in links) {
            val app = signedIn(this)
            app.env.authLinks.handle(HiveUri.encodeOps(JsonArray(listOf(Json.parseToJsonElement("""["$name",$body]"""))), HiveUri.Params(callback = cb)))
            testScheduler.advanceUntilIdle()
            val prompt = assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt, name)
            assertEquals(name, prompt.operation)
            assertEquals("active", prompt.authority, name)
            assertTrue(app.broadcasts().isEmpty(), "nothing before confirm")
            app.env.authLinks.confirm()
            testScheduler.advanceUntilIdle()
            val tx = app.broadcasts().single().params.jsonArray.single().jsonObject
            val sent = tx["operations"]!!.jsonArray.single().jsonArray
            assertEquals(name, sent[0].jsonPrimitive.content)
            val shown = prompt.fields.associate { it.name to it.value }
            val signed = sent[1].jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
            assertEquals(signed, shown, "$name: the confirmation shows exactly the signed fields")
            assertEquals(alice, signed[if (name == "create_proposal") "creator" else "owner"])
            // and the signature is the account's active key over those operations
            val t = Transaction(
                tx["ref_block_num"]!!.jsonPrimitive.content.toInt(), tx["ref_block_prefix"]!!.jsonPrimitive.content.toLong(),
                tx["expiration"]!!.jsonPrimitive.content, listOf(OperationJson.decode(sent)),
            )
            assertEquals(AppTestKeys.pub(alice, KeyRole.ACTIVE), Signature.fromHex(tx["signatures"]!!.jsonArray.single().jsonPrimitive.content).recover(t.digest()).toString())
            assertEquals("success", params(app.env.authLinks.current.callback!!)["status"])
        }
    }

    @Test
    fun walletOperationsFromLinksAreSignableByKeyAccountsNow() = runTest {
        // the wallet wave added their serializers: a delegation link asks for confirmation instead of being refused
        val app = signedIn(this)
        val op = HiveUri.ops("delegate_vesting_shares" to buildJsonObject {
            put("delegator", JsonPrimitive("__signer")); put("delegatee", JsonPrimitive("spike-bob")); put("vesting_shares", JsonPrimitive("1.000000 VESTS"))
        })
        app.env.authLinks.handle(HiveUri.encodeOps(op, HiveUri.Params(callback = cb)))
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt)
    }

    @Test
    fun aRefusalReplacedByANewLinkNeverAnswersItsRequester() = runTest {
        val app = signedIn(this, VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "access-$alice"))
        val links = app.env.authLinks
        // two login links in a row: the user never closed the first refusal
        links.handle("ecency://login?username=spike-nobody&callback=otherapp://first&request_id=a")
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.Refused>(links.current.prompt)
        links.handle("ecency://login?username=spike-nobody2&callback=otherapp://second&request_id=b")
        testScheduler.advanceUntilIdle()
        links.answers().let { assertTrue(it.isEmpty(), "the first requester hears nothing: $it") }
        links.dismiss()
        testScheduler.advanceUntilIdle()
        val answer = params(links.answers().single())
        assertEquals("b", answer["request_id"], "only the refusal the user closed answers")
        assertEquals("not_supported", answer["error"])
    }

    @Test
    fun aSecondLinkReplacesTheOpenQuestionWithoutAnsweringIt() = runTest {
        val app = signedIn(this)
        val links = app.env.authLinks
        links.handle("ecency://auth-request?callback=otherapp://first&request_id=a")
        testScheduler.advanceUntilIdle()
        links.handle("ecency://auth-request?callback=otherapp://second&request_id=b")
        testScheduler.advanceUntilIdle()
        assertNull(links.current.callback, "the replaced question answers no one: the user did not decline it")
        assertIs<LinkPrompt.AuthRequest>(links.current.prompt)
        // while the approved request runs, a third link is told the app is busy
        links.confirm()
        links.handle("ecency://auth-request?callback=otherapp://third&request_id=c")
        assertEquals(AuthLinkModel.BUSY, links.current.notice)
        testScheduler.advanceUntilIdle()
        val answers = buildList { while (links.current.callback != null) { add(params(links.current.callback!!)); links.consumeCallback() } }
        assertTrue(answers.any { it["request_id"] == "c" && it["error"] == "busy" }, answers.toString())
        assertTrue(answers.any { it["request_id"] == "b" && it["status"] == "success" }, answers.toString())
    }

    @Test
    fun linksWaitWhileTheAppIsLocked() = runTest {
        val app = signedIn(this)
        PinLock(KeyVault(app.store), 16).setPin("1234")
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.appLock.current.isLocked)
        app.env.authLinks.handle(voteLink())
        testScheduler.advanceUntilIdle()
        assertNull(app.env.authLinks.current.prompt, "nothing shows behind the lock")
        "1234".forEach { app.env.appLock.enterDigit(it - '0') }
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt)
    }

    @Test
    fun authTransferNamesTheStoredMethod() = runTest {
        val app = signedIn(this, VaultAccount(alice, AuthType.HIVE_AUTH, hiveAuthKey = "k", accessToken = "access-$alice"))
        app.env.authLinks.handle("ecency://auth-transfer?username=$alice&accessToken=a&refreshToken=refresh-$alice")
        testScheduler.advanceUntilIdle()
        assertEquals(com.ecency.core.auth.LoginMethod.HIVEAUTH, assertIs<LinkPrompt.AuthTransfer>(app.env.authLinks.current.prompt).storedMethod)
    }

    @Test
    fun aProofIsNeverSignedWithTheActiveKey() = runTest {
        val app = signedIn(this, VaultAccount(alice, AuthType.ACTIVE_KEY, activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE), accessToken = "access-$alice"))
        app.env.authLinks.handle("ecency://auth-request?callback=$cb")
        testScheduler.advanceUntilIdle()
        app.env.authLinks.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("use_hivesigner", params(app.env.authLinks.current.callback!!)["error"])
    }

    @Test
    fun aTransferFromAnotherAccountIsRefusedBeforeThePrompt() = runTest {
        val app = signedIn(this)
        app.env.authLinks.handle("ecency://transfer?to=spike-bob&from=spike-carol&amount=1&asset=HIVE&callback=$cb")
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.Refused>(app.env.authLinks.current.prompt)
        assertEquals("account_mismatch", params(app.env.authLinks.current.callback!!)["error"])
    }

    @Test
    fun aLinkWaitingBehindTheLockIsDeclinedWhenAnotherArrives() = runTest {
        val app = signedIn(this)
        PinLock(KeyVault(app.store), 16).setPin("1234")
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        val links = app.env.authLinks
        links.handle("ecency://auth-request?callback=otherapp://first&request_id=a")
        testScheduler.advanceUntilIdle()
        links.handle("ecency://auth-request?callback=otherapp://second&request_id=b")
        testScheduler.advanceUntilIdle()
        val first = params(links.current.callback!!)
        assertEquals("a", first["request_id"])
        assertEquals("user_cancelled", first["error"])
        links.consumeCallback()
        "1234".forEach { app.env.appLock.enterDigit(it - '0') }
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.AuthRequest>(links.current.prompt)
        links.decline()
        assertEquals("b", params(links.current.callback!!)["request_id"], "the prompt belongs to the second link")
    }

    @Test
    fun aReplacedWaitingLinkNeverActsAfterTheUnlock() = runTest {
        val app = signedIn(this)
        PinLock(KeyVault(app.store), 16).setPin("1234")
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        val links = app.env.authLinks
        links.handle("https://ecency.com/signup?referral=spike-bob")
        testScheduler.advanceUntilIdle()
        links.handle("ecency://auth-request?callback=otherapp://second&request_id=b")
        testScheduler.advanceUntilIdle()
        "1234".forEach { app.env.appLock.enterDigit(it - '0') }
        testScheduler.advanceUntilIdle()
        assertNull(links.current.navigation, "the first link's job was cancelled")
        assertIs<LinkPrompt.AuthRequest>(links.current.prompt)
    }

    @Test
    fun linksWaitWhileTheLockStartFailedClosed() = runTest {
        val app = signedIn(this)
        app.store.namesUnavailable = true
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        assertNotNull(app.env.appLock.current.error, "the lock failed closed")
        app.store.namesUnavailable = false
        app.env.authLinks.handle(voteLink())
        testScheduler.advanceUntilIdle()
        assertNull(app.env.authLinks.current.prompt, "nothing routes while the lock state is unknown")
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(app.env.authLinks.current.prompt)
    }
}
