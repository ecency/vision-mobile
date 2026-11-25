package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #3725: a proposal vote whose outcome the chain is still asked for, across account changes and a logout. */
class ProposalVoteModelTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private fun stored(name: String) = VaultAccount(
        name, AuthType.MASTER_PASSWORD,
        postingKey = AppTestKeys.wif(name, KeyRole.POSTING), activeKey = AppTestKeys.wif(name, KeyRole.ACTIVE), accessToken = "access-$name",
    )

    private class Chain {
        /** Every node times out on the broadcast: the send is checked with the chain. */
        var hangBroadcast = true
        var txStatus = "unknown"
    }

    private suspend fun app(scope: TestScope, chain: Chain): TestApp {
        val base = appWithAccounts(scope)
        val app = TestApp(scope) { req ->
            when (req.method) {
                // established accounts: no Getting started, the card shows at once (#3728)
                "condenser_api.get_accounts" -> {
                    val names = listOf(alice, bob).filter { "\"$it\"" in req.body }
                    FakeTransport.result(req, names.joinToString(",", "[", "]") { AppTestKeys.accountJson(it).replace("\"post_count\":1", "\"post_count\":50") })
                }
                "database_api.list_proposals" -> FakeTransport.result(req, """{"proposals":[{"id":3,"proposal_id":350,"creator":"ecency","status":"active"}]}""")
                "database_api.list_proposal_votes" -> FakeTransport.result(req, """{"proposal_votes":[]}""")
                "condenser_api.broadcast_transaction" -> if (chain.hangBroadcast) kotlinx.coroutines.awaitCancellation() else base.handler(this, req)
                "transaction_status_api.find_transaction" -> FakeTransport.result(req, """{"status":"${chain.txStatus}"}""")
                else -> base.handler(this, req)
            }
        }
        KeyVault(app.store).run { save(stored(alice)); save(stored(bob)); setCurrentUsername(alice) }
        app.env.session.start()
        scope.testScheduler.advanceUntilIdle()
        return app
    }

    private fun start(scope: TestScope, app: TestApp): ProposalVoteModel =
        app.env.proposalVote.also { it.start(); scope.testScheduler.advanceUntilIdle() }

    @Test
    fun switchingAwayAndBackWhileAVoteIsCheckedStartsNoSecondVote() = runTest {
        val chain = Chain()
        val app = app(this, chain)
        try {
            val model = start(this, app)
            assertTrue(model.current.visible)
            model.vote()
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            assertTrue(model.current.voting)
            app.env.session.switchTo(bob)
            testScheduler.runCurrent()
            app.env.session.switchTo(alice)
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            assertEquals(alice, model.current.username)
            assertTrue(model.current.voting, "her vote is still in flight")
            model.vote()
            testScheduler.runCurrent()
            // one send admitted: a second vote is another action. (The fixed test clock and chain head would sign a
            // second vote byte for byte as the first, so the broadcasts alone cannot tell.)
            assertEquals(1, app.env.walletSends.current.sends.size, "no second vote")
            // node failover sends the same signed transaction again
            assertEquals(1, app.broadcasts().map { it.params.toString() }.distinct().size, "the same transaction")
            chain.txStatus = "within_irreversible_block"
            testScheduler.advanceTimeBy(WalletSends.RECHECK_MILLIS + 1)
            testScheduler.advanceUntilIdle()
            assertTrue(model.current.voted, "the first vote thanks")
            assertFalse(model.current.voting)
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun anOutcomeWhileAnotherAccountIsShownIsStillToasted() = runTest {
        val chain = Chain()
        val app = app(this, chain)
        try {
            val model = start(this, app)
            model.vote()
            testScheduler.advanceTimeBy(60_000)
            testScheduler.runCurrent()
            app.env.session.switchTo(bob)
            testScheduler.runCurrent()
            // the earlier toasts read and gone
            while (true) app.env.ui.dismiss(app.env.ui.current.toast?.id ?: break)
            chain.txStatus = "within_irreversible_block"
            testScheduler.advanceTimeBy(WalletSends.RECHECK_MILLIS + 1)
            testScheduler.advanceUntilIdle()
            assertEquals(ProposalCopy.THANK_YOU, app.env.ui.current.toast?.text)
            assertEquals(bob, model.current.username)
            assertFalse(model.current.voted, "Bob's card is not Alice's")
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun aLogoutDuringAVoteEndsTheWait() = runTest {
        val chain = Chain()
        val app = app(this, chain)
        try {
            val model = start(this, app)
            model.vote()
            testScheduler.runCurrent()
            assertTrue(model.current.voting)
            app.env.session.logout(alice)
            testScheduler.advanceUntilIdle()
            KeyVault(app.store).run { save(stored(alice)); setCurrentUsername(alice) }
            app.env.session.switchTo(alice)
            testScheduler.advanceUntilIdle()
            assertEquals(alice, model.current.username)
            assertFalse(model.current.voting, "the cancelled vote no longer holds the card")
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }

    @Test
    fun aLogoutBeforeTheSendIsSeenStillEndsTheWait() = runTest {
        val chain = Chain()
        val app = app(this, chain)
        try {
            val model = start(this, app)
            model.vote()
            // the logout lands in the same main thread step as the send's entry, before the card's watcher subscribes:
            // that watcher only ever sees the entry gone
            launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                app.env.walletSends.state.first { it.sends.isNotEmpty() }
                app.env.walletSends.accountsLeft(listOf(alice))
            }
            testScheduler.advanceUntilIdle()
            assertEquals(alice, model.current.username)
            assertFalse(model.current.voting, "a send cancelled before it was seen no longer holds the card")
        } finally {
            chain.txStatus = "within_irreversible_block"
        }
    }
}
