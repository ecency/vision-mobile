package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
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
    private class Chain(val fields: MutableMap<String, String>, var failBroadcast: Boolean = false)

    private fun governance(expiration: String, proxy: String = "", votes: List<String> = emptyList()) =
        """"proxy":"$proxy","witness_votes":[${votes.joinToString(",") { "\"$it\"" }}],"governance_vote_expiration_ts":"$expiration""""

    private suspend fun app(scope: TestScope, chain: Chain): TestApp {
        val app = appWithAccounts(scope, known = setOf(alice, bob, GovernanceCardModel.PROXY)) { req ->
            when (req.method) {
                "condenser_api.get_accounts" -> {
                    val names = req.params.jsonArray[0].jsonArray.map { it.jsonPrimitive.content }
                    FakeTransport.result(
                        req,
                        names.joinToString(",", "[", "]") { n -> AppTestKeys.accountJson(n).removeSuffix("}") + (chain.fields[n]?.let { ",$it" } ?: "") + "}" },
                    )
                }
                "condenser_api.broadcast_transaction" ->
                    if (chain.failBroadcast) FakeTransport.error(req, -31000, "Assert Exception: Account has declined the ability to vote") else null
                else -> null
            }
        }
        KeyVault(app.store).run { save(stored(alice)); save(stored(bob)); setCurrentUsername(alice) }
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
        assertTrue(app.env.ui.current.toast?.text.orEmpty().startsWith(GovernanceCopy.FAIL), app.env.ui.current.toast?.text)
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
}
