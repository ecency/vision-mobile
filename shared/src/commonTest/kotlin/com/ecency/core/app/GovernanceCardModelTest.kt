package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #3724: the wallet's governance card, its proxy to @ecency, its renewal and its dismissal. */
class GovernanceCardModelTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"
    private val day = 86_400_000L

    private fun stored(name: String) = VaultAccount(
        name, AuthType.MASTER_PASSWORD,
        postingKey = AppTestKeys.wif(name, KeyRole.POSTING), activeKey = AppTestKeys.wif(name, KeyRole.ACTIVE), accessToken = "access-$name",
    )

    private fun chainTime(millis: Long) = kotlin.time.Instant.fromEpochMilliseconds(millis).toString().removeSuffix("Z")

    /** The governance fields of each account on the fake chain; @ecency proxies to @good-karma. */
    private class Chain(val fields: MutableMap<String, String>, var failBroadcast: Boolean = false, var hangBroadcast: Boolean = false) {
        /** find_transaction's answer while a send is checked. */
        var txStatus = "unknown"

        /** get_accounts fails (a node down). */
        var readsDown = false
    }

    private fun governance(expiration: String, proxy: String = "", votes: List<String> = emptyList()) =
        """"proxy":"$proxy","witness_votes":[${votes.joinToString(",") { "\"$it\"" }}],"governance_vote_expiration_ts":"$expiration""""

    private suspend fun app(scope: TestScope, chain: Chain, postingOnly: Boolean = false): TestApp {
        val base = appWithAccounts(scope, known = setOf(alice, bob, GovernanceCardModel.PROXY))
        val app = TestApp(scope) { req ->
            when (req.method) {
                "condenser_api.get_accounts" -> if (chain.readsDown) HttpResult(500, "") else {
                    val names = req.params.jsonArray[0].jsonArray.map { it.jsonPrimitive.content }
                    FakeTransport.result(
                        req,
                        names.joinToString(",", "[", "]") { n -> AppTestKeys.accountJson(n).removeSuffix("}") + (chain.fields[n]?.let { ",$it" } ?: "") + "}" },
                    )
                }
                "condenser_api.broadcast_transaction" -> when {
                    chain.hangBroadcast -> kotlinx.coroutines.awaitCancellation()
                    chain.failBroadcast -> FakeTransport.error(req, -31000, "Assert Exception: Proxy must change.")
                    else -> base.handler(this, req)
                }
                "transaction_status_api.find_transaction" -> FakeTransport.result(req, """{"status":"${chain.txStatus}"}""")
                else -> base.handler(this, req)
            }
        }
        KeyVault(app.store).run {
            // postingOnly: the proxy needs the active key, so signing asks for the active key upgrade first
            save(if (postingOnly) VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING)) else stored(alice))
            save(stored(bob)); setCurrentUsername(alice)
        }
        app.env.session.start()
        scope.testScheduler.advanceUntilIdle()
        return app
    }

    private fun chain(alice: String) = Chain(mutableMapOf(this.alice to alice, GovernanceCardModel.PROXY to governance(chainTime(TestApp.NOW + 200 * day), proxy = "good-karma")))

    private fun ops(app: TestApp): List<String> = app.broadcasts().flatMap { b ->
        ((b.params.jsonArray[0] as JsonObject)["operations"] as JsonArray).map { it.toString() }
    }

    /** The key role that signed each broadcast, recovered from its signature (WalletFixtures.signerRoles). */
    private fun signers(app: TestApp, user: String): List<KeyRole?> = app.broadcasts().map { b ->
        val tx = b.params.jsonArray[0] as JsonObject
        val ops = (tx["operations"] as JsonArray).map { com.ecency.core.api.broadcast.OperationJson.decodeOwn(it as JsonArray) }
        val t = com.ecency.core.hive.Transaction(
            tx["ref_block_num"]!!.jsonPrimitive.content.toInt(), tx["ref_block_prefix"]!!.jsonPrimitive.content.toLong(),
            tx["expiration"]!!.jsonPrimitive.content, ops,
        )
        val pub = com.ecency.core.hive.Signature.fromHex((tx["signatures"] as JsonArray).single().jsonPrimitive.content).recover(t.digest()).toString()
        listOf(KeyRole.POSTING, KeyRole.ACTIVE, KeyRole.OWNER, KeyRole.MEMO).firstOrNull { AppTestKeys.pub(user, it) == pub }
    }

    private fun start(scope: TestScope, app: TestApp): GovernanceCardModel =
        app.env.governanceCard.also { it.start(); scope.testScheduler.advanceUntilIdle() }

    @Test
    fun anInactiveAccountSeesTheCardWithTheProxyOffer() = runTest {
        val app = app(this, chain(governance(Governance.EXPIRED_TS)))
        val card = start(this, app).current
        assertTrue(card.visible)
        assertEquals(alice, card.username)
        assertEquals(GovernanceStatus.INACTIVE, card.status)
        assertEquals(GovernanceAction.SET_PROXY, card.action)
        assertEquals("good-karma", card.ecencyProxy, "the sheet says whose votes @ecency follows, read from the chain")
        assertEquals(GovernanceCopy.TITLE_INACTIVE, card.title)
    }

    @Test
    fun anActiveAccountSeesNoCard() = runTest {
        val app = app(this, chain(governance(chainTime(TestApp.NOW + 200 * day), votes = listOf("good-karma"))))
        assertFalse(start(this, app).current.visible)
        val proxied = app(this, chain(governance(Governance.EXPIRED_TS, proxy = "someone")))
        assertFalse(start(this, proxied).current.visible)
    }

    @Test
    fun settingTheProxySignsOneProxyOperationWithTheActiveKey() = runTest {
        val app = app(this, chain(governance(Governance.EXPIRED_TS)))
        val model = start(this, app)
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(app.broadcasts().isEmpty(), "nothing is signed before the sheet was opened")
        model.askConfirm()
        assertTrue(model.current.confirming)
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("""["account_witness_proxy",{"account":"$alice","proxy":"ecency"}]"""), ops(app))
        assertEquals(listOf<KeyRole?>(KeyRole.ACTIVE), signers(app, alice))
        assertTrue(model.current.done)
        assertFalse(model.current.confirming)
        assertTrue(model.current.visible, "the thanks show for the rest of the run")
        assertEquals(GovernanceCopy.TITLE_DONE, model.current.title)
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size, "never sent twice")
    }

    @Test
    fun renewingAnEcencyProxyClearsAndSetsItInOneTransaction() = runTest {
        val expires = TestApp.NOW + 10 * day
        val app = app(this, chain(governance(chainTime(expires), proxy = "ecency")))
        val model = start(this, app)
        assertEquals(GovernanceStatus.EXPIRING, model.current.status)
        assertEquals(GovernanceAction.RENEW, model.current.action)
        assertEquals(expires, model.current.expiresAt)
        model.askConfirm()
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.broadcasts().size)
        assertEquals(
            listOf(
                """["account_witness_proxy",{"account":"$alice","proxy":""}]""",
                """["account_witness_proxy",{"account":"$alice","proxy":"ecency"}]""",
            ),
            ops(app),
        )
        assertEquals(listOf<KeyRole?>(KeyRole.ACTIVE), signers(app, alice))
        // the same operations as the SDK's golden renew transaction
        val golden = com.ecency.core.hive.PlatformGoldenVectors.transactions.first { it.name == "account_witness_proxy_renew" }
        assertEquals(golden.operationsJson.replace("spike-voter", alice), ops(app).joinToString(",", "[", "]"))
    }

    @Test
    fun expiringOwnVotesOrAnotherProxyOnlyPointToVotingYourself() = runTest {
        val own = app(this, chain(governance(chainTime(TestApp.NOW + 10 * day), votes = listOf("good-karma"))))
        val card = start(this, own)
        assertTrue(card.current.visible)
        assertEquals(GovernanceStatus.EXPIRING, card.current.status)
        assertNull(card.current.action)
        card.askConfirm()
        assertFalse(card.current.confirming, "no proxy offer to an account with its own votes")
        val other = app(this, chain(governance(chainTime(TestApp.NOW + 10 * day), proxy = "someone")))
        assertNull(start(this, other).current.action)
        assertEquals("https://ecency.com/witnesses", GovernanceCardModel.VOTE_URL)
    }

    @Test
    fun aFailedBroadcastKeepsTheCardAndSaysWhy() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS)).also { it.failBroadcast = true }
        val app = app(this, chain)
        val model = start(this, app)
        model.askConfirm()
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.done)
        assertFalse(model.current.sending)
        assertTrue(model.current.visible)
        // one catalog message with the reason in it (native.governance.failed_with)
        val toast = app.env.ui.current.toast?.text.orEmpty()
        assertTrue(toast.startsWith(com.ecency.core.l10n.L.native.governance.failedWith(message = "")) && toast.contains("Proxy must change"), toast)
    }

    @Test
    fun laterHidesItForThreeDaysAndDontShowAgainForGood() = runTest {
        val app = app(this, chain(governance(Governance.EXPIRED_TS)))
        val model = start(this, app)
        model.askDismiss()
        assertTrue(model.current.askingDismiss)
        model.dismiss(null)
        assertTrue(model.current.visible, "closing the choice changes nothing")
        model.dismiss(GovernanceDismiss.LATER)
        assertFalse(model.current.visible)
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible)
        app.now += GovernanceCardModel.RE_REQUEST_MILLIS + 1
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible, "back after 3 days")
        model.dismiss(GovernanceDismiss.FOREVER)
        app.now += 100 * day
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible)
        // the choice names the account: a wipe removes it
        assertTrue(app.env.prefs.keys().any { it.startsWith(GovernanceCardModel.KEY_PREFIX) })
        AccountPrefs.clear(app.env.prefs)
        assertFalse(app.env.prefs.keys().any { it.startsWith(GovernanceCardModel.KEY_PREFIX) })
    }

    @Test
    fun dontShowAgainOnAnExpiringCardStillWarnsNextYear() = runTest {
        val chain = chain(governance(chainTime(TestApp.NOW + 10 * day), proxy = "ecency"))
        val app = app(this, chain)
        val model = start(this, app)
        model.dismiss(GovernanceDismiss.FOREVER)
        assertFalse(model.current.visible)
        // renewed elsewhere, and a year later it is about to expire again
        chain.fields[alice] = governance(chainTime(TestApp.NOW + 375 * day), proxy = "ecency")
        app.now += 355 * day
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
    }

    @Test
    fun anAccountChangeResetsTheCard() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS))
        chain.fields[bob] = governance(chainTime(TestApp.NOW + 200 * day), votes = listOf("good-karma"))
        val app = app(this, chain)
        val model = start(this, app)
        assertTrue(model.current.visible)
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, model.current.username)
        assertFalse(model.current.visible)
        app.env.session.switchTo(alice)
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
    }

    private fun reads(app: TestApp) = app.transport.sent.count { it.method == "condenser_api.get_accounts" }

    @Test
    fun aRefreshKeepsTheCardWhileItReads() = runTest {
        val app = app(this, chain(governance(Governance.EXPIRED_TS)))
        val model = start(this, app)
        model.refresh()
        assertTrue(model.current.visible, "no blank card while the account is read again")
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
    }

    @Test
    fun theWalletOpeningReadsAgainSoRemindLaterComesBack() = runTest {
        val app = app(this, chain(governance(Governance.EXPIRED_TS)))
        val model = app.env.governanceCard
        model.shown()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
        model.dismiss(GovernanceDismiss.LATER)
        app.now += GovernanceCardModel.RE_REQUEST_MILLIS + 1
        model.shown()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
    }

    @Test
    fun theSheetStaysWhileSendingAndClosesOnceTheChainIsAsked() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS)).also { it.hangBroadcast = true }
        val app = app(this, chain)
        // the send keeps asking the chain until it answers: answer even when an assertion fails, so the test ends
        try {
            val model = start(this, app)
            model.askConfirm()
            model.confirm()
            testScheduler.runCurrent()
            assertTrue(model.current.sending)
            model.cancelConfirm()
            assertTrue(model.current.confirming, "the sheet cannot be closed while it signs and sends")
            // every node times out: the send may have landed, the app keeps asking; the sheet gives way to the card
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            assertFalse(model.current.confirming)
            assertTrue(model.current.sending)
            assertTrue(model.current.visible)
            model.askConfirm()
            assertFalse(model.current.confirming, "no second send while the first is checked")
            // the chain says it is in: the card thanks
            chain.txStatus = "within_irreversible_block"
            testScheduler.advanceTimeBy(WalletSends.RECHECK_MILLIS + 1)
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
            assertTrue(model.current.done)
            assertFalse(model.current.sending)
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun aFailedSendReadsTheAccountAgain() = runTest {
        // the chain cleared the expiring proxy after the card was read: Renew is refused ("Proxy must change")
        val chain = chain(governance(chainTime(TestApp.NOW + 10 * day), proxy = "ecency")).also { it.failBroadcast = true }
        val app = app(this, chain)
        val model = start(this, app)
        assertEquals(GovernanceAction.RENEW, model.current.action)
        chain.fields[alice] = governance(Governance.EXPIRED_TS)
        val before = reads(app)
        model.askConfirm()
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(reads(app) > before)
        assertEquals(GovernanceAction.SET_PROXY, model.current.action, "the card now offers what the chain accepts")
    }

    @Test
    fun dontShowAgainOnInactiveEndsOnceTheVotesCountAgain() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS))
        val app = app(this, chain)
        val model = start(this, app)
        model.dismiss(GovernanceDismiss.FOREVER)
        chain.fields[alice] = governance(chainTime(TestApp.NOW + 200 * day), votes = listOf("good-karma"))
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible)
        // a year later the votes expired again: the earlier choice was about the earlier expiry
        chain.fields[alice] = governance(Governance.EXPIRED_TS)
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible)
    }

    @Test
    fun switchingAwayAndBackWhileASendIsCheckedStartsNoSecondSend() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS)).also { it.hangBroadcast = true }
        val app = app(this, chain)
        try {
            val model = start(this, app)
            model.askConfirm()
            model.confirm()
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            assertTrue(model.current.sending)
            // away to Bob and back: the fresh card for Alice still waits on her send
            app.env.session.switchTo(bob)
            testScheduler.runCurrent()
            app.env.session.switchTo(alice)
            testScheduler.runCurrent()
            assertEquals(alice, model.current.username)
            assertTrue(model.current.sending, "her send is still in flight")
            model.askConfirm()
            model.confirm()
            testScheduler.runCurrent()
            // node failover sends the same signed transaction again; a second send would be another transaction
            assertEquals(1, app.broadcasts().map { it.params.toString() }.distinct().size, "no second send")
            // the first send lands: Alice's card thanks
            chain.txStatus = "within_irreversible_block"
            testScheduler.advanceTimeBy(WalletSends.RECHECK_MILLIS + 1)
            testScheduler.advanceUntilIdle()
            assertTrue(model.current.done)
            assertFalse(model.current.sending)
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun anOutcomeWhileAnotherAccountIsShownIsStillToasted() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS)).also { it.hangBroadcast = true }
        val app = app(this, chain)
        try {
            val model = start(this, app)
            model.askConfirm()
            model.confirm()
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            app.env.session.switchTo(bob)
            testScheduler.runCurrent()
            // the earlier toasts read and gone
            while (true) app.env.ui.dismiss(app.env.ui.current.toast?.id ?: break)
            chain.txStatus = "within_irreversible_block"
            testScheduler.advanceTimeBy(WalletSends.RECHECK_MILLIS + 1)
            testScheduler.advanceUntilIdle()
            assertEquals(GovernanceCopy.THANK_YOU, app.env.ui.current.toast?.text)
            assertEquals(bob, model.current.username)
            assertFalse(model.current.done, "Bob's card is not Alice's")
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun aLogoutDuringASendEndsTheWait() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS)).also { it.hangBroadcast = true }
        val app = app(this, chain)
        try {
            val model = start(this, app)
            model.askConfirm()
            model.confirm()
            testScheduler.runCurrent()
            assertTrue(model.current.sending)
            app.env.session.logout(alice)
            testScheduler.advanceUntilIdle()
            // logging Alice in again finds no send of hers in flight
            KeyVault(app.store).run { save(stored(alice)); setCurrentUsername(alice) }
            app.env.session.switchTo(alice)
            testScheduler.advanceUntilIdle()
            assertEquals(alice, model.current.username)
            assertFalse(model.current.sending, "the cancelled send no longer holds the card")
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun aMissingExpiryDoesNotUndoDontShowAgain() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS))
        val app = app(this, chain)
        val model = start(this, app)
        model.dismiss(GovernanceDismiss.FOREVER)
        // a node that leaves the field out: no card, and no proof the votes count
        chain.fields[alice] = """"proxy":"","witness_votes":[]"""
        model.refresh()
        testScheduler.advanceUntilIdle()
        chain.fields[alice] = governance(Governance.EXPIRED_TS)
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.visible, "the choice stands")
    }

    @Test
    fun dontShowAgainOnInactiveEndsWhenTheVotesCountEvenIfFirstSeenExpiring() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS))
        val app = app(this, chain)
        val model = start(this, app)
        model.dismiss(GovernanceDismiss.FOREVER)
        // the next read already finds the renewed votes in their last month
        chain.fields[alice] = governance(chainTime(TestApp.NOW + 10 * day), votes = listOf("good-karma"))
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(GovernanceStatus.EXPIRING, model.current.status)
        chain.fields[alice] = governance(Governance.EXPIRED_TS)
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.visible, "the earlier choice was about the earlier lapse")
    }

    @Test
    fun aReadLandingWhileSendingKeepsTheSheet() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS)).also { it.hangBroadcast = true }
        val app = app(this, chain)
        try {
            val model = start(this, app)
            model.askConfirm()
            model.confirm()
            testScheduler.runCurrent()
            // set on another device meanwhile: the read says the votes count
            chain.fields[alice] = governance(chainTime(TestApp.NOW + 200 * day), proxy = "ecency")
            model.refresh()
            testScheduler.runCurrent()
            assertTrue(model.current.sending)
            assertTrue(model.current.visible, "a read does not hide a card that is sending")
            assertTrue(model.current.confirming, "nor close its sheet")
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun aConfirmedSendReadsTheAccountAgainAndKeepsItsThanks() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS))
        val app = app(this, chain)
        val model = start(this, app)
        model.askConfirm()
        model.confirm()
        // the chain now holds the proxy
        chain.fields[alice] = governance(chainTime(TestApp.NOW + 365 * day), proxy = "ecency")
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.done)
        assertTrue(model.current.visible, "the thanks stay")
        assertEquals("ecency", model.current.proxy, "the card follows the chain")
        assertEquals(GovernanceStatus.ACTIVE, model.current.status)
    }

    @Test
    fun noDismissalWhileASendRuns() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS)).also { it.hangBroadcast = true }
        val app = app(this, chain)
        try {
            val model = start(this, app)
            model.askConfirm()
            model.confirm()
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            assertTrue(model.current.sending)
            model.askDismiss()
            assertFalse(model.current.askingDismiss)
            model.dismiss(GovernanceDismiss.FOREVER)
            assertTrue(model.current.visible)
            assertFalse(app.env.prefs.keys().any { it.startsWith(GovernanceCardModel.KEY_PREFIX) }, "nothing kept that could hide the card a failed send needs")
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun aCancelledSigningAfterTheVotesCameToCountClosesTheSheet() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS))
        val app = app(this, chain, postingOnly = true)
        val model = start(this, app)
        model.askConfirm()
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertIs<SigningPrompt.AuthUpgrade>(app.env.signing.current.prompt)
        // set on another device meanwhile: the read lands while the send waits for the key
        chain.fields[alice] = governance(chainTime(TestApp.NOW + 200 * day), proxy = "ecency")
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.confirming)
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.sending)
        assertFalse(model.current.confirming, "no sheet whose Confirm does nothing")
        assertFalse(model.current.visible, "the votes count: no card")
    }

    @Test
    fun aCancelledSigningClosesASheetWithNothingToSignEvenWhenTheNextReadFails() = runTest {
        // PR #3730 review: the card's own state says nothing is left to sign; no read is needed to close the sheet
        val chain = chain(governance(Governance.EXPIRED_TS))
        val app = app(this, chain, postingOnly = true)
        val model = start(this, app)
        model.askConfirm()
        model.confirm()
        testScheduler.advanceUntilIdle()
        chain.fields[alice] = governance(chainTime(TestApp.NOW + 200 * day), proxy = "ecency")
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(GovernanceStatus.ACTIVE, model.current.status)
        chain.readsDown = true
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.confirming, "no sheet whose Confirm does nothing")
        assertFalse(model.current.visible, "the votes count: no card")
    }

    @Test
    fun aCancelledSigningWhileStillInactiveKeepsTheSheetReady() = runTest {
        val chain = chain(governance(Governance.EXPIRED_TS))
        val app = app(this, chain, postingOnly = true)
        val model = start(this, app)
        model.askConfirm()
        model.confirm()
        testScheduler.advanceUntilIdle()
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.sending)
        assertTrue(model.current.confirming, "nothing was signed: the sheet stays to confirm again or cancel")
        assertEquals(GovernanceAction.SET_PROXY, model.current.action)
    }

    @Test
    fun aCancelledSigningKeepsAnExpiringCardWithNothingToSign() = runTest {
        // PR #3730 review: nothing to sign closes the sheet, but an expiring card still says "Vote yourself"
        val chain = chain(governance(Governance.EXPIRED_TS))
        val app = app(this, chain, postingOnly = true)
        val model = start(this, app)
        model.askConfirm()
        model.confirm()
        testScheduler.advanceUntilIdle()
        // another proxy set meanwhile, expiring soon: the card stays, with no proxy offer
        chain.fields[alice] = governance(chainTime(TestApp.NOW + 10 * day), proxy = "someone")
        model.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(GovernanceStatus.EXPIRING, model.current.status)
        assertNull(model.current.action)
        chain.readsDown = true
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.confirming, "nothing to sign: the sheet closes")
        assertTrue(model.current.visible, "the expiring card stays")
    }
}
